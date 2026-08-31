// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.product.StardomSessionController
import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.product.policy.DesiredExitMode
import com.tailscale.ipn.product.ui.AccessStatusView
import com.tailscale.ipn.product.ui.ConnectionStage
import com.tailscale.ipn.product.ui.resolveConnectionStage
import com.tailscale.ipn.ui.Links
import com.tailscale.ipn.ui.components.StardomAccountDialog
import com.tailscale.ipn.ui.components.StardomBackground
import com.tailscale.ipn.ui.components.StardomHeader
import com.tailscale.ipn.ui.components.StardomOrbitControl
import com.tailscale.ipn.ui.components.StardomRoutingPanel
import com.tailscale.ipn.ui.components.StardomServerSelectorSheet
import com.tailscale.ipn.ui.components.StardomSettingsSheet
import com.tailscale.ipn.ui.components.StardomStatus
import com.tailscale.ipn.ui.model.AccountProfile
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.ConnectionMode
import com.tailscale.ipn.ui.model.DnsProvider
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.StarServerNode
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.model.VpnProtocol
import com.tailscale.ipn.ui.model.VpnState
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors
import com.tailscale.ipn.ui.theme.StardomDimensions
import com.tailscale.ipn.ui.theme.customErrorContainer
import com.tailscale.ipn.ui.theme.errorListItem
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.primaryListItem
import com.tailscale.ipn.ui.theme.warningButton
import com.tailscale.ipn.ui.theme.warningListItem
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory
import com.tailscale.ipn.ui.viewModel.IpnViewModel.NodeState
import com.tailscale.ipn.ui.viewModel.MainViewModel
import kotlinx.coroutines.launch

// Navigation actions for the MainView
data class MainViewNavigation(
    val onNavigateToSettings: () -> Unit,
    val onNavigateStardomLogin: () -> Unit,
    val onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    val onNavigateToExitNodes: () -> Unit,
    val onNavigateToHealth: () -> Unit,
    val onNavigateToSearch: () -> Unit,
)

internal fun shouldRenderPeerContent(state: Ipn.State, connectionStage: ConnectionStage): Boolean =
    state == Ipn.State.Running && connectionStage == ConnectionStage.Connect

val DefaultStarServers =
    listOf(
        StarServerNode(
            id = "star-fra-01",
            starName = "POLARIS-01",
            constellation = "CYGNUS-ALPHA",
            city = "Frankfurt",
            countryCode = "DE",
            coordinates = "50.1109° N, 8.6821° E",
            basePingMs = 18,
            loadPercent = 24,
            ipAddress = "100.64.0.1"),
        StarServerNode(
            id = "star-ams-01",
            starName = "VEGA-PRIME",
            constellation = "ORION-PRIME",
            city = "Amsterdam",
            countryCode = "NL",
            coordinates = "52.3676° N, 4.9041° E",
            basePingMs = 22,
            loadPercent = 38,
            ipAddress = "100.64.0.2"),
        StarServerNode(
            id = "star-sto-01",
            starName = "SIRIUS-04",
            constellation = "CASSIOPEIA-IV",
            city = "Stockholm",
            countryCode = "SE",
            coordinates = "59.3293° N, 18.0686° E",
            basePingMs = 31,
            loadPercent = 19,
            ipAddress = "100.64.0.3"),
        StarServerNode(
            id = "star-zrh-01",
            starName = "ALTAIR-02",
            constellation = "VEGA-SECTOR",
            city = "Zurich",
            countryCode = "CH",
            coordinates = "47.3769° N, 8.5417° E",
            basePingMs = 26,
            loadPercent = 42,
            ipAddress = "100.64.0.4"))

