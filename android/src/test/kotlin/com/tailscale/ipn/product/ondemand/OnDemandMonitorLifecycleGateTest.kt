// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

import org.junit.Assert.assertEquals
import org.junit.Test

class OnDemandMonitorLifecycleGateTest {

  @Test
  fun backgroundColdBootstrap_withEnabledConfig_neverStartsFgs() {
    // When the process bootstraps in the background, isAppVisible is false.
    // Even if config is enabled, the gate must return NO_ACTION to prevent
    // ForegroundServiceStartNotAllowedException on Android 12+.
    val action = OnDemandMonitorLifecycleGate.evaluateConfigChange(
        newEnabled = true,
        isAppVisible = false,
        isVpnStartingOrRunning = false,
    )
    assertEquals(OnDemandMonitorAction.NO_ACTION, action)
  }

  @Test
  fun backgroundColdBootstrap_withDisabledConfig_doesNotStartMonitor() {
    val action = OnDemandMonitorLifecycleGate.evaluateConfigChange(
        newEnabled = false,
        isAppVisible = false,
        isVpnStartingOrRunning = false,
    )
    assertEquals(OnDemandMonitorAction.STOP_MONITOR, action)
  }

  @Test
  fun activityOnResume_reassertsMonitorWhenEnabledAndServiceInactive() {
    // When an activity reaches ON_RESUME, if OnDemand is enabled and the monitor
    // service is not active, the gate explicitly reasserts START_MONITOR.
    val action = OnDemandMonitorLifecycleGate.evaluateOnResume(
        configEnabled = true,
        isVpnStartingOrRunning = false,
        isServiceActive = false,
    )
    assertEquals(OnDemandMonitorAction.START_MONITOR, action)
  }

  @Test
  fun activityOnResume_doesNotStartMonitorWhenServiceAlreadyActive() {
    val action = OnDemandMonitorLifecycleGate.evaluateOnResume(
        configEnabled = true,
        isVpnStartingOrRunning = false,
        isServiceActive = true,
    )
    assertEquals(OnDemandMonitorAction.NO_ACTION, action)
  }

  @Test
  fun activityOnResume_doesNotStartMonitorWhenVpnAlreadyRunning() {
    val action = OnDemandMonitorLifecycleGate.evaluateOnResume(
        configEnabled = true,
        isVpnStartingOrRunning = true,
        isServiceActive = true,
    )
    assertEquals(OnDemandMonitorAction.NO_ACTION, action)
  }

  @Test
  fun activityOnResume_doesNotStartMonitorWhenOnDemandDisabled() {
    val action = OnDemandMonitorLifecycleGate.evaluateOnResume(
        configEnabled = false,
        isVpnStartingOrRunning = false,
        isServiceActive = false,
    )
    assertEquals(OnDemandMonitorAction.NO_ACTION, action)
  }

  @Test
  fun visibleConfigChange_userEnablesOnDemand_startsMonitor() {
    // When the user toggles OnDemand ON in the UI (app is visible), the gate starts monitor.
    val action = OnDemandMonitorLifecycleGate.evaluateConfigChange(
        newEnabled = true,
        isAppVisible = true,
        isVpnStartingOrRunning = false,
    )
    assertEquals(OnDemandMonitorAction.START_MONITOR, action)
  }

  @Test
  fun visibleConfigChange_userDisablesOnDemand_stopsMonitor() {
    // When the user toggles OnDemand OFF in the UI, the gate stops monitor.
    val action = OnDemandMonitorLifecycleGate.evaluateConfigChange(
        newEnabled = false,
        isAppVisible = true,
        isVpnStartingOrRunning = false,
    )
    assertEquals(OnDemandMonitorAction.STOP_MONITOR, action)
  }

  @Test
  fun visibleConfigChange_whenVpnAlreadyRunning_doesNotStartMonitor() {
    val action = OnDemandMonitorLifecycleGate.evaluateConfigChange(
        newEnabled = true,
        isAppVisible = true,
        isVpnStartingOrRunning = true,
    )
    assertEquals(OnDemandMonitorAction.NO_ACTION, action)
  }

  @Test
  fun backgroundConfigChange_disablesMonitorWhenTurnedOffRemotely() {
    val action = OnDemandMonitorLifecycleGate.evaluateConfigChange(
        newEnabled = false,
        isAppVisible = false,
        isVpnStartingOrRunning = false,
    )
    assertEquals(OnDemandMonitorAction.STOP_MONITOR, action)
  }
}
