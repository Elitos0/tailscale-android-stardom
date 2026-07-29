// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ui

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.policy.AccessState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStageTest {
  @Test
  fun reauthenticationTakesPrecedenceOverHeadscaleProfileAndPreparedVpn() {
    assertEquals(
        ConnectionStage.SignIn,
        resolveConnectionStage(
            AuthentikState.ReauthenticationRequired,
            hasHeadscaleProfile = true,
            accessState = AccessState.Active(setOf("node-a")),
            isVpnPrepared = true))
  }

  @Test
  fun signedOutAlwaysRequiresStardomSignIn() {
    assertEquals(
        ConnectionStage.SignIn,
        resolveConnectionStage(
            authentikState = AuthentikState.SignedOut,
            hasHeadscaleProfile = true,
            accessState = AccessState.Active(setOf("node-a")),
            isVpnPrepared = false))
  }

  @Test
  fun unavailableAccessFailsClosed() {
    assertEquals(
        ConnectionStage.AccessUnavailable,
        resolveConnectionStage(
            AuthentikState.Authorized,
            hasHeadscaleProfile = true,
            accessState = AccessState.Unavailable,
            isVpnPrepared = true))
  }

  @Test
  fun disabledAccessFailsClosed() {
    assertEquals(
        ConnectionStage.AccessDisabled,
        resolveConnectionStage(
            AuthentikState.Authorized,
            hasHeadscaleProfile = true,
            accessState = AccessState.Disabled,
            isVpnPrepared = true))
  }

  @Test
  fun activeAccessRequestsVpnPermissionBeforeConnection() {
    assertEquals(
        ConnectionStage.RequestVpnPermission,
        resolveConnectionStage(
            AuthentikState.Authorized,
            hasHeadscaleProfile = true,
            accessState = AccessState.Active(setOf("node-a")),
            isVpnPrepared = false))
  }

  @Test
  fun activeAccessWithPermissionCanConnect() {
    assertEquals(
        ConnectionStage.Connect,
        resolveConnectionStage(
            AuthentikState.Authorized,
            hasHeadscaleProfile = true,
            accessState = AccessState.Active(setOf("node-a")),
            isVpnPrepared = true))
  }
}