fun mapExitNodeToStarNode(
    exitNode: ExitNodePickerViewModel.ExitNode,
    index: Int = 0
): StarServerNode {
  val defaultStarNames =
      listOf(
          "POLARIS-01",
          "VEGA-PRIME",
          "SIRIUS-04",
          "ALTAIR-02",
          "DENEB-07",
          "RIGEL-IX",
          "ANTARES-03",
          "BETELGEUSE-V")
  val defaultConstellations =
      listOf(
          "CYGNUS-ALPHA",
          "ORION-PRIME",
          "CASSIOPEIA-IV",
          "VEGA-SECTOR",
          "ANDROMEDA-IX",
          "CENTAURI-VII",
          "URSA-MAJOR",
          "PEGASUS-III")
  val defaultCoordinates =
      listOf(
          "50.1109° N, 8.6821° E",
          "52.3676° N, 4.9041° E",
          "59.3293° N, 18.0686° E",
          "47.3769° N, 8.5417° E",
          "51.5074° N, 0.1278° W")

  val starName =
      if (exitNode.label.isNotBlank() && exitNode.label != "auto:any") {
        exitNode.label.uppercase()
      } else {
        defaultStarNames[index % defaultStarNames.size]
      }

  val city = if (exitNode.city.isNotBlank()) exitNode.city else "Frankfurt"
  val countryCode =
      if (exitNode.countryCode.isNotBlank()) exitNode.countryCode.uppercase() else "DE"
  val hash = Math.abs((exitNode.id ?: exitNode.label).hashCode())
  val constellation = defaultConstellations[hash % defaultConstellations.size]
  val coordinates = defaultCoordinates[hash % defaultCoordinates.size]
  val ping = if (exitNode.priority > 0) exitNode.priority else (18 + (hash % 25))
  val load = 15 + (hash % 40)

  return StarServerNode(
      id = exitNode.id ?: exitNode.label,
      starName = starName,
      constellation = constellation,
      city = city,
      countryCode = countryCode,
      coordinates = coordinates,
      basePingMs = ping,
      loadPercent = load,
      ipAddress = exitNode.id ?: "100.64.0.1")
}

