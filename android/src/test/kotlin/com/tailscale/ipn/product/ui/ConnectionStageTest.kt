// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ui

import com.tailscale.ipn.product.policy.AccessState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionStageTest {
  @Test
  fun signedOutAlwaysRequiresStardomSignIn() {
    assertEquals(
        ConnectionStage.SignIn,
        resolveConnectionStage(
            signedIn = false,
            accessState = AccessState.Active(setOf("node-a")),
            isVpnPrepared = false))
  }

  @Test
  fun unavailableAccessFailsClosed() {
    assertEquals(
        ConnectionStage.AccessUnavailable,
        resolveConnectionStage(true, AccessState.Unavailable, isVpnPrepared = true))
  }

  @Test
  fun disabledAccessFailsClosed() {
    assertEquals(
        ConnectionStage.AccessDisabled,
        resolveConnectionStage(true, AccessState.Disabled, isVpnPrepared = true))
  }

  @Test
  fun activeAccessRequestsVpnPermissionBeforeConnection() {
    assertEquals(
        ConnectionStage.RequestVpnPermission,
        resolveConnectionStage(true, AccessState.Active(setOf("node-a")), isVpnPrepared = false))
  }

  @Test
  fun activeAccessWithPermissionCanConnect() {
    assertEquals(
        ConnectionStage.Connect,
        resolveConnectionStage(true, AccessState.Active(setOf("node-a")), isVpnPrepared = true))
  }
}
