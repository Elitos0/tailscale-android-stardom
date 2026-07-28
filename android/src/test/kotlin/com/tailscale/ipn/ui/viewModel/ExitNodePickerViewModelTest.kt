// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
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
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class ExitNodePickerViewModelTest {
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
  fun emitsOnlyAllowedTailnetExitNodesInAscendingLabelOrder() = runTest {
    val netmap =
        MutableStateFlow(
            networkMap(
                exitNode("node-b", "Bravo"),
                exitNode("node-a", "Alpha"),
                exitNode("node-c", "Charlie")))
    val prefs = MutableStateFlow<Ipn.Prefs?>(Ipn.Prefs())
    val access = MutableStateFlow<AccessState>(AccessState.Active(setOf("node-a", "node-c")))
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = access,
            netmapFlow = netmap,
            prefsFlow = prefs,
        )

    advanceUntilIdle()

    assertEquals(listOf("Alpha", "Charlie"), viewModel.tailnetExitNodes.value.map { it.label })
  }
}

private val testNavigation =
    ExitNodePickerNav(
        onNavigateBackHome = {},
        onNavigateBackToExitNodes = {},
        onNavigateToMullvad = {},
        onNavigateToMullvadInfo = {},
        onNavigateBackToMullvad = {},
        onNavigateToMullvadCountry = {},
        onNavigateToRunAsExitNode = {},
    )

private fun networkMap(vararg peers: Tailcfg.Node) =
    Netmap.NetworkMap(
        SelfNode = Tailcfg.Node(),
        Peers = peers.toList(),
        Domain = "tailnet.example.com",
        UserProfiles = emptyMap(),
        TKAEnabled = false,
    )

private fun exitNode(id: String, label: String) =
    Tailcfg.Node(
        StableID = id,
        Name = label,
        ComputedName = label,
        AllowedIPs = listOf("0.0.0.0/0", "::/0"),
        Online = true,
    )
