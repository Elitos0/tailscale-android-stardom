// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDemandDecisionEngineTest {

  @Test
  fun evaluate_whenDisabled_returnsNoAction() {
    val config = OnDemandConfig(
        enabled = false,
        cellularAction = OnDemandAction.CONNECT,
        wifiAction = OnDemandAction.CONNECT,
    )
    val cellular = ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR)
    val wifi = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "TestNet")

    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(cellular, config, isVpnRunning = false))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(cellular, config, isVpnRunning = true))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(wifi, config, isVpnRunning = false))
  }

  @Test
  fun evaluate_cellular_connectRule() {
    val config = OnDemandConfig(
        enabled = true,
        cellularAction = OnDemandAction.CONNECT,
    )
    val network = ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR)

    assertEquals(OnDemandDecision.Connect, OnDemandDecisionEngine.evaluate(network, config, isVpnRunning = false))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(network, config, isVpnRunning = true))
  }

  @Test
  fun evaluate_cellular_disconnectRule() {
    val config = OnDemandConfig(
        enabled = true,
        cellularAction = OnDemandAction.DISCONNECT,
    )
    val network = ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR)

    assertEquals(OnDemandDecision.Disconnect, OnDemandDecisionEngine.evaluate(network, config, isVpnRunning = true))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(network, config, isVpnRunning = false))
  }

  @Test
  fun evaluate_cellular_doNothingRule() {
    val config = OnDemandConfig(
        enabled = true,
        cellularAction = OnDemandAction.DO_NOTHING,
    )
    val network = ActiveNetworkSnapshot(transport = NetworkTransport.CELLULAR)

    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(network, config, isVpnRunning = true))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(network, config, isVpnRunning = false))
  }

  @Test
  fun evaluate_wifi_scopeAll() {
    val configDisconnect = OnDemandConfig(
        enabled = true,
        wifiScope = WifiRuleScope.ALL,
        wifiAction = OnDemandAction.DISCONNECT,
    )
    val wifiA = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "NetworkA")
    val wifiB = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "NetworkB")

    // Disconnect when running
    assertEquals(OnDemandDecision.Disconnect, OnDemandDecisionEngine.evaluate(wifiA, configDisconnect, isVpnRunning = true))
    assertEquals(OnDemandDecision.Disconnect, OnDemandDecisionEngine.evaluate(wifiB, configDisconnect, isVpnRunning = true))
    // NoAction when already stopped
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(wifiA, configDisconnect, isVpnRunning = false))

    val configConnect = OnDemandConfig(
        enabled = true,
        wifiScope = WifiRuleScope.ALL,
        wifiAction = OnDemandAction.CONNECT,
    )
    assertEquals(OnDemandDecision.Connect, OnDemandDecisionEngine.evaluate(wifiA, configConnect, isVpnRunning = false))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(wifiA, configConnect, isVpnRunning = true))
  }

  @Test
  fun evaluate_wifi_scopeOnlySelected_selectedSsidMatches() {
    val config = OnDemandConfig(
        enabled = true,
        wifiScope = WifiRuleScope.ONLY_SELECTED,
        wifiAction = OnDemandAction.DISCONNECT,
        selectedSsids = setOf("Home-5G", "Work-Office"),
        unlistedWifiAction = OnDemandAction.CONNECT,
    )

    val homeWifi = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "Home-5G")
    val workWifi = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "Work-Office")

    // Home & Work are selected -> wifiAction applies (DISCONNECT)
    assertEquals(OnDemandDecision.Disconnect, OnDemandDecisionEngine.evaluate(homeWifi, config, isVpnRunning = true))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(homeWifi, config, isVpnRunning = false))
    assertEquals(OnDemandDecision.Disconnect, OnDemandDecisionEngine.evaluate(workWifi, config, isVpnRunning = true))
  }

  @Test
  fun evaluate_wifi_scopeOnlySelected_unlistedSsidFallsBack() {
    val config = OnDemandConfig(
        enabled = true,
        wifiScope = WifiRuleScope.ONLY_SELECTED,
        wifiAction = OnDemandAction.DISCONNECT,
        selectedSsids = setOf("Home-5G"),
        unlistedWifiAction = OnDemandAction.CONNECT,
    )

    val publicWifi = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = "Cafe-Free-WiFi")
    val nullSsidWifi = ActiveNetworkSnapshot(transport = NetworkTransport.WIFI, ssid = null)

    // Unlisted networks fall back to unlistedWifiAction (CONNECT)
    assertEquals(OnDemandDecision.Connect, OnDemandDecisionEngine.evaluate(publicWifi, config, isVpnRunning = false))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(publicWifi, config, isVpnRunning = true))

    // Null SSID (e.g. without location permission) also falls back to unlistedWifiAction
    assertEquals(OnDemandDecision.Connect, OnDemandDecisionEngine.evaluate(nullSsidWifi, config, isVpnRunning = false))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(nullSsidWifi, config, isVpnRunning = true))
  }

  @Test
  fun evaluate_ethernetAndNone_returnNoAction() {
    val config = OnDemandConfig(
        enabled = true,
        cellularAction = OnDemandAction.CONNECT,
        wifiAction = OnDemandAction.CONNECT,
    )
    val ethernet = ActiveNetworkSnapshot(transport = NetworkTransport.ETHERNET)
    val none = ActiveNetworkSnapshot(transport = NetworkTransport.NONE)

    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(ethernet, config, isVpnRunning = false))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(ethernet, config, isVpnRunning = true))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(none, config, isVpnRunning = false))
    assertEquals(OnDemandDecision.NoAction, OnDemandDecisionEngine.evaluate(none, config, isVpnRunning = true))
  }
}
