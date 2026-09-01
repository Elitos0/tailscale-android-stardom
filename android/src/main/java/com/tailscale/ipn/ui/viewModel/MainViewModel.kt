// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.ui.viewModel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.R
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.product.StardomSessionController
import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.product.policy.PolicyApiClient
import com.tailscale.ipn.product.policy.PolicyApiUnauthorizedException
import com.tailscale.ipn.product.policy.VpnEntitlementController
import com.tailscale.ipn.product.policy.VpnStartOrigin
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.State
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.PeerCategorizer
import com.tailscale.ipn.ui.util.PeerSet
import com.tailscale.ipn.ui.util.TimeUtil
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MainViewModelFactory(
    private val appViewModel: AppViewModel,
    private val vpnEntitlementController: VpnEntitlementController,
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
      return MainViewModel(appViewModel, vpnEntitlementController) as T
    }
    throw IllegalArgumentException("Unknown ViewModel class")
  }
}

@OptIn(FlowPreview::class)
class MainViewModel(
    private val appViewModel: AppViewModel,
    private val vpnEntitlementController: VpnEntitlementController,
    observeUserProfiles: Boolean = true,
    clientProvider: (kotlinx.coroutines.CoroutineScope) -> com.tailscale.ipn.ui.localapi.Client = {
      com.tailscale.ipn.ui.localapi.Client(it)
    },
    vpnStarter: () -> Unit = { runCatching { UninitializedApp.get().startVPN() } },
    vpnStopper: () -> Unit = { runCatching { UninitializedApp.get().stopVPN() } },
) :
    IpnViewModel(
        observeUserProfiles = observeUserProfiles,
        clientProvider = clientProvider,
        vpnStarter = vpnStarter,
        vpnStopper = vpnStopper,
        vpnActiveFlowProvider = { appViewModel.vpnActive },
    ) {
  // The user readable state of the system
  val stateRes: StateFlow<Int> = MutableStateFlow(userStringRes(State.NoState, State.NoState, true))
  // The expected state of the VPN toggle
  private val _vpnToggleState = MutableStateFlow(false)
  val vpnToggleState: StateFlow<Boolean> = _vpnToggleState
  // Keeps track of whether a toggle operation is in progress. This ensures that toggleVpn cannot be
  // invoked until the current operation is complete.
  var isToggleInProgress = MutableStateFlow(false)
  // Permission to prepare VPN
  private var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null
  private val _requestVpnPermission = MutableStateFlow(false)
  val requestVpnPermission: StateFlow<Boolean> = _requestVpnPermission
  // Select Taildrop directory
  private var directoryPickerLauncher: ActivityResultLauncher<Uri?>? = null
  // The list of peers
  private val _peers = MutableStateFlow<List<PeerSet>>(emptyList())
  val peers: StateFlow<List<PeerSet>> = _peers
  // The list of peers
  private val _searchViewPeers = MutableStateFlow<List<PeerSet>>(emptyList())
  val searchViewPeers: StateFlow<List<PeerSet>> = _searchViewPeers
  // The current state of the IPN for determining view visibility
  val ipnState = Notifier.state
  // The active search term for filtering peers
  private val _searchTerm = MutableStateFlow("")
  val searchTerm: StateFlow<String> = _searchTerm
  var autoFocusSearch by mutableStateOf(true)
    private set

  // True if we should render the key expiry bannder
  val showExpiry: StateFlow<Boolean> = MutableStateFlow(false)
  // The peer for which the dropdown menu is currently expanded. Null if no menu is expanded
  var expandedMenuPeer: StateFlow<Tailcfg.Node?> = MutableStateFlow(null)

  var pingViewModel: PingViewModel = PingViewModel()

  val isVpnPrepared: StateFlow<Boolean> = appViewModel.vpnPrepared

  val isVpnActive: StateFlow<Boolean> = appViewModel.vpnActive

  var searchJob: Job? = null

  // Icon displayed in the button to present the health view
  val healthIcon: StateFlow<Int?> = MutableStateFlow(null)

  private val _authError = MutableStateFlow(false)
  val authError: StateFlow<Boolean> = _authError

  fun setAuthError(error: Boolean) {
    _authError.value = error
  }

  private val _isLoginLoading = MutableStateFlow(false)
  val isLoginLoading: StateFlow<Boolean> = _isLoginLoading

  fun setLoginLoading(loading: Boolean) {
    _isLoginLoading.value = loading
  }

  fun executeStardomLoginPipeline(
      context: Context,
      sessionController: StardomSessionController,
      policyApiClient: PolicyApiClient = PolicyApiClient(),
      onComplete: (Result<Unit>) -> Unit = {},
  ) {
    _isLoginLoading.value = true
    _authError.value = false
    viewModelScope.launch {
      val result = runCatching {
        val token =
            suspendCancellableCoroutine<String> { continuation ->
              sessionController.authSessionRepository.withFreshBearerToken(context) { tokenResult ->
                tokenResult.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it) })
              }
            }

        val authKeyResult = withContext(Dispatchers.IO) { policyApiClient.fetchNodeAuthKey(token) }
        val authKey =
            authKeyResult.getOrElse { error ->
              if (error is PolicyApiUnauthorizedException) {
                sessionController.requireReauthentication()
              }
              throw error
            }

        suspendCancellableCoroutine<Unit> { continuation ->
          loginWithAuthKey(authKey) { loginResult ->
            loginResult.fold(
                onSuccess = { continuation.resume(Unit) },
                onFailure = { continuation.resumeWithException(it) })
          }
        }

        loadUserProfilesSuspend()

        val accessState = sessionController.refreshAccess(context, force = true)
        if (accessState == AccessState.Unavailable) {
          throw IllegalStateException("Access policy unavailable after login")
        }

        sessionController.ackFixedHeadscaleContinuation()
      }

      result.fold(
          onSuccess = {
            sessionController.authSessionRepository.markAuthorizationReady()
            _authError.value = false
            _isLoginLoading.value = false
            onComplete(Result.success(Unit))
          },
          onFailure = { error ->
            TSLog.e(
                TAG,
                "Stardom login pipeline failed: ${error::class.java.simpleName}: ${error.message}",
                error)
            _authError.value = true
            _isLoginLoading.value = false
            onComplete(Result.failure(error))
          })
    }
  }

  fun updateSearchTerm(term: String) {
    _searchTerm.value = term
  }

  fun hidePeerDropdownMenu() {
    expandedMenuPeer.set(null)
  }

  fun copyIpAddress(peer: Tailcfg.Node, clipboardManager: ClipboardManager) {
    clipboardManager.setText(AnnotatedString(peer.primaryIPv4Address ?: ""))
  }

  fun startPing(peer: Tailcfg.Node) {
    this.pingViewModel.startPing(peer)
  }

  fun onPingDismissal() {
    this.pingViewModel.handleDismissal()
  }

  private val peerCategorizer = PeerCategorizer()

  init {
    viewModelScope.launch {
      var previousState: State? = null
      combine(Notifier.state, isVpnActive) { state, active -> state to active }
          .collect { (currentState, active) ->
            // Determine the correct state resource string
            stateRes.set(userStringRes(currentState, previousState, active))
            // Determine if the VPN toggle should be on
            val isOn =
                when {
                  active && (currentState == State.Running || currentState == State.Starting) ->
                      true
                  else -> false
                }
            TSLog.d(
                "MainViewModel",
                "State changed: ipnState=$currentState previousState=$previousState vpnActive=$active toggleIsOn=$isOn")
            // Update the VPN toggle state
            _vpnToggleState.value = isOn
            // Update the previous state
            previousState = currentState
          }
    }
    viewModelScope.launch {
      _searchTerm.debounce(250L).collect { term ->
        // run the search as a background task
        searchJob?.cancel()
        searchJob =
            launch(Dispatchers.Default) {
              val filteredPeers = peerCategorizer.groupedAndFilteredPeers(term)
              _searchViewPeers.value = filteredPeers
            }
      }
    }
    viewModelScope.launch {
      Notifier.netmap.collect { it ->
        it?.let { netmap ->
          searchJob?.cancel()
          launch(Dispatchers.Default) {
            peerCategorizer.regenerateGroupedPeers(netmap)
            val filteredPeers = peerCategorizer.groupedAndFilteredPeers(searchTerm.value)
            _peers.value = peerCategorizer.peerSets
            _searchViewPeers.value = filteredPeers
          }
          if (netmap.SelfNode.keyDoesNotExpire) {
            showExpiry.set(false)
            return@let
          } else {
            val expiryNotificationWindowMDM = MDMSettings.keyExpirationNotice.flow.value.value
            val window =
                expiryNotificationWindowMDM?.let { TimeUtil.duration(it) } ?: Duration.ofHours(24)
            val expiresSoon =
                TimeUtil.isWithinExpiryNotificationWindow(window, it.SelfNode.KeyExpiry ?: "")
            showExpiry.set(expiresSoon)
          }
        }
      }
    }
    viewModelScope.launch {
      runCatching { App.get().healthNotifier?.currentIcon }
          .getOrNull()
          ?.collect { icon -> healthIcon.set(icon) }
    }
  }

  fun maybeRequestVpnPermission() {
    TSLog.d("MainViewModel", "maybeRequestVpnPermission called")
    _requestVpnPermission.value = true
  }

  fun showVPNPermissionLauncherIfUnauthorized() {
    TSLog.d("MainViewModel", "showVPNPermissionLauncherIfUnauthorized called")
    viewModelScope.launch { requestVpnPermissionIfAuthorized() }
  }

  private suspend fun requestVpnPermissionIfAuthorized() {
    try {
      val authorized = vpnEntitlementController.authorizeStart(VpnStartOrigin.PermissionRequest)
      TSLog.d(
          "MainViewModel", "requestVpnPermissionIfAuthorized: entitlement authorized=$authorized")
      if (!authorized) return
      val vpnIntent = VpnService.prepare(App.get())
      TSLog.d(
          "MainViewModel",
          "requestVpnPermissionIfAuthorized: VpnService.prepare vpnIntent=$vpnIntent (needsPermissionLauncher=${vpnIntent != null})")
      if (vpnIntent != null) {
        TSLog.d("MainViewModel", "launching vpnPermissionLauncher")
        vpnPermissionLauncher?.launch(vpnIntent)
      } else {
        TSLog.d("MainViewModel", "VPN already prepared, calling startVPN()")
        appViewModel.setVpnPrepared(true)
        startVPN()
      }
    } finally {
      _requestVpnPermission.value = false
    }
  }

  fun toggleVpn(desiredState: Boolean) {
    TSLog.d(
        "MainViewModel",
        "toggleVpn called: desiredState=$desiredState isToggleInProgress=${isToggleInProgress.value}")
    if (isToggleInProgress.value) {
      // Prevent toggling while a previous toggle is in progress
      return
    }

    viewModelScope.launch {
      isToggleInProgress.value = true
      try {
        val currentState = Notifier.state.value

        TSLog.d(
            "VpnLifecycle",
            "toggle requested desired=$desiredState state=$currentState active=${isVpnActive.value}")
        if (desiredState) {
          // A stale backend Running state can survive service teardown/re-authentication. The
          // interface state is the source of truth for whether a new start is needed.
          if (currentState != Ipn.State.Running || !isVpnActive.value) {
            TSLog.d("MainViewModel", "toggleVpn initiating start sequence")
            requestVpnPermissionIfAuthorized()
          } else {
            TSLog.d("MainViewModel", "toggleVpn already running and active, no start needed")
          }
        } else {
          if (currentState != Ipn.State.Stopped && currentState != Ipn.State.NoState) {
            TSLog.d(
                "VpnLifecycle",
                "toggle stop requested state=$currentState active=${isVpnActive.value}")
            stopVPN()
          } else {
            TSLog.d("MainViewModel", "toggleVpn already stopped, no stop needed")
          }
        }
      } finally {
        isToggleInProgress.value = false
      }
    }
  }

  fun searchPeers(searchTerm: String) {
    this.searchTerm.set(searchTerm)
  }

  fun enableSearchAutoFocus() {
    autoFocusSearch = true
  }

  fun disableSearchAutoFocus() {
    autoFocusSearch = false
  }

  fun setVpnPermissionLauncher(launcher: ActivityResultLauncher<Intent>) {
    // No intent means we're already authorized
    vpnPermissionLauncher = launcher
  }
}

private fun userStringRes(currentState: State?, previousState: State?, vpnActive: Boolean): Int {
  return when {
    previousState == State.NoState && currentState == State.Starting && vpnActive ->
        R.string.starting
    currentState == State.NoState -> R.string.placeholder
    currentState == State.InUseOtherUser -> R.string.placeholder
    currentState == State.NeedsLogin ->
        if (vpnActive) R.string.please_login else R.string.connect_to_vpn
    currentState == State.NeedsMachineAuth -> R.string.needs_machine_auth
    currentState == State.Stopped -> R.string.stopped
    currentState == State.Starting -> if (vpnActive) R.string.starting else R.string.stopped
    currentState == State.Running -> if (vpnActive) R.string.connected else R.string.stopped
    else -> R.string.placeholder
  }
}
