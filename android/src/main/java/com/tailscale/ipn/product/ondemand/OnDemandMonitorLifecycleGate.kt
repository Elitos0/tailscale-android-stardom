// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

enum class OnDemandMonitorAction {
  START_MONITOR,
  STOP_MONITOR,
  NO_ACTION,
}

object OnDemandMonitorLifecycleGate {

  /**
   * Evaluated when an Activity reaches ON_RESUME.
   * If OnDemand is enabled and VPN is neither starting/running nor monitor already active,
   * reasserts the foreground monitor service.
   */
  fun evaluateOnResume(
      configEnabled: Boolean,
      isVpnStartingOrRunning: Boolean,
      isServiceActive: Boolean,
  ): OnDemandMonitorAction {
    return if (configEnabled && !isVpnStartingOrRunning && !isServiceActive) {
      OnDemandMonitorAction.START_MONITOR
    } else {
      OnDemandMonitorAction.NO_ACTION
    }
  }

  /**
   * Evaluated when OnDemandConfig.enabled changes.
   * - While the app is visible (an Activity is in foreground), the user's toggle takes effect:
   *   enabled=true starts monitor; enabled=false stops monitor/VPN.
   * - While the app is backgrounded (no Activity visible / cold process bootstrap):
   *   NEVER start a foreground service to prevent ForegroundServiceStartNotAllowedException!
   *   Only allow stopping if config was turned off.
   */
  fun evaluateConfigChange(
      newEnabled: Boolean,
      isAppVisible: Boolean,
      isVpnStartingOrRunning: Boolean,
  ): OnDemandMonitorAction {
    return if (isAppVisible) {
      if (newEnabled) {
        if (!isVpnStartingOrRunning) OnDemandMonitorAction.START_MONITOR else OnDemandMonitorAction.NO_ACTION
      } else {
        if (!isVpnStartingOrRunning) OnDemandMonitorAction.STOP_MONITOR else OnDemandMonitorAction.NO_ACTION
      }
    } else {
      if (!newEnabled && !isVpnStartingOrRunning) {
        OnDemandMonitorAction.STOP_MONITOR
      } else {
        OnDemandMonitorAction.NO_ACTION
      }
    }
  }
}
