// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.mdm.MDMSetting
import com.tailscale.ipn.mdm.MDMSettings

object StardomSyspolicyBridge {
  private val rejectedAuthenticationKeys = setOf("AuthKey", "LoginURL")

  val visibleSettings: List<MDMSetting<*>>
    get() = MDMSettings.allSettings.filterNot { it.key in rejectedAuthenticationKeys }

  fun <T> get(key: String, fallbackValue: () -> T): T {
    if (key in rejectedAuthenticationKeys) {
      throw MDMSettings.NoSuchKeyException()
    }
    return fallbackValue()
  }

  fun getString(key: String, fallbackValue: () -> String): String = get(key, fallbackValue)
}
