// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailscale.ipn.product.auth.AUTH_TRANSACTION_STATE_EXTRA
import com.tailscale.ipn.product.auth.AuthCallbackActivity
import com.tailscale.ipn.product.auth.callbackMatchesPendingAuthorization
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthCallbackActivitySecurityTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()

  @Test
  fun appAuthCompletionActivityIsInternalAndNoHistory() {
    val info =
        context.packageManager.getActivityInfo(
            ComponentName(context, AuthCallbackActivity::class.java), 0)

    assertFalse(info.exported)
    assertTrue(info.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
  }

  @Test
  fun callbackActionCannotResolveToExportedMainActivity() {
    val resolved =
        context.packageManager.resolveActivity(
            Intent("com.stardom.vpn.AUTH_CALLBACK").setPackage(context.packageName), 0)

    assertEquals(null, resolved)
  }

  @Test
  fun callbackMustMatchTheCurrentStateClientRedirectAndPkceRequest() {
    val pending = request(clientId = "stardom-client", redirect = "com.stardom.vpn:/oauth2redirect")
    val state = requireNotNull(pending.state)
    val clientId = requireNotNull(pending.clientId)
    val callback = Intent().putExtra(AUTH_TRANSACTION_STATE_EXTRA, state)
    val valid = response(pending, state)
    val differentClient =
        request(clientId = "other-client", redirect = pending.redirectUri.toString())
    val differentRedirect = request(clientId = clientId, redirect = "com.stardom.vpn:/other")
    val differentPkce =
        request(
            clientId = clientId,
            redirect = pending.redirectUri.toString(),
            verifier = "d".repeat(43))

    assertTrue(callbackMatchesPendingAuthorization(callback, valid, pending))
    assertFalse(
        callbackMatchesPendingAuthorization(callback, response(pending, "wrong-state"), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(callback, response(differentClient, state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(callback, response(differentRedirect, state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(callback, response(differentPkce, state), pending))
  }

  private fun request(
      clientId: String,
      redirect: String,
      verifier: String = "v".repeat(43),
  ): AuthorizationRequest =
      AuthorizationRequest.Builder(
              AuthorizationServiceConfiguration(
                  Uri.parse("https://issuer.example/authorize"),
                  Uri.parse("https://issuer.example/token")),
              clientId,
              ResponseTypeValues.CODE,
              Uri.parse(redirect))
          .setState("current-state")
          .setCodeVerifier(verifier, "challenge-$verifier", "S256")
          .build()

  private fun response(request: AuthorizationRequest, state: String): AuthorizationResponse =
      AuthorizationResponse.Builder(request).setAuthorizationCode("code").setState(state).build()
}
