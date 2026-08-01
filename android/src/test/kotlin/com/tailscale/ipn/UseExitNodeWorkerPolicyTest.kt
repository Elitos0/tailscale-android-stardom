// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import com.tailscale.ipn.ui.model.Tailcfg
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UseExitNodeWorkerPolicyTest {
  @Test
  fun mullvadPeerIsRejectedEvenWhenItAdvertisesExitRoutes() {
    val mullvad =
        Tailcfg.Node(
            StableID = "mullvad-node",
            Name = "de-fra-wg-001.mullvad.ts.net.",
            AllowedIPs = listOf("0.0.0.0/0", "::/0"),
        )

    assertFalse(isStardomExitNodeCandidate(mullvad))
  }

  @Test
  fun ownedNonMullvadPeerAdvertisingBothDefaultRoutesIsRetained() {
    val owned =
        Tailcfg.Node(
            StableID = "owned-node",
            Name = "exit-owned.example.net.",
            AllowedIPs = listOf("0.0.0.0/0", "::/0"),
        )

    assertTrue(isStardomExitNodeCandidate(owned))
  }
}
