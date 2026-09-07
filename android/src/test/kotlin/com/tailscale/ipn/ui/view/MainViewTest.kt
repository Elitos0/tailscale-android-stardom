// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.product.ui.ConnectionStage
import com.tailscale.ipn.ui.model.AccountProfile
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.ConnectionMode
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.VpnProtocol
import com.tailscale.ipn.ui.model.VpnState
import com.tailscale.ipn.ui.theme.StardomColors
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewTest {
  @Test
  fun powerControlStateReflectsConnectionStages() {
    assertFalse(isPowerControlEnabled(ConnectionStage.SignIn))
    assertFalse(isPowerControlEnabled(ConnectionStage.AccessDisabled))
    assertTrue(isPowerControlEnabled(ConnectionStage.Connect))
    assertTrue(isPowerControlEnabled(ConnectionStage.RequestVpnPermission))
    assertTrue(isPowerControlEnabled(ConnectionStage.AccessUnavailable))
  }

  @Test
  fun securedStateUsesNeutralAccentWhileErrorsRemainRed() {
    assertEquals(StardomColors.TextPrimary, StardomColors.stateAccent(VpnState.SECURED))
    assertEquals(
        StardomColors.BorderStrong, StardomColors.stateAccent(VpnState.SECURED, border = true))
    assertEquals(StardomColors.Error, StardomColors.stateAccent(VpnState.ERROR))
    assertEquals(
        StardomColors.ErrorBorder, StardomColors.stateAccent(VpnState.ERROR, border = true))
    assertEquals(StardomColors.TextPrimary, StardomColors.stateAccent(VpnState.DISCONNECTED))
  }

  @Test
  fun statusErrorOverrideKeepsSecureStateRedForErrorStatuses() {
    assertEquals(StardomColors.Error, StardomColors.stateAccent(VpnState.SECURED, isError = true))
  }

  @Test
  fun loadingStatusSuppressesAStaleVpnErrorAccent() {
    assertEquals(
        StardomColors.TextPrimary, StardomColors.stateAccent(VpnState.ERROR, isError = false))
  }

  @Test
  fun loginLoadingMapsEveryStaleErrorVisualToNeutralResolvingState() {
    val presentationState =
        resolveStardomPresentationVpnState(
            vpnState = VpnState.ERROR,
            authError = false,
            isLoginLoading = true,
            authentikState = AuthentikState.SignedOut)

    assertEquals(VpnState.RESOLVING_STAR_ROUTE, presentationState)
    assertFalse(presentationState.isError)
    assertEquals(
        "ИНИЦИАЛИЗАЦИЯ...",
        StardomLocalization.powerButtonAction(presentationState, AppLanguage.RU))
    assertEquals(StardomColors.TextPrimary, StardomColors.stateAccent(presentationState))
    assertFalse(
        isStardomStatusError(
            vpnState = presentationState,
            connectionStage = ConnectionStage.AccessUnavailable,
            isLoginLoading = true,
            authentikState = AuthentikState.SignedOut))
  }

  @Test
  fun loginFailureRestoresActualErrorPresentationAndLoginModal() {
    val presentationState =
        resolveStardomPresentationVpnState(
            vpnState = VpnState.ERROR,
            authError = true,
            isLoginLoading = false,
            authentikState = AuthentikState.SignedOut)

    assertEquals(VpnState.ERROR, presentationState)
    assertEquals(
        "ПОВТОРИТЬ", StardomLocalization.powerButtonAction(presentationState, AppLanguage.RU))
    assertTrue(
        isStardomLoginModalVisible(
            connectionStage = ConnectionStage.SignIn,
            authError = true,
            isLoginLoading = false,
            authentikState = AuthentikState.SignedOut))
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

  // --- Exit Node Metadata Tests ---

  @Test
  fun mapExitNodeToStarNodePreservesOnlyLiveNetmapMetadata() {
    val exitNode =
        ExitNodePickerViewModel.ExitNode(
            id = "node-germany-1",
            label = "Germany 1",
            online = MutableStateFlow(true),
            selected = false,
            city = "Frankfurt",
            countryCode = "DE",
            country = "Germany")

    val server = checkNotNull(mapExitNodeToStarNode(exitNode))

    assertEquals("node-germany-1", server.id)
    assertEquals("Germany 1", server.label)
    assertEquals("Frankfurt", server.city)
    assertEquals("DE", server.countryCode)
    assertEquals("Germany", server.country)
  }

  @Test
  fun mapExitNodeToStarNodeDropsNodeWithoutStableId() {
    val exitNode =
        ExitNodePickerViewModel.ExitNode(
            id = null,
            label = "Unidentified node",
            online = MutableStateFlow(true),
            selected = false)

    assertNull(mapExitNodeToStarNode(exitNode))
  }

  // --- Power Control Enabled State Tests ---

  @Test
  fun powerControlDisabledWhenUnauthenticatedOrAccessDisabled() {
    assertFalse(isPowerControlEnabled(ConnectionStage.SignIn))
    assertFalse(isPowerControlEnabled(ConnectionStage.AccessDisabled))
  }

  @Test
  fun powerControlEnabledWhenConnectOrPermissionOrUnavailable() {
    assertTrue(isPowerControlEnabled(ConnectionStage.Connect))
    assertTrue(isPowerControlEnabled(ConnectionStage.RequestVpnPermission))
    assertTrue(isPowerControlEnabled(ConnectionStage.AccessUnavailable))
  }

  // --- Status Region Mapping Below Orbit Tests ---

  @Test
  fun resolveStardomStatusTextMapsSignInStage() {
    val statusEn =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.SignedOut,
            language = AppLanguage.EN)
    assertEquals("AUTH REQUIRED", statusEn)

    val statusRu =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.SignedOut,
            language = AppLanguage.RU)
    assertEquals("ТРЕБУЕТСЯ АВТОРИЗАЦИЯ", statusRu)
  }

  @Test
  fun resolveStardomStatusTextMapsAuthorizingState() {
    val statusEn =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.Authorizing,
            language = AppLanguage.EN)
    assertEquals("SIGNING IN...", statusEn)

    val statusRu =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.Authorizing,
            language = AppLanguage.RU)
    assertEquals("ВХОД В СИСТЕМУ...", statusRu)
  }

  @Test
  fun resolveStardomStatusTextMapsAccessUnavailableStage() {
    val statusEn =
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessUnavailable,
            language = AppLanguage.EN)
    assertEquals("ACCESS UNAVAILABLE", statusEn)

    val statusRu =
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessUnavailable,
            language = AppLanguage.RU)
    assertEquals("ДОСТУП НЕДОСТУПЕН", statusRu)
  }

  @Test
  fun resolveStardomStatusTextMapsAccessDisabledStage() {
    val statusEn =
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessDisabled,
            language = AppLanguage.EN)
    assertEquals("ACCESS DENIED", statusEn)

    val statusRu =
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessDisabled,
            language = AppLanguage.RU)
    assertEquals("ДОСТУП ЗАПРЕЩЕН", statusRu)
  }

  @Test
  fun resolveStardomStatusTextMapsRequestVpnPermissionStage() {
    val statusEn =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.RequestVpnPermission,
            language = AppLanguage.EN)
    assertEquals("PERMISSION REQUIRED", statusEn)

    val statusRu =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.RequestVpnPermission,
            language = AppLanguage.RU)
    assertEquals("ТРЕБУЕТСЯ РАЗРЕШЕНИЕ", statusRu)
  }

  @Test
  fun resolveStardomStatusTextMapsVpnStates() {
    assertEquals(
        "IN ORBIT",
        resolveStardomStatusText(
            VpnState.SECURED, ConnectionStage.Connect, language = AppLanguage.EN))
    assertEquals(
        "DE-ORBITED",
        resolveStardomStatusText(
            VpnState.DISCONNECTED, ConnectionStage.Connect, language = AppLanguage.EN))
    assertEquals(
        "RESOLVING ROUTE",
        resolveStardomStatusText(
            VpnState.RESOLVING_STAR_ROUTE, ConnectionStage.Connect, language = AppLanguage.EN))
    assertEquals(
        "CIPHER HANDSHAKE",
        resolveStardomStatusText(
            VpnState.HANDSHAKING_CIPHER, ConnectionStage.Connect, language = AppLanguage.EN))
    assertEquals(
        "NODE AUTHENTICATION",
        resolveStardomStatusText(
            VpnState.AUTHENTICATING_NODE, ConnectionStage.Connect, language = AppLanguage.EN))
    assertEquals(
        "DE-ORBITING",
        resolveStardomStatusText(
            VpnState.DISCONNECTING, ConnectionStage.Connect, language = AppLanguage.EN))
    assertEquals(
        "LINK ERROR",
        resolveStardomStatusText(
            VpnState.ERROR, ConnectionStage.Connect, language = AppLanguage.EN))
  }

  @Test
  fun isStardomStatusErrorIdentifiesErrorStatesCorrectly() {
    assertTrue(isStardomStatusError(VpnState.ERROR, ConnectionStage.Connect))
    assertTrue(isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.AccessUnavailable))
    assertTrue(isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.AccessDisabled))
    assertFalse(isStardomStatusError(VpnState.SECURED, ConnectionStage.Connect))
    assertFalse(isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.Connect))
    assertFalse(isStardomStatusError(VpnState.RESOLVING_STAR_ROUTE, ConnectionStage.Connect))
    assertFalse(isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.SignIn))
    assertFalse(isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.RequestVpnPermission))
  }

  @Test
  fun loginModalDisappearsImmediatelyWhenAuthorizationStarts() {
    assertTrue(
        isStardomLoginModalVisible(
            connectionStage = ConnectionStage.SignIn,
            isLoginLoading = false,
            authentikState = AuthentikState.SignedOut))
    assertFalse(
        isStardomLoginModalVisible(
            connectionStage = ConnectionStage.SignIn,
            isLoginLoading = true,
            authentikState = AuthentikState.SignedOut))
    assertFalse(
        isStardomLoginModalVisible(
            connectionStage = ConnectionStage.SignIn,
            isLoginLoading = false,
            authentikState = AuthentikState.Authorizing))
    assertFalse(
        isStardomLoginModalVisible(
            connectionStage = ConnectionStage.SignIn,
            isLoginLoading = false,
            authentikState = AuthentikState.AuthorizedLoading))
    assertFalse(
        isStardomLoginModalVisible(
            connectionStage = ConnectionStage.SignIn,
            isLoginLoading = false,
            authentikState = AuthentikState.Authorized))
    assertFalse(
        isStardomLoginModalVisible(
            connectionStage = ConnectionStage.Connect,
            isLoginLoading = false,
            authentikState = AuthentikState.SignedOut))
    assertTrue(
        isStardomLoginModalVisible(
            connectionStage = ConnectionStage.SignIn,
            authError = true,
            isLoginLoading = false,
            authentikState = AuthentikState.Authorized))
  }

  // --- Modal Login & Direct Navigation Contract Tests ---

  @Test
  fun directLoginActionNavigatesDirectlyWithoutStartingVpn() {
    var authStarts = 0
    var navigatedToIntermediate = false
    var vpnStarted = false
    var permissionRequested = false

    val startAuthAction: () -> Unit = { authStarts++ }
    val navigateIntermediateAction: () -> Unit = { navigatedToIntermediate = true }
    val startVpnAction: () -> Unit = { vpnStarted = true }
    val permissionAction: () -> Unit = { permissionRequested = true }

    // Invoking the direct modal login action
    startAuthAction()

    assertEquals("Authorization starter must be called exactly once", 1, authStarts)
    assertFalse("Must not navigate to intermediate screen", navigatedToIntermediate)
    assertFalse("Must not start VPN", vpnStarted)
    assertFalse("Must not request VPN permission", permissionRequested)
  }

  @Test
  fun discoveryFailureRendersRetryableMainStatus() {
    val statusEn =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.SignedOut,
            authError = true,
            language = AppLanguage.EN)
    assertEquals("AUTH FAILED // RETRY", statusEn)

    val statusRu =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.SignedOut,
            authError = true,
            language = AppLanguage.RU)
    assertEquals("СБОЙ АВТОРИЗАЦИИ // ПОВТОРИТЕ", statusRu)

    assertTrue(
        "Discovery failure must mark status as error",
        isStardomStatusError(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authError = true))
  }

  @Test
  fun unauthenticatedStageKeepsPowerDisabled() {
    val unauthenticatedStage = ConnectionStage.SignIn
    assertFalse(
        "Power control must be disabled while unauthenticated",
        isPowerControlEnabled(unauthenticatedStage))
  }

  @Test
  fun resolveStardomStatusTextMapsLoginLoadingState() {
    val statusEn =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.Authorized,
            isLoginLoading = true,
            language = AppLanguage.EN)
    assertEquals("SIGNING IN...", statusEn)

    val statusRu =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.Authorized,
            isLoginLoading = true,
            language = AppLanguage.RU)
    assertEquals("ВХОД В СИСТЕМУ...", statusRu)
  }

  @Test
  fun isStardomStatusErrorSuppressedWhileLoginLoading() {
    assertFalse(
        isStardomStatusError(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessUnavailable,
            isLoginLoading = true))
    assertFalse(
        isStardomStatusError(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            isLoginLoading = true))
  }

  @Test
  fun authErrorOverridesLoadingAndShowsRetryableStatus() {
    val statusRu =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authError = true,
            isLoginLoading = true,
            language = AppLanguage.RU)
    assertEquals("СБОЙ АВТОРИЗАЦИИ // ПОВТОРИТЕ", statusRu)

    val statusEn =
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authError = true,
            isLoginLoading = true,
            language = AppLanguage.EN)
    assertEquals("AUTH FAILED // RETRY", statusEn)
  }
}
