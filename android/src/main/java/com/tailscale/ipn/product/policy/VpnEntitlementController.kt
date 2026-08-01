// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.auth.AuthentikState
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VpnStartOrigin {
  PermissionRequest,
  PermissionResult,
  AppStart,
  AppRestart,
  ServiceStart,
  ServiceRestart,
  AlwaysOn,
  StickyRestart,
}

enum class VpnRuntimeState {
  Idle,
  Starting,
  Running,
}

interface VpnEntitlementDecisionSource {
  val authentikState: StateFlow<AuthentikState>
  val accessState: StateFlow<AccessState>

  suspend fun refreshAccess(origin: VpnStartOrigin?): AccessState
}

interface VpnEntitlementRuntime {
  val state: StateFlow<VpnRuntimeState>

  fun revoke()
}

class VpnRuntimeStateTracker(private val revokeVpn: () -> Unit) : VpnEntitlementRuntime {
  private val lock = Any()
  private val _state = MutableStateFlow(VpnRuntimeState.Idle)
  override val state: StateFlow<VpnRuntimeState> = _state.asStateFlow()
  private var generation = 0L
  private var requestGeneration: Long? = null

  fun beginStarting(): VpnRuntimeLease =
      checkNotNull(tryBeginStarting()) { "a VPN run is already active" }

  fun tryBeginStarting(): VpnRuntimeLease? =
      synchronized(lock) {
        if (requestGeneration != null || _state.value != VpnRuntimeState.Idle) {
          return@synchronized null
        }
        VpnRuntimeLease(++generation).also { _state.value = VpnRuntimeState.Starting }
      }

  fun isCurrent(lease: VpnRuntimeLease): Boolean = synchronized(lock) { isCurrentLocked(lease) }

  fun markRunning(lease: VpnRuntimeLease): Boolean =
      synchronized(lock) {
        if (!isCurrentLocked(lease)) return@synchronized false
        _state.value = VpnRuntimeState.Running
        true
      }

  fun finish(lease: VpnRuntimeLease): Boolean =
      synchronized(lock) {
        if (!isCurrentLocked(lease)) return@synchronized false
        generation++
        _state.value = VpnRuntimeState.Idle
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
    return lease.generation == generation && _state.value != VpnRuntimeState.Idle
  }

  override fun revoke() {
    revokeVpn()
  }
}

data class VpnRuntimeLease internal constructor(internal val generation: Long)

data class VpnRuntimeRequestPermit internal constructor(internal val generation: Long)

interface VpnStartAuthorizer {
  suspend fun authorizeStart(origin: VpnStartOrigin): Boolean
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
    scope: CoroutineScope,
    refreshInterval: Duration = Duration.ofSeconds(30),
) : VpnStartAuthorizer {
  private val decisionMutex = Mutex()
  private val revocationMutex = Mutex()
  private val refreshIntervalMillis = refreshInterval.toMillis().coerceAtLeast(1)
  private var revokedForCurrentRun = false

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
    val active = freshActiveAccess(origin) != null
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

  suspend fun revokeRejectedRuntimeStart() {
    revokeOnce()
  }

  private suspend fun refreshAndEnforce() {
    if (freshActiveAccess(origin = null) == null && runtime.state.value.isStartingOrRunning()) {
      revokeOnce()
    }
  }

  private suspend fun freshActiveAccess(origin: VpnStartOrigin?): AccessState.Active? =
      decisionMutex.withLock {
        if (decisionSource.authentikState.value != AuthentikState.Authorized) return@withLock null
        val access = decisionSource.refreshAccess(origin)
        if (decisionSource.authentikState.value != AuthentikState.Authorized) return@withLock null
        access as? AccessState.Active
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
}
