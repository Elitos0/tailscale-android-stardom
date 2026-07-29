// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product

import android.content.Context
import android.content.Intent
import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.policy.AccessRepository
import com.tailscale.ipn.product.policy.AccessState
import kotlinx.coroutines.flow.StateFlow

class StardomSessionController(
    val authSessionRepository: AuthSessionRepository,
    val accessRepository: AccessRepository,
) {
  val authentikState: StateFlow<AuthentikState> = authSessionRepository.authentikState
  val accessState: StateFlow<AccessState> = accessRepository.state

  fun startAuthorization(context: Context, onComplete: (Result<Unit>) -> Unit) {
    authSessionRepository.startAuthorization(context, onComplete)
  }

  fun handleAuthorizationIntent(
      context: Context,
      intent: Intent,
      onRecoveredAuthorization: () -> Unit = {},
  ) {
    authSessionRepository.handleAuthorizationIntent(context, intent, onRecoveredAuthorization)
  }

  suspend fun refreshAccess(context: Context): AccessState =
      accessRepository.refresh(context, authSessionRepository)

  fun clearSession() {
    authSessionRepository.clearSession()
    accessRepository.clear()
  }
}
