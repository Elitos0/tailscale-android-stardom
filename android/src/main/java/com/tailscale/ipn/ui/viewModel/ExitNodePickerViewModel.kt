// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StableNodeID
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import java.util.TreeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    private val editPrefsOverride: ((Ipn.MaskedPrefs, (Result<Ipn.Prefs>) -> Unit) -> Unit)? = null,
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
      netmapFlow
          .combine(prefsFlow) { netmap, prefs -> Pair(netmap, prefs) }
          .combine(accessState) { (netmap, prefs), accessState ->
            Triple(netmap, prefs, accessState)
          }
          .stateIn(viewModelScope)
          .collect { (netmap, prefs, accessState) ->
            val exitNodeId = prefs?.activeExitNodeID ?: prefs?.selectedExitNodeID
            val autoExitNodeEnabled = prefs?.AutoExitNode == "any"
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
                            mullvad = it.Name.endsWith(".mullvad.ts.net."),
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

              val allMullvadExitNodes =
                  allNodes.filter { node ->
                    // Pick all mullvad nodes that are online or the currently selected
                    val online = node.online.value
                    node.mullvad && (node.selected || online)
                  }
              val mullvadExitNodes =
                  allMullvadExitNodes
                      .groupBy {
                        // Group by countryCode
                        it.countryCode
                      }
                      .mapValues { (_, nodes) ->
                        // Group by city
                        nodes
                            .groupBy { it.city }
                            .mapValues { (_, nodes) ->
                              // Pick one node per city, either the selected one or the best
                              // available
                              nodes
                                  .sortedWith { a, b ->
                                    if (a.selected && !b.selected) {
                                      -1
                                    } else if (b.selected && !a.selected) {
                                      1
                                    } else {
                                      b.priority.compareTo(a.priority)
                                    }
                                  }
                                  .first()
                            }
                            .values
                            .sortedBy { it.city.lowercase() }
                      }
              mullvadExitNodesByCountryCode.set(mullvadExitNodes)
              mullvadExitNodeCount.set(allMullvadExitNodes.size)

              val bestAvailableByCountry =
                  mullvadExitNodes.mapValues { (_, nodes) ->
                    nodes.minByOrNull { -1 * it.priority }!!
                  }
              mullvadBestAvailableByCountry.set(bestAvailableByCountry)

              val effectiveNode = allNodes.find { it.id == effectiveExitNodeId }
              autoExitNode.set(
                  AutoExitNode(
                      selected = autoExitNodeEnabled,
                      effectiveExitNodeID = effectiveExitNodeId,
                      effectiveNodeLabel = effectiveNode?.city?.ifEmpty { effectiveNode.label },
                  ))
              anyActive.set(autoExitNodeEnabled || allNodes.any { it.selected })

              prefs?.let { prefs ->
                // Only show the Mullvad info view if the user is an admin and is using a Tailscale
                // control server, as it wouldn't be actionable information otherwise.
                shouldShowMullvadInfo.set(
                    netmap.SelfNode.isAdmin && prefs.ControlURL.endsWith(".tailscale.com"))
              }
            }
          }
    }
  }

  fun setExitNode(node: ExitNode) {
    setExitNodePrefs(Ipn.MaskedPrefs().apply { ExitNodeID = node.id })
  }

  fun setAutoExitNode() {
    setExitNodePrefs(Ipn.MaskedPrefs().apply { AutoExitNode = "any" })
  }

  private fun setExitNodePrefs(prefs: Ipn.MaskedPrefs) {
    LoadingIndicator.start()
    val callback: (Result<Ipn.Prefs>) -> Unit = {
      nav.onNavigateBackHome()
      LoadingIndicator.stop()
    }
    editPrefsOverride?.invoke(prefs, callback) ?: Client(viewModelScope).editPrefs(prefs, callback)
  }

  fun toggleAllowLANAccess(callback: (Result<Ipn.Prefs>) -> Unit) {
    val prefs =
        Notifier.prefs.value
            ?: run {
              callback(Result.failure(Exception("no prefs")))
              return@toggleAllowLANAccess
            }

    val prefsOut = Ipn.MaskedPrefs()
    prefsOut.ExitNodeAllowLANAccess = !prefs.ExitNodeAllowLANAccess
    Client(viewModelScope).editPrefs(prefsOut, callback)
  }
}

val List<ExitNodePickerViewModel.ExitNode>.selected
  get() = this.any { it.selected }

val Map<String, List<ExitNodePickerViewModel.ExitNode>>.selected
  get() = this.any { it.value.selected }
