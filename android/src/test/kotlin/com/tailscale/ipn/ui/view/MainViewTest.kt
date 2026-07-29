// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import com.tailscale.ipn.product.ui.ConnectionStage
import com.tailscale.ipn.ui.model.Ipn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainViewTest {
  @Test
  fun runningWithoutVpnPermissionRendersConnectionContent() {
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.RequestVpnPermission))
  }

  @Test
  fun runningWithUnavailableAccessRendersConnectionContent() {
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.AccessUnavailable))
  }

  @Test
  fun runningWithDisabledAccessRendersConnectionContent() {
    assertFalse(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.AccessDisabled))
  }

  @Test
  fun runningWithConnectStageRendersPeerContent() {
    assertTrue(shouldRenderPeerContent(Ipn.State.Running, ConnectionStage.Connect))
  }
}
