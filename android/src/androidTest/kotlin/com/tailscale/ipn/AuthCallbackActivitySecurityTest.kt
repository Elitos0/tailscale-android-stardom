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
    val callback =
        Intent("com.stardom.vpn.AUTH_CALLBACK").putExtra(AUTH_TRANSACTION_STATE_EXTRA, state)
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
    assertFalse(callbackMatchesPendingAuthorization(callback, response(pending, null), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(callback, response(pending, "wrong-state"), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(callback, response(differentClient, state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(callback, response(differentRedirect, state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(callback, response(differentPkce, state), pending))
  }

  @Test
  fun callbackMustMatchTheEntireAuthorizationRequestIdentity() {
    val pending = request(clientId = "stardom-client", redirect = "com.stardom.vpn:/oauth2redirect")
    val state = requireNotNull(pending.state)
    val callback =
        Intent("com.stardom.vpn.AUTH_CALLBACK").putExtra(AUTH_TRANSACTION_STATE_EXTRA, state)

    assertFalse(
        callbackMatchesPendingAuthorization(
            callback, response(request(scope = "openid email"), state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(
            callback, response(request(nonce = "different-nonce"), state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(
            callback, response(request(prompt = "login"), state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(
            callback, response(request(loginHint = "other@example.test"), state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(
            callback, response(request(uiLocales = "ru-RU"), state), pending))
    assertFalse(
        callbackMatchesPendingAuthorization(
            callback,
            response(request(additionalParameters = mapOf("resource" to "other")), state),
            pending))
    assertFalse(
        callbackMatchesPendingAuthorization(
            callback,
            response(request(tokenEndpoint = "https://issuer.example/other-token"), state),
            pending))
    assertFalse(
        callbackMatchesPendingAuthorization(
            Intent("unexpected-action").putExtra(AUTH_TRANSACTION_STATE_EXTRA, state),
            response(pending, state),
            pending))
  }

  private fun request(
      clientId: String = "stardom-client",
      redirect: String = "com.stardom.vpn:/oauth2redirect",
      verifier: String = "v".repeat(43),
      scope: String = "openid profile email",
      nonce: String = "nonce",
      prompt: String = "consent",
      loginHint: String = "tester@example.test",
      uiLocales: String = "en-US",
      additionalParameters: Map<String, String> = mapOf("resource" to "stardom"),
      tokenEndpoint: String = "https://issuer.example/token",
  ): AuthorizationRequest =
      AuthorizationRequest.Builder(
              AuthorizationServiceConfiguration(
                  Uri.parse("https://issuer.example/authorize"), Uri.parse(tokenEndpoint)),
              clientId,
              ResponseTypeValues.CODE,
              Uri.parse(redirect))
          .setState("current-state")
          .setScope(scope)
          .setNonce(nonce)
          .setPrompt(prompt)
          .setLoginHint(loginHint)
          .setUiLocales(uiLocales)
          .setAdditionalParameters(additionalParameters)
          .setCodeVerifier(verifier, "challenge-$verifier", "S256")
          .build()

  private fun response(request: AuthorizationRequest, state: String?): AuthorizationResponse =
      AuthorizationResponse.Builder(request).setAuthorizationCode("code").setState(state).build()
}
