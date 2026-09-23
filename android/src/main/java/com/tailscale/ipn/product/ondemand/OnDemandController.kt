// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class OnDemandController(
    private val networkFlow: StateFlow<ActiveNetworkSnapshot>,
    private val configFlow: StateFlow<OnDemandConfig>,
    private val isVpnRunningFlow: StateFlow<Boolean>,
    private val onConnect: () -> Unit,
    private val onDisconnect: () -> Unit,
    private val connectDebounceMs: Long = 2000L,
    private val disconnectDebounceMs: Long = 4000L,
) {
  private var collectJob: Job? = null
  private var pendingActionJob: Job? = null
  private var activeScope: CoroutineScope? = null
  private var currentPendingDecision: OnDemandDecision? = null
  private var currentPendingKey: String? = null

  // Records manual user override: networkKey -> desiredRunningState
  // If user explicitly toggled VPN to `false` (disconnected) while on networkKey,
  // we do not automatically connect while on this exact networkKey.
  // If user explicitly toggled VPN to `true` (connected) while on networkKey,
  // we do not automatically disconnect while on this exact networkKey.
  @Volatile private var manualOverrideNetworkKey: String? = null
  @Volatile private var manualOverrideState: Boolean? = null

  private fun networkKey(snapshot: ActiveNetworkSnapshot): String {
    return "${snapshot.transport}:${snapshot.networkId ?: 0}:${snapshot.ssid.orEmpty()}"
  }

  fun notifyManualVpnToggle(newRunningState: Boolean) {
    val currentSnapshot = networkFlow.value
    if (currentSnapshot.transport == NetworkTransport.WIFI && currentSnapshot.ssid == null) {
      TSLog.d(TAG, "Manual toggle ignored for Wi-Fi because SSID is null")
      pendingActionJob?.cancel()
      pendingActionJob = null
      currentPendingDecision = null
      currentPendingKey = null
      return
    }
    val currentKey = networkKey(currentSnapshot)
    manualOverrideNetworkKey = currentKey
    manualOverrideState = newRunningState
    pendingActionJob?.cancel()
    pendingActionJob = null
    currentPendingDecision = null
    currentPendingKey = null
    TSLog.d(TAG, "Manual toggle recorded: state=$newRunningState on network=$currentKey")
  }
  fun start(scope: CoroutineScope): Job {
    collectJob?.cancel()
    activeScope = scope
    val job = scope.launch {
      combine(networkFlow, configFlow, isVpnRunningFlow) { network, config, isVpnRunning ->
        Triple(network, config, isVpnRunning)
      }.collect { (network, config, isVpnRunning) ->
        evaluateAndSchedule(scope, network, config, isVpnRunning)
      }
    }
    collectJob = job
    return job
  }

  fun stop() {
    collectJob?.cancel()
    collectJob = null
    pendingActionJob?.cancel()
    pendingActionJob = null
    currentPendingDecision = null
    currentPendingKey = null
    activeScope = null
  }

  private fun evaluateAndSchedule(
      scope: CoroutineScope,
      network: ActiveNetworkSnapshot,
      config: OnDemandConfig,
      isVpnRunning: Boolean
  ) {
    val currentKey = networkKey(network)

    // Clear manual override if network has changed
    if (manualOverrideNetworkKey != null && manualOverrideNetworkKey != currentKey) {
      manualOverrideNetworkKey = null
      manualOverrideState = null
    }

    val rawDecision = OnDemandDecisionEngine.evaluate(network, config, isVpnRunning)

    // Check manual override suppression
    val isWifiWithoutSsid = network.transport == NetworkTransport.WIFI && network.ssid == null
    val hasManualOverride = !isWifiWithoutSsid && manualOverrideNetworkKey == currentKey

    val decision = when (rawDecision) {
      OnDemandDecision.Connect -> {
        if (hasManualOverride && manualOverrideState == false) {
          TSLog.d(TAG, "Connect suppressed by manual override on network $currentKey")
          OnDemandDecision.NoAction
        } else {
          OnDemandDecision.Connect
        }
      }
      OnDemandDecision.Disconnect -> {
        if (hasManualOverride && manualOverrideState == true) {
          TSLog.d(TAG, "Disconnect suppressed by manual override on network $currentKey")
          OnDemandDecision.NoAction
        } else {
          OnDemandDecision.Disconnect
        }
      }
      OnDemandDecision.NoAction -> OnDemandDecision.NoAction
    }

    TSLog.d(
        "OnDemandNetwork",
        "network=${network.networkId} transport=${network.transport} ssid=${network.ssid} validated=${network.isValidated} decision=$decision vpnRunning=$isVpnRunning"
    )

    when (decision) {
      OnDemandDecision.Connect -> {
        if (pendingActionJob?.isActive == true &&
            currentPendingDecision == decision &&
            currentPendingKey == currentKey) {
          // Same pending decision on same network; let existing timer continue
          return
        }
        pendingActionJob?.cancel()
        currentPendingDecision = decision
        currentPendingKey = currentKey
        pendingActionJob = scope.launch {
          try {
            TSLog.d(TAG, "Scheduling Connect after ${connectDebounceMs}ms on $currentKey")
            delay(connectDebounceMs)
            val freshSnapshot = networkFlow.value
            val freshDecision = OnDemandDecisionEngine.evaluate(
                freshSnapshot,
                configFlow.value,
                isVpnRunningFlow.value
            )
            val freshKey = networkKey(freshSnapshot)
            val isFreshWifiWithoutSsid = freshSnapshot.transport == NetworkTransport.WIFI && freshSnapshot.ssid == null
            val freshHasManualOverride = !isFreshWifiWithoutSsid && manualOverrideNetworkKey == freshKey
            if (freshDecision is OnDemandDecision.Connect &&
                !(freshHasManualOverride && manualOverrideState == false)) {
              TSLog.d(TAG, "Executing onConnect on $freshKey")
              onConnect()
            }
          } finally {
            if (currentPendingDecision == decision && currentPendingKey == currentKey) {
              currentPendingDecision = null
              currentPendingKey = null
            }
          }
        }
      }
      OnDemandDecision.Disconnect -> {
        if (pendingActionJob?.isActive == true &&
            currentPendingDecision == decision &&
            currentPendingKey == currentKey) {
          // Same pending decision on same network; let existing timer continue
          return
        }
        pendingActionJob?.cancel()
        currentPendingDecision = decision
        currentPendingKey = currentKey
        pendingActionJob = scope.launch {
          try {
            TSLog.d(TAG, "Scheduling Disconnect after ${disconnectDebounceMs}ms on $currentKey")
            delay(disconnectDebounceMs)
            val freshSnapshot = networkFlow.value
            val freshDecision = OnDemandDecisionEngine.evaluate(
                freshSnapshot,
                configFlow.value,
                isVpnRunningFlow.value
            )
            val freshKey = networkKey(freshSnapshot)
            val isFreshWifiWithoutSsid = freshSnapshot.transport == NetworkTransport.WIFI && freshSnapshot.ssid == null
            val freshHasManualOverride = !isFreshWifiWithoutSsid && manualOverrideNetworkKey == freshKey
            if (freshDecision is OnDemandDecision.Disconnect &&
                !(freshHasManualOverride && manualOverrideState == true)) {
              TSLog.d(TAG, "Executing onDisconnect on $freshKey")
              onDisconnect()
            }
          } finally {
            if (currentPendingDecision == decision && currentPendingKey == currentKey) {
              currentPendingDecision = null
              currentPendingKey = null
            }
          }
        }
      }
      OnDemandDecision.NoAction -> {
        pendingActionJob?.cancel()
        pendingActionJob = null
        currentPendingDecision = null
        currentPendingKey = null
      }
    }
  }

  companion object {
    private const val TAG = "OnDemandController"
  }
}
