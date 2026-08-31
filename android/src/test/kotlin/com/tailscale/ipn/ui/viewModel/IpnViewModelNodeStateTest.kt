// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.product.policy.DesiredExitMode
import com.tailscale.ipn.product.policy.DesiredExitModeStore
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class IpnViewModelNodeStateTest {
  private class FakeDesiredExitModeStore(initial: DesiredExitMode? = null) : DesiredExitModeStore {
    private val _mode = MutableStateFlow(initial)
    override val mode: StateFlow<DesiredExitMode?> = _mode.asStateFlow()

    override fun set(mode: DesiredExitMode) {
      _mode.value = mode
    }

    override fun clear() {
      _mode.value = null
    }
  }

  private val dispatcher = StandardTestDispatcher()
  private lateinit var originalLogWrapper: LibtailscaleWrapper

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    originalLogWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper =
        mock<LibtailscaleWrapper>().also {
          doNothing().`when`(it).sendLog(anyString(), anyString())
        }
  }

  @After
  fun tearDown() {
    TSLog.libtailscaleWrapper = originalLogWrapper
    Dispatchers.resetMain()
  }

  private fun exitNodePeer(id: String, label: String, online: Boolean = true) =
      Tailcfg.Node(
          StableID = id,
          Name = label,
          ComputedName = label,
          AllowedIPs = listOf("0.0.0.0/0", "::/0"),
          Online = online,
      )

  private fun networkMap(vararg peers: Tailcfg.Node) =
      Netmap.NetworkMap(
          SelfNode = Tailcfg.Node(),
          Peers = peers.toList(),
          Domain = "tailnet.example.com",
          UserProfiles = emptyMap(),
          TKAEnabled = false,
      )

  @Test
  fun selectedExitNodeWithVpnInactiveComputesActiveNotRunning() = runTest {
    val prefsFlow =
        MutableStateFlow<Ipn.Prefs?>(
            Ipn.Prefs().apply {
              ExitNodeID = "node-1"
              InternalExitNodePrior = "node-1"
            })
    val netmapFlow =
        MutableStateFlow<Netmap.NetworkMap?>(networkMap(exitNodePeer("node-1", "Exit Node 1")))
    val vpnActiveFlow = MutableStateFlow(false)
    val desiredExitStore = FakeDesiredExitModeStore(DesiredExitMode.Manual("node-1"))

    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            desiredExitModeStoreProvider = { desiredExitStore },
            vpnActiveFlowProvider = { vpnActiveFlow },
            prefsFlow = prefsFlow,
            netmapFlow = netmapFlow,
        )
    advanceUntilIdle()

    assertEquals(IpnViewModel.NodeState.ACTIVE_NOT_RUNNING, viewModel.nodeState.value)
  }

  @Test
  fun selectedExitNodeTransitionsToActiveAndRunningWhenVpnBecomesActiveAndBack() = runTest {
    val prefsFlow =
        MutableStateFlow<Ipn.Prefs?>(
            Ipn.Prefs().apply {
              ExitNodeID = "node-1"
              InternalExitNodePrior = "node-1"
            })
    val netmapFlow =
        MutableStateFlow<Netmap.NetworkMap?>(networkMap(exitNodePeer("node-1", "Exit Node 1")))
    val vpnActiveFlow = MutableStateFlow(false)
    val desiredExitStore = FakeDesiredExitModeStore(DesiredExitMode.Manual("node-1"))

    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            desiredExitModeStoreProvider = { desiredExitStore },
            vpnActiveFlowProvider = { vpnActiveFlow },
            prefsFlow = prefsFlow,
            netmapFlow = netmapFlow,
        )
    advanceUntilIdle()

    assertEquals(IpnViewModel.NodeState.ACTIVE_NOT_RUNNING, viewModel.nodeState.value)

    // VPN tunnel becomes active
    vpnActiveFlow.value = true
    advanceUntilIdle()
    assertEquals(IpnViewModel.NodeState.ACTIVE_AND_RUNNING, viewModel.nodeState.value)

    // VPN tunnel becomes inactive
    vpnActiveFlow.value = false
    advanceUntilIdle()
    assertEquals(IpnViewModel.NodeState.ACTIVE_NOT_RUNNING, viewModel.nodeState.value)
  }

  @Test
  fun autoExitNodeWithoutConcretePeerComputesAutoPendingRegardlessOfVpnActive() = runTest {
    val prefsFlow =
        MutableStateFlow<Ipn.Prefs?>(
            Ipn.Prefs().apply {
              AutoExitNode = "any"
              ExitNodeID = null
              InternalExitNodePrior = null
            })
    val netmapFlow = MutableStateFlow<Netmap.NetworkMap?>(networkMap())
    val vpnActiveFlow = MutableStateFlow(false)
    val desiredExitStore = FakeDesiredExitModeStore(DesiredExitMode.Auto)

    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            desiredExitModeStoreProvider = { desiredExitStore },
            vpnActiveFlowProvider = { vpnActiveFlow },
            prefsFlow = prefsFlow,
            netmapFlow = netmapFlow,
        )
    advanceUntilIdle()

    assertEquals(IpnViewModel.NodeState.AUTO_PENDING, viewModel.nodeState.value)

    vpnActiveFlow.value = true
    advanceUntilIdle()
    assertEquals(IpnViewModel.NodeState.AUTO_PENDING, viewModel.nodeState.value)
  }

  @Test
  fun autoExitNodeWithConcretePeerTransitionsWithVpnActive() = runTest {
    val prefsFlow =
        MutableStateFlow<Ipn.Prefs?>(
            Ipn.Prefs().apply {
              AutoExitNode = "any"
              ExitNodeID = "auto-node-1"
              InternalExitNodePrior = null
            })
    val netmapFlow =
        MutableStateFlow<Netmap.NetworkMap?>(
            networkMap(exitNodePeer("auto-node-1", "Auto Exit Node 1")))
    val vpnActiveFlow = MutableStateFlow(false)
    val desiredExitStore = FakeDesiredExitModeStore(DesiredExitMode.Auto)

    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            desiredExitModeStoreProvider = { desiredExitStore },
            vpnActiveFlowProvider = { vpnActiveFlow },
            prefsFlow = prefsFlow,
            netmapFlow = netmapFlow,
        )
    advanceUntilIdle()

    assertEquals(IpnViewModel.NodeState.ACTIVE_NOT_RUNNING, viewModel.nodeState.value)

    vpnActiveFlow.value = true
    advanceUntilIdle()
    assertEquals(IpnViewModel.NodeState.ACTIVE_AND_RUNNING, viewModel.nodeState.value)
  }

  @Test
  fun offlineExitNodeComputesOfflineState() = runTest {
    val prefsFlow =
        MutableStateFlow<Ipn.Prefs?>(
            Ipn.Prefs().apply {
              ExitNodeID = "offline-node"
              InternalExitNodePrior = "offline-node"
            })
    val netmapFlow =
        MutableStateFlow<Netmap.NetworkMap?>(
            networkMap(exitNodePeer("offline-node", "Offline Node", online = false)))
    val vpnActiveFlow = MutableStateFlow(true)
    val desiredExitStore = FakeDesiredExitModeStore(DesiredExitMode.Manual("offline-node"))

    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            desiredExitModeStoreProvider = { desiredExitStore },
            vpnActiveFlowProvider = { vpnActiveFlow },
            prefsFlow = prefsFlow,
            netmapFlow = netmapFlow,
        )
    advanceUntilIdle()

    assertEquals(IpnViewModel.NodeState.OFFLINE_ENABLED, viewModel.nodeState.value)
  }
}
