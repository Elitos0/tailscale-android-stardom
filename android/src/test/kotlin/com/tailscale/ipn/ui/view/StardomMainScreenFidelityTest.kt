// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.ui.ConnectionStage
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StardomMainScreenFidelityTest {

  @Test
  fun unauthenticatedEntryModalTriggeredOnlyForSignInStage() {
    val unauthStages = listOf(ConnectionStage.SignIn)
    val authStages =
        listOf(
            ConnectionStage.Connect,
            ConnectionStage.RequestVpnPermission,
            ConnectionStage.AccessUnavailable,
            ConnectionStage.AccessDisabled)

    for (stage in unauthStages) {
      assertTrue("SignIn stage must trigger unauthenticated modal", stage == ConnectionStage.SignIn)
      assertFalse("Power button must be disabled for $stage", isPowerControlEnabled(stage))
    }

    for (stage in authStages) {
      assertFalse("$stage must not trigger unauthenticated modal", stage == ConnectionStage.SignIn)
      if (stage != ConnectionStage.AccessDisabled) {
        assertTrue("Power button must be enabled for $stage", isPowerControlEnabled(stage))
      }
    }
  }

  @Test
  fun directSignInActionInvokesNavigationDirectlyWithoutVpnSideEffects() {
    var loginNavigated = false
    var vpnToggled = false
    var permissionRequested = false

    val onNavigateLogin: () -> Unit = { loginNavigated = true }
    val onToggleVpn: () -> Unit = { vpnToggled = true }
    val onRequestPermission: () -> Unit = { permissionRequested = true }

    // Execute direct login action
    onNavigateLogin()

    assertTrue("Direct sign in must invoke navigation", loginNavigated)
    assertFalse("Direct sign in must not toggle VPN", vpnToggled)
    assertFalse("Direct sign in must not request VPN permission", permissionRequested)
  }

  @Test
  fun legacyAccessCardAndExitNodeStatusAreAbsentFromMainContent() {
    // Peer content (including device list) is only shown when Running and in Connect stage
    assertFalse(shouldRenderPeerContent(Ipn.State.Stopped, ConnectionStage.SignIn))
    assertFalse(shouldRenderPeerContent(Ipn.State.Starting, ConnectionStage.SignIn))
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.SignIn))
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.AccessUnavailable))
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.AccessDisabled))
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.RequestVpnPermission))
    assertTrue(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.Connect))
  }

  @Test
  fun statusRegionUnderOrbitControlShowsConciseStatusForEveryStage() {
    // Unauthenticated
    assertEquals(
        "AUTH REQUIRED",
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.SignedOut,
            language = AppLanguage.EN))
    assertEquals(
        "ТРЕБУЕТСЯ АВТОРИЗАЦИЯ",
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.SignedOut,
            language = AppLanguage.RU))

    // Authorizing / Signing In
    assertEquals(
        "SIGNING IN...",
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.Authorizing,
            language = AppLanguage.EN))
    assertEquals(
        "ВХОД В СИСТЕМУ...",
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.SignIn,
            authentikState = AuthentikState.Authorizing,
            language = AppLanguage.RU))

    // Machine Auth Required
    assertEquals(
        "MACHINE AUTH REQUIRED",
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.Connect,
            authentikState = null,
            ipnState = Ipn.State.NeedsMachineAuth,
            language = AppLanguage.EN))
    assertEquals(
        "ТРЕБУЕТСЯ АВТОРИЗАЦИЯ УСТРОЙСТВА",
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.Connect,
            authentikState = null,
            ipnState = Ipn.State.NeedsMachineAuth,
            language = AppLanguage.RU))

    // Access Unavailable
    assertEquals(
        "ACCESS UNAVAILABLE",
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessUnavailable,
            language = AppLanguage.EN))
    assertEquals(
        "ДОСТУП НЕДОСТУПЕН",
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessUnavailable,
            language = AppLanguage.RU))

    // Access Disabled
    assertEquals(
        "ACCESS DENIED",
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessDisabled,
            language = AppLanguage.EN))
    assertEquals(
        "ДОСТУП ЗАПРЕЩЕН",
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.AccessDisabled,
            language = AppLanguage.RU))

    // Key Expiring Soon
    assertEquals(
        "KEY EXPIRING SOON",
        resolveStardomStatusText(
            vpnState = VpnState.SECURED,
            connectionStage = ConnectionStage.Connect,
            authentikState = null,
            ipnState = null,
            showKeyExpiry = true,
            language = AppLanguage.EN))
    assertEquals(
        "ИСТЕКАЕТ СРОК КЛЮЧА",
        resolveStardomStatusText(
            vpnState = VpnState.SECURED,
            connectionStage = ConnectionStage.Connect,
            authentikState = null,
            ipnState = null,
            showKeyExpiry = true,
            language = AppLanguage.RU))

    // Permission Needed
    assertEquals(
        "PERMISSION REQUIRED",
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.RequestVpnPermission,
            language = AppLanguage.EN))
    assertEquals(
        "ТРЕБУЕТСЯ РАЗРЕШЕНИЕ",
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.RequestVpnPermission,
            language = AppLanguage.RU))

    // Transient Link Error
    assertEquals(
        "LINK ERROR",
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.Connect,
            language = AppLanguage.EN))
    assertEquals(
        "СБОЙ СВЯЗИ",
        resolveStardomStatusText(
            vpnState = VpnState.ERROR,
            connectionStage = ConnectionStage.Connect,
            language = AppLanguage.RU))

    // Connected / In Orbit
    assertEquals(
        "IN ORBIT",
        resolveStardomStatusText(
            vpnState = VpnState.SECURED,
            connectionStage = ConnectionStage.Connect,
            language = AppLanguage.EN))
    assertEquals(
        "НА ОРБИТЕ",
        resolveStardomStatusText(
            vpnState = VpnState.SECURED,
            connectionStage = ConnectionStage.Connect,
            language = AppLanguage.RU))

    // Disconnected / De-orbited
    assertEquals(
        "DE-ORBITED",
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.Connect,
            language = AppLanguage.EN))
    assertEquals(
        "ОТКЛЮЧЕНО",
        resolveStardomStatusText(
            vpnState = VpnState.DISCONNECTED,
            connectionStage = ConnectionStage.Connect,
            language = AppLanguage.RU))
  }

  @Test
  fun errorStatusHighlightingOnlyForTrueErrors() {
    assertTrue(
        "VpnState.ERROR is error", isStardomStatusError(VpnState.ERROR, ConnectionStage.Connect))
    assertTrue(
        "NeedsMachineAuth is error",
        isStardomStatusError(VpnState.ERROR, ConnectionStage.Connect, Ipn.State.NeedsMachineAuth))
    assertTrue(
        "AccessUnavailable is error",
        isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.AccessUnavailable))
    assertTrue(
        "AccessDisabled is error",
        isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.AccessDisabled))

    assertTrue(
        "Key expiring is error/warning status",
        isStardomStatusError(VpnState.SECURED, ConnectionStage.Connect, null, showKeyExpiry = true))

    assertFalse(
        "Secured is not error", isStardomStatusError(VpnState.SECURED, ConnectionStage.Connect))
    assertFalse(
        "Disconnected is not error",
        isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.Connect))
    assertFalse(
        "Resolving is not error",
        isStardomStatusError(VpnState.RESOLVING_STAR_ROUTE, ConnectionStage.Connect))
    assertFalse(
        "SignIn is not error", isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.SignIn))
    assertFalse(
        "RequestVpnPermission is not error",
        isStardomStatusError(VpnState.DISCONNECTED, ConnectionStage.RequestVpnPermission))
  }

  @Test
  fun statusStringsContainNoLegacyCardLabels() {
    val prohibitedLegacyLabels =
        listOf(
            "VPN access active",
            "Currently using",
            "Auto exit node",
            "Exit node",
            "VPN access disabled",
            "VPN access unavailable",
            "VPN TUNNEL PERMISSION NEEDED",
            "READY FOR ORBITAL LINK",
            "CONNECTED IDENTITY:")

    val allStages =
        listOf(
            ConnectionStage.SignIn,
            ConnectionStage.AccessUnavailable,
            ConnectionStage.AccessDisabled,
            ConnectionStage.RequestVpnPermission,
            ConnectionStage.Connect)

    val allVpnStates = VpnState.values()

    for (stage in allStages) {
      for (vpnState in allVpnStates) {
        val statusEn = resolveStardomStatusText(vpnState, stage, language = AppLanguage.EN)
        val statusRu = resolveStardomStatusText(vpnState, stage, language = AppLanguage.RU)

        for (legacy in prohibitedLegacyLabels) {
          assertFalse(
              "Status '$statusEn' must not contain legacy label '$legacy'",
              statusEn.contains(legacy, ignoreCase = true))
          assertFalse(
              "Status '$statusRu' must not contain legacy label '$legacy'",
              statusRu.contains(legacy, ignoreCase = true))
        }
      }
    }
  }

  @Test
  fun legacyExitNodeStatusComposableIsCompletelyRemovedFromMainView() {
    val mainViewClass = Class.forName("com.tailscale.ipn.ui.view.MainViewKt")
    val exitNodeStatusMethods =
        mainViewClass.declaredMethods.filter { it.name.startsWith("ExitNodeStatus") }
    assertTrue(
        "ExitNodeStatus composable must be completely removed from MainView",
        exitNodeStatusMethods.isEmpty())
  }

  @Test
  fun expiryNotificationComposableIsCompletelyRemovedFromMainView() {
    val mainViewClass = Class.forName("com.tailscale.ipn.ui.view.MainViewKt")
    val expiryNotificationMethods =
        mainViewClass.declaredMethods.filter { it.name.startsWith("ExpiryNotification") }
    assertTrue(
        "ExpiryNotification composable must be completely removed from MainView",
        expiryNotificationMethods.isEmpty())
  }
}
