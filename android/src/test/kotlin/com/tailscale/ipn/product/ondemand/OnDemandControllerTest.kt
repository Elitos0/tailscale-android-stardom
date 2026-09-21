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
}
