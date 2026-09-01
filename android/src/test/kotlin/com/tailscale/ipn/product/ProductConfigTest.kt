// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductConfigTest {

  @Test
  fun defaultDebugProductConfigEqualsIntendedPublicEndpoints() {
    assertEquals("https://headscale.elitoswork.ru", ProductConfig.headscaleControlUrl)
    assertEquals("https://api.elitoswork.ru", ProductConfig.policyApiBaseUrl)
    assertEquals(
        "https://auth.elitoswork.ru/application/o/policy-api-android-mvp/",
        ProductConfig.authentikIssuerUrl)
    assertEquals("policy-api-android-mvp", ProductConfig.policyApiOidcClientId)

    assertTrue(
        "Headscale URL must be HTTPS",
        ProductConfig.headscaleControlUrl.startsWith("https://headscale.elitoswork.ru"))
    assertTrue(
        "Policy API URL must be HTTPS",
        ProductConfig.policyApiBaseUrl.startsWith("https://api.elitoswork.ru"))
    assertTrue(
        "Authentik Issuer URL must be HTTPS",
        ProductConfig.authentikIssuerUrl.startsWith(
            "https://auth.elitoswork.ru/application/o/policy-api-android-mvp/"))
  }
}
