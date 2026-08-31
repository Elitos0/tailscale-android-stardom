// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.product.ProductConfig
import com.tailscale.ipn.product.policy.DesiredExitMode
import com.tailscale.ipn.product.policy.DesiredExitModeStore
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.UserID
import com.tailscale.ipn.ui.model.deepCopy
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.AdvertisedRoutesHelper
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Base model for most models in this application. Provides common facilities for watching IPN
 * notifications, managing login/logout, updating preferences, etc.
 */
open class IpnViewModel(
    private val observeUserProfiles: Boolean = true,
    private val clientProvider: (CoroutineScope) -> Client = { Client(it) },
    private val foregroundServiceLauncher: () -> Unit = {
      runCatching { UninitializedApp.get().startForegroundForLogin() }
    },
    private val desiredExitModeStoreProvider: () -> DesiredExitModeStore? = {
      runCatching { App.get().desiredExitModeStore }.getOrNull()
    },
    private val vpnStarter: () -> Unit = { runCatching { UninitializedApp.get().startVPN() } },
    private val vpnStopper: () -> Unit = { runCatching { UninitializedApp.get().stopVPN() } },
) : ViewModel() {
  protected val TAG = this::class.simpleName

  val loggedInUser: StateFlow<IpnLocal.LoginProfile?> = MutableStateFlow(null)
  val loginProfiles: StateFlow<List<IpnLocal.LoginProfile>?> = MutableStateFlow(null)

  private val _vpnPrepared = MutableStateFlow(false)
  val vpnPrepared: StateFlow<Boolean> = _vpnPrepared

  // The userId associated with the current node. ie: The logged in user.
  private var selfNodeUserId: UserID? = null

  val isRunningExitNode: StateFlow<Boolean> = MutableStateFlow(false)
  private var lastPrefs: Ipn.Prefs? = null

  val prefs = Notifier.prefs
  val netmap = Notifier.netmap
  private val _nodeState = MutableStateFlow(NodeState.NONE)
  val nodeState: StateFlow<NodeState> = _nodeState
  val managedByOrganization = MDMSettings.managedByOrganizationName.flow

  enum class NodeState {
    NONE,
    ACTIVE_AND_RUNNING,
    // Last selected exit node is active but is not being used.
    ACTIVE_NOT_RUNNING,
    // Native Auto is enabled but has not resolved a concrete exit node yet.
    AUTO_PENDING,
    // Last selected exit node is currently offline.
    OFFLINE_ENABLED,
    // Last selected exit node has been de-selected and is currently offline.
    OFFLINE_DISABLED,
    // Exit node selection is managed by an administrator, and last selected exit node is currently
    // offline
    OFFLINE_MDM,
    RUNNING_AS_EXIT_NODE
  }

  init {
    if (observeUserProfiles) {
      viewModelScope.launch {
        Notifier.state.collect {
          // Reload the user profiles on all state transitions to ensure loggedInUser is correct
          viewModelScope.launch { loadUserProfiles() }
        }
      }

      // This will observe the userId of the current node and reload our user profiles if
      // we discover it has changed (e.g. due to a login or user switch)
      viewModelScope.launch {
        Notifier.netmap.collect {
          it?.SelfNode?.User.let {
            if (it != selfNodeUserId) {
              selfNodeUserId = it
              viewModelScope.launch { loadUserProfiles() }
            }
          }
        }
      }
    }

    viewModelScope.launch {
      Notifier.prefs.collect {
        it?.let {
          lastPrefs = it
          isRunningExitNode.set(AdvertisedRoutesHelper.exitNodeOnFromPrefs(it))
        }
      }
    }

    if (observeUserProfiles) {
      viewModelScope.launch { loadUserProfiles() }
    }

    viewModelScope.launch {
      combine(prefs, netmap, isRunningExitNode) { prefs, netmap, isRunningExitNode ->
            // Handle nullability for prefs and netmap
            val validPrefs = prefs ?: return@combine NodeState.NONE
            val validNetmap = netmap ?: return@combine NodeState.NONE

            val autoExitNodeEnabled =
                validPrefs.AutoExitNode == "any" ||
                    runCatching {
                          App.get().desiredExitModeStore.mode.value is DesiredExitMode.Auto
                        }
                        .getOrDefault(false)
            val chosenExitNodeId =
                (validPrefs.activeExitNodeID?.takeUnless { it == "auto:any" })
                    ?: validPrefs.selectedExitNodeID
            val exitNodePeer =
                chosenExitNodeId?.let { id -> validNetmap.Peers?.find { it.StableID == id } }

            val computedState =
                when {
                  exitNodePeer?.Online == false -> {
                    if (MDMSettings.exitNodeID.flow.value.value != null) {
                      NodeState.OFFLINE_MDM
                    } else if (validPrefs.activeExitNodeID != null) {
                      NodeState.OFFLINE_ENABLED
                    } else {
                      NodeState.OFFLINE_DISABLED
                    }
                  }
                  exitNodePeer != null -> {
                    if (!validPrefs.activeExitNodeID.isNullOrEmpty() &&
                        validPrefs.activeExitNodeID != "auto:any") {
                      NodeState.ACTIVE_AND_RUNNING
                    } else {
                      NodeState.ACTIVE_NOT_RUNNING
                    }
                  }
                  autoExitNodeEnabled -> NodeState.AUTO_PENDING
                  isRunningExitNode == true -> NodeState.RUNNING_AS_EXIT_NODE
                  else -> NodeState.NONE
                }
            TSLog.d(
                TAG,
                "nodeState computed: $computedState (active=${validPrefs.activeExitNodeID}, selected=${validPrefs.selectedExitNodeID}, auto=${validPrefs.AutoExitNode}, isRunningExitNode=$isRunningExitNode)")
            computedState
          }
          .collect { nodeState -> _nodeState.value = nodeState }
    }
    TSLog.d(TAG, "Created")
  }

  // VPN Control
  fun startVPN() {
    TSLog.d(TAG, "startVPN() invoked")
    vpnStarter()
  }

  fun stopVPN() {
    TSLog.d(TAG, "stopVPN() invoked")
    vpnStopper()
  }

  // Login/Logout

  /**
   * Order of operations:
   * 1. editPrefs() with maskedPrefs (to allow ControlURL override), LoggedOut=false if AuthKey !=
   *    null
   * 2. start() starts the LocalBackend state machine with WantRunning=true. to avoid a race: if
   *    editPrefs() set WantRunning=true, the backend would fire a cc.Login(LoginDefault) on the
   *    existing control client immediately. start() then tears down this client and creates a new
   *    one
   * 3. startLoginInteractive() is currently required for both interactive and non-interactive
   *    (using auth key) login
   *
   * Any failure short‑circuits the chain and invokes completionHandler once.
   */
  fun login(
      maskedPrefs: Ipn.MaskedPrefs? = null,
      authKey: String? = null,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    TSLog.d(
        TAG,
        "login() starting: authKeyProvided=${authKey != null}, maskedPrefsProvided=${maskedPrefs != null}")
    // Start the IPNService foreground notification so that Android
    // does not freeze the process or cut network access while the user is in the browser
    // completing auth. The foreground service transitions to a full VPN service later when
    // startVPN() is called after the backend reaches Running state.
    foregroundServiceLauncher()
    val client = clientProvider(viewModelScope)

    val finalMaskedPrefs = maskedPrefs?.deepCopy() ?: Ipn.MaskedPrefs()
    // Don't set WantRunning=true here. Setting it in editPrefs() triggers cc.Login(LoginDefault)
    // in the Go backend on the existing control client; when the user taps "Log in," login() calls
    // start(), which triggers resetControlClientLocked(), cancelling the existing control client
    // and creating a new one. On a slow connection, the first control client's register is still
    // in flight when it is canceled. Instead, set WantRunning=true on the Prefs returned by
    // editPrefs() and pass
    // it via start()'s UpdatePrefs, which resets the control client first.
    finalMaskedPrefs.WantRunning = false
    if (authKey != null) {
      finalMaskedPrefs.LoggedOut = false
    }
    client.editPrefs(finalMaskedPrefs) { editResult ->
      editResult
          .onFailure {
            TSLog.e(TAG, "login: editPrefs() failed (${it::class.simpleName})")
            completionHandler(Result.failure(it))
          }
          .onSuccess {
            TSLog.d(TAG, "login: editPrefs() succeeded")
            it.WantRunning = true
            val opts = Ipn.Options(UpdatePrefs = it, AuthKey = authKey)
            client.start(opts) { startResult ->
              startResult
                  .onFailure {
                    TSLog.e(TAG, "login: start() failed (${it::class.simpleName})")
                    completionHandler(Result.failure(it))
                  }
                  .onSuccess {
                    TSLog.d(TAG, "login: start() succeeded, starting login interactive")
                    client.startLoginInteractive { loginResult ->
                      loginResult
                          .onFailure {
                            TSLog.e(
                                TAG,
                                "login: startLoginInteractive() failed (${it::class.simpleName})")
                            completionHandler(Result.failure(it))
                          }
                          .onSuccess {
                            TSLog.d(TAG, "login: startLoginInteractive() succeeded")
                            completionHandler(Result.success(Unit))
                          }
                    }
                  }
            }
          }
    }
  }

  fun loginWithAuthKey(
      authKey: String,
      controlURL: String? = ProductConfig.headscaleControlUrl,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    TSLog.d(
        TAG,
        "loginWithAuthKey() called: controlURLProvided=${!controlURL.isNullOrBlank()} authKeyProvided=${authKey.isNotBlank()}")
    val prefs = Ipn.MaskedPrefs()
    prefs.WantRunning = false
    prefs.AutoExitNode = "any"
    prefs.LoggedOut = false
    // Native prefs and the product-level intent must be persisted together. The fallback may run
    // as soon as native observes AutoExitNode="any" and must not reinterpret the temporary node
    // selection as a user-requested Manual mode.
    desiredExitModeStoreProvider()?.set(DesiredExitMode.Auto)
    if (!controlURL.isNullOrBlank()) {
      prefs.ControlURL = controlURL
    }
    login(prefs, authKey = authKey, completionHandler)
  }

  fun loginWithCustomControlURL(
      controlURL: String,
      completionHandler: (Result<Unit>) -> Unit = {}
  ) {
    TSLog.d(
        TAG, "loginWithCustomControlURL() called: controlURLProvided=${controlURL.isNotBlank()}")
    val prefs = Ipn.MaskedPrefs()
    prefs.ControlURL = controlURL
    login(prefs, completionHandler = completionHandler)
  }

  fun logout(completionHandler: (Result<String>) -> Unit = {}) {
    TSLog.d("AuthLifecycle", "logout requested")
    clientProvider(viewModelScope).logout { result ->
      result
          .onSuccess { TSLog.d("AuthLifecycle", "logout started") }
          .onFailure { TSLog.e("AuthLifecycle", "logout request failed: ${it.message}", it) }
      completionHandler(result)
    }
  }

  // User Profiles

  private fun loadUserProfiles() {
    clientProvider(viewModelScope).profiles { result ->
      result.onSuccess(loginProfiles::set).onFailure {
        TSLog.e(TAG, "Error loading profiles: ${it.message}")
      }
    }

    clientProvider(viewModelScope).currentProfile { result ->
      result
          .onSuccess { loggedInUser.set(if (it.isEmpty()) null else it) }
          .onFailure { TSLog.e(TAG, "Error loading current profile: ${it.message}") }
    }
  }

  fun switchProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    TSLog.d(TAG, "switchProfile() called for profile=${profile.LocalUserID}")
    val switchProfile = {
      clientProvider(viewModelScope).switchProfile(profile) {
        startVPN()
        completionHandler(it)
      }
    }
    clientProvider(viewModelScope).editPrefs(Ipn.MaskedPrefs().apply { WantRunning = false }) {
        result ->
      result
          .onSuccess { switchProfile() }
          .onFailure { TSLog.e(TAG, "Error setting wantRunning to false: ${it.message}") }
    }
  }

  fun addProfile(completionHandler: (Result<String>) -> Unit) {
    TSLog.d(TAG, "addProfile() called")
    clientProvider(viewModelScope).addProfile {
      if (it.isSuccess) {
        login()
      }
      startVPN()
      completionHandler(it)
    }
  }

  fun deleteProfile(profile: IpnLocal.LoginProfile, completionHandler: (Result<String>) -> Unit) {
    TSLog.d(TAG, "deleteProfile() called for profile=${profile.LocalUserID}")
    clientProvider(viewModelScope).deleteProfile(profile) {
      viewModelScope.launch { loadUserProfiles() }
      completionHandler(it)
    }
  }

  fun setRunningExitNode(isOn: Boolean) {
    LoadingIndicator.start()
    lastPrefs?.let { currentPrefs ->
      val newPrefs: Ipn.MaskedPrefs
      if (isOn) {
        newPrefs = setZeroRoutes(currentPrefs)
      } else {
        newPrefs = removeAllZeroRoutes(currentPrefs)
      }
      clientProvider(viewModelScope).editPrefs(newPrefs) { result ->
        LoadingIndicator.stop()
        TSLog.d("RunExitNodeViewModel", "Edited prefs: $result")
      }
    }
  }

  private fun setZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = (removeAllZeroRoutes(prefs).AdvertiseRoutes ?: emptyList()).toMutableList()
    newRoutes.add("0.0.0.0/0")
    newRoutes.add("::/0")
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }

  private fun removeAllZeroRoutes(prefs: Ipn.Prefs): Ipn.MaskedPrefs {
    val newRoutes = emptyList<String>().toMutableList()
    (prefs.AdvertiseRoutes ?: emptyList()).forEach {
      if (it != "0.0.0.0/0" && it != "::/0") {
        newRoutes.add(it)
      }
    }
    val newPrefs = Ipn.MaskedPrefs()
    newPrefs.AdvertiseRoutes = newRoutes
    return newPrefs
  }
}
