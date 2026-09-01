// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.auth

sealed interface AuthentikState {
  data object SignedOut : AuthentikState

  data object Authorizing : AuthentikState

  data object Authorized : AuthentikState

  data object AuthorizedLoading : AuthentikState

  data object ReauthenticationRequired : AuthentikState
}
