// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
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
    private val prefs: StateFlow<Ipn.Prefs?>,
    private val runtimeSnapshot: StateFlow<VpnRuntimeSnapshot>,
    private val notifyPolicyChanged: () -> Unit,
    private val revokeDisallowedAutoExitNode: () -> Unit,
    private val candidateMapper:
        (AuthentikState, AccessState, ManagedAllowedSuggestedExitNodes) -> List<String> =
        AllowedSuggestedExitNodePolicyMapper::map,
    private val jsonEncoder: (List<String>) -> String = ::encodeJSON,
    private val onCallbackError: (String, Throwable) -> Unit = { _, _ -> },
) {
  private data class AutoExitPrefs(
      val autoExitNode: String?,
      val effectiveExitNodeID: String?,
  )

  private data class Inputs(
      val authentikState: AuthentikState,
      val accessState: AccessState,
      val mdm: ManagedAllowedSuggestedExitNodes,
      val prefs: AutoExitPrefs?,
      val runtimeSnapshot: VpnRuntimeSnapshot,
  )

  private data class Evaluation(
      val candidates: List<String>,
      val json: String,
      val prefs: AutoExitPrefs?,
      val runtimeSnapshot: VpnRuntimeSnapshot,
  )

  private val observerStarted = AtomicBoolean(false)
  private val lock = Any()
  private var lastNativeCandidates: List<String>? = null
  private var revokedRuntimeGeneration: Long? = null

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
    return !currentPrefs.hasDisallowedEffectiveAutoExitNode(evaluation.candidates)
  }

  fun start(scope: CoroutineScope) {
    if (!observerStarted.compareAndSet(false, true)) return
    scope.launch {
      combine(
              authentikState,
              accessState,
              mdmAllowedSuggestedExitNodes,
              prefs,
              runtimeSnapshot,
          ) { currentAuthentik, currentAccess, currentMdm, currentPrefs, currentRuntime ->
            Inputs(
                authentikState = currentAuthentik,
                accessState = currentAccess.snapshot(),
                mdm = currentMdm.toManagedAllowList(),
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
      Evaluation(candidates, jsonEncoder(candidates), inputs.prefs, inputs.runtimeSnapshot)
    } catch (_: Throwable) {
      failClosed(inputs)
    }
  }

  private fun failClosed(inputs: Inputs): Evaluation =
      Evaluation(emptyList(), "[]", inputs.prefs, inputs.runtimeSnapshot)

  private fun process(evaluation: Evaluation) {
    val (shouldRevoke, notificationChange) =
        synchronized(lock) {
          val revoke = shouldRevokeLocked(evaluation)
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
          revoke to notify
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

  private fun shouldRevokeLocked(evaluation: Evaluation): Boolean {
    val currentRuntime = evaluation.runtimeSnapshot
    val isUnsafe = evaluation.prefs.hasDisallowedEffectiveAutoExitNode(evaluation.candidates)
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

  private fun AutoExitPrefs?.hasDisallowedEffectiveAutoExitNode(
      allowedCandidates: List<String>
  ): Boolean {
    if (this == null) return true
    if (autoExitNode != NATIVE_AUTO_EXIT_NODE_ANY) return false
    val normalizedEffectiveExitNodeID = effectiveExitNodeID?.trim().orEmpty()
    if (normalizedEffectiveExitNodeID == NATIVE_AUTO_EXIT_NODE_BLACKHOLE) return false
    if (normalizedEffectiveExitNodeID.isEmpty()) return true
    return normalizedEffectiveExitNodeID !in allowedCandidates
  }

  companion object {
    private const val MAX_STABLE_READ_ATTEMPTS = 8
    private const val NATIVE_AUTO_EXIT_NODE_ANY = "any"
    private const val NATIVE_AUTO_EXIT_NODE_BLACKHOLE = "auto:any"

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
