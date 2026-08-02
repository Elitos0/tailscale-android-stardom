// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class VpnStartOrigin {
  PermissionRequest,
  PermissionResult,
  AppStart,
  AppRestart,
  ServiceStart,
  ServiceRestart,
  AlwaysOn,
  StickyRestart,
  QuickSettings,
  InternalWorker,
}

sealed interface ExitNodeMutation {
  val allowLanAccess: Boolean?

  data class Clear(override val allowLanAccess: Boolean? = null) : ExitNodeMutation

  data class Auto(override val allowLanAccess: Boolean? = null) : ExitNodeMutation

  data class Manual(
      val nodeId: String,
      override val allowLanAccess: Boolean? = null,
  ) : ExitNodeMutation
}

fun interface ExitNodeMutationPolicyGuard {
  fun isAllowed(activeAccess: AccessState.Active, mutation: ExitNodeMutation): Boolean

  companion object {
    val DenySelections = ExitNodeMutationPolicyGuard { _, mutation ->
      mutation is ExitNodeMutation.Clear
    }
  }
}

interface ExitNodeMutationBoundary {
  suspend fun mutateExitNode(mutation: ExitNodeMutation): Result<Unit>
}

fun interface ExitNodePreferenceWriter {
  fun write(prefs: Ipn.MaskedPrefs, onComplete: (Result<Unit>) -> Unit)
}

class ExitNodeMutationDeniedException : IllegalStateException("exit-node mutation denied")

class ExitNodeMutationTimeoutException : IllegalStateException("exit-node mutation timed out")

enum class VpnRuntimeState {
  Idle,
  Starting,
  Running,
}

data class VpnRuntimeSnapshot(
    val state: VpnRuntimeState,
    val generation: Long,
)

interface VpnEntitlementDecisionSource {
  val authentikState: StateFlow<AuthentikState>
  val accessState: StateFlow<AccessState>

  suspend fun refreshAccess(origin: VpnStartOrigin?): AccessState
}

interface VpnEntitlementRuntime {
  val state: StateFlow<VpnRuntimeState>

  fun revoke()
}

fun interface VpnStopCommandRegistration {
  fun unregister()
}

class VpnStopCommandDispatcher(private val fallbackDispatch: () -> Unit) {
  private val lock = Any()
  private var generation = 0L
  private var registeredHandler: Pair<Long, () -> Unit>? = null

  fun register(handler: () -> Unit): VpnStopCommandRegistration {
    val registrationGeneration =
        synchronized(lock) {
          val nextGeneration = ++generation
          registeredHandler = nextGeneration to handler
          nextGeneration
        }
    return VpnStopCommandRegistration {
      synchronized(lock) {
        if (registeredHandler?.first == registrationGeneration) registeredHandler = null
      }
    }
  }

  fun dispatchStopCommand() {
    synchronized(lock) {
      // Delivery must be linearizable with registration lifecycle changes: once STOP selects a
      // handler, that handler must queue WantRunning=false and enter the close fence before a new
      // service can register and start. JVM monitors are reentrant, so same-thread unregister or
      // replacement from a handler is safe. Production handlers do not wait for another thread to
      // register or unregister.
      (registeredHandler?.second ?: fallbackDispatch).invoke()
    }
  }
}

class VpnRuntimeStateTracker(private val revokeVpn: () -> Unit) : VpnEntitlementRuntime {
  private val lock = Any()
  private val _snapshot = MutableStateFlow(VpnRuntimeSnapshot(VpnRuntimeState.Idle, generation = 0))
  val snapshot: StateFlow<VpnRuntimeSnapshot> = _snapshot.asStateFlow()
  override val state: StateFlow<VpnRuntimeState> = VpnRuntimeStateFlow(snapshot)
  private var generation = 0L
  private var requestGeneration: Long? = null
  private var revocationGeneration: Long? = null

  fun beginStarting(): VpnRuntimeLease =
      checkNotNull(tryBeginStarting()) { "a VPN run is already active" }

