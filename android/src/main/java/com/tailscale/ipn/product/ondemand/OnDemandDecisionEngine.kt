// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

object OnDemandDecisionEngine {

  fun evaluate(
      network: ActiveNetworkSnapshot,
      config: OnDemandConfig,
      isVpnRunning: Boolean
  ): OnDemandDecision {
    if (!config.enabled) {
      return OnDemandDecision.NoAction
    }

    val targetAction = when (network.transport) {
      NetworkTransport.CELLULAR -> config.cellularAction
      NetworkTransport.WIFI -> {
        when (config.wifiScope) {
          WifiRuleScope.ALL -> config.wifiAction
          WifiRuleScope.ONLY_SELECTED -> {
            if (network.ssid != null && config.selectedSsids.contains(network.ssid)) {
              config.wifiAction
            } else {
              config.unlistedWifiAction
            }
          }
        }
      }
      NetworkTransport.ETHERNET, NetworkTransport.NONE -> OnDemandAction.DO_NOTHING
    }

    return when (targetAction) {
      OnDemandAction.CONNECT -> {
        if (!isVpnRunning) OnDemandDecision.Connect else OnDemandDecision.NoAction
      }
      OnDemandAction.DISCONNECT -> {
        if (isVpnRunning) OnDemandDecision.Disconnect else OnDemandDecision.NoAction
      }
      OnDemandAction.DO_NOTHING -> OnDemandDecision.NoAction
    }
  }
}
