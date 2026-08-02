// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExitNodeFallbackSelectorTest {
  @Test
  fun keepsCurrentAllowedOnlineExitBeforeSortingCandidates() {
    val decision =
        PolicyAwareAutoExitNodeFallbackSelector.decide(
            autoConfigured = true,
            allowedNodeIds = setOf("node-a", "node-b"),
            currentEffectiveNodeId = "node-b",
            peers = listOf(exitPeer("node-a"), exitPeer("node-b")),
        )

    assertEquals(AutoExitNodeFallbackDecision.Keep, decision)
  }

  @Test
  fun selectsTheStableFirstAllowedOnlineOwnedExit() {
    val decision =
        PolicyAwareAutoExitNodeFallbackSelector.decide(
            autoConfigured = true,
            allowedNodeIds = setOf("node-b", "node-a"),
            currentEffectiveNodeId = "auto:any",
            peers =
                listOf(
                    exitPeer("node-b"),
                    exitPeer("node-a"),
                    exitPeer("foreign"),
                    exitPeer("mullvad", mullvad = true),
                    exitPeer("offline", online = false),
                    Tailcfg.Node(StableID = "not-exit", Online = true),
                ),
        )

    assertEquals(AutoExitNodeFallbackDecision.Select("node-a"), decision)
  }

  @Test
  fun noAllowedOnlineCandidateIsStopAndClear() {
    val decision =
        PolicyAwareAutoExitNodeFallbackSelector.decide(
            autoConfigured = true,
            allowedNodeIds = setOf("node-a"),
            currentEffectiveNodeId = "node-a",
            peers = listOf(exitPeer("node-a", online = false)),
        )

    assertEquals(AutoExitNodeFallbackDecision.StopAndClear, decision)
  }

  @Test
  fun manualModeIsNeverTouched() {
    val decision =
        PolicyAwareAutoExitNodeFallbackSelector.decide(
            autoConfigured = false,
            allowedNodeIds = emptySet(),
            currentEffectiveNodeId = null,
            peers = emptyList(),
        )

    assertEquals(AutoExitNodeFallbackDecision.Keep, decision)
  }

  private fun exitPeer(
      id: String,
      online: Boolean? = true,
      mullvad: Boolean = false,
  ): Tailcfg.Node =
      Tailcfg.Node(
          StableID = id,
          Name = if (mullvad) "$id.mullvad.ts.net." else "$id.example.net.",
          AllowedIPs = listOf("0.0.0.0/0", "::/0"),
          Online = online,
      )
}

@OptIn(ExperimentalCoroutinesApi::class)
class PolicyAwareAutoExitNodeFallbackControllerTest {
  @Test
  fun selectionUsesBoundaryAndDoesNotDispatchTheSameSnapshotTwice() = runTest {
    val fixture = fixture(prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "auto:any"))
    fixture.controller.start(backgroundScope)
    runCurrent()

