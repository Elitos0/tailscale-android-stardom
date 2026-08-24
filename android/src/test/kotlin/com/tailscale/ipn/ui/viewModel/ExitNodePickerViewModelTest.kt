// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.product.policy.DesiredExitMode
import com.tailscale.ipn.product.policy.DesiredExitModeStore
import com.tailscale.ipn.product.policy.ExitNodeMutation
import com.tailscale.ipn.product.policy.ExitNodeMutationBoundary
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
  fun selectingAutoUsesTheNativeAutoMutationCommand() = runTest {
    val boundary = CapturingMutationBoundary()
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(emptySet())),
            mutationBoundaryOverride = boundary,
        )

    viewModel.setAutoExitNode()
    advanceUntilIdle()

    assertEquals(listOf(ExitNodeMutation.Auto()), boundary.mutations)
  }

  @Test
  fun selectingManualExitNodeKeepsExistingStableNodePreferencePath() = runTest {
    val boundary = CapturingMutationBoundary()
    val viewModel = pickerViewModelCapturing(boundary)

    viewModel.setExitNode(
        ExitNodePickerViewModel.ExitNode(
            id = "node-a",
            label = "Alpha",
            online = MutableStateFlow(true),
            selected = false,
        ))
    advanceUntilIdle()

    assertEquals(listOf(ExitNodeMutation.Manual("node-a")), boundary.mutations)
  }

  @Test
  fun clearingExitNodeKeepsExistingStableNodeClearPath() = runTest {
    val boundary = CapturingMutationBoundary()
    val viewModel = pickerViewModelCapturing(boundary)

    viewModel.setExitNode(
        ExitNodePickerViewModel.ExitNode(
            label = "None",
            online = MutableStateFlow(true),
            selected = false,
        ))
    advanceUntilIdle()

    assertEquals(listOf(ExitNodeMutation.Clear()), boundary.mutations)
  }

  @Test
  fun deniedManualSelectionRemainsInsideApplicationMutationBoundary() = runTest {
    DenyingMutationBoundary.calls = 0
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(setOf("node-a"))),
            mutationBoundaryOverride = DenyingMutationBoundary,
        )

    viewModel.setExitNode(
        ExitNodePickerViewModel.ExitNode(
            id = "node-a",
            label = "Alpha",
            online = MutableStateFlow(true),
            selected = false,
        ))
    advanceUntilIdle()

    assertEquals(1, DenyingMutationBoundary.calls)
  }

  @Test
  fun mullvadSelectionNeverReachesBoundaryOrWriterEvenIfPolicyContainsItsId() = runTest {
    var authorizations = 0
    val boundary =
        object : ExitNodeMutationBoundary {
          override suspend fun mutateExitNode(mutation: ExitNodeMutation): Result<Unit> {
            authorizations++
            return Result.success(Unit)
          }
        }
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(setOf("mullvad-node"))),
            mutationBoundaryOverride = boundary,
        )

    viewModel.setExitNode(
        ExitNodePickerViewModel.ExitNode(
            id = "mullvad-node",
            label = "Mullvad",
            online = MutableStateFlow(true),
            selected = false,
            mullvad = true,
        ))
    advanceUntilIdle()

    assertEquals(0, authorizations)
  }

  @Test
  fun lanToggleThatWouldRetainExitNodeUsesTheSameMutationBoundary() = runTest {
    val seen = mutableListOf<ExitNodeMutation>()
    val boundary =
        object : ExitNodeMutationBoundary {
          override suspend fun mutateExitNode(mutation: ExitNodeMutation): Result<Unit> {
            seen += mutation
            return Result.failure(IllegalStateException("revoked"))
          }
        }
    var result: Result<Ipn.Prefs>? = null
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(setOf("node-a"))),
            prefsFlow = MutableStateFlow(Ipn.Prefs(ExitNodeID = "node-a")),
            mutationBoundaryOverride = boundary,
        )

    viewModel.toggleAllowLANAccess { result = it }
    advanceUntilIdle()

    assertEquals(listOf(ExitNodeMutation.Manual("node-a", allowLanAccess = true)), seen)
    assertTrue(result?.isFailure == true)
  }

  @Test
  fun stardomNeverPublishesMullvadNodesOrMullvadInfo() = runTest {
    val mullvad =
        exitNode("mullvad-node", "de-fra-wg-001").copy(Name = "de-fra-wg-001.mullvad.ts.net.")
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(setOf("mullvad-node"))),
            netmapFlow = MutableStateFlow(networkMap(mullvad)),
            prefsFlow =
                MutableStateFlow(Ipn.Prefs(ControlURL = "https://controlplane.tailscale.com")),
        )

    advanceUntilIdle()

    assertTrue(viewModel.mullvadExitNodesByCountryCode.value.isEmpty())
    assertEquals(0, viewModel.mullvadExitNodeCount.value)
    assertFalse(viewModel.shouldShowMullvadInfo.value)
  }

  @Test
  fun selectingAutoWritesToDesiredExitModeStore() = runTest {
    val boundary = CapturingMutationBoundary()
    val store = FakeDesiredExitModeStore()
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(emptySet())),
            mutationBoundaryOverride = boundary,
            desiredExitModeStoreOverride = store,
        )

    viewModel.setAutoExitNode()
    advanceUntilIdle()

    assertEquals(listOf(DesiredExitMode.Auto), store.sets)
    assertEquals(DesiredExitMode.Auto, store.mode.value)
  }

  @Test
  fun selectingManualWritesToDesiredExitModeStore() = runTest {
    val boundary = CapturingMutationBoundary()
    val store = FakeDesiredExitModeStore()
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(emptySet())),
            mutationBoundaryOverride = boundary,
            desiredExitModeStoreOverride = store,
        )

    viewModel.setExitNode(
        ExitNodePickerViewModel.ExitNode(
            id = "node-a",
            label = "Alpha",
            online = MutableStateFlow(true),
            selected = false,
        ))
    advanceUntilIdle()

    assertEquals(listOf(DesiredExitMode.Manual("node-a")), store.sets)
    assertEquals(DesiredExitMode.Manual("node-a"), store.mode.value)
  }

  @Test
  fun clearingExitNodeClearsDesiredExitModeStore() = runTest {
    val boundary = CapturingMutationBoundary()
    val store = FakeDesiredExitModeStore(initial = DesiredExitMode.Auto)
    val viewModel =
        ExitNodePickerViewModel(
            nav = testNavigation,
            accessState = MutableStateFlow(AccessState.Active(emptySet())),
            mutationBoundaryOverride = boundary,
            desiredExitModeStoreOverride = store,
        )

    viewModel.setExitNode(
        ExitNodePickerViewModel.ExitNode(
            label = "None",
            online = MutableStateFlow(true),
            selected = false,
        ))
    advanceUntilIdle()

    assertEquals(1, store.clears)
    assertNull(store.mode.value)
  }
}

