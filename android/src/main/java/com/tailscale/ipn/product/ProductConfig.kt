// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product

import com.tailscale.ipn.BuildConfig

object ProductConfig {
  val headscaleControlUrl = BuildConfig.HEADSCALE_CONTROL_URL
  val policyApiBaseUrl = BuildConfig.POLICY_API_BASE_URL
  val authentikIssuerUrl = BuildConfig.AUTHENTIK_ISSUER_URL
  val policyApiOidcClientId = BuildConfig.POLICY_API_OIDC_CLIENT_ID
}
