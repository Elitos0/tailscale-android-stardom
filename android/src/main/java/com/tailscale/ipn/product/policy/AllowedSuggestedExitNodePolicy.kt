// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val ALLOWED_SUGGESTED_EXIT_NODES_KEY = "AllowedSuggestedExitNodes"

sealed interface ManagedAllowedSuggestedExitNodes {
  data object Unset : ManagedAllowedSuggestedExitNodes

  data class Configured(val values: List<String>?) : ManagedAllowedSuggestedExitNodes
}

object AllowedSuggestedExitNodePolicyMapper {
  fun map(
      authentikState: AuthentikState,
      accessState: AccessState,
      mdm: ManagedAllowedSuggestedExitNodes,
  ): List<String> {
    if (authentikState != AuthentikState.Authorized || accessState !is AccessState.Active) {
      return emptyList()
    }
    val productCandidates = accessState.allowedExitNodeIds.normalizedNodeIds()
    if (productCandidates.isEmpty()) return emptyList()
    val effectiveCandidates =
        when (mdm) {
          ManagedAllowedSuggestedExitNodes.Unset -> productCandidates
          is ManagedAllowedSuggestedExitNodes.Configured ->
              productCandidates.intersect(mdm.values.orEmpty().normalizedNodeIds())
        }
    return effectiveCandidates.sorted()
  }

  private fun Iterable<String>.normalizedNodeIds(): Set<String> =
      map(String::trim).filter(String::isNotEmpty).toSet()
}