  fun tryBeginStarting(): VpnRuntimeLease? =
      synchronized(lock) {
        if (requestGeneration != null || _snapshot.value.state != VpnRuntimeState.Idle) {
          return@synchronized null
        }
        VpnRuntimeLease(++generation).also {
          revocationGeneration = null
          _snapshot.value = VpnRuntimeSnapshot(VpnRuntimeState.Starting, generation)
        }
      }

  fun isCurrent(lease: VpnRuntimeLease): Boolean = synchronized(lock) { isCurrentLocked(lease) }

  fun markRunning(lease: VpnRuntimeLease): Boolean =
      synchronized(lock) {
        if (!isCurrentLocked(lease)) return@synchronized false
        _snapshot.value = VpnRuntimeSnapshot(VpnRuntimeState.Running, generation)
        true
      }

  fun finish(lease: VpnRuntimeLease): Boolean =
      synchronized(lock) {
        if (!isCurrentLocked(lease)) return@synchronized false
        _snapshot.value = VpnRuntimeSnapshot(VpnRuntimeState.Idle, generation)
        true
      }

  fun claimRequest(lease: VpnRuntimeLease): VpnRuntimeRequestPermit? =
      synchronized(lock) {
        if (!isCurrentLocked(lease) || requestGeneration != null) return@synchronized null
        requestGeneration = lease.generation
        VpnRuntimeRequestPermit(lease.generation)
      }

  fun releaseRequest(permit: VpnRuntimeRequestPermit) {
    synchronized(lock) { if (requestGeneration == permit.generation) requestGeneration = null }
  }

  private fun isCurrentLocked(lease: VpnRuntimeLease): Boolean {
    return lease.generation == generation && _snapshot.value.state != VpnRuntimeState.Idle
  }

  override fun revoke() {
    val shouldRevoke =
        synchronized(lock) {
          if (_snapshot.value.state == VpnRuntimeState.Idle) {
            // Idle revocation is still a valid cleanup signal for rejected starts. It is not
            // generation-deduplicated because no active VPN run owns the stop fence yet.
            true
          } else if (revocationGeneration == generation) {
            false
          } else {
            revocationGeneration = generation
            true
          }
        }
    if (shouldRevoke) revokeVpn()
  }
}

private class VpnRuntimeStateFlow(private val snapshots: StateFlow<VpnRuntimeSnapshot>) :
    StateFlow<VpnRuntimeState> {
  override val value: VpnRuntimeState
    get() = snapshots.value.state

  override val replayCache: List<VpnRuntimeState>
    get() = listOf(value)

  override suspend fun collect(collector: FlowCollector<VpnRuntimeState>): Nothing {
    snapshots.map { it.state }.distinctUntilChanged().collect(collector)
    error("runtime snapshot StateFlow completed")
  }
}

data class VpnRuntimeLease internal constructor(internal val generation: Long)

data class VpnRuntimeRequestPermit internal constructor(internal val generation: Long)

interface VpnStartAuthorizer {
  suspend fun authorizeStart(origin: VpnStartOrigin): Boolean
}

fun interface VpnStartPolicyGuard {
  fun isAllowed(activeAccess: AccessState.Active): Boolean

  companion object {
    val AllowAll = VpnStartPolicyGuard { true }
  }
}

sealed interface VpnStartDispatchResult {
  data object Denied : VpnStartDispatchResult

  data object Dispatched : VpnStartDispatchResult

  data class Failed(val error: Throwable) : VpnStartDispatchResult
}

class VpnStartDispatchBoundary(private val authorizer: VpnStartAuthorizer) {
  suspend fun dispatchIfAuthorized(
      origin: VpnStartOrigin,
      dispatch: () -> Unit,
  ): VpnStartDispatchResult {
    if (!authorizer.authorizeStart(origin)) return VpnStartDispatchResult.Denied
    return try {
      dispatch()
      VpnStartDispatchResult.Dispatched
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      VpnStartDispatchResult.Failed(error)
    }
  }
}

