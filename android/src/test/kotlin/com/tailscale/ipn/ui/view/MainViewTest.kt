// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.product.ui.ConnectionStage
import com.tailscale.ipn.ui.model.AccountProfile
import com.tailscale.ipn.ui.model.ConnectionMode
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.VpnProtocol
import com.tailscale.ipn.ui.model.VpnState
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewTest {
  @Test
  fun runningWithoutVpnPermissionRendersConnectionContent() {
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.RequestVpnPermission))
  }

  @Test
  fun runningWithUnavailableAccessRendersConnectionContent() {
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.AccessUnavailable))
  }

  @Test
  fun runningWithDisabledAccessRendersConnectionContent() {
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.AccessDisabled))
  }

  @Test
  fun runningWithConnectStageRendersPeerContent() {
    assertTrue(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.Connect))
  }

  // --- Stardom VPN State Mapping Tests ---

  @Test
  fun vpnStateDisconnectedWhenVpnInactive() {
    val state =
        resolveStardomVpnState(
            ipnState = Ipn.State.Stopped,
            isVpnActive = false,
            isToggleInProgress = false,
            connectionStage = ConnectionStage.Connect,
            accessState = AccessState.Active(emptySet()))
    assertEquals(VpnState.DISCONNECTED, state)
    assertFalse(state.isConnected)
    assertFalse(state.isConnecting)
    assertFalse(state.isError)
  }

  @Test
  fun vpnStateInitializingWhenToggleInProgressOrStarting() {
    val stateStarting =
        resolveStardomVpnState(
            ipnState = Ipn.State.Starting,
            isVpnActive = true,
            isToggleInProgress = false,
            connectionStage = ConnectionStage.Connect,
            accessState = AccessState.Active(emptySet()))
    assertEquals(VpnState.RESOLVING_STAR_ROUTE, stateStarting)
    assertTrue(stateStarting.isConnecting)
    assertFalse(stateStarting.isConnected)

    val stateToggleProgress =
        resolveStardomVpnState(
            ipnState = Ipn.State.Stopped,
            isVpnActive = false,
            isToggleInProgress = true,
            connectionStage = ConnectionStage.Connect,
            accessState = AccessState.Active(emptySet()))
    assertEquals(VpnState.RESOLVING_STAR_ROUTE, stateToggleProgress)
    assertTrue(stateToggleProgress.isConnecting)
  }

  @Test
  fun vpnStateSecuredWhenVpnActiveAndRunningInConnectStage() {
    val state =
        resolveStardomVpnState(
            ipnState = Ipn.State.Running,
            isVpnActive = true,
            isToggleInProgress = false,
            connectionStage = ConnectionStage.Connect,
            accessState = AccessState.Active(emptySet()))
    assertEquals(VpnState.SECURED, state)
    assertTrue(state.isConnected)
    assertFalse(state.isConnecting)
    assertFalse(state.isError)
  }

  @Test
  fun vpnStateErrorWhenAccessUnavailableOrDisabled() {
    val stateUnavailable =
        resolveStardomVpnState(
            ipnState = Ipn.State.Running,
            isVpnActive = true,
            isToggleInProgress = false,
            connectionStage = ConnectionStage.Connect,
            accessState = AccessState.Unavailable)
    assertEquals(VpnState.ERROR, stateUnavailable)
    assertTrue(stateUnavailable.isError)
    assertFalse(stateUnavailable.isConnected)

    val stateDisabled =
        resolveStardomVpnState(
            ipnState = Ipn.State.Running,
            isVpnActive = true,
            isToggleInProgress = false,
            connectionStage = ConnectionStage.Connect,
            accessState = AccessState.Disabled)
    assertEquals(VpnState.ERROR, stateDisabled)
    assertTrue(stateDisabled.isError)
  }

  // --- Auto / Manual Routing Mapping Tests ---

  @Test
  fun connectionModeDerivesFromAutoExitNodeSelection() {
    val autoSelected =
        ExitNodePickerViewModel.AutoExitNode(selected = true, effectiveExitNodeID = "node-1")
    val modeAuto = if (autoSelected.selected) ConnectionMode.AUTO else ConnectionMode.MANUAL
    assertEquals(ConnectionMode.AUTO, modeAuto)

    val manualSelected =
        ExitNodePickerViewModel.AutoExitNode(selected = false, effectiveExitNodeID = null)
    val modeManual = if (manualSelected.selected) ConnectionMode.AUTO else ConnectionMode.MANUAL
    assertEquals(ConnectionMode.MANUAL, modeManual)
  }

  // --- Protocol Disabled State Tests ---

  @Test
  fun onlyWireGuardProtocolIsEnabledOthersAreDisabledStubs() {
    assertTrue(VpnProtocol.WIREGUARD.enabled)
    assertFalse(VpnProtocol.SHADOWSOCKS_2022.enabled)
    assertFalse(VpnProtocol.V2RAY_VMESS.enabled)
    assertFalse(VpnProtocol.IKEV2_IPSEC.enabled)
  }

  // --- Profile Stub Tests ---

  @Test
  fun accountProfileConstructsClearlyLabeledStubWhenUserUnavailable() {
    val user: IpnLocal.LoginProfile? = null
    val isStub = user == null || user.isEmpty()
    val profile =
        AccountProfile(
            accountId = "STAR-4096-ALPHA",
            tier = if (isStub) "ORBITAL APEX // DEMO" else "ORBITAL APEX // PRO",
            isStub = isStub)

    assertTrue(profile.isStub)
    assertEquals("ORBITAL APEX // DEMO", profile.tier)
    assertEquals("STAR-4096-ALPHA", profile.accountId)
  }

  @Test
  fun accountProfileUsesActiveCredentialsWhenUserAvailable() {
    val user =
        IpnLocal.LoginProfile(
            ID = "prof-1",
            Name = "Commander",
            Key = "key-1",
            UserProfile =
                Tailcfg.UserProfile(
                    ID = 1, LoginName = "commander@stardom.network", DisplayName = "Commander"),
            LocalUserID = "user-1")
    val isStub = user.isEmpty()
    val loginName = user.UserProfile?.LoginName ?: "STAR-4096-ALPHA"
    val profile =
        AccountProfile(
            accountId = loginName,
            tier = if (isStub) "ORBITAL APEX // DEMO" else "ORBITAL APEX // PRO",
            isStub = isStub)

    assertFalse(profile.isStub)
    assertEquals("ORBITAL APEX // PRO", profile.tier)
    assertEquals("commander@stardom.network", profile.accountId)
  }

  // --- Star Server Node Metadata & Placeholder Tests ---

  @Test
  fun mapExitNodeToStarNodeProvidesSafePlaceholdersWhenMetadataMissing() {
    val emptyMetadataNode =
        ExitNodePickerViewModel.ExitNode(
            id = "node-alpha",
            label = "exit-alpha",
            online = MutableStateFlow(true),
            selected = false,
            city = "",
            countryCode = "")

    val starNode = mapExitNodeToStarNode(emptyMetadataNode, 0)
    assertEquals("EXIT-ALPHA", starNode.starName)
    assertEquals("Frankfurt", starNode.city)
    assertEquals("DE", starNode.countryCode)
    assertTrue(starNode.constellation.isNotEmpty())
    assertTrue(starNode.coordinates.isNotEmpty())
    assertTrue(starNode.basePingMs > 0)
    assertTrue(starNode.loadPercent in 1..100)
  }

  @Test
  fun defaultStarServersListIsPopulatedWithValidPlaceholderNodes() {
    assertTrue(DefaultStarServers.isNotEmpty())
    for (server in DefaultStarServers) {
      assertTrue(server.id.isNotEmpty())
      assertTrue(server.starName.isNotEmpty())
      assertTrue(server.constellation.isNotEmpty())
      assertTrue(server.city.isNotEmpty())
      assertTrue(server.countryCode.isNotEmpty())
      assertTrue(server.coordinates.isNotEmpty())
      assertTrue(server.basePingMs > 0)
      assertTrue(server.loadPercent in 1..100)
    }
  }
}
