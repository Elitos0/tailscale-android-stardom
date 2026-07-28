// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadscaleLoginTest {
  @Test
  fun brandedBuildUsesSecureFixedServices() {
    assertTrue(BuildConfig.HEADSCALE_CONTROL_URL.startsWith("https://"))
    assertFalse(BuildConfig.HEADSCALE_CONTROL_URL.endsWith("tailscale.com"))
    assertTrue(BuildConfig.POLICY_API_BASE_URL.startsWith("https://"))
    assertTrue(BuildConfig.AUTHENTIK_ISSUER_URL.startsWith("https://"))
    assertTrue(BuildConfig.POLICY_API_OIDC_CLIENT_ID.isNotBlank())
  }
}