fun interface VpnWantRunningWriter {
  fun setWantRunning(wantRunning: Boolean, onComplete: (Result<Unit>) -> Unit)
}

fun interface VpnWantRunningPersistence {
  fun write(wantRunning: Boolean, onComplete: (Result<Unit>) -> Unit)
}

class SerializedVpnWantRunningWriter(private val persistence: VpnWantRunningPersistence) {
  private data class PendingWrite(
      val wantRunning: Boolean,
      val onComplete: (Result<Unit>) -> Unit,
      val completed: AtomicBoolean = AtomicBoolean(false),
  )

  private val lock = Any()
  private val queuedWrites = ArrayDeque<PendingWrite>()
  private val queuedTasks = ArrayDeque<() -> Unit>()
  private var activeWrite: PendingWrite? = null
  private var drainingTasks = false

  fun write(wantRunning: Boolean, onComplete: (Result<Unit>) -> Unit = {}) {
    val nextWrite =
        synchronized(lock) {
          queuedWrites.addLast(PendingWrite(wantRunning, onComplete))
          if (activeWrite != null) return@synchronized null
          queuedWrites.removeFirst().also { activeWrite = it }
        }
    nextWrite?.let { enqueueTask { dispatch(it) } }
  }

  private fun dispatch(write: PendingWrite) {
    try {
      persistence.write(write.wantRunning) { result -> enqueueTask { complete(write, result) } }
    } catch (error: Throwable) {
      enqueueTask { complete(write, Result.failure(error)) }
    }
  }

  private fun complete(write: PendingWrite, result: Result<Unit>) {
    if (!write.completed.compareAndSet(false, true)) return
    try {
      write.onComplete(result)
    } finally {
      val nextWrite =
          synchronized(lock) {
            check(activeWrite === write) { "completed WantRunning write is not active" }
            queuedWrites.removeFirstOrNull().also { activeWrite = it }
          }
      nextWrite?.let { enqueueTask { dispatch(it) } }
    }
  }

  private fun enqueueTask(task: () -> Unit) {
    val shouldDrain =
        synchronized(lock) {
          queuedTasks.addLast(task)
          if (drainingTasks) return@synchronized false
          drainingTasks = true
          true
        }
    if (shouldDrain) drainTasks()
  }

  private fun drainTasks() {
    while (true) {
      val task =
          synchronized(lock) {
            queuedTasks.removeFirstOrNull()
                ?: run {
                  drainingTasks = false
                  return
                }
          }
      try {
        task()
      } catch (_: Throwable) {
        // A persistence/client callback must not strand the remaining FIFO work.
      }
    }
  }
}

class VpnServiceStartRejectionBoundary(private val dispatchFencedStop: () -> Unit) {
  private val rejected = AtomicBoolean(false)

  fun reject() {
    if (rejected.compareAndSet(false, true)) dispatchFencedStop()
  }
}