fun resolveStardomVpnState(
    ipnState: Ipn.State,
    isVpnActive: Boolean,
    isToggleInProgress: Boolean,
    connectionStage: ConnectionStage,
    accessState: AccessState,
): VpnState {
  return when {
    accessState == AccessState.Disabled || accessState == AccessState.Unavailable -> VpnState.ERROR
    isToggleInProgress || (ipnState == Ipn.State.Starting && isVpnActive) ->
        VpnState.RESOLVING_STAR_ROUTE
    isVpnActive && ipnState == Ipn.State.Running && connectionStage == ConnectionStage.Connect ->
        VpnState.SECURED
    ipnState == Ipn.State.Stopped && isToggleInProgress -> VpnState.DISCONNECTING
    else -> VpnState.DISCONNECTED
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainView(
    loginAtUrl: (String) -> Unit,
    navigation: MainViewNavigation,
    viewModel: MainViewModel,
    sessionController: StardomSessionController,
) {
  val currentPingDevice by viewModel.pingViewModel.peer.collectAsState()
  val healthIcon by viewModel.healthIcon.collectAsState()

  val isPrepared by viewModel.isVpnPrepared.collectAsState(initial = true)
  val isOn by viewModel.vpnToggleState.collectAsState(initial = false)
  val state by viewModel.ipnState.collectAsState(initial = Ipn.State.NoState)
  val user by viewModel.loggedInUser.collectAsState(initial = null)
  val netmap by viewModel.netmap.collectAsState(initial = null)
  val showExitNodePicker by MDMSettings.exitNodesPicker.flow.collectAsState()
  val showKeyExpiry by viewModel.showExpiry.collectAsState(initial = false)
  val accessState by sessionController.accessState.collectAsState()
  val authentikState by sessionController.authentikState.collectAsState()
  val isToggleInProgress by viewModel.isToggleInProgress.collectAsState(initial = false)
  val isVpnActive by viewModel.isVpnActive.collectAsState(initial = false)
  val peersList by viewModel.peers.collectAsState(initial = emptyList())

  val hasHeadscaleProfile = state != Ipn.State.NeedsLogin && user?.let { !it.isEmpty() } == true
  val context = LocalContext.current
  val refreshScope = rememberCoroutineScope()

  val connectionStage =
      resolveConnectionStage(
          authentikState = authentikState,
          hasHeadscaleProfile = hasHeadscaleProfile,
          accessState = accessState,
          isVpnPrepared = isPrepared)

  val refreshAccess: () -> Unit = {
    refreshScope.launch { sessionController.refreshAccess(context, force = true) }
    Unit
  }

  val stardomVpnState =
      resolveStardomVpnState(
          ipnState = state,
          isVpnActive = isVpnActive,
          isToggleInProgress = isToggleInProgress,
          connectionStage = connectionStage,
          accessState = accessState)

  val exitNodeViewModel: ExitNodePickerViewModel =
      viewModel(
          factory =
              ExitNodePickerViewModelFactory(
                  nav =
                      ExitNodePickerNav(
                          onNavigateBackHome = {},
                          onNavigateBackToExitNodes = {},
                          onNavigateToMullvad = {},
                          onNavigateToMullvadInfo = {},
                          onNavigateBackToMullvad = {},
                          onNavigateToMullvadCountry = {},
                          onNavigateToRunAsExitNode = {}),
                  accessState = sessionController.accessState))

  val autoExitNodeState by exitNodeViewModel.autoExitNode.collectAsState()
  val tailnetExitNodesState by exitNodeViewModel.tailnetExitNodes.collectAsState()

  val connectionMode =
      if (autoExitNodeState.selected) ConnectionMode.AUTO else ConnectionMode.MANUAL

  val servers =
      remember(tailnetExitNodesState) {
        if (tailnetExitNodesState.isNotEmpty()) {
          tailnetExitNodesState.mapIndexed { index, node -> mapExitNodeToStarNode(node, index) }
        } else {
          DefaultStarServers
        }
      }

  val activeServer =
      remember(connectionMode, autoExitNodeState, tailnetExitNodesState, servers) {
        if (connectionMode == ConnectionMode.AUTO) {
          val effectiveId = autoExitNodeState.effectiveExitNodeID
          servers.find { it.id == effectiveId }
              ?: servers.firstOrNull()
              ?: DefaultStarServers.first()
        } else {
          val selectedExitNode = tailnetExitNodesState.find { it.selected }
          if (selectedExitNode != null) {
            servers.find { it.id == selectedExitNode.id }
                ?: servers.firstOrNull()
                ?: DefaultStarServers.first()
          } else {
            servers.firstOrNull() ?: DefaultStarServers.first()
          }
        }
      }

  var showAccountDialog by remember { mutableStateOf(false) }
  var showSettingsSheet by remember { mutableStateOf(false) }
  var showServerSheet by remember { mutableStateOf(false) }
  var selectedLanguage by remember { mutableStateOf(AppLanguage.RU) }
  var selectedProtocol by remember { mutableStateOf(VpnProtocol.WIREGUARD) }
  var selectedDns by remember { mutableStateOf(DnsProvider.STARDOM_ZERO_KNOWLEDGE) }

  val serverSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val accountProfile =
      remember(user, netmap, peersList) {
        val currentUser = user
        val selfKey = netmap?.SelfNode?.Key ?: "ed25519:7a4f89d31ce02b66"
        val keyExpiry = netmap?.SelfNode?.KeyExpiry ?: "2028.12.31"
        val loginName =
            currentUser?.UserProfile?.LoginName?.ifEmpty { null }
                ?: currentUser?.NetworkProfile?.DomainName?.ifEmpty { null }
                ?: "STAR-4096-ALPHA"
        val deviceCount = peersList.sumOf { it.peers.size } + 1
        val isStub = currentUser == null || currentUser.isEmpty()

        AccountProfile(
            accountId = loginName,
            tier = if (isStub) "ORBITAL APEX // DEMO" else "ORBITAL APEX // PRO",
            publicKey = selfKey,
            validUntil = keyExpiry,
            activeDevices = deviceCount,
            maxDevices = 5,
            bandwidthUsedGb = 0.0,
            totalQuota = "UNLIMITED",
            isStub = isStub)
      }

  val onPowerToggle: () -> Unit = {
    when {
      stardomVpnState.isConnected -> {
        viewModel.toggleVpn(desiredState = false)
      }
      stardomVpnState.isConnecting -> {
        viewModel.toggleVpn(desiredState = false)
      }
      stardomVpnState.isError -> {
        if (connectionStage == ConnectionStage.AccessUnavailable) {
          refreshAccess()
        } else if (connectionStage == ConnectionStage.SignIn) {
          navigation.onNavigateStardomLogin()
        } else {
          viewModel.toggleVpn(desiredState = true)
        }
      }
      else -> {
        when (connectionStage) {
          ConnectionStage.SignIn -> navigation.onNavigateStardomLogin()
          ConnectionStage.RequestVpnPermission ->
              viewModel.showVPNPermissionLauncherIfUnauthorized()
          ConnectionStage.AccessUnavailable -> refreshAccess()
          ConnectionStage.AccessDisabled -> {
            /* fail-closed, access disabled */
          }
          ConnectionStage.Connect -> viewModel.toggleVpn(desiredState = true)
        }
      }
    }
  }

  LoadingIndicator.Wrap {
    Scaffold(
        containerColor = StardomColors.Background,
        contentWindowInsets = WindowInsets.Companion.statusBars) { paddingInsets ->
          Box(
              modifier =
                  Modifier.fillMaxSize()
                      .background(StardomColors.Background)
                      .padding(paddingInsets)) {
                StardomBackground(vpnState = stardomVpnState)

                Column(
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(horizontal = StardomDimensions.ScreenHorizontal),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                      StardomHeader(
                          vpnState = stardomVpnState,
                          language = selectedLanguage,
                          onProfileClick = { showAccountDialog = true },
                          onSettingsClick = { showSettingsSheet = true })

                      Spacer(Modifier.height(8.dp))

                      StardomOrbitControl(
                          vpnState = stardomVpnState,
                          onClick = onPowerToggle,
                          language = selectedLanguage,
                          modifier = Modifier.fillMaxWidth())

                      Spacer(Modifier.height(6.dp))

                      StardomStatus(vpnState = stardomVpnState, language = selectedLanguage)

                      Spacer(Modifier.height(16.dp))

                      StardomRoutingPanel(
                          connectionMode = connectionMode,
                          activeServer = activeServer,
                          onRoutingModeChange = { mode ->
                            if (mode == ConnectionMode.AUTO) {
                              exitNodeViewModel.setAutoExitNode()
                            } else {
                              showServerSheet = true
                            }
                          },
                          onNodeClick = { showServerSheet = true },
                          language = selectedLanguage)

                      Spacer(Modifier.height(12.dp))

                      if (hasHeadscaleProfile) {
                        AccessStatusView(sessionController)
                      }

                      when {
                        shouldRenderPeerContent(state, connectionStage) -> {
                          PromptForMissingPermissions()

                          if (showKeyExpiry) {
                            ExpiryNotification(
                                netmap = netmap, action = navigation.onNavigateStardomLogin)
                          }
                          if (showExitNodePicker.value == ShowHide.Show) {
                            ExitNodeStatus(
                                navAction = navigation.onNavigateToExitNodes, viewModel = viewModel)
                          }
                          PeerList(
                              viewModel = viewModel,
                              onNavigateToPeerDetails = navigation.onNavigateToPeerDetails,
                              onSearchBarClick = navigation.onNavigateToSearch,
                              onSearch = { viewModel.searchPeers(it) })
                        }
                        state == Ipn.State.NoState || state == Ipn.State.Starting -> StartingView()
                        else -> {
                          ConnectView(
                              state = state,
                              connectionStage = connectionStage,
                              user = user,
                              connectAction = { viewModel.toggleVpn(desiredState = !isOn) },
                              refreshAccess = refreshAccess,
                              loginAction = navigation.onNavigateStardomLogin,
                              loginAtUrlAction = loginAtUrl,
                              selfNode = netmap?.SelfNode,
                              showVPNPermissionLauncher = {
                                viewModel.showVPNPermissionLauncherIfUnauthorized()
                              })
                        }
                      }
                    }

                // Modals & Bottom Sheets
                if (showAccountDialog) {
                  StardomAccountDialog(
                      profile = accountProfile,
                      onDismiss = { showAccountDialog = false },
                      onLogout = {
                        sessionController.clearSession()
                        navigation.onNavigateStardomLogin()
                      },
                      language = selectedLanguage)
                }

                if (showSettingsSheet) {
                  StardomSettingsSheet(
                      selectedProtocol = selectedProtocol,
                      onSelectProtocol = { selectedProtocol = it },
                      selectedDns = selectedDns,
                      onSelectDns = { selectedDns = it },
                      selectedLanguage = selectedLanguage,
                      onSelectLanguage = { selectedLanguage = it },
                      sheetState = settingsSheetState,
                      onDismiss = { showSettingsSheet = false },
                      onNavigateToAdvancedSettings = { navigation.onNavigateToSettings() })
                }

                if (showServerSheet) {
                  StardomServerSelectorSheet(
                      servers = servers,
                      selectedServer = activeServer,
                      sheetState = serverSheetState,
                      onDismiss = { showServerSheet = false },
                      onSelectServer = { server ->
                        val matchingNode = tailnetExitNodesState.find { it.id == server.id }
                        if (matchingNode != null) {
                          exitNodeViewModel.setExitNode(matchingNode)
                        }
                      },
                      language = selectedLanguage)
                }

                currentPingDevice?.let { _ ->
                  ModalBottomSheet(onDismissRequest = { viewModel.onPingDismissal() }) {
                    PingView(model = viewModel.pingViewModel)
                  }
                }
              }
        }
  }
}

@Composable
fun TaildropDirectoryPickerPrompt() {
  val uriHandler = LocalUriHandler.current
  Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.Start) {
    Text(text = stringResource(id = R.string.taildrop_directory_picker_body))
    Text(
        text = stringResource(id = R.string.taildrop_directory_picker_info),
        modifier = Modifier.clickable { uriHandler.openUri(Links.TAILDROP_KB_URL) },
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline)
  }
}

@Composable
fun ExitNodeStatus(navAction: () -> Unit, viewModel: MainViewModel) {
  val nodeState by viewModel.nodeState.collectAsState()
  val maybePrefs by viewModel.prefs.collectAsState()
  val netmap by viewModel.netmap.collectAsState()
  val prefs = maybePrefs ?: return
  val chosenExitNodeId = prefs.activeExitNodeID ?: prefs.selectedExitNodeID
  val desiredMode by App.get().desiredExitModeStore.mode.collectAsState()
  val isDesiredAuto = desiredMode is DesiredExitMode.Auto
  val autoExitNodeEnabled = prefs.AutoExitNode == "any" || isDesiredAuto
  val effectiveExitNodeId =
      if (autoExitNodeEnabled) chosenExitNodeId?.takeUnless { it == "auto:any" }
      else chosenExitNodeId
  val exitNodePeer = effectiveExitNodeId?.let { id -> netmap?.Peers?.find { it.StableID == id } }
  val name = exitNodePeer?.exitNodeName
  val managedByOrganization by viewModel.managedByOrganization.collectAsState()
  Box(
      modifier =
          Modifier.fillMaxWidth().background(color = MaterialTheme.colorScheme.surfaceContainer)) {
        if (nodeState == NodeState.OFFLINE_MDM) {
          Box(
              modifier =
                  Modifier.padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 16.dp)
                      .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                      .background(MaterialTheme.colorScheme.customErrorContainer)
                      .fillMaxWidth()
                      .align(Alignment.TopCenter)) {
                Column(
                    modifier =
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 16.dp)) {
                      Text(
                          text =
                              managedByOrganization.value?.let {
                                stringResource(R.string.exit_node_offline_mdm_orgname, it)
                              } ?: stringResource(R.string.exit_node_offline_mdm),
                          style = MaterialTheme.typography.bodyMedium,
                          color = Color.White)
                    }
              }
        }
        Box(
            modifier =
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                    .clip(shape = RoundedCornerShape(10.dp, 10.dp, 10.dp, 10.dp))
                    .fillMaxWidth()) {
              ListItem(
                  modifier = Modifier.clickable { navAction() },
                  colors =
                      when (nodeState) {
                        NodeState.ACTIVE_AND_RUNNING -> MaterialTheme.colorScheme.primaryListItem
                        NodeState.ACTIVE_NOT_RUNNING -> MaterialTheme.colorScheme.listItem
                        NodeState.AUTO_PENDING -> MaterialTheme.colorScheme.listItem
                        NodeState.RUNNING_AS_EXIT_NODE -> MaterialTheme.colorScheme.warningListItem
                        NodeState.OFFLINE_ENABLED -> MaterialTheme.colorScheme.errorListItem
                        NodeState.OFFLINE_DISABLED -> MaterialTheme.colorScheme.errorListItem
                        NodeState.OFFLINE_MDM -> MaterialTheme.colorScheme.errorListItem
                        else ->
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface)
                      },
                  overlineContent = {
                    Text(
                        text =
                            if (nodeState == NodeState.OFFLINE_ENABLED ||
                                nodeState == NodeState.OFFLINE_DISABLED ||
                                nodeState == NodeState.OFFLINE_MDM)
                                stringResource(R.string.exit_node_offline)
                            else stringResource(R.string.exit_node),
                        style = MaterialTheme.typography.bodySmall,
                    )
                  },
                  headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                          when (nodeState) {
                            NodeState.NONE ->
                                if (autoExitNodeEnabled) {
                                  stringResource(id = R.string.auto_exit_node)
                                } else {
                                  name
                                      ?: chosenExitNodeId
                                      ?: stringResource(id = R.string.auto_exit_node)
                                }
                            NodeState.RUNNING_AS_EXIT_NODE ->
                                stringResource(id = R.string.running_exit_node)
                            else ->
                                if (autoExitNodeEnabled) {
                                  stringResource(id = R.string.auto_exit_node)
                                } else {
                                  name ?: chosenExitNodeId ?: ""
                                }
                          },
                          style = MaterialTheme.typography.bodyMedium,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis)
                      Icon(
                          imageVector = Icons.Outlined.ArrowDropDown,
                          contentDescription = null,
                          tint =
                              if (nodeState == NodeState.ACTIVE_AND_RUNNING)
                                  MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                              else MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  },
                  supportingContent = {
                    if (autoExitNodeEnabled && nodeState != NodeState.RUNNING_AS_EXIT_NODE) {
                      if (name != null || effectiveExitNodeId != null) {
                        Text(
                            stringResource(
                                R.string.auto_exit_node_effective, name ?: effectiveExitNodeId!!),
                            style = MaterialTheme.typography.bodyMedium)
                      } else if (nodeState == NodeState.AUTO_PENDING) {
                        Text(
                            stringResource(R.string.auto_exit_node_connecting),
                            style = MaterialTheme.typography.bodyMedium)
                      }
                    }
                  },
                  trailingContent = {
                    if (nodeState == NodeState.RUNNING_AS_EXIT_NODE) {
                      Button(
                          colors = MaterialTheme.colorScheme.warningButton,
                          onClick = { viewModel.setRunningExitNode(false) }) {
                            Text(stringResource(id = R.string.stop))
                          }
                    }
                  })
            }
      }
}

