// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnDemandControllerTest {

  @Test
  fun connect_debouncesAndInvokesOnConnect() = runTest {
    val networkFlow = MutableStateFlow(ActiveNetworkSnapshot(transport = NetworkTransport.NONE))
    val configFlow = MutableStateFlow(
        OnDemandConfig(enabled = true, cellularAction = OnDemandAction.CONNECT)
    )
    val isVpnRunningFlow = MutableStateFlow(false)
    var connects = 0
    var disconnects = 0

    val controller = OnDemandController(
        networkFlow = networkFlow,
        configFlow = configFlow,
        isVpnRunningFlow = isVpnRunningFlow,
        onConnect = { connects++ },
        onDisconnect = { disconnects++ },
        connectDebounceMs = 2000L,
        disconnectDebounceMs = 4000L,
    )
    controller.start(backgroundScope)
    runCurrent()

    // Switch to cellular -> requires CONNECT
    networkFlow.value = ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR)
    runCurrent()

    // Advance 1000ms: debounce should NOT have fired yet
    advanceTimeBy(1000L)
    runCurrent()
    assertEquals(0, connects)

    // Advance another 1001ms: debounce fires
    advanceTimeBy(1001L)
    runCurrent()
    assertEquals(1, connects)
    assertEquals(0, disconnects)
  }

  @Test
  fun rapidNetworkChanges_cancelsPreviousDebounce() = runTest {
    val networkFlow = MutableStateFlow(ActiveNetworkSnapshot(transport = NetworkTransport.NONE))
    val configFlow = MutableStateFlow(
        OnDemandConfig(
            enabled = true,
            cellularAction = OnDemandAction.CONNECT,
            wifiScope = WifiRuleScope.ALL,
            wifiAction = OnDemandAction.DISCONNECT,
        )
    )
    val isVpnRunningFlow = MutableStateFlow(true)
    var connects = 0
    var disconnects = 0

    val controller = OnDemandController(
        networkFlow = networkFlow,
        configFlow = configFlow,
        isVpnRunningFlow = isVpnRunningFlow,
        onConnect = { connects++ },
        onDisconnect = { disconnects++ },
        connectDebounceMs = 1000L,
        disconnectDebounceMs = 2000L,
    )
    controller.start(backgroundScope)
    runCurrent()

    // Switch to Wi-Fi (requires DISCONNECT)
    networkFlow.value = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "WiFi-1")
    runCurrent()
    advanceTimeBy(500L)
    runCurrent()
    assertEquals(0, disconnects)

    // Switch to Cellular before disconnect fires (now requires NO_ACTION because already running)
    networkFlow.value = ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR)
    runCurrent()
    advanceTimeBy(2500L)
    runCurrent()

    // Wi-Fi disconnect was cancelled by Cellular switch!
    assertEquals(0, disconnects)
    assertEquals(0, connects)
  }

  @Test
  fun manualOverride_suppressesActionUntilNetworkChanges() = runTest {
    val networkFlow = MutableStateFlow(ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR))
    val configFlow = MutableStateFlow(
        OnDemandConfig(enabled = true, cellularAction = OnDemandAction.CONNECT, wifiAction = OnDemandAction.CONNECT)
    )
    val isVpnRunningFlow = MutableStateFlow(true)
    var connects = 0
    var disconnects = 0

    val controller = OnDemandController(
        networkFlow = networkFlow,
        configFlow = configFlow,
        isVpnRunningFlow = isVpnRunningFlow,
        onConnect = { connects++ },
        onDisconnect = { disconnects++ },
        connectDebounceMs = 500L,
        disconnectDebounceMs = 500L,
    )
    controller.start(backgroundScope)
    runCurrent()

    // User manually toggles VPN off on Cellular
    controller.notifyManualVpnToggle(false)
    isVpnRunningFlow.value = false
    runCurrent()
    advanceTimeBy(1000L)
    runCurrent()

    // Connect must be SUPPRESSED because user explicitly turned it off on Cellular
    assertEquals(0, connects)

    // Now user switches to Wi-Fi -> network changed! Manual override must clear!
    networkFlow.value = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "Office-Net")
    runCurrent()
    advanceTimeBy(600L)
    runCurrent()

    // Connect succeeds on the new network
    assertEquals(1, connects)
  }

  @Test
  fun debounce_identicalPendingDecisionDoesNotRestartTimer() = runTest {
    val networkFlow = MutableStateFlow(ActiveNetworkSnapshot(transport = NetworkTransport.NONE))
    val configFlow = MutableStateFlow(
        OnDemandConfig(enabled = true, cellularAction = OnDemandAction.CONNECT)
    )
    val isVpnRunningFlow = MutableStateFlow(false)
    var connects = 0

    val controller = OnDemandController(
        networkFlow = networkFlow,
        configFlow = configFlow,
        isVpnRunningFlow = isVpnRunningFlow,
        onConnect = { connects++ },
        onDisconnect = {},
        connectDebounceMs = 2000L,
        disconnectDebounceMs = 4000L,
    )
    controller.start(backgroundScope)
    runCurrent()

    // Switch to cellular (networkId = 100) -> schedules CONNECT with 2000ms debounce
    networkFlow.value = ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR, networkId = 100L)
    runCurrent()

    // Advance 1000ms: timer at 1000/2000ms
    advanceTimeBy(1000L)
    runCurrent()
    assertEquals(0, connects)

    // Capabilities or validation update arrives for SAME decision and SAME networkKey
    networkFlow.value = ActiveNetworkSnapshot(
        transport = NetworkTransport.CELLULAR,
        networkId = 100L,
        isValidated = true
    )
    runCurrent()

    // Advance 1001ms (total 2001ms from initial event): original debounce MUST finish and fire
    advanceTimeBy(1001L)
    runCurrent()
    assertEquals(1, connects)
  }

  @Test
  fun manualOverride_ignoredOnWifiWhenSsidIsNull() = runTest {
    val networkFlow = MutableStateFlow(ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = null))
    val configFlow = MutableStateFlow(
        OnDemandConfig(
            enabled = true,
            wifiScope = WifiRuleScope.ONLY_SELECTED,
            selectedSsids = setOf("Home-5G"),
            wifiAction = OnDemandAction.CONNECT
        )
    )
    val isVpnRunningFlow = MutableStateFlow(true)
    var connects = 0

    val controller = OnDemandController(
        networkFlow = networkFlow,
        configFlow = configFlow,
        isVpnRunningFlow = isVpnRunningFlow,
        onConnect = { connects++ },
        onDisconnect = {},
        connectDebounceMs = 500L,
        disconnectDebounceMs = 500L,
    )
    controller.start(backgroundScope)
    runCurrent()

    // User toggles off while on Wi-Fi with null SSID: MUST NOT record override for all Wi-Fi
    controller.notifyManualVpnToggle(false)
    isVpnRunningFlow.value = false
    runCurrent()

    // Now Wi-Fi SSID resolves to "Home-5G" -> requires CONNECT
    networkFlow.value = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "Home-5G")
    runCurrent()
    advanceTimeBy(600L)
    runCurrent()

    // Connect succeeds because null-SSID Wi-Fi did not set manual override
    assertEquals(1, connects)
  }

  @Test
  fun disconnect_onSelectedWifi_executesWithoutInternetValidation() = runTest {
    val networkFlow = MutableStateFlow(ActiveNetworkSnapshot(transport = NetworkTransport.NONE))
    val configFlow = MutableStateFlow(
        OnDemandConfig(
            enabled = true,
            wifiScope = WifiRuleScope.ONLY_SELECTED,
            selectedSsids = setOf("Home-NoInternet"),
            wifiAction = OnDemandAction.DISCONNECT,
        )
    )
    val isVpnRunningFlow = MutableStateFlow(true)
    var disconnects = 0

    val controller = OnDemandController(
        networkFlow = networkFlow,
        configFlow = configFlow,
        isVpnRunningFlow = isVpnRunningFlow,
        onConnect = {},
        onDisconnect = { disconnects++ },
        connectDebounceMs = 500L,
        disconnectDebounceMs = 500L,
    )
    controller.start(backgroundScope)
    runCurrent()

    // Connected to selected Wi-Fi, but internet is NOT validated (isValidated = false)
    networkFlow.value = ActiveNetworkSnapshot(
        transport = NetworkTransport.WIFI,
        ssid = "Home-NoInternet",
        isValidated = false
    )
    runCurrent()
    advanceTimeBy(600L)
    runCurrent()

    // Disconnect MUST execute even though isValidated is false
    assertEquals(1, disconnects)
  }

  @Test
  fun networkKey_distinguishesDifferentNetworkIds() = runTest {
    val networkFlow = MutableStateFlow(
        ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR, networkId = 1L)
    )
    val configFlow = MutableStateFlow(
        OnDemandConfig(enabled = true, cellularAction = OnDemandAction.CONNECT)
    )
    val isVpnRunningFlow = MutableStateFlow(true)
    var connects = 0

    val controller = OnDemandController(
        networkFlow = networkFlow,
        configFlow = configFlow,
        isVpnRunningFlow = isVpnRunningFlow,
        onConnect = { connects++ },
        onDisconnect = {},
        connectDebounceMs = 500L,
        disconnectDebounceMs = 500L,
    )
    controller.start(backgroundScope)
    runCurrent()

    // User manually toggles VPN off on Cellular network 1
    controller.notifyManualVpnToggle(false)
    isVpnRunningFlow.value = false
    runCurrent()
    advanceTimeBy(600L)
    runCurrent()
    assertEquals(0, connects)

    // Switch to Cellular network 2 (different networkId)
    networkFlow.value = ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR, networkId = 2L)
    runCurrent()
    advanceTimeBy(600L)
    runCurrent()

    // Connect succeeds because networkId changed!
    assertEquals(1, connects)
  }
}