private fun pickerViewModelCapturing(boundary: ExitNodeMutationBoundary) =
    ExitNodePickerViewModel(
        nav = testNavigation,
        accessState = MutableStateFlow(AccessState.Active(emptySet())),
        mutationBoundaryOverride = boundary,
    )

private class CapturingMutationBoundary : ExitNodeMutationBoundary {
  val mutations = mutableListOf<ExitNodeMutation>()

  override suspend fun mutateExitNode(mutation: ExitNodeMutation): Result<Unit> {
    mutations += mutation
    return Result.success(Unit)
  }
}

private object DenyingMutationBoundary : ExitNodeMutationBoundary {
  var calls = 0

  override suspend fun mutateExitNode(mutation: ExitNodeMutation): Result<Unit> {
    calls++
    return Result.failure(IllegalStateException("denied"))
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

private class FakeDesiredExitModeStore(initial: DesiredExitMode? = null) : DesiredExitModeStore {
  private val _mode = MutableStateFlow(initial)
  override val mode: StateFlow<DesiredExitMode?> = _mode.asStateFlow()
  val sets = mutableListOf<DesiredExitMode>()
  var clears = 0

  override fun set(mode: DesiredExitMode) {
    sets += mode
    _mode.value = mode
  }

  override fun clear() {
    clears++
    _mode.value = null
  }
}