@Composable
fun SettingsButton(action: () -> Unit) {
  IconButton(modifier = Modifier.size(24.dp), onClick = { action() }) {
    Icon(
        Icons.Outlined.Settings,
        contentDescription = "Open settings",
        tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
fun StartingView() {
  Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "INITIALIZING STARDOM KERNEL...",
            color = StardomColors.TextSecondary,
            fontFamily = IbmPlexMono,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp)
      }
}

@Composable
fun ConnectView(
    state: Ipn.State,
    connectionStage: ConnectionStage,
    user: IpnLocal.LoginProfile?,
    connectAction: () -> Unit,
    refreshAccess: () -> Unit,
    loginAction: () -> Unit,
    loginAtUrlAction: (String) -> Unit,
    selfNode: Tailcfg.Node?,
    showVPNPermissionLauncher: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxWidth()
              .padding(vertical = 12.dp)
              .background(StardomColors.Panel)
              .border(1.dp, StardomColors.Border)
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      horizontalAlignment = Alignment.CenterHorizontally) {
        if (state == Ipn.State.NeedsMachineAuth) {
          Icon(
              modifier = Modifier.size(32.dp),
              imageVector = Icons.Outlined.Lock,
              contentDescription = "Device requires authentication",
              tint = StardomColors.Error)
          Text(
              text = stringResource(id = R.string.machine_auth_required),
              color = StardomColors.TextPrimary,
              fontFamily = SpaceGrotesk,
              fontWeight = FontWeight.Medium,
              fontSize = 14.sp,
              textAlign = TextAlign.Center)
          Text(
              text = stringResource(id = R.string.machine_auth_explainer),
              color = StardomColors.TextSecondary,
              fontFamily = IbmPlexMono,
              fontSize = 10.sp,
              textAlign = TextAlign.Center)
          selfNode?.let {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier.fillMaxWidth()
                        .background(StardomColors.Selected)
                        .clickable(onClickLabel = "Open Admin Console") {
                          loginAtUrlAction(it.nodeAdminUrl)
                        }
                        .padding(vertical = 10.dp)) {
                  Text(
                      text = stringResource(id = R.string.open_admin_console).uppercase(),
                      color = StardomColors.Background,
                      fontFamily = SpaceGrotesk,
                      fontWeight = FontWeight.Bold,
                      fontSize = 11.sp,
                      letterSpacing = 1.sp)
                }
          }
        } else if (connectionStage == ConnectionStage.SignIn) {
          Text(
              text = "STARDOM // ACCESS REQUIRED",
              color = StardomColors.TextPrimary,
              fontFamily = SpaceGrotesk,
              fontWeight = FontWeight.Medium,
              fontSize = 13.sp,
              letterSpacing = 1.sp,
              textAlign = TextAlign.Center)
          Text(
              text = stringResource(R.string.login_to_join_your_tailnet),
              color = StardomColors.TextSecondary,
              fontFamily = IbmPlexMono,
              fontSize = 10.sp,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.height(4.dp))
          Box(
              contentAlignment = Alignment.Center,
              modifier =
                  Modifier.fillMaxWidth()
                      .background(StardomColors.Selected)
                      .clickable(onClickLabel = "Log In") { loginAction() }
                      .padding(vertical = 10.dp)) {
                Text(
                    text = "LOG IN VIA AUTHENTIK ❯",
                    color = StardomColors.Background,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp)
              }
        } else if (connectionStage == ConnectionStage.AccessUnavailable) {
          Text(
              text = stringResource(id = R.string.vpn_access_unavailable).uppercase(),
              color = StardomColors.Error,
              fontFamily = SpaceGrotesk,
              fontWeight = FontWeight.Medium,
              fontSize = 13.sp,
              letterSpacing = 1.sp,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.height(4.dp))
          Box(
              contentAlignment = Alignment.Center,
              modifier =
                  Modifier.fillMaxWidth()
                      .background(StardomColors.PanelSelected)
                      .border(1.dp, StardomColors.BorderStrong)
                      .clickable(onClickLabel = "Try Again") { refreshAccess() }
                      .padding(vertical = 10.dp)) {
                Text(
                    text = "RETRY ACCESS VERIFICATION ❯",
                    color = StardomColors.TextPrimary,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp)
              }
        } else if (connectionStage == ConnectionStage.AccessDisabled) {
          Text(
              text = stringResource(id = R.string.vpn_access_disabled).uppercase(),
              color = StardomColors.Error,
              fontFamily = SpaceGrotesk,
              fontWeight = FontWeight.Medium,
              fontSize = 13.sp,
              letterSpacing = 1.sp,
              textAlign = TextAlign.Center)
        } else if (connectionStage == ConnectionStage.RequestVpnPermission) {
          Text(
              text = "VPN TUNNEL PERMISSION NEEDED",
              color = StardomColors.TextPrimary,
              fontFamily = SpaceGrotesk,
              fontWeight = FontWeight.Medium,
              fontSize = 13.sp,
              letterSpacing = 1.sp,
              textAlign = TextAlign.Center)
          Text(
              text = stringResource(R.string.give_permissions),
              color = StardomColors.TextSecondary,
              fontFamily = IbmPlexMono,
              fontSize = 10.sp,
              textAlign = TextAlign.Center)
          Spacer(modifier = Modifier.height(4.dp))
          Box(
              contentAlignment = Alignment.Center,
              modifier =
                  Modifier.fillMaxWidth()
                      .background(StardomColors.Selected)
                      .clickable(onClickLabel = "Grant Permission") { showVPNPermissionLauncher() }
                      .padding(vertical = 10.dp)) {
                Text(
                    text = "GRANT PERMISSION ❯",
                    color = StardomColors.Background,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp)
              }
        } else if (connectionStage == ConnectionStage.Connect) {
          val tailnetName = user?.NetworkProfile?.tailnetNameForDisplay() ?: "TAILNET"
          Text(
              text = "READY FOR ORBITAL LINK",
              color = StardomColors.TextSecondary,
              fontFamily = IbmPlexMono,
              fontSize = 10.sp,
              letterSpacing = 1.sp,
              textAlign = TextAlign.Center)
          Text(
              text = "CONNECTED IDENTITY: $tailnetName",
              color = StardomColors.TextPrimary,
              fontFamily = SpaceGrotesk,
              fontWeight = FontWeight.Medium,
              fontSize = 12.sp,
              textAlign = TextAlign.Center)
        }
      }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PeerList(
    viewModel: MainViewModel,
    onNavigateToPeerDetails: (Tailcfg.Node) -> Unit,
    onSearchBarClick: () -> Unit,
    onSearch: (String) -> Unit,
) {
  val peerList by viewModel.peers.collectAsState(initial = emptyList<PeerSet>())
  val expandedPeer by viewModel.expandedMenuPeer.collectAsState()
  val searchTermStr by viewModel.searchTerm.collectAsState(initial = "")
  val netmap by viewModel.netmap.collectAsState()
  val localClipboardManager = LocalClipboardManager.current
  val enableSearch = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

  Column(modifier = Modifier.fillMaxWidth()) {
    if (enableSearch) {
      Search(onSearchBarClick = onSearchBarClick, searchTerm = searchTermStr, onSearch = onSearch)
      Spacer(Modifier.height(8.dp))
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
          peerList.forEach { peerSet ->
            item(key = "header_${peerSet.user?.ID ?: peerSet.hashCode()}") {
              NodesSectionHeader(peerSet = peerSet)
            }

            itemsWithDividers(peerSet.peers, key = { it.StableID }) { peer ->
              ListItem(
                  modifier =
                      Modifier.clip(shape = RoundedCornerShape(0.dp))
                          .combinedClickable(
                              onClick = { onNavigateToPeerDetails(peer) },
                              onLongClick = { viewModel.expandedMenuPeer.set(peer) }),
                  colors = MaterialTheme.colorScheme.listItem,
                  overlineContent = {
                    peer.Addresses?.firstOrNull()?.split("/")?.firstOrNull()?.let {
                      Text(
                          text = it,
                          style = MaterialTheme.typography.bodySmall,
                          color = StardomColors.TextMuted)
                    }
                  },
                  headlineContent = {
                    Text(
                        text = peer.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                  },
                  trailingContent = {
                    Box {
                      val isSelf = netmap?.let { peer.isSelfNode(it) } ?: false
                      if (isSelf) {
                        Text(
                            text = "SELF",
                            color = StardomColors.TextMuted,
                            fontSize = 9.sp,
                            fontFamily = IbmPlexMono)
                      } else {
                        Text(
                            text = if (peer.Online == true) "ONLINE" else "OFFLINE",
                            color =
                                if (peer.Online == true) StardomColors.Selected
                                else StardomColors.TextMuted,
                            fontSize = 9.sp,
                            fontFamily = IbmPlexMono)
                      }

                      DropdownMenu(
                          expanded = (expandedPeer?.StableID == peer.StableID),
                          onDismissRequest = { viewModel.hidePeerDropdownMenu() }) {
                            DropdownMenuItem(
                                text = { Text("Copy IP Address") },
                                onClick = {
                                  viewModel.copyIpAddress(peer, localClipboardManager)
                                  viewModel.hidePeerDropdownMenu()
                                })
                            netmap?.let { netMap ->
                              if (!peer.isSelfNode(netMap)) {
                                DropdownMenuItem(
                                    text = { Text("Ping") },
                                    onClick = {
                                      viewModel.hidePeerDropdownMenu()
                                      viewModel.startPing(peer)
                                    })
                              }
                            }
                          }
                    }
                  })
            }
          }
        }
  }
}

@Composable
fun NodesSectionHeader(peerSet: PeerSet) {
  Text(
      text =
          peerSet.user?.DisplayName?.uppercase()
              ?: stringResource(id = R.string.unknown_user).uppercase(),
      color = StardomColors.TextSecondary,
      fontFamily = IbmPlexMono,
      fontSize = 9.sp,
      letterSpacing = 2.sp,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun ExpiryNotification(netmap: Netmap.NetworkMap?, action: () -> Unit = {}) {
  val expiryStr = netmap?.SelfNode?.KeyExpiry ?: ""
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .background(StardomColors.Panel)
              .border(1.dp, StardomColors.ErrorBorder)
              .clickable(onClickLabel = "Reauthenticate") { action() }
              .padding(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
              Column {
                Text(
                    text = "KEY EXPIRATION WARNING",
                    color = StardomColors.Error,
                    fontSize = 11.sp,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Bold)
                Text(
                    text = "Key expires soon ($expiryStr). Tap to reauthenticate.",
                    color = StardomColors.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = IbmPlexMono)
              }
              Text(
                  text = "REAUTH ❯",
                  color = StardomColors.Error,
                  fontSize = 10.sp,
                  fontFamily = IbmPlexMono)
            }
      }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PromptForMissingPermissions() {
  // Handled via permission launchers as needed
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Search(
    onSearchBarClick: () -> Unit,
    backgroundColor: Color = StardomColors.Panel,
    onSearch: (String) -> Unit = {},
    searchTerm: String = "",
) {
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .background(backgroundColor)
              .border(1.dp, StardomColors.Border)
              .clickable(onClickLabel = "Search peers") { onSearchBarClick() }
              .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
              imageVector = Icons.Outlined.Search,
              contentDescription = "Search",
              tint = StardomColors.TextSecondary,
              modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(10.dp))
          Text(
              text = if (searchTerm.isEmpty()) "SEARCH PEERS..." else searchTerm,
              color =
                  if (searchTerm.isEmpty()) StardomColors.TextMuted else StardomColors.TextPrimary,
              fontSize = 11.sp,
              fontFamily = IbmPlexMono,
              letterSpacing = 1.sp)
        }
      }
}

@Preview
@Composable
fun MainViewPreview() {
  // Preview composable
}
