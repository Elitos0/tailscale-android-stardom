// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.product.policy.DesiredExitMode
import com.tailscale.ipn.product.policy.DesiredExitModeStore
import com.tailscale.ipn.product.policy.ExitNodeMutation
import com.tailscale.ipn.product.policy.ExitNodeMutationBoundary
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import java.util.TreeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ExitNodePickerNav(
    val onNavigateBackHome: () -> Unit,
    val onNavigateBackToExitNodes: () -> Unit,
    val onNavigateToMullvad: () -> Unit,
    val onNavigateToMullvadInfo: () -> Unit,
    val onNavigateBackToMullvad: () -> Unit,
    val onNavigateToMullvadCountry: (String) -> Unit,
    val onNavigateToRunAsExitNode: () -> Unit,
)

class ExitNodePickerViewModelFactory(
    private val nav: ExitNodePickerNav,
    private val accessState: StateFlow<AccessState> = MutableStateFlow(AccessState.Unavailable)
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return ExitNodePickerViewModel(nav, accessState) as T
  }
}

class ExitNodePickerViewModel(
    private val nav: ExitNodePickerNav,
    private val accessState: StateFlow<AccessState>,
    private val netmapFlow: StateFlow<Netmap.NetworkMap?> = Notifier.netmap,
    private val prefsFlow: StateFlow<Ipn.Prefs?> = Notifier.prefs,
    private val mutationBoundaryOverride: ExitNodeMutationBoundary? = null,
    private val desiredExitModeStoreOverride: DesiredExitModeStore? = null,
) : IpnViewModel(observeUserProfiles = false) {
  data class ExitNode(
      val id: StableNodeID? = null,
      val label: String,
      val online: StateFlow<Boolean>,
      val selected: Boolean,
      val mullvad: Boolean = false,
      val priority: Int = 0,
      val countryCode: String = "",
      val country: String = "",
      val city: String = ""
  )

  data class AutoExitNode(
      val selected: Boolean,
      val effectiveExitNodeID: StableNodeID? = null,
      val effectiveNodeLabel: String? = null,
  )

  val tailnetExitNodes: StateFlow<List<ExitNode>> = MutableStateFlow(emptyList())
  val mullvadExitNodesByCountryCode: StateFlow<Map<String, List<ExitNode>>> =
      MutableStateFlow(TreeMap())
  val mullvadBestAvailableByCountry: StateFlow<Map<String, ExitNode>> = MutableStateFlow(TreeMap())
  val mullvadExitNodeCount: StateFlow<Int> = MutableStateFlow(0)
  val anyActive: StateFlow<Boolean> = MutableStateFlow(false)
  val autoExitNode: StateFlow<AutoExitNode> = MutableStateFlow(AutoExitNode(selected = false))
  val shouldShowMullvadInfo: StateFlow<Boolean> = MutableStateFlow(false)

  init {
    viewModelScope.launch {
      val desiredStore = desiredExitModeStore()
      val desiredModeFlow = desiredStore?.mode ?: MutableStateFlow<DesiredExitMode?>(null)
      combine(netmapFlow, prefsFlow, accessState, desiredModeFlow) {
              netmap,
              prefs,
              accessState,
              desiredMode ->
            val exitNodeId = prefs?.activeExitNodeID ?: prefs?.selectedExitNodeID
            val isDesiredAuto = desiredMode is DesiredExitMode.Auto
            val autoExitNodeEnabled = prefs?.AutoExitNode == "any" || isDesiredAuto
            val effectiveExitNodeId =
                if (autoExitNodeEnabled) exitNodeId?.takeUnless { it == "auto:any" } else exitNodeId
            autoExitNode.set(
                AutoExitNode(
                    selected = autoExitNodeEnabled,
                    effectiveExitNodeID = effectiveExitNodeId,
                ))
            anyActive.set(autoExitNodeEnabled)
            netmap?.Peers?.let { peers ->
              val allNodes =
                  peers
                      .filter { it.isExitNode }
                      .map {
                        ExitNode(
                            id = it.StableID,
                            label = it.displayName,
                            online = MutableStateFlow(it.Online ?: false),
                            selected = !autoExitNodeEnabled && it.StableID == exitNodeId,
                            mullvad = it.isMullvadNode,
                            priority = it.Hostinfo.Location?.Priority ?: 0,
                            countryCode = it.Hostinfo.Location?.CountryCode ?: "",
                            country = it.Hostinfo.Location?.Country ?: "",
                            city = it.Hostinfo.Location?.City ?: "",
                        )
                      }

              val allowedExitNodeIds =
                  (accessState as? AccessState.Active)?.allowedExitNodeIds.orEmpty()
              val tailnetNodes = allNodes.filter { !it.mullvad && it.id in allowedExitNodeIds }
              tailnetExitNodes.set(tailnetNodes.sortedWith { a, b -> a.label.compareTo(b.label) })

              val effectiveNode = allNodes.find { it.id == effectiveExitNodeId }
              autoExitNode.set(
                  AutoExitNode(
                      selected = autoExitNodeEnabled,
                      effectiveExitNodeID = effectiveExitNodeId,
                      effectiveNodeLabel = effectiveNode?.city?.ifEmpty { effectiveNode.label },
                  ))
              anyActive.set(autoExitNodeEnabled || allNodes.any { it.selected })
            }
          }
          .collect {}
    }
  }

  fun setExitNode(node: ExitNode) {
    if (node.mullvad) return
    val nodeId = node.id?.trim().orEmpty()
    if (nodeId.isEmpty()) return
    setExitNodePrefs(ExitNodeMutation.Manual(nodeId))
  }

  fun setAutoExitNode() {
    setExitNodePrefs(ExitNodeMutation.Auto())
  }

  private fun setExitNodePrefs(mutation: ExitNodeMutation) {
    TSLog.d(
        "ExitNodePicker",
        "operation=mutate desired=${mutation::class.simpleName} prefs=${prefsFlow.value != null} netmap=${netmapFlow.value != null} selected=${prefsFlow.value?.selectedExitNodeID != null} effective=${prefsFlow.value?.activeExitNodeID ?: "none"}")
    LoadingIndicator.start()
    viewModelScope.launch {
      val result = mutationBoundary().mutateExitNode(mutation)
      if (result.isSuccess) {
        when (mutation) {
          is ExitNodeMutation.Auto -> desiredExitModeStore()?.set(DesiredExitMode.Auto)
          is ExitNodeMutation.Manual ->
              desiredExitModeStore()?.set(DesiredExitMode.Manual(mutation.nodeId))
          is ExitNodeMutation.Clear -> desiredExitModeStore()?.clear()
        }
        nav.onNavigateBackHome()
      } else {
        TSLog.e(
            "ExitNodePicker",
            "operation=mutate desired=${mutation::class.simpleName} failed=${result.exceptionOrNull()?.message}",
            result.exceptionOrNull())
      }
      LoadingIndicator.stop()
    }
  }

  fun toggleAllowLANAccess(callback: (Result<Ipn.Prefs>) -> Unit) {
    val prefs =
        prefsFlow.value
            ?: run {
              callback(Result.failure(Exception("no prefs")))
              return@toggleAllowLANAccess
            }

    val allowLanAccess = !prefs.ExitNodeAllowLANAccess
    val desiredMode = desiredExitModeStore()?.mode?.value
    val mutation =
        when {
          prefs.AutoExitNode == "any" || desiredMode is DesiredExitMode.Auto ->
              ExitNodeMutation.Auto(allowLanAccess)
          !prefs.activeExitNodeID.isNullOrBlank() ->
              ExitNodeMutation.Manual(checkNotNull(prefs.activeExitNodeID), allowLanAccess)
          !prefs.selectedExitNodeID.isNullOrBlank() ->
              ExitNodeMutation.Manual(checkNotNull(prefs.selectedExitNodeID), allowLanAccess)
          desiredMode is DesiredExitMode.Manual ->
              ExitNodeMutation.Manual(desiredMode.nodeId, allowLanAccess)
          else -> ExitNodeMutation.Auto(allowLanAccess)
        }
    viewModelScope.launch {
      val result = mutationBoundary().mutateExitNode(mutation)
      result.fold(
          onSuccess = { callback(Result.success(prefs)) },
          onFailure = { callback(Result.failure(it)) },
      )
    }
  }

  private fun mutationBoundary(): ExitNodeMutationBoundary =
      mutationBoundaryOverride ?: App.get().vpnEntitlementController

  private fun desiredExitModeStore(): DesiredExitModeStore? =
      desiredExitModeStoreOverride ?: runCatching { App.get().desiredExitModeStore }.getOrNull()
}

val List<ExitNodePickerViewModel.ExitNode>.selected
  get() = this.any { it.selected }

val Map<String, List<ExitNodePickerViewModel.ExitNode>>.selected
  get() = this.any { it.value.selected }
