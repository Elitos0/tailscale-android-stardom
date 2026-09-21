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

  // Records manual user override: networkKey -> desiredRunningState
  // If user explicitly toggled VPN to `false` (disconnected) while on networkKey,
  // we do not automatically connect while on this exact networkKey.
  // If user explicitly toggled VPN to `true` (connected) while on networkKey,
  // we do not automatically disconnect while on this exact networkKey.
  @Volatile private var manualOverrideNetworkKey: String? = null
  @Volatile private var manualOverrideState: Boolean? = null

  private fun networkKey(snapshot: ActiveNetworkSnapshot): String {
    return "${snapshot.transport}:${snapshot.ssid.orEmpty()}"
  }

  fun notifyManualVpnToggle(newRunningState: Boolean) {
    val currentSnapshot = networkFlow.value
    val currentKey = networkKey(currentSnapshot)
    manualOverrideNetworkKey = currentKey
    manualOverrideState = newRunningState
    pendingActionJob?.cancel()
    pendingActionJob = null
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
    val decision = when (rawDecision) {
      OnDemandDecision.Connect -> {
        if (manualOverrideNetworkKey == currentKey && manualOverrideState == false) {
          TSLog.d(TAG, "Connect suppressed by manual override on network $currentKey")
          OnDemandDecision.NoAction
        } else {
          OnDemandDecision.Connect
        }
      }
      OnDemandDecision.Disconnect -> {
        if (manualOverrideNetworkKey == currentKey && manualOverrideState == true) {
          TSLog.d(TAG, "Disconnect suppressed by manual override on network $currentKey")
          OnDemandDecision.NoAction
        } else {
          OnDemandDecision.Disconnect
        }
      }
      OnDemandDecision.NoAction -> OnDemandDecision.NoAction
    }

    when (decision) {
      OnDemandDecision.Connect -> {
        pendingActionJob?.cancel()
        pendingActionJob = scope.launch {
          TSLog.d(TAG, "Scheduling Connect after ${connectDebounceMs}ms on $currentKey")
          delay(connectDebounceMs)
          val freshDecision = OnDemandDecisionEngine.evaluate(
              networkFlow.value,
              configFlow.value,
              isVpnRunningFlow.value
          )
          val freshKey = networkKey(networkFlow.value)
          if (freshDecision is OnDemandDecision.Connect &&
              !(manualOverrideNetworkKey == freshKey && manualOverrideState == false)) {
            TSLog.d(TAG, "Executing onConnect on $freshKey")
            onConnect()
          }
        }
      }
      OnDemandDecision.Disconnect -> {
        pendingActionJob?.cancel()
        pendingActionJob = scope.launch {
          TSLog.d(TAG, "Scheduling Disconnect after ${disconnectDebounceMs}ms on $currentKey")
          delay(disconnectDebounceMs)
          val freshDecision = OnDemandDecisionEngine.evaluate(
              networkFlow.value,
              configFlow.value,
              isVpnRunningFlow.value
          )
          val freshKey = networkKey(networkFlow.value)
          if (freshDecision is OnDemandDecision.Disconnect &&
              !(manualOverrideNetworkKey == freshKey && manualOverrideState == true)) {
            TSLog.d(TAG, "Executing onDisconnect on $freshKey")
            onDisconnect()
          }
        }
      }
      OnDemandDecision.NoAction -> {
        pendingActionJob?.cancel()
        pendingActionJob = null
      }
    }
  }

  companion object {
    private const val TAG = "OnDemandController"
  }
}
