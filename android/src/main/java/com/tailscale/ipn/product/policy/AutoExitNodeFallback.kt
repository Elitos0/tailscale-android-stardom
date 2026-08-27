// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.util.TSLog
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface AutoExitNodeFallbackDecision {
  data object Keep : AutoExitNodeFallbackDecision

  /** Temporary concrete exit while DesiredExitMode remains Auto. */
  data class Select(val nodeId: String) : AutoExitNodeFallbackDecision

  data object StopAndClear : AutoExitNodeFallbackDecision
}

/**
 * Chooses one policy-approved, currently reachable owned exit node for the native-auto fallback.
 *
 * Native auto gets a grace window once eligible peers exist. Capability presence alone never proves
 * resolution. Selection is deterministic (lexicographically first stable ID).
 */
object PolicyAwareAutoExitNodeFallbackSelector {
  fun decide(
      autoConfigured: Boolean,
      allowedNodeIds: Collection<String>,
      currentEffectiveNodeId: String?,
      peers: Collection<Tailcfg.Node>,
      nativeGraceActive: Boolean = false,
  ): AutoExitNodeFallbackDecision {
    if (!autoConfigured) {
      TSLog.d("AutoExitFallback", "decide: autoConfigured=false -> Keep")
      return AutoExitNodeFallbackDecision.Keep
    }

    val allowed = allowedNodeIds.map(String::trim).filter(String::isNotEmpty).toSet()
    val eligible =
        peers
            .asSequence()
            .filter { it.Online == true && it.isExitNode && !it.isMullvadNode }
            .map { it.StableID.trim() }
            .filter { it.isNotEmpty() && it in allowed }
            .distinct()
            .sorted()
            .toList()
    val current = currentEffectiveNodeId?.trim().orEmpty()
    TSLog.d(
        "AutoExitFallback",
        "decide evaluation: totalPeers=${peers.size} allowedCount=${allowed.size} eligible=$eligible currentEffective=$current nativeGraceActive=$nativeGraceActive")
    if (current in eligible) {
      TSLog.d(
          "AutoExitFallback",
          "decide: current effective node ($current) is in eligible set -> Keep")
      return AutoExitNodeFallbackDecision.Keep
    }
    // During grace, keep native auto blackhole even if still unresolved.
    if (nativeGraceActive && eligible.isNotEmpty()) {
      TSLog.d("AutoExitFallback", "decide: native grace active and eligible peers present -> Keep")
      return AutoExitNodeFallbackDecision.Keep
    }
    val decision =
        eligible.firstOrNull()?.let(AutoExitNodeFallbackDecision::Select)
            ?: AutoExitNodeFallbackDecision.StopAndClear
    TSLog.d("AutoExitFallback", "decide: calculated decision=$decision")
    return decision
  }
}

