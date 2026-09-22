// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class OnDemandRepository(
    private val prefs: SharedPreferences
) {
  constructor(context: Context) : this(
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  )

  private val _config = MutableStateFlow(loadConfig())
  val config: StateFlow<OnDemandConfig> = _config.asStateFlow()
  private val _knownSsids = MutableStateFlow(loadKnownSsids())
  val knownSsids: StateFlow<Set<String>> = _knownSsids.asStateFlow()

  fun rememberKnownSsid(ssid: String) {
    val trimmed = ssid.trim()
    if (trimmed.isEmpty()) return
    val updated = _knownSsids.value + trimmed
    prefs.edit().putStringSet(KEY_KNOWN_SSIDS, updated).apply()
    _knownSsids.value = updated
  }

  fun removeKnownSsid(ssid: String) {
    val updated = _knownSsids.value - ssid
    prefs.edit().putStringSet(KEY_KNOWN_SSIDS, updated).apply()
    _knownSsids.value = updated
  }

  private fun loadKnownSsids(): Set<String> {
    return prefs.getStringSet(KEY_KNOWN_SSIDS, emptySet())?.toSet() ?: emptySet()
  }

  fun updateConfig(newConfig: OnDemandConfig) {
    saveConfig(newConfig)
    _config.value = newConfig
  }

  fun updateEnabled(enabled: Boolean) {
    updateConfig(_config.value.copy(enabled = enabled))
  }

  fun setCellularAction(action: OnDemandAction) {
    updateConfig(_config.value.copy(cellularAction = action))
  }

  fun setWifiScope(scope: WifiRuleScope) {
    updateConfig(_config.value.copy(wifiScope = scope))
  }

  fun setWifiAction(action: OnDemandAction) {
    updateConfig(_config.value.copy(wifiAction = action))
  }

  fun setUnlistedWifiAction(action: OnDemandAction) {
    updateConfig(_config.value.copy(unlistedWifiAction = action))
  }

  fun addSsid(ssid: String) {
    val trimmed = ssid.trim()
    if (trimmed.isEmpty()) return
    updateConfig(_config.value.copy(selectedSsids = _config.value.selectedSsids + trimmed))
  }

  fun removeSsid(ssid: String) {
    updateConfig(_config.value.copy(selectedSsids = _config.value.selectedSsids - ssid))
  }

  private fun loadConfig(): OnDemandConfig {
    val enabled = prefs.getBoolean(KEY_ENABLED, false)
    val cellularActionStr = prefs.getString(KEY_CELLULAR_ACTION, null)
    val cellularAction = cellularActionStr?.let { runCatching { OnDemandAction.valueOf(it) }.getOrNull() }
        ?: OnDemandAction.CONNECT
    val wifiScopeStr = prefs.getString(KEY_WIFI_SCOPE, null)
    val wifiScope = wifiScopeStr?.let { runCatching { WifiRuleScope.valueOf(it) }.getOrNull() }
        ?: WifiRuleScope.ALL
    val wifiActionStr = prefs.getString(KEY_WIFI_ACTION, null)
    val wifiAction = wifiActionStr?.let { runCatching { OnDemandAction.valueOf(it) }.getOrNull() }
        ?: OnDemandAction.DISCONNECT
    val selectedSsids = prefs.getStringSet(KEY_SELECTED_SSIDS, emptySet())?.toSet() ?: emptySet()
    val unlistedWifiActionStr = prefs.getString(KEY_UNLISTED_WIFI_ACTION, null)
    val unlistedWifiAction = unlistedWifiActionStr?.let { runCatching { OnDemandAction.valueOf(it) }.getOrNull() }
        ?: OnDemandAction.CONNECT

    return OnDemandConfig(
        enabled = enabled,
        cellularAction = cellularAction,
        wifiScope = wifiScope,
        wifiAction = wifiAction,
        selectedSsids = selectedSsids,
        unlistedWifiAction = unlistedWifiAction,
    )
  }

  private fun saveConfig(config: OnDemandConfig) {
    prefs.edit()
        .putBoolean(KEY_ENABLED, config.enabled)
        .putString(KEY_CELLULAR_ACTION, config.cellularAction.name)
        .putString(KEY_WIFI_SCOPE, config.wifiScope.name)
        .putString(KEY_WIFI_ACTION, config.wifiAction.name)
        .putStringSet(KEY_SELECTED_SSIDS, config.selectedSsids)
        .putString(KEY_UNLISTED_WIFI_ACTION, config.unlistedWifiAction.name)
        .apply()
  }

  companion object {
    const val PREFS_NAME = "stardom_ondemand_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_CELLULAR_ACTION = "cellular_action"
    private const val KEY_WIFI_SCOPE = "wifi_scope"
    private const val KEY_WIFI_ACTION = "wifi_action"
    private const val KEY_SELECTED_SSIDS = "selected_ssids"
    private const val KEY_UNLISTED_WIFI_ACTION = "unlisted_wifi_action"
    private const val KEY_KNOWN_SSIDS = "known_ssids"
  }
}