class AllowedSuggestedExitNodePolicyController(
    private val authentikState: StateFlow<AuthentikState>,
    private val accessState: StateFlow<AccessState>,
    private val mdmAllowedSuggestedExitNodes: StateFlow<SettingState<List<String>?>>,
    private val mdmForcedExitNodeId: StateFlow<SettingState<String?>> =
        MutableStateFlow(SettingState(null, false)),
    private val prefs: StateFlow<Ipn.Prefs?>,
    private val runtimeSnapshot: StateFlow<VpnRuntimeSnapshot>,
    private val notifyPolicyChanged: () -> Unit,
    private val revokeDisallowedAutoExitNode: () -> Unit,
    private val clearDisallowedExitNode: (((Result<Unit>) -> Unit) -> Unit) = { complete ->
      complete(Result.success(Unit))
    },
    private val candidateMapper:
        (AuthentikState, AccessState, ManagedAllowedSuggestedExitNodes) -> List<String> =
        AllowedSuggestedExitNodePolicyMapper::map,
    private val jsonEncoder: (List<String>) -> String = ::encodeJSON,
    private val onCallbackError: (String, Throwable) -> Unit = { _, _ -> },
    clearRetryDelay: Duration = Duration.ofMillis(250),
) {
  private data class AutoExitPrefs(
      val autoExitNode: String?,
      val effectiveExitNodeID: String?,
  )

  private data class Inputs(
      val authentikState: AuthentikState,
      val accessState: AccessState,
      val mdm: ManagedAllowedSuggestedExitNodes,
      val manualSelectionMutable: Boolean,
      val prefs: AutoExitPrefs?,
      val runtimeSnapshot: VpnRuntimeSnapshot,
  )

  private data class Evaluation(
      val candidates: List<String>,
      val json: String,
      val prefs: AutoExitPrefs?,
      val runtimeSnapshot: VpnRuntimeSnapshot,
      val manualSelectionMutable: Boolean,
  )

  private val observerStarted = AtomicBoolean(false)
  private val clearRetryDelayMillis = clearRetryDelay.toMillis().coerceAtLeast(1)
  private val lock = Any()
  private var observerScope: CoroutineScope? = null
  private var lastNativeCandidates: List<String>? = null
  private var revokedRuntimeGeneration: Long? = null
  private var clearedUnsafeState: Pair<AutoExitPrefs?, List<String>>? = null
  private val clearRetryAttempts = mutableMapOf<Pair<AutoExitPrefs?, List<String>>, Int>()

  fun currentCandidatesJSON(): String {
    val evaluation = evaluateStable()
    synchronized(lock) {
      // Before observation begins, synchronous reads seed the value native actually received.
      // Afterwards the observer owns this state; a slower read that captured an older snapshot
      // must not overwrite a newer observed candidate list.
      if (!observerStarted.get() || lastNativeCandidates == null) {
        lastNativeCandidates = evaluation.candidates
      }
    }
    return evaluation.json
  }

  fun isVpnStartAllowed(activeAccess: AccessState.Active): Boolean {
    val evaluation = evaluateStable(activeAccess)
    val currentPrefs = evaluation.prefs ?: return false
    return !currentPrefs.hasDisallowedEffectiveExitNode(evaluation.candidates)
  }

  fun isExitNodeMutationAllowed(
      activeAccess: AccessState.Active,
      mutation: ExitNodeMutation,
  ): Boolean {
    if (mutation is ExitNodeMutation.Clear) return true
    val candidates = evaluateStable(activeAccess).candidates
    return when (mutation) {
      is ExitNodeMutation.Clear -> true
      is ExitNodeMutation.Auto -> candidates.isNotEmpty()
      is ExitNodeMutation.Manual -> {
        val nodeId = mutation.nodeId.trim()
        nodeId.isNotEmpty() && nodeId != NATIVE_AUTO_EXIT_NODE_BLACKHOLE && nodeId in candidates
      }
    }
  }

  fun start(scope: CoroutineScope) {
    if (!observerStarted.compareAndSet(false, true)) return
    synchronized(lock) { observerScope = scope }
    scope.launch {
      val managedExitNodeSettings =
          combine(mdmAllowedSuggestedExitNodes, mdmForcedExitNodeId) { allowed, forced ->
            allowed to forced
          }
      combine(
              authentikState,
              accessState,
              managedExitNodeSettings,
              prefs,
              runtimeSnapshot,
          ) { currentAuthentik, currentAccess, currentMdm, currentPrefs, currentRuntime ->
            Inputs(
                authentikState = currentAuthentik,
                accessState = currentAccess.snapshot(),
                mdm = currentMdm.first.toManagedAllowList(),
                manualSelectionMutable = !currentMdm.second.isSet,
                prefs = currentPrefs.snapshot(),
                runtimeSnapshot = currentRuntime,
            )
          }
          .collect { process(evaluate(it)) }
    }
  }

  private fun evaluateStable(activeAccess: AccessState.Active? = null): Evaluation {
    repeat(MAX_STABLE_READ_ATTEMPTS) {
      val before = captureInputs()
      if (activeAccess != null && before.accessState != activeAccess) {
        return failClosed(before)
      }
      val evaluation = evaluate(before)
      if (captureInputs() == before) return evaluation
    }
    return failClosed(captureInputs())
  }

  private fun captureInputs(): Inputs =
      Inputs(
          authentikState = authentikState.value,
          accessState = accessState.value.snapshot(),
          mdm = mdmAllowedSuggestedExitNodes.value.toManagedAllowList(),
          manualSelectionMutable = !mdmForcedExitNodeId.value.isSet,
          prefs = prefs.value.snapshot(),
          runtimeSnapshot = runtimeSnapshot.value,
      )

  private fun evaluate(inputs: Inputs): Evaluation {
    return try {
      val candidates =
          candidateMapper(
              inputs.authentikState,
              inputs.accessState,
              inputs.mdm,
          )
      Evaluation(
          candidates,
          jsonEncoder(candidates),
          inputs.prefs,
          inputs.runtimeSnapshot,
          inputs.manualSelectionMutable,
      )
    } catch (_: Throwable) {
      failClosed(inputs)
    }
  }

  private fun failClosed(inputs: Inputs): Evaluation =
      Evaluation(
          emptyList(),
          "[]",
          inputs.prefs,
          inputs.runtimeSnapshot,
          inputs.manualSelectionMutable,
      )

  private fun process(evaluation: Evaluation) {
    val (shouldRevoke, shouldClear, notificationChange) =
        synchronized(lock) {
          val revoke = shouldRevokeLocked(evaluation)
          val clear = shouldClearLocked(evaluation)
          val previousCandidates = lastNativeCandidates
          val notify =
              when (lastNativeCandidates) {
                null -> {
                  lastNativeCandidates = evaluation.candidates
                  null
                }
                evaluation.candidates -> null
                else -> {
                  lastNativeCandidates = evaluation.candidates
                  previousCandidates
                }
              }
          Triple(revoke, clear, notify)
        }
    // Stop the stale tunnel before asking the asynchronous native policy machinery to recompute.
    if (shouldRevoke) {
      try {
        revokeDisallowedAutoExitNode()
      } catch (error: Throwable) {
        synchronized(lock) {
          if (revokedRuntimeGeneration == evaluation.runtimeSnapshot.generation) {
            revokedRuntimeGeneration = null
          }
        }
        reportCallbackError("revoke", error)
      }
    }
    if (shouldClear) {
      try {
        clearDisallowedExitNode { result ->
          result.fold(
              onSuccess = {
                synchronized(lock) {
                  clearRetryAttempts.remove(evaluation.prefs to evaluation.candidates)
                }
              },
              onFailure = { error -> handleClearFailure(evaluation, error) },
          )
        }
      } catch (error: Throwable) {
        handleClearFailure(evaluation, error)
      }
    }
    if (notificationChange != null) {
      try {
        notifyPolicyChanged()
      } catch (error: Throwable) {
        synchronized(lock) {
          if (lastNativeCandidates == evaluation.candidates) {
            lastNativeCandidates = notificationChange
          }
        }
        reportCallbackError("notify", error)
      }
    }
  }

  private fun reportCallbackError(operation: String, error: Throwable) {
    try {
      onCallbackError(operation, error)
    } catch (_: Throwable) {
      // Error reporting must not cancel the single app-scoped observer either.
    }
  }

  private fun handleClearFailure(evaluation: Evaluation, error: Throwable) {
    val retryScope =
        synchronized(lock) {
          val signature = evaluation.prefs to evaluation.candidates
          if (clearedUnsafeState == signature) clearedUnsafeState = null
          val attempts = (clearRetryAttempts[signature] ?: 0) + 1
          clearRetryAttempts[signature] = attempts
          observerScope.takeIf { attempts < MAX_CLEAR_ATTEMPTS }
        }
    reportCallbackError("clear", error)
    retryScope?.launch {
      delay(clearRetryDelayMillis)
      process(evaluateStable())
    }
  }

  private fun shouldRevokeLocked(evaluation: Evaluation): Boolean {
    val currentRuntime = evaluation.runtimeSnapshot
    val isUnsafe = evaluation.prefs.hasDisallowedEffectiveExitNode(evaluation.candidates)
    if (!isUnsafe) {
      revokedRuntimeGeneration = null
      return false
    }

    val shouldRevoke =
        when (currentRuntime.state) {
          VpnRuntimeState.Idle -> {
            false
          }
          VpnRuntimeState.Starting,
          VpnRuntimeState.Running -> {
            if (revokedRuntimeGeneration == currentRuntime.generation) {
              false
            } else {
              revokedRuntimeGeneration = currentRuntime.generation
              true
            }
          }
        }
    return shouldRevoke
  }

  private fun shouldClearLocked(evaluation: Evaluation): Boolean {
    val shouldClear =
        evaluation.prefs.hasDisallowedConcreteManualExitNode(evaluation.candidates) &&
            evaluation.manualSelectionMutable
    if (!shouldClear) {
      clearedUnsafeState = null
      clearRetryAttempts.clear()
      return false
    }
    val signature = evaluation.prefs to evaluation.candidates
    if (clearedUnsafeState == signature) return false
    clearedUnsafeState = signature
    return true
  }

  private fun SettingState<List<String>?>.toManagedAllowList(): ManagedAllowedSuggestedExitNodes =
      if (isSet) {
        ManagedAllowedSuggestedExitNodes.Configured(value?.toList())
      } else {
        ManagedAllowedSuggestedExitNodes.Unset
      }

  private fun AccessState.snapshot(): AccessState =
      when (this) {
        is AccessState.Active -> AccessState.Active(allowedExitNodeIds.toSet())
        AccessState.Disabled -> AccessState.Disabled
        AccessState.Unavailable -> AccessState.Unavailable
      }

  private fun Ipn.Prefs?.snapshot(): AutoExitPrefs? =
      this?.let {
        AutoExitPrefs(autoExitNode = it.AutoExitNode, effectiveExitNodeID = it.ExitNodeID)
      }

  private fun AutoExitPrefs?.hasDisallowedEffectiveExitNode(
      allowedCandidates: List<String>
  ): Boolean {
    if (this == null) return true
    val normalizedEffectiveExitNodeID = effectiveExitNodeID?.trim().orEmpty()
    if (autoExitNode != NATIVE_AUTO_EXIT_NODE_ANY) {
      return normalizedEffectiveExitNodeID.isNotEmpty() &&
          normalizedEffectiveExitNodeID !in allowedCandidates
    }
    if (normalizedEffectiveExitNodeID == NATIVE_AUTO_EXIT_NODE_BLACKHOLE) return false
    if (normalizedEffectiveExitNodeID.isEmpty()) return true
    return normalizedEffectiveExitNodeID !in allowedCandidates
  }

  private fun AutoExitPrefs?.hasDisallowedConcreteManualExitNode(
      allowedCandidates: List<String>
  ): Boolean {
    if (this == null || autoExitNode == NATIVE_AUTO_EXIT_NODE_ANY) return false
    val normalizedEffectiveExitNodeID = effectiveExitNodeID?.trim().orEmpty()
    return normalizedEffectiveExitNodeID.isNotEmpty() &&
        normalizedEffectiveExitNodeID !in allowedCandidates
  }

  companion object {
    private const val MAX_STABLE_READ_ATTEMPTS = 8
    private const val NATIVE_AUTO_EXIT_NODE_ANY = "any"
    private const val NATIVE_AUTO_EXIT_NODE_BLACKHOLE = "auto:any"
    private const val MAX_CLEAR_ATTEMPTS = 3

    fun encodeJSON(candidates: List<String>): String = Json.encodeToString(candidates)
  }
}

object SyspolicyStringArrayJSONBridge {
  fun get(
      key: String,
      productCandidatesJSON: () -> String,
      fallbackValue: () -> String,
  ): String {
    if (key == ALLOWED_SUGGESTED_EXIT_NODES_KEY) {
      return try {
        productCandidatesJSON()
      } catch (_: Throwable) {
        "[]"
      }
    }
    return fallbackValue()
  }
}
