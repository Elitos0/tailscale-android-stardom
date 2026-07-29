// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ui

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.policy.AccessState

sealed interface ConnectionStage {
  data object SignIn : ConnectionStage

  data object AccessUnavailable : ConnectionStage

  data object AccessDisabled : ConnectionStage

  data object RequestVpnPermission : ConnectionStage

  data object Connect : ConnectionStage
}

fun resolveConnectionStage(
    authentikState: AuthentikState,
    hasHeadscaleProfile: Boolean,
    accessState: AccessState,
    isVpnPrepared: Boolean,
): ConnectionStage =
    when {
      authentikState == AuthentikState.SignedOut ||
          authentikState == AuthentikState.ReauthenticationRequired -> ConnectionStage.SignIn
      !hasHeadscaleProfile -> ConnectionStage.SignIn
      accessState == AccessState.Unavailable -> ConnectionStage.AccessUnavailable
      accessState == AccessState.Disabled -> ConnectionStage.AccessDisabled
      !isVpnPrepared -> ConnectionStage.RequestVpnPermission
      else -> ConnectionStage.Connect
    }
