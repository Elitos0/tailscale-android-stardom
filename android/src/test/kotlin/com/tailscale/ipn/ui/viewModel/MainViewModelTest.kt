// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.R
import com.tailscale.ipn.product.policy.VpnEntitlementController
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var originalLogWrapper: LibtailscaleWrapper

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    originalLogWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper =
        mock<LibtailscaleWrapper>().also {
          doNothing().`when`(it).sendLog(anyString(), anyString())
        }
  }

  @After
  fun tearDown() {
    TSLog.libtailscaleWrapper = originalLogWrapper
    Dispatchers.resetMain()
  }

  @Test
  fun toggleStateRemainsOffWhenVpnIsInactiveEvenIfBackendIsRunning() = runTest {
    val vpnActiveFlow = MutableStateFlow(false)
    val vpnPreparedFlow = MutableStateFlow(true)
    val appViewModel = mock<AppViewModel>()
    `when`(appViewModel.vpnActive).thenReturn(vpnActiveFlow)
    `when`(appViewModel.vpnPrepared).thenReturn(vpnPreparedFlow)
    val vpnEntitlementController = mock<VpnEntitlementController>()

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
        )

    Notifier.setState(Ipn.State.NoState)
    advanceUntilIdle()
    assertFalse(viewModel.vpnToggleState.value)

    Notifier.setState(Ipn.State.Starting)
    advanceUntilIdle()
    assertFalse(viewModel.vpnToggleState.value)

    Notifier.setState(Ipn.State.Running)
    advanceUntilIdle()
    assertFalse(viewModel.vpnToggleState.value)
    assertEquals(R.string.stopped, viewModel.stateRes.value)
  }

  @Test
  fun toggleStateTurnsOnWhenVpnActiveAndBackendRunning() = runTest {
    val vpnActiveFlow = MutableStateFlow(false)
    val vpnPreparedFlow = MutableStateFlow(true)
    val appViewModel = mock<AppViewModel>()
    `when`(appViewModel.vpnActive).thenReturn(vpnActiveFlow)
    `when`(appViewModel.vpnPrepared).thenReturn(vpnPreparedFlow)
    val vpnEntitlementController = mock<VpnEntitlementController>()

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
        )

    Notifier.setState(Ipn.State.Running)
    vpnActiveFlow.value = true
    advanceUntilIdle()

    assertTrue(viewModel.vpnToggleState.value)
    assertEquals(R.string.connected, viewModel.stateRes.value)

    vpnActiveFlow.value = false
    advanceUntilIdle()

    assertFalse(viewModel.vpnToggleState.value)
    assertEquals(R.string.stopped, viewModel.stateRes.value)
  }

  @Test
  fun toggleVpnInitiatesStartSequenceWhenBackendRunningAndVpnInactive() = runTest {
    val vpnActiveFlow = MutableStateFlow(false)
    val vpnPreparedFlow = MutableStateFlow(true)
    val appViewModel = mock<AppViewModel>()
    `when`(appViewModel.vpnActive).thenReturn(vpnActiveFlow)
    `when`(appViewModel.vpnPrepared).thenReturn(vpnPreparedFlow)
    val vpnEntitlementController = mock<VpnEntitlementController>()
    `when`(
            vpnEntitlementController.authorizeStart(
                com.tailscale.ipn.product.policy.VpnStartOrigin.PermissionRequest))
        .thenReturn(false)

    var vpnStarts = 0
    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
            vpnStarter = { vpnStarts++ },
        )

    Notifier.setState(Ipn.State.Running)
    advanceUntilIdle()

    viewModel.toggleVpn(desiredState = true)
    advanceUntilIdle()

    org.mockito.Mockito.verify(vpnEntitlementController)
        .authorizeStart(com.tailscale.ipn.product.policy.VpnStartOrigin.PermissionRequest)
  }
}