class VpnServiceRunCoordinator(
    private val runtime: VpnRuntimeStateTracker,
    authorizer: VpnStartAuthorizer,
    private val wantRunningWriter: VpnWantRunningWriter,
    private val scope: CoroutineScope,
    private val beforeRequestHandoff: () -> Unit = {},
) {
  private val lock = Any()
  private val requestBoundary = VpnRequestBoundary(authorizer)
  private var closed = false
  private var currentLease: VpnRuntimeLease? = null
  private var requestHandoffInFlight = false
  private var deferredCloseAction: (() -> Unit)? = null

  fun beginAuthorizedStart(
      origin: VpnStartOrigin,
      requestVpn: () -> Unit,
      rejectStart: () -> Unit,
  ) {
    val lease =
        synchronized(lock) {
          if (closed || currentLease != null) return
          val nextLease = runtime.tryBeginStarting() ?: return
          nextLease.also { currentLease = it }
        }
    try {
      wantRunningWriter.setWantRunning(true) { result ->
        if (!isActionable(lease)) return@setWantRunning
        result.fold(
            onSuccess = {
              scope.launch {
                if (!isActionable(lease)) return@launch
                try {
                  val authorized =
                      requestBoundary.requestIfAuthorized(origin) {
                        runRequestIfCurrent(lease, requestVpn)
                      }
                  if (!authorized) rejectCurrent(lease, rejectStart)
                } catch (error: CancellationException) {
                  throw error
                } catch (_: Throwable) {
                  rejectCurrent(lease, rejectStart)
                }
              }
            },
            onFailure = { rejectCurrent(lease, rejectStart) },
        )
      }
    } catch (_: Throwable) {
      rejectCurrent(lease, rejectStart)
    }
  }

  fun updateVpnStatus(running: Boolean): Boolean {
    val lease = synchronized(lock) { if (closed) null else currentLease } ?: return false
    return if (running) runtime.markRunning(lease) else finishCurrent(lease)
  }

  fun close(afterFence: () -> Unit = {}) {
    val closeNow =
        synchronized(lock) {
          if (closed) return
          closed = true
          currentLease?.let(runtime::finish)
          currentLease = null
          if (requestHandoffInFlight) {
            deferredCloseAction = afterFence
            null
          } else {
            afterFence
          }
        }
    closeNow?.invoke()
  }

  private fun isActionable(lease: VpnRuntimeLease): Boolean =
      synchronized(lock) { !closed && currentLease == lease && runtime.isCurrent(lease) }

  private fun runRequestIfCurrent(lease: VpnRuntimeLease, requestVpn: () -> Unit): Boolean {
    val permit = runtime.claimRequest(lease) ?: return false
    return try {
      beforeRequestHandoff()
      if (!beginRequestHandoff(lease)) return false
      try {
        requestVpn()
        true
      } finally {
        finishRequestHandoff()?.invoke()
      }
    } finally {
      runtime.releaseRequest(permit)
    }
  }

  private fun beginRequestHandoff(lease: VpnRuntimeLease): Boolean =
      synchronized(lock) {
        if (closed || currentLease != lease || !runtime.isCurrent(lease)) {
          return@synchronized false
        }
        check(!requestHandoffInFlight) { "a VPN request handoff is already in flight" }
        requestHandoffInFlight = true
        true
      }

  private fun finishRequestHandoff(): (() -> Unit)? =
      synchronized(lock) {
        check(requestHandoffInFlight) { "no VPN request handoff is in flight" }
        requestHandoffInFlight = false
        deferredCloseAction.also { deferredCloseAction = null }
      }

  private fun finishCurrent(lease: VpnRuntimeLease): Boolean {
    return synchronized(lock) {
      if (currentLease != lease) return@synchronized false
      currentLease = null
      runtime.finish(lease)
    }
  }

  private fun rejectCurrent(lease: VpnRuntimeLease, rejectStart: () -> Unit) {
    if (finishCurrent(lease)) rejectStart()
  }
}

class VpnRequestBoundary(private val authorizer: VpnStartAuthorizer) {
  suspend fun requestIfAuthorized(origin: VpnStartOrigin, requestVpn: () -> Unit): Boolean {
    if (!authorizer.authorizeStart(origin)) return false
    requestVpn()
    return true
  }
}

