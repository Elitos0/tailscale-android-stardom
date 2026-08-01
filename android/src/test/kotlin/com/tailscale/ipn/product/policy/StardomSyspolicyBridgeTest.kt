// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.mdm.MDMSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class StardomSyspolicyBridgeTest {
  @Test
  fun managedAuthKeyIsAlwaysUnconfiguredWithoutReadingItsValue() {
    var fallbackInvoked = false

    assertThrows(MDMSettings.NoSuchKeyException::class.java) {
      StardomSyspolicyBridge.getString("AuthKey") {
        fallbackInvoked = true
        "tskey-auth-adversarial"
      }
    }

    assertFalse(fallbackInvoked)
  }

  @Test
  fun managedLoginUrlIsAlwaysUnconfiguredEvenWhenMalformed() {
    var fallbackInvoked = false

    assertThrows(MDMSettings.NoSuchKeyException::class.java) {
      StardomSyspolicyBridge.getString("LoginURL") {
        fallbackInvoked = true
        "not a valid control url"
      }
    }

    assertFalse(fallbackInvoked)
  }

  @Test
  fun unrelatedManagedStringRetainsUpstreamSemantics() {
    assertEquals(
        "device-name",
        StardomSyspolicyBridge.getString("Hostname") { "device-name" },
    )
  }

  @Test
  fun rejectedAuthenticationSettingsAreNotVisibleInProductMdmUi() {
    val visibleKeys = StardomSyspolicyBridge.visibleSettings.map { it.key }.toSet()

    assertFalse("AuthKey" in visibleKeys)
    assertFalse("LoginURL" in visibleKeys)
    org.junit.Assert.assertTrue("Hostname" in visibleKeys)
  }
}