class PolicyAwareAutoExitNodeFallbackController(
    private val authentikState: StateFlow<AuthentikState>,
    private val accessState: StateFlow<AccessState>,
    private val mdmAllowedSuggestedExitNodes: StateFlow<SettingState<List<String>?>>,
    private val mdmForcedExitNodeId: StateFlow<SettingState<String?>>,
    private val prefs: StateFlow<Ipn.Prefs?>,
    private val netmap: StateFlow<Netmap.NetworkMap?>,
    private val runtimeSnapshot: StateFlow<VpnRuntimeSnapshot>,
    private val runtime: VpnEntitlementRuntime,
    private val mutationBoundary: ExitNodeMutationBoundary,
    private val desiredExitModeStore: DesiredExitModeStore? = null,
    private val stopThenClear: (suspend (VpnStopReason) -> Result<Unit>)? = null,
    private val nativeGrace: Duration = Duration.ZERO,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {
  private data class ManagedSettings(
      val allowed: SettingState<List<String>?>,
      val forced: SettingState<String?>,
  )

  private data class Inputs(
      val authentik: AuthentikState,
      val access: AccessState,
      val managed: ManagedSettings,
      val prefs: Ipn.Prefs?,
      val netmap: Netmap.NetworkMap?,
      val runtime: VpnRuntimeSnapshot,
  )

  private data class ActionKey(
      val decision: AutoExitNodeFallbackDecision,
      val currentEffectiveNodeId: String?,
      val allowedNodeIds: List<String>,
      val eligibleNodeIds: List<String>,
      val runtimeGeneration: Long,
  )

  private val started = AtomicBoolean(false)
  private val lock = Any()
  private var lastActionKey: ActionKey? = null
  private var graceDeadlineMillis: Long? = null
  private var graceEligibleSignature: List<String>? = null

  fun start(scope: CoroutineScope) {
    if (!started.compareAndSet(false, true)) return
    scope.launch {
      combine(
              listOf(
                  authentikState,
                  accessState,
                  mdmAllowedSuggestedExitNodes,
                  mdmForcedExitNodeId,
                  prefs,
                  netmap,
                  runtimeSnapshot,
              )) { values ->
                @Suppress("UNCHECKED_CAST")
                Inputs(
                    authentik = values[0] as AuthentikState,
                    access = values[1] as AccessState,
                    managed =
                        ManagedSettings(
                            allowed = values[2] as SettingState<List<String>?>,
                            forced = values[3] as SettingState<String?>,
                        ),
                    prefs = values[4] as Ipn.Prefs?,
                    netmap = values[5] as Netmap.NetworkMap?,
                    runtime = values[6] as VpnRuntimeSnapshot,
                )
              }
          .collect { process(scope, it) }
    }
  }

  private suspend fun process(scope: CoroutineScope, inputs: Inputs) {
    val decision = evaluate(inputs)
    if (decision is AutoExitNodeFallbackDecision.Keep) {
      synchronized(lock) { lastActionKey = null }
      return
    }
    TSLog.d(
        "AutoExitFallback",
        "operation=decision desired=auto prefs=${inputs.prefs != null} netmap=${inputs.netmap != null} effective=${inputs.prefs?.activeExitNodeID ?: "none"} decision=$decision runtime=${inputs.runtime.state} generation=${inputs.runtime.generation}")
    val actionKey = actionKey(inputs, decision)
    synchronized(lock) {
      if (lastActionKey == actionKey) return
      // Only record after success — set tentatively; clear on failure below.
      lastActionKey = actionKey
    }

    when (decision) {
      is AutoExitNodeFallbackDecision.Select -> {
        TSLog.d("AutoExitFallback", "applying fallback mutation: Select(${decision.nodeId})")
        // Preserve DesiredExitMode.Auto — do not rewrite user intent to Manual.
        val result =
            runCatching {
                  mutationBoundary.mutateExitNode(ExitNodeMutation.Manual(decision.nodeId))
                }
                .getOrElse { Result.failure(it) }
        if (result.isFailure) {
          TSLog.e(
              "AutoExitFallback",
              "fallback mutation Select(${decision.nodeId}) failed: ${result.exceptionOrNull()?.message}",
              result.exceptionOrNull())
          synchronized(lock) { if (lastActionKey == actionKey) lastActionKey = null }
          revokeSafely("select", result.exceptionOrNull())
        } else {
          TSLog.d("AutoExitFallback", "fallback mutation Select(${decision.nodeId}) succeeded")
        }
      }
      AutoExitNodeFallbackDecision.StopAndClear -> {
        TSLog.d("AutoExitFallback", "applying fallback action: StopAndClear")
        val clearer = stopThenClear
        if (clearer != null) {
          clearer(VpnStopReason.EmptyCandidatePool)
              .onFailure {
                TSLog.e("AutoExitFallback", "StopAndClear stopThenClear failed: ${it.message}", it)
                synchronized(lock) { if (lastActionKey == actionKey) lastActionKey = null }
                report("stop-clear", it)
                scope.launch {
                  delay(250)
                  process(scope, inputs)
                }
              }
              .onSuccess { TSLog.d("AutoExitFallback", "StopAndClear stopThenClear succeeded") }
        } else {
          if (inputs.runtime.state.isStartingOrRunning()) {
            TSLog.d("AutoExitFallback", "StopAndClear revoking runtime state")
            revokeSafely("stop", null)
          }
          // Only clear when idle; otherwise re-arm.
          if (!inputs.runtime.state.isStartingOrRunning()) {
            TSLog.d("AutoExitFallback", "StopAndClear mutating exit node to Clear")
            runCatching { mutationBoundary.mutateExitNode(ExitNodeMutation.Clear()) }
                .onFailure {
                  TSLog.e(
                      "AutoExitFallback", "StopAndClear mutation Clear failed: ${it.message}", it)
                  synchronized(lock) { if (lastActionKey == actionKey) lastActionKey = null }
                  report("clear", it)
                }
                .onSuccess { result ->
                  result.exceptionOrNull()?.let {
                    TSLog.e(
                        "AutoExitFallback",
                        "StopAndClear mutation Clear result error: ${it.message}",
                        it)
                    synchronized(lock) { if (lastActionKey == actionKey) lastActionKey = null }
                    report("clear", it)
                  } ?: TSLog.d("AutoExitFallback", "StopAndClear mutation Clear succeeded")
                }
          } else {
            synchronized(lock) { if (lastActionKey == actionKey) lastActionKey = null }
          }
        }
      }
      AutoExitNodeFallbackDecision.Keep -> error("Keep is handled before dispatch")
    }
  }

  private fun evaluate(inputs: Inputs): AutoExitNodeFallbackDecision {
    val currentPrefs = inputs.prefs
    if (currentPrefs == null) {
      TSLog.d("AutoExitFallback", "evaluate: prefs is null -> Keep")
      return AutoExitNodeFallbackDecision.Keep
    }
    val desired = desiredExitModeStore?.mode?.value
    val autoConfigured =
        desired is DesiredExitMode.Auto ||
            (desired == null && currentPrefs.AutoExitNode == NATIVE_AUTO_EXIT_NODE_ANY)
    if (!autoConfigured) {
      TSLog.d(
          "AutoExitFallback",
          "evaluate: autoConfigured=false (desired=$desired, AutoExitNode=${currentPrefs.AutoExitNode}) -> Keep")
      return AutoExitNodeFallbackDecision.Keep
    }
    if (inputs.authentik != AuthentikState.Authorized || inputs.access !is AccessState.Active) {
      TSLog.d(
          "AutoExitFallback",
          "evaluate: unauthenticated/inactive (authentik=${inputs.authentik}, access=${inputs.access}) -> Keep")
      return AutoExitNodeFallbackDecision.Keep
    }
    if (inputs.managed.forced.isSet) {
      TSLog.d("AutoExitFallback", "evaluate: mdm forced exit node is set -> Keep")
      return AutoExitNodeFallbackDecision.Keep
    }

    val currentNetmap = inputs.netmap
    if (currentNetmap == null) {
      TSLog.d("AutoExitFallback", "evaluate: netmap is null -> Keep")
      return AutoExitNodeFallbackDecision.Keep
    }
    val peers = currentNetmap.Peers.orEmpty()
    val allowed =
        AllowedSuggestedExitNodePolicyMapper.map(
            inputs.authentik,
            inputs.access,
            inputs.managed.allowed.toManagedAllowedSuggestedExitNodes(),
        )
    val eligible =
        peers
            .filter { it.Online == true && it.isExitNode && !it.isMullvadNode }
            .map { it.StableID.trim() }
            .filter { it.isNotEmpty() && it in allowed }
            .distinct()
            .sorted()

    val graceActive = updateGraceWindow(eligible)
    TSLog.d(
        "AutoExitFallback",
        "evaluate inputs: peersCount=${peers.size} allowedCount=${allowed.size} eligibleCount=${eligible.size} eligible=$eligible graceActive=$graceActive")
    return PolicyAwareAutoExitNodeFallbackSelector.decide(
        autoConfigured = true,
        allowedNodeIds = allowed,
        currentEffectiveNodeId = currentPrefs.activeExitNodeID,
        peers = peers,
        nativeGraceActive = graceActive,
    )
  }

  private fun updateGraceWindow(eligible: List<String>): Boolean {
    synchronized(lock) {
      if (eligible.isEmpty()) {
        graceDeadlineMillis = null
        graceEligibleSignature = null
        return false
      }
      val graceMillis = nativeGrace.toMillis()
      // Zero/negative grace means "no native window" (tests and fail-closed paths).
      if (graceMillis <= 0L) {
        graceDeadlineMillis = null
        graceEligibleSignature = eligible
        return false
      }
      if (graceEligibleSignature != eligible) {
        graceEligibleSignature = eligible
        graceDeadlineMillis = nowMillis() + graceMillis
      }
      val deadline = graceDeadlineMillis ?: return false
      return nowMillis() < deadline
    }
  }

  private fun actionKey(
      inputs: Inputs,
      decision: AutoExitNodeFallbackDecision,
  ): ActionKey {
    val allowed =
        if (inputs.access is AccessState.Active) {
          AllowedSuggestedExitNodePolicyMapper.map(
              inputs.authentik,
              inputs.access,
              inputs.managed.allowed.toManagedAllowedSuggestedExitNodes(),
          )
        } else {
          emptyList()
        }
    val eligible =
        inputs.netmap
            ?.Peers
            .orEmpty()
            .filter { it.Online == true && it.isExitNode && !it.isMullvadNode }
            .map { it.StableID.trim() }
            .filter { it.isNotEmpty() && it in allowed }
            .distinct()
            .sorted()
    return ActionKey(
        decision = decision,
        currentEffectiveNodeId = inputs.prefs?.activeExitNodeID?.trim(),
        allowedNodeIds = allowed,
        eligibleNodeIds = eligible,
        runtimeGeneration = inputs.runtime.generation,
    )
  }

  private fun revokeSafely(operation: String, failure: Throwable?) {
    runCatching { runtime.revoke() }.onFailure { report(operation, it) }
    failure?.let { report(operation, it) }
  }

  private fun report(operation: String, error: Throwable) {
    runCatching { onError(operation, error) }
  }

  private fun VpnRuntimeState.isStartingOrRunning(): Boolean =
      this == VpnRuntimeState.Starting || this == VpnRuntimeState.Running

  private fun SettingState<List<String>?>.toManagedAllowedSuggestedExitNodes():
      ManagedAllowedSuggestedExitNodes =
      if (isSet) ManagedAllowedSuggestedExitNodes.Configured(value?.toList())
      else ManagedAllowedSuggestedExitNodes.Unset

  companion object {
    private const val NATIVE_AUTO_EXIT_NODE_ANY = "any"
  }
}
