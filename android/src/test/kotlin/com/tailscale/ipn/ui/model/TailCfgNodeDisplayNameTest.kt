// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import com.tailscale.ipn.ui.util.capitalizeNodeNameForDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class TailCfgNodeDisplayNameTest {
  @Test
  fun displayNameCapitalizesOnlyTheFirstCharacter() {
    val node = Tailcfg.Node(Name = "finland", ComputedName = "finland")

    assertEquals("Finland", node.displayName)
    assertEquals("finland", node.ComputedName)
    assertEquals("finland", node.Name)
  }

  @Test
  fun displayNamePreservesAlreadyCapitalizedAndMultiwordNames() {
    assertEquals("Finland North", "Finland North".capitalizeNodeNameForDisplay())
    assertEquals("New york", "new york".capitalizeNodeNameForDisplay())
    assertEquals("mTLS-gateway", "mTLS-gateway".capitalizeNodeNameForDisplay())
  }

  @Test
  fun displayNameDoesNotApplyLocaleSensitiveIdentifierTransformations() {
    assertEquals("Istanbul", "istanbul".capitalizeNodeNameForDisplay())
    assertEquals("123-node", "123-node".capitalizeNodeNameForDisplay())
    assertEquals("", "".capitalizeNodeNameForDisplay())
  }
}