class VpnEntitlementController(
    private val decisionSource: VpnEntitlementDecisionSource,
    private val runtime: VpnEntitlementRuntime,
    private val scope: CoroutineScope,
    refreshInterval: Duration = Duration.ofSeconds(30),
    private val startPolicyGuard: VpnStartPolicyGuard = VpnStartPolicyGuard.AllowAll,
    private val exitNodeMutationPolicyGuard: ExitNodeMutationPolicyGuard =
        ExitNodeMutationPolicyGuard.DenySelections,
    private val beforeExitNodeMutationWrite: suspend () -> Unit = {},
    private val exitNodeMutationTimeout: Duration = Duration.ofSeconds(35),
    private val exitNodePreferenceWriter: ExitNodePreferenceWriter =
        ExitNodePreferenceWriter { _, complete ->
          complete(Result.failure(IllegalStateException("exit-node writer unavailable")))
        },
) : VpnStartAuthorizer, ExitNodeMutationBoundary {
  private val decisionMutex = Mutex()
  private val exitNodeMutationWriteMutex = Mutex()
  private val revocationMutex = Mutex()
  private val refreshIntervalMillis = refreshInterval.toMillis().coerceAtLeast(1)
  private val exitNodeMutationTimeoutMillis = exitNodeMutationTimeout.toMillis().coerceAtLeast(1)
  private var revokedForCurrentRun = false
  private var exitNodeMutationStateUncertain = false

  private data class MutationDispatchOutcome(
      val result: Result<Unit>,
      val completion: CompletableDeferred<Result<Unit>>?,
  )

  init {
    scope.launch {
      combine(
              decisionSource.authentikState,
              decisionSource.accessState,
              runtime.state,
          ) { authentik, access, runtimeState ->
            Triple(authentik, access, runtimeState)
          }
          .collect { (authentik, access, runtimeState) ->
            if (runtimeState == VpnRuntimeState.Idle) {
              resetRevocationGuard()
            } else if (authentik != AuthentikState.Authorized || access !is AccessState.Active) {
              revokeOnce()
            }
          }
    }
    scope.launch {
      runtime.state.collectLatest { runtimeState ->
        if (!runtimeState.isStartingOrRunning()) return@collectLatest
        refreshAndEnforce()
        while (true) {
          delay(refreshIntervalMillis)
          refreshAndEnforce()
        }
      }
    }
  }

  override suspend fun authorizeStart(origin: VpnStartOrigin): Boolean {
    val active = freshActiveAccess(origin, requireStartPolicy = true) != null
    if (active && runtime.state.value == VpnRuntimeState.Idle) {
      resetRevocationGuard()
    } else if (!active && runtime.state.value.isStartingOrRunning()) {
      revokeOnce()
    }
    return active
  }

  suspend fun refreshRuntimeEntitlement() {
    if (runtime.state.value.isStartingOrRunning()) refreshAndEnforce()
  }

  fun revokeRejectedRuntimeStart() {
    runtime.revoke()
  }

  fun revokeDisallowedAutoExitNode() {
    runtime.revoke()
  }

  suspend fun authorizeExitNodeMutation(nodeId: String?): Boolean {
    val normalized = nodeId?.trim().orEmpty()
    if (normalized.isEmpty()) return true
    return try {
      decisionMutex.withLock {
        authorizeExitNodeMutationLocked(ExitNodeMutation.Manual(normalized))
      }
    } catch (_: Throwable) {
      false
    }
  }

  suspend fun authorizeAutoExitNodeMutation(): Boolean =
      try {
        decisionMutex.withLock { authorizeExitNodeMutationLocked(ExitNodeMutation.Auto()) }
      } catch (_: Throwable) {
        false
      }

  override suspend fun mutateExitNode(mutation: ExitNodeMutation): Result<Unit> =
      exitNodeMutationWriteMutex.withLock {
        // Once a LocalAPI PATCH has been dispatched, keep this independent write fence until its
        // callback arrives even if the caller is cancelled. A later mutation cannot overtake it.
        withContext(NonCancellable) {
          val normalizedMutation = mutation.normalized()
          if (exitNodeMutationStateUncertain) {
            return@withContext Result.failure(ExitNodeMutationDeniedException())
          }
          val authorizationFailure = authorizeMutationForDispatch(normalizedMutation)
          if (authorizationFailure != null) return@withContext Result.failure(authorizationFailure)

          val dispatch = dispatchMutation(normalizedMutation)
          if (dispatch.result.isFailure) {
            if (dispatch.result.exceptionOrNull() is ExitNodeMutationTimeoutException) {
              markMutationStateUncertain()
              dispatch.completion?.let { recoverAfterTimedOutMutation(normalizedMutation, it) }
            }
            return@withContext dispatch.result
          }

          if (normalizedMutation is ExitNodeMutation.Clear) {
            exitNodeMutationStateUncertain = false
          }

          if (!isMutationStillAllowedFresh(normalizedMutation)) {
            if (normalizedMutation is ExitNodeMutation.Manual) {
              val compensation = dispatchMutation(ExitNodeMutation.Clear())
              if (compensation.result.isFailure) {
                markMutationStateUncertain()
                if (compensation.result.exceptionOrNull() is ExitNodeMutationTimeoutException) {
                  compensation.completion?.let {
                    recoverAfterTimedOutMutation(ExitNodeMutation.Clear(), it)
                  }
                } else {
                  scheduleCompensatingClearRetry()
                }
              }
            }
            if (runtime.state.value.isStartingOrRunning()) runtime.revoke()
            return@withContext Result.failure(ExitNodeMutationDeniedException())
          }
          dispatch.result
        }
      }

  private suspend fun authorizeMutationForDispatch(
      mutation: ExitNodeMutation
  ): ExitNodeMutationDeniedException? =
      try {
        decisionMutex.withLock {
          if (mutation is ExitNodeMutation.Clear) return@withLock null
          val active = freshActiveAccessLocked(origin = null, requireStartPolicy = false)
          if (active == null || !isExitNodeMutationAllowed(active, mutation)) {
            return@withLock ExitNodeMutationDeniedException()
          }

          beforeExitNodeMutationWrite()

          val finalActive = currentActiveAccess()
          if (finalActive == null || !isExitNodeMutationAllowed(finalActive, mutation)) {
            ExitNodeMutationDeniedException()
          } else {
            null
          }
        }
      } catch (_: Throwable) {
        ExitNodeMutationDeniedException()
      }

  private suspend fun dispatchMutation(mutation: ExitNodeMutation): MutationDispatchOutcome {
    val completion = CompletableDeferred<Result<Unit>>()
    return try {
      exitNodePreferenceWriter.write(mutation.toMaskedPrefs()) { result ->
        completion.complete(result)
      }
      val result =
          withTimeoutOrNull(exitNodeMutationTimeoutMillis) { completion.await() }
              ?: Result.failure(ExitNodeMutationTimeoutException())
      MutationDispatchOutcome(result, completion)
    } catch (error: Throwable) {
      MutationDispatchOutcome(Result.failure(error), null)
    }
  }

  private suspend fun isMutationStillAllowedFresh(mutation: ExitNodeMutation): Boolean {
    if (mutation is ExitNodeMutation.Clear) return true
    return try {
      decisionMutex.withLock {
        val active =
            freshActiveAccessLocked(origin = null, requireStartPolicy = false)
                ?: return@withLock false
        isExitNodeMutationAllowed(active, mutation)
      }
    } catch (_: Throwable) {
      false
    }
  }

  private fun markMutationStateUncertain() {
    exitNodeMutationStateUncertain = true
    if (runtime.state.value.isStartingOrRunning()) runtime.revoke()
  }

  private fun recoverAfterTimedOutMutation(
      mutation: ExitNodeMutation,
      completion: CompletableDeferred<Result<Unit>>,
  ) {
    scope.launch {
      val lateResult = completion.await()
      exitNodeMutationWriteMutex.withLock {
        if (!exitNodeMutationStateUncertain) return@withLock
        if (lateResult.isFailure) {
          exitNodeMutationStateUncertain = false
        } else if (mutation is ExitNodeMutation.Clear) {
          exitNodeMutationStateUncertain = false
        } else {
          retryCompensatingClearLocked()
        }
      }
    }
  }

  private fun scheduleCompensatingClearRetry() {
    scope.launch {
      exitNodeMutationWriteMutex.withLock {
        if (exitNodeMutationStateUncertain) retryCompensatingClearLocked()
      }
    }
  }

  private suspend fun retryCompensatingClearLocked() {
    repeat(MAX_COMPENSATING_CLEAR_ATTEMPTS) {
      val dispatch = dispatchMutation(ExitNodeMutation.Clear())
      var result = dispatch.result
      if (result.exceptionOrNull() is ExitNodeMutationTimeoutException) {
        result = dispatch.completion?.await() ?: result
      }
      if (result.isSuccess) {
        exitNodeMutationStateUncertain = false
        return
      }
      delay(COMPENSATING_CLEAR_RETRY_DELAY_MILLIS)
    }
  }

  private suspend fun refreshAndEnforce() {
    if (freshActiveAccess(origin = null, requireStartPolicy = true) == null &&
        runtime.state.value.isStartingOrRunning()) {
      revokeOnce()
    }
  }

  private suspend fun freshActiveAccess(
      origin: VpnStartOrigin?,
      requireStartPolicy: Boolean,
  ): AccessState.Active? =
      decisionMutex.withLock { freshActiveAccessLocked(origin, requireStartPolicy) }

  private suspend fun freshActiveAccessLocked(
      origin: VpnStartOrigin?,
      requireStartPolicy: Boolean,
  ): AccessState.Active? {
    if (decisionSource.authentikState.value != AuthentikState.Authorized) return null
    val access = decisionSource.refreshAccess(origin)
    if (decisionSource.authentikState.value != AuthentikState.Authorized) return null
    val activeAccess = access as? AccessState.Active ?: return null
    if (!requireStartPolicy) return activeAccess
    return activeAccess.takeIf {
      runCatching { startPolicyGuard.isAllowed(it) }.getOrDefault(false)
    }
  }

  private suspend fun authorizeExitNodeMutationLocked(mutation: ExitNodeMutation): Boolean {
    if (mutation is ExitNodeMutation.Clear) return true
    val active = freshActiveAccessLocked(origin = null, requireStartPolicy = false) ?: return false
    return isExitNodeMutationAllowed(active, mutation.normalized())
  }

  private fun currentActiveAccess(): AccessState.Active? {
    if (decisionSource.authentikState.value != AuthentikState.Authorized) return null
    return decisionSource.accessState.value as? AccessState.Active
  }

  private fun isExitNodeMutationAllowed(
      active: AccessState.Active,
      mutation: ExitNodeMutation,
  ): Boolean =
      runCatching { exitNodeMutationPolicyGuard.isAllowed(active, mutation) }.getOrDefault(false)

  private fun ExitNodeMutation.normalized(): ExitNodeMutation =
      when (this) {
        is ExitNodeMutation.Clear -> this
        is ExitNodeMutation.Auto -> this
        is ExitNodeMutation.Manual -> copy(nodeId = nodeId.trim())
      }

  private fun ExitNodeMutation.toMaskedPrefs(): Ipn.MaskedPrefs =
      Ipn.MaskedPrefs().apply {
        when (this@toMaskedPrefs) {
          is ExitNodeMutation.Clear -> {
            ExitNodeID = null
            AutoExitNode = null
          }
          is ExitNodeMutation.Auto -> {
            AutoExitNode = "any"
            ExitNodeID = null
          }
          is ExitNodeMutation.Manual -> {
            ExitNodeID = nodeId
            AutoExitNode = null
          }
        }
        this@toMaskedPrefs.allowLanAccess?.let { ExitNodeAllowLANAccess = it }
      }

  private suspend fun revokeOnce() {
    revocationMutex.withLock {
      if (revokedForCurrentRun) return
      revokedForCurrentRun = true
      runtime.revoke()
    }
  }

  private suspend fun resetRevocationGuard() {
    revocationMutex.withLock { revokedForCurrentRun = false }
  }

  private fun VpnRuntimeState.isStartingOrRunning(): Boolean =
      this == VpnRuntimeState.Starting || this == VpnRuntimeState.Running

  companion object {
    private const val MAX_COMPENSATING_CLEAR_ATTEMPTS = 3
    private const val COMPENSATING_CLEAR_RETRY_DELAY_MILLIS = 250L
  }
}
