// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

enum class NetworkTransport {
  CELLULAR,
  WIFI,
  ETHERNET,
  NONE,
}

data class ActiveNetworkSnapshot(
    val transport: NetworkTransport = NetworkTransport.NONE,
    val ssid: String? = null,
    val isValidated: Boolean = false,
)

enum class OnDemandAction {
  CONNECT,
  DISCONNECT,
  DO_NOTHING,
}

enum class WifiRuleScope {
  ALL,
  ONLY_SELECTED,
}

data class OnDemandConfig(
    val enabled: Boolean = false,
    val cellularAction: OnDemandAction = OnDemandAction.CONNECT,
    val wifiScope: WifiRuleScope = WifiRuleScope.ALL,
    val wifiAction: OnDemandAction = OnDemandAction.DISCONNECT,
    val selectedSsids: Set<String> = emptySet(),
    val unlistedWifiAction: OnDemandAction = OnDemandAction.CONNECT,
)

sealed interface OnDemandDecision {
  object Connect : OnDemandDecision
  object Disconnect : OnDemandDecision
  object NoAction : OnDemandDecision
}
