// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tailscale.ipn.product.ProductConfig
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues

private const val AUTH_STATE_KEY = "auth_state"
private const val AUTH_PREFERENCES = "stardom_auth_session"
private const val AUTH_CALLBACK_ACTION = "com.stardom.vpn.AUTH_CALLBACK"
private const val AUTH_CANCEL_ACTION = "com.stardom.vpn.AUTH_CANCELLED"
private const val AUTH_REDIRECT_URI = "com.stardom.vpn:/oauth2redirect"
private const val OIDC_SCOPES = "openid profile email offline_access"

interface AuthStateStorage {
  fun read(): String?

  fun write(value: String)

  fun clear()
}

class AuthSessionRepository(private val storage: AuthStateStorage) {
  constructor(context: Context) : this(EncryptedAuthStateStorage(context.applicationContext))

  private var authState = deserialize(storage.read())
  private var completion: ((Result<Unit>) -> Unit)? = null
  private var authorizationService: AuthorizationService? = null

  val isSignedOut: Boolean
    get() = !authState.isAuthorized

  fun startAuthorization(context: Context, onComplete: (Result<Unit>) -> Unit) {
    completion = onComplete
    AuthorizationServiceConfiguration.fetchFromIssuer(
        Uri.parse(ProductConfig.authentikIssuerUrl)) { configuration, exception ->
          if (configuration == null) {
            finish(
                Result.failure(
                    exception ?: IllegalStateException("Unable to discover OIDC issuer")))
            return@fetchFromIssuer
          }

          val request =
              AuthorizationRequest.Builder(
                      configuration,
                      ProductConfig.policyApiOidcClientId,
                      ResponseTypeValues.CODE,
                      Uri.parse(AUTH_REDIRECT_URI))
                  .setScope(OIDC_SCOPES)
                  .build()
          authorizationService?.dispose()
          authorizationService = AuthorizationService(context)
          authorizationService!!.performAuthorizationRequest(
              request,
              callbackPendingIntent(context, AUTH_CALLBACK_ACTION),
              callbackPendingIntent(context, AUTH_CANCEL_ACTION))
        }
  }

  fun handleAuthorizationIntent(
      context: Context,
      intent: Intent,
      onRecoveredAuthorization: () -> Unit = {}
  ) {
    val response = AuthorizationResponse.fromIntent(intent)
    val exception = AuthorizationException.fromIntent(intent)
    if (response == null && exception == null) {
      return
    }

    authState.update(response, exception)
    persist()
    if (response == null) {
      finish(Result.failure(exception ?: IllegalStateException("Authorization was cancelled")))
      return
    }

    val service = authorizationService ?: AuthorizationService(context)
    service.performTokenRequest(response.createTokenExchangeRequest()) {
        tokenResponse,
        tokenException ->
      authState.update(tokenResponse, tokenException)
      persist()
      service.dispose()
      authorizationService = null
      if (tokenResponse == null) {
        finish(
            Result.failure(tokenException ?: IllegalStateException("Unable to exchange OIDC code")))
      } else {
        if (!finish(Result.success(Unit))) {
          onRecoveredAuthorization()
        }
      }
    }
  }

  fun withFreshBearerToken(context: Context, onResult: (Result<String>) -> Unit) {
    if (isSignedOut) {
      onResult(Result.failure(IllegalStateException("Signed out")))
      return
    }

    val service = AuthorizationService(context)
    authState.performActionWithFreshTokens(service) { accessToken, _, exception ->
      persist()
      service.dispose()
      if (exception != null || accessToken.isNullOrBlank()) {
        clearSession()
        onResult(Result.failure(exception ?: IllegalStateException("Signed out")))
      } else {
        onResult(Result.success(accessToken))
      }
    }
  }

  fun clearSession() {
    authState = AuthState()
    storage.clear()
  }

  private fun callbackPendingIntent(context: Context, action: String): PendingIntent {
    val intent = Intent(context, com.tailscale.ipn.MainActivity::class.java).setAction(action)
    return PendingIntent.getActivity(
        context,
        action.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }

  private fun finish(result: Result<Unit>): Boolean {
    val hasCompletion = completion != null
    completion?.invoke(result)
    completion = null
    authorizationService?.dispose()
    authorizationService = null
    return hasCompletion
  }

  private fun persist() {
    storage.write(authState.jsonSerializeString())
  }

  private fun deserialize(value: String?): AuthState {
    if (value == null) {
      return AuthState()
    }
    return try {
      AuthState.jsonDeserialize(value)
    } catch (_: Exception) {
      AuthState()
    }
  }
}

private class EncryptedAuthStateStorage(context: Context) : AuthStateStorage {
  private val preferences =
      EncryptedSharedPreferences.create(
          context,
          AUTH_PREFERENCES,
          MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

  override fun read(): String? = preferences.getString(AUTH_STATE_KEY, null)

  override fun write(value: String) {
    preferences.edit().putString(AUTH_STATE_KEY, value).apply()
  }

  override fun clear() {
    preferences.edit().remove(AUTH_STATE_KEY).apply()
  }
}