    assertEquals(listOf("node-a"), fixture.boundary.mutations)
    fixture.netmap.value = fixture.netmap.value
    runCurrent()
    assertEquals(listOf("node-a"), fixture.boundary.mutations)
  }

  @Test
  fun noCandidateStopsBeforeClearingAndClearIsStillSerializedThroughBoundary() = runTest {
    val fixture =
        fixture(
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            peers = listOf(exitPeer("node-a", online = false)),
            runtimeState = VpnRuntimeState.Running,
        )
    fixture.controller.start(backgroundScope)
    runCurrent()

    assertEquals(1, fixture.runtime.revocations)
    assertEquals(listOf(null), fixture.boundary.mutations)
    assertTrue(fixture.events.indexOf("revoke") < fixture.events.indexOf("clear"))
  }

  @Test
  fun policyShrinkFromCurrentASelectsAllowedB() = runTest {
    val fixture =
        fixture(
            allowed = setOf("node-a", "node-b"),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            peers = listOf(exitPeer("node-a"), exitPeer("node-b")),
        )
    fixture.controller.start(backgroundScope)
    runCurrent()
    assertEquals(emptyList<String>(), fixture.boundary.mutations)

    fixture.allowed.value = AccessState.Active(setOf("node-b"))
    runCurrent()

    assertEquals(listOf("node-b"), fixture.boundary.mutations)
  }

  private fun kotlinx.coroutines.test.TestScope.fixture(
      allowed: Set<String> = setOf("node-a"),
      prefs: Ipn.Prefs? = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "auto:any"),
      peers: List<Tailcfg.Node> = listOf(exitPeer("node-a")),
      runtimeState: VpnRuntimeState = VpnRuntimeState.Idle,
  ): Fixture {
    val authentik = MutableStateFlow(AuthentikState.Authorized)
    val access = MutableStateFlow<AccessState>(AccessState.Active(allowed))
    val mdm = MutableStateFlow(SettingState<List<String>?>(null, false))
    val forced = MutableStateFlow(SettingState<String?>(null, false))
    val prefsFlow = MutableStateFlow(prefs)
    val netmap = MutableStateFlow<Netmap.NetworkMap?>(networkMap(peers))
    val runtime = FakeRuntime(runtimeState)
    val events = mutableListOf<String>()
    val boundary = CapturingBoundary(events, prefsFlow)
    val controller =
        PolicyAwareAutoExitNodeFallbackController(
            authentikState = authentik,
            accessState = access,
            mdmAllowedSuggestedExitNodes = mdm,
            mdmForcedExitNodeId = forced,
            prefs = prefsFlow,
            netmap = netmap,
            runtimeSnapshot = runtime.snapshot,
            runtime = runtime,
            mutationBoundary = boundary,
        )
    return Fixture(controller, access, netmap, runtime, boundary, events)
  }

  private data class Fixture(
      val controller: PolicyAwareAutoExitNodeFallbackController,
      val allowed: MutableStateFlow<AccessState>,
      val netmap: MutableStateFlow<Netmap.NetworkMap?>,
      val runtime: FakeRuntime,
      val boundary: CapturingBoundary,
      val events: MutableList<String>,
  )

  private class FakeRuntime(initial: VpnRuntimeState) : VpnEntitlementRuntime {
    private val _snapshot =
        MutableStateFlow<VpnRuntimeSnapshot>(
            VpnRuntimeSnapshot(initial, if (initial == VpnRuntimeState.Idle) 0 else 1))
    val snapshot: StateFlow<VpnRuntimeSnapshot> = _snapshot
    var revocations = 0

    override val state: StateFlow<VpnRuntimeState> =
        object : StateFlow<VpnRuntimeState> by MutableStateFlow(initial) {
          override val value: VpnRuntimeState
            get() = _snapshot.value.state
        }

    override fun revoke() {
      revocations++
    }
  }

  private class CapturingBoundary(
      private val events: MutableList<String>,
      private val prefs: MutableStateFlow<Ipn.Prefs?>,
  ) : ExitNodeMutationBoundary {
    val mutations = mutableListOf<String?>()

    override suspend fun mutateExitNode(mutation: ExitNodeMutation): Result<Unit> {
      when (mutation) {
        is ExitNodeMutation.Clear -> {
          events += "clear"
          mutations += null
          prefs.value = Ipn.Prefs()
        }
        is ExitNodeMutation.Manual -> {
          events += "manual"
          mutations += mutation.nodeId
          prefs.value = Ipn.Prefs(ExitNodeID = mutation.nodeId)
        }
        is ExitNodeMutation.Auto -> error("fallback must not dispatch native Auto")
      }
      return Result.success(Unit)
    }
  }

  private fun networkMap(peers: List<Tailcfg.Node>) =
      Netmap.NetworkMap(
          SelfNode = Tailcfg.Node(StableID = "self"),
          Peers = peers,
          Domain = "example",
          UserProfiles = emptyMap(),
          TKAEnabled = false,
      )

  private fun exitPeer(id: String, online: Boolean? = true): Tailcfg.Node =
      Tailcfg.Node(
          StableID = id,
          Name = "$id.example.net.",
          AllowedIPs = listOf("0.0.0.0/0", "::/0"),
          Online = online,
      )
}
