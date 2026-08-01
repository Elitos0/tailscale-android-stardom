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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

  @Test
  fun representsConfiguredAutoModeSeparatelyFromItsEffectiveExitNode() = runTest {
    val netmap = MutableStateFlow(networkMap(exitNode("node-a", "Alpha")))
    val prefs = MutableStateFlow<Ipn.Prefs?>(Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"))
    val access = MutableStateFlow<AccessState>(AccessState.Active(setOf("node-a")))
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = access,
            netmapFlow = netmap,
            prefsFlow = prefs,
        )

    advanceUntilIdle()

    assertEquals(true, viewModel.autoExitNode.value.selected)
    assertEquals("node-a", viewModel.autoExitNode.value.effectiveExitNodeID)
    assertEquals("Alpha", viewModel.autoExitNode.value.effectiveNodeLabel)
    assertFalse(viewModel.tailnetExitNodes.value.single().selected)
  }

  @Test
  fun doesNotTreatUnresolvedAutoPlaceholderAsEffectiveExitNode() = runTest {
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(emptySet())),
            netmapFlow = MutableStateFlow(networkMap()),
            prefsFlow = MutableStateFlow(Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "auto:any")),
        )

    advanceUntilIdle()

    assertNull(viewModel.autoExitNode.value.effectiveExitNodeID)
  }

  @Test
  fun selectingAutoSendsNativeAutoExpressionWithoutStableNodeId() = runTest {
    var sent: Ipn.MaskedPrefs? = null
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(emptySet())),
            editPrefsOverride = { prefs, callback ->
              sent = prefs
              callback(Result.success(Ipn.Prefs()))
            },
        )

    viewModel.setAutoExitNode()

    assertEquals("any", sent?.AutoExitNode)
    assertEquals(true, sent?.AutoExitNodeSet)
    assertNull(sent?.ExitNodeID)
    assertNull(sent?.ExitNodeIDSet)
  }

  @Test
  fun selectingManualExitNodeKeepsExistingStableNodePreferencePath() = runTest {
    var sent: Ipn.MaskedPrefs? = null
    val viewModel = pickerViewModelCapturing { sent = it }

    viewModel.setExitNode(
        ExitNodePickerViewModel.ExitNode(
            id = "node-a",
            label = "Alpha",
            online = MutableStateFlow(true),
            selected = false,
        ))

    assertEquals("node-a", sent?.ExitNodeID)
    assertEquals(true, sent?.ExitNodeIDSet)
    assertNull(sent?.AutoExitNode)
    assertNull(sent?.AutoExitNodeSet)
  }

  @Test
  fun clearingExitNodeKeepsExistingStableNodeClearPath() = runTest {
    var sent: Ipn.MaskedPrefs? = null
    val viewModel = pickerViewModelCapturing { sent = it }

    viewModel.setExitNode(
        ExitNodePickerViewModel.ExitNode(
            label = "None",
            online = MutableStateFlow(true),
            selected = false,
        ))

    assertNull(sent?.ExitNodeID)
    assertEquals(true, sent?.ExitNodeIDSet)
    assertNull(sent?.AutoExitNode)
    assertNull(sent?.AutoExitNodeSet)
  }
}

private fun pickerViewModelCapturing(capture: (Ipn.MaskedPrefs) -> Unit) =
    ExitNodePickerViewModel(
        nav = testNavigation,
        accessState = MutableStateFlow(AccessState.Active(emptySet())),
        editPrefsOverride = { prefs, callback ->
          capture(prefs)
          callback(Result.success(Ipn.Prefs()))
        },
    )

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
