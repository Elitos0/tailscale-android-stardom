// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface AutoExitNodeFallbackDecision {
  data object Keep : AutoExitNodeFallbackDecision

  data class Select(val nodeId: String) : AutoExitNodeFallbackDecision

  data object StopAndClear : AutoExitNodeFallbackDecision
}

/**
 * Chooses one policy-approved, currently reachable owned exit node for the native-auto fallback.
 *
 * This is deliberately a control-plane decision only. It does not rank nodes, probe the public
 * internet, or retry on a timer. A stable ID is selected deterministically so that every input
 * snapshot has one result and no unowned route can enter the mutation boundary.
 */
object PolicyAwareAutoExitNodeFallbackSelector {
  fun decide(
      autoConfigured: Boolean,
      allowedNodeIds: Collection<String>,
      currentEffectiveNodeId: String?,
      peers: Collection<Tailcfg.Node>,
  ): AutoExitNodeFallbackDecision {
    if (!autoConfigured) return AutoExitNodeFallbackDecision.Keep

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
    if (current in eligible) return AutoExitNodeFallbackDecision.Keep
    return eligible.firstOrNull()?.let(AutoExitNodeFallbackDecision::Select)
        ?: AutoExitNodeFallbackDecision.StopAndClear
  }
}

/**
 * App-scoped bridge used only when pinned native Auto cannot resolve an exit candidate. Inputs are
 * StateFlows from the existing policy/netmap/prefs surfaces; each mutation still passes through
 * [ExitNodeMutationBoundary], whose write mutex and fresh policy checks are authoritative.
 */
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

  fun start(scope: CoroutineScope) {
    if (!started.compareAndSet(false, true)) return
    val authAndAccess = combine(authentikState, accessState) { auth, access -> auth to access }
    val managed =
        combine(mdmAllowedSuggestedExitNodes, mdmForcedExitNodeId) { allowed, forced ->
          ManagedSettings(allowed, forced)
        }
    val prefsAndNetmap =
        combine(prefs, netmap) { currentPrefs, currentNetmap -> currentPrefs to currentNetmap }
    scope.launch {
      combine(authAndAccess, managed, prefsAndNetmap, runtimeSnapshot) {
              (auth, access),
              currentManaged,
              (currentPrefs, currentNetmap),
              currentRuntime ->
            Inputs(auth, access, currentManaged, currentPrefs, currentNetmap, currentRuntime)
          }
          .collect { process(it) }
    }
  }

  private suspend fun process(inputs: Inputs) {
    val decision = evaluate(inputs)
    if (decision is AutoExitNodeFallbackDecision.Keep) {
      synchronized(lock) { lastActionKey = null }
      return
    }
    val actionKey = actionKey(inputs, decision)
    synchronized(lock) {
      if (lastActionKey == actionKey) return
      lastActionKey = actionKey
    }

    when (decision) {
      is AutoExitNodeFallbackDecision.Select -> {
        val result =
            runCatching {
                  mutationBoundary.mutateExitNode(ExitNodeMutation.Manual(decision.nodeId))
                }
                .getOrElse { Result.failure(it) }
        if (result.isFailure) {
          revokeSafely("select", result.exceptionOrNull())
        }
      }
      AutoExitNodeFallbackDecision.StopAndClear -> {
        revokeSafely("stop", null)
        runCatching { mutationBoundary.mutateExitNode(ExitNodeMutation.Clear()) }
            .onFailure { report("clear", it) }
            .onSuccess { result -> result.exceptionOrNull()?.let { report("clear", it) } }
      }
      AutoExitNodeFallbackDecision.Keep -> error("Keep is handled before dispatch")
    }
  }

  private fun evaluate(inputs: Inputs): AutoExitNodeFallbackDecision {
    val currentPrefs = inputs.prefs ?: return AutoExitNodeFallbackDecision.Keep
    if (currentPrefs.AutoExitNode != NATIVE_AUTO_EXIT_NODE_ANY) {
      return AutoExitNodeFallbackDecision.Keep
    }
    if (inputs.authentik != AuthentikState.Authorized || inputs.access !is AccessState.Active) {
      // The entitlement controller owns non-Active stop decisions. Do not clear an Auto
      // preference here, so a fresh authorized decision can still be made later.
      return AutoExitNodeFallbackDecision.Keep
    }
    if (inputs.managed.forced.isSet) return AutoExitNodeFallbackDecision.Keep

    // A null map means the backend has not supplied peer availability yet. Keep native Auto in
    // its own fail-closed blackhole state until a concrete map arrives; an empty peer list is a
    // known no-candidate state and is handled by StopAndClear.
    val currentNetmap = inputs.netmap ?: return AutoExitNodeFallbackDecision.Keep
    val peers = currentNetmap.Peers ?: return AutoExitNodeFallbackDecision.Keep
    val allowed =
        AllowedSuggestedExitNodePolicyMapper.map(
            inputs.authentik,
            inputs.access,
            inputs.managed.allowed.toManagedAllowedSuggestedExitNodes(),
        )
    return PolicyAwareAutoExitNodeFallbackSelector.decide(
        autoConfigured = true,
        allowedNodeIds = allowed,
        currentEffectiveNodeId = currentPrefs.activeExitNodeID,
        peers = peers,
    )
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

  private fun SettingState<List<String>?>.toManagedAllowedSuggestedExitNodes():
      ManagedAllowedSuggestedExitNodes =
      if (isSet) ManagedAllowedSuggestedExitNodes.Configured(value?.toList())
      else ManagedAllowedSuggestedExitNodes.Unset

  companion object {
    private const val NATIVE_AUTO_EXIT_NODE_ANY = "any"
  }
}
