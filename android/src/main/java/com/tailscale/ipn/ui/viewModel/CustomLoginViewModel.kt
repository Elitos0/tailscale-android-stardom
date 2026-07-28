// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Context
import com.tailscale.ipn.product.ProductConfig
import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.ErrorDialogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

const val AUTH_KEY_LENGTH = 16

open class CustomLoginViewModel : IpnViewModel() {
  val errorDialog: StateFlow<ErrorDialogType?> = MutableStateFlow(null)
}

class LoginWithAuthKeyViewModel : CustomLoginViewModel() {
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

class LoginWithCustomControlURLViewModel(private val authSessionRepository: AuthSessionRepository) :
    CustomLoginViewModel() {
  // Authentik identity is established before the fixed Headscale login starts.
  fun setControlURL(context: Context, onSuccess: () -> Unit) {
    authSessionRepository.startAuthorization(context) { authentication ->
      authentication
          .onFailure { errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED) }
          .onSuccess {
            loginWithCustomControlURL(ProductConfig.headscaleControlUrl) {
              it.onFailure { errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED) }
              it.onSuccess { onSuccess() }
            }
          }
    }
  }
}
