// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.product.policy.PolicyApiClient
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.ErrorDialogType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val AUTH_KEY_LENGTH = 16

open class CustomLoginViewModel(
    observeUserProfiles: Boolean = true,
    clientProvider: (kotlinx.coroutines.CoroutineScope) -> com.tailscale.ipn.ui.localapi.Client = {
      com.tailscale.ipn.ui.localapi.Client(it)
    },
    foregroundServiceLauncher: () -> Unit = {
      runCatching { com.tailscale.ipn.UninitializedApp.get().startForegroundForLogin() }
    },
    desiredExitModeStoreProvider: () -> com.tailscale.ipn.product.policy.DesiredExitModeStore? = {
      runCatching { com.tailscale.ipn.App.get().desiredExitModeStore }.getOrNull()
    },
    vpnStarter: () -> Unit = {
      runCatching { com.tailscale.ipn.UninitializedApp.get().startVPN() }
    },
) :
    IpnViewModel(
        observeUserProfiles = observeUserProfiles,
        clientProvider = clientProvider,
        foregroundServiceLauncher = foregroundServiceLauncher,
        desiredExitModeStoreProvider = desiredExitModeStoreProvider,
        vpnStarter = vpnStarter,
    ) {
  val errorDialog: StateFlow<ErrorDialogType?> = MutableStateFlow(null)
}

class LoginWithAuthKeyViewModel(
    observeUserProfiles: Boolean = true,
    clientProvider: (kotlinx.coroutines.CoroutineScope) -> com.tailscale.ipn.ui.localapi.Client = {
      com.tailscale.ipn.ui.localapi.Client(it)
    },
    foregroundServiceLauncher: () -> Unit = {
      runCatching { com.tailscale.ipn.UninitializedApp.get().startForegroundForLogin() }
    },
    desiredExitModeStoreProvider: () -> com.tailscale.ipn.product.policy.DesiredExitModeStore? = {
      runCatching { com.tailscale.ipn.App.get().desiredExitModeStore }.getOrNull()
    },
    vpnStarter: () -> Unit = {
      runCatching { com.tailscale.ipn.UninitializedApp.get().startVPN() }
    },
) :
    CustomLoginViewModel(
        observeUserProfiles = observeUserProfiles,
        clientProvider = clientProvider,
        foregroundServiceLauncher = foregroundServiceLauncher,
        desiredExitModeStoreProvider = desiredExitModeStoreProvider,
        vpnStarter = vpnStarter,
    ) {
  // Sets the auth key and invokes the login flow
  fun setAuthKey(authKey: String, onSuccess: () -> Unit) {
    // The most basic of checks for auth key syntax
    if (authKey.isEmpty()) {
      errorDialog.set(ErrorDialogType.INVALID_AUTH_KEY)
      return
    }
    loginWithAuthKey(authKey) {
      it.onFailure { errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED) }
      it.onSuccess { onSuccess() }
    }
  }
}

class LoginWithCustomControlURLViewModelFactory(
    private val authSessionRepository: AuthSessionRepository
) : ViewModelProvider.Factory {
  @Suppress("UNCHECKED_CAST")
  override fun <T : ViewModel> create(modelClass: Class<T>): T {
    return LoginWithCustomControlURLViewModel(authSessionRepository) as T
  }
}

class LoginWithCustomControlURLViewModel(
    private val authSessionRepository: AuthSessionRepository,
    private val policyApiClient: PolicyApiClient = PolicyApiClient(),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Main,
    observeUserProfiles: Boolean = true,
    clientProvider: (kotlinx.coroutines.CoroutineScope) -> com.tailscale.ipn.ui.localapi.Client = {
      com.tailscale.ipn.ui.localapi.Client(it)
    },
    foregroundServiceLauncher: () -> Unit = {
      runCatching { com.tailscale.ipn.UninitializedApp.get().startForegroundForLogin() }
    },
    desiredExitModeStoreProvider: () -> com.tailscale.ipn.product.policy.DesiredExitModeStore? = {
      runCatching { com.tailscale.ipn.App.get().desiredExitModeStore }.getOrNull()
    },
    vpnStarter: () -> Unit = {
      runCatching { com.tailscale.ipn.UninitializedApp.get().startVPN() }
    },
) :
    CustomLoginViewModel(
        observeUserProfiles = observeUserProfiles,
        clientProvider = clientProvider,
        foregroundServiceLauncher = foregroundServiceLauncher,
        desiredExitModeStoreProvider = desiredExitModeStoreProvider,
        vpnStarter = vpnStarter,
    ) {
  // Authentik identity is established before obtaining node auth key and logging in.
  fun setControlURL(context: Context, onSuccess: () -> Unit) {
    authSessionRepository.startAuthorization(context) { authentication ->
      authentication
          .onFailure { errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED) }
          .onSuccess {
            authSessionRepository.withFreshBearerToken(context) { tokenResult ->
              tokenResult
                  .onFailure {
                    authSessionRepository.clearSession()
                    errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED)
                  }
                  .onSuccess { token ->
                    viewModelScope.launch(ioDispatcher) {
                      val keyResult = policyApiClient.fetchNodeAuthKey(token)
                      withContext(mainDispatcher) {
                        keyResult
                            .onFailure { error ->
                              if (error
                                  is
                                  com.tailscale.ipn.product.policy.PolicyApiUnauthorizedException) {
                                authSessionRepository.requireReauthentication()
                              } else {
                                authSessionRepository.clearSession()
                              }
                              errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED)
                            }
                            .onSuccess { authKey ->
                              loginWithAuthKey(authKey) { loginResult ->
                                loginResult
                                    .onFailure {
                                      authSessionRepository.clearSession()
                                      errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED)
                                    }
                                    .onSuccess {
                                      authSessionRepository.markAuthorizationReady()
                                      onSuccess()
                                    }
                              }
                            }
                      }
                    }
                  }
            }
          }
    }
  }
}
