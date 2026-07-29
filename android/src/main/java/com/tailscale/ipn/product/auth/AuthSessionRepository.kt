// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import net.openid.appauth.TokenResponse

private const val AUTH_STATE_KEY = "auth_state"
private const val AUTH_PREFERENCES = "stardom_auth_session"
private const val AUTH_CALLBACK_ACTION = "com.stardom.vpn.AUTH_CALLBACK"
private const val AUTH_CANCEL_ACTION = "com.stardom.vpn.AUTH_CANCELLED"
private const val AUTH_REDIRECT_URI = "com.stardom.vpn:/oauth2redirect"
private const val OIDC_SCOPES = "openid profile email offline_access"

internal fun callbackPendingIntentFlags(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    PendingIntent.FLAG_UPDATE_CURRENT or
        if (sdkInt >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

interface AuthStateStorage {
  fun read(): String?

  fun write(value: String)

  fun clear()
}

interface AuthSessionState {
  val isAuthorized: Boolean
  val appAuthState: AuthState

  fun updateAuthorization(response: AuthorizationResponse?, exception: AuthorizationException?)

  fun updateToken(response: TokenResponse?, exception: AuthorizationException?)

  fun serialize(): String
}

data class AuthorizationResult(
    val response: AuthorizationResponse?,
    val exception: AuthorizationException?
)

interface AppAuthGateway {
  fun discover(callback: (AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit)

  fun startAuthorization(context: Context, configuration: AuthorizationServiceConfiguration)

  fun authorizationResult(intent: Intent): AuthorizationResult?

  fun exchangeCode(
      context: Context,
      response: AuthorizationResponse,
      callback: (TokenResponse?, AuthorizationException?) -> Unit
  )

  fun freshToken(
      context: Context,
      state: AuthSessionState,
      callback: (String?, AuthorizationException?) -> Unit
  )

  fun dispose()
}

class AuthSessionRepository(
    private val storage: AuthStateStorage,
    private var authState: AuthSessionState,
    private val appAuth: AppAuthGateway
) {
  constructor(
      storage: AuthStateStorage
  ) : this(storage, PersistedAuthSessionState.deserialize(storage.read()), RealAppAuthGateway())

  constructor(context: Context) : this(EncryptedAuthStateStorage(context.applicationContext))

  private var completion: ((Result<Unit>) -> Unit)? = null

  val isSignedOut: Boolean
    get() = !authState.isAuthorized

  fun startAuthorization(context: Context, onComplete: (Result<Unit>) -> Unit) {
    completion = onComplete
    appAuth.discover { configuration, exception ->
      if (configuration == null) {
        finish(Result.failure(exception ?: IllegalStateException("Unable to discover OIDC issuer")))
      } else {
        appAuth.startAuthorization(context, configuration)
      }
    }
  }

  fun handleAuthorizationIntent(
      context: Context,
      intent: Intent,
      onRecoveredAuthorization: () -> Unit = {}
  ) {
    val result = appAuth.authorizationResult(intent) ?: return
    authState.updateAuthorization(result.response, result.exception)
    persist()
    val response = result.response
    if (response == null) {
      finish(
          Result.failure(result.exception ?: IllegalStateException("Authorization was cancelled")))
      return
    }

    appAuth.exchangeCode(context, response) { tokenResponse, tokenException ->
      authState.updateToken(tokenResponse, tokenException)
      persist()
      if (tokenResponse == null) {
        finish(
            Result.failure(tokenException ?: IllegalStateException("Unable to exchange OIDC code")))
      } else if (!finish(Result.success(Unit))) {
        onRecoveredAuthorization()
      }
    }
  }

  fun withFreshBearerToken(context: Context, onResult: (Result<String>) -> Unit) {
    if (isSignedOut) {
      onResult(Result.failure(IllegalStateException("Signed out")))
      return
    }

    appAuth.freshToken(context, authState) { accessToken, exception ->
      persist()
      if (exception != null || accessToken.isNullOrBlank()) {
        if (isInvalidOrRevokedCredential(exception)) {
          clearSession()
        }
        onResult(Result.failure(exception ?: IllegalStateException("Unable to refresh token")))
      } else {
        onResult(Result.success(accessToken))
      }
    }
  }

  fun clearSession() {
    authState = PersistedAuthSessionState(AuthState())
    storage.clear()
  }

  private fun finish(result: Result<Unit>): Boolean {
    val hasCompletion = completion != null
    completion?.invoke(result)
    completion = null
    appAuth.dispose()
    return hasCompletion
  }

  private fun persist() {
    storage.write(authState.serialize())
  }

  private fun isInvalidOrRevokedCredential(exception: AuthorizationException?): Boolean {
    return exception?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
        exception.error in setOf("invalid_grant", "invalid_token")
  }
}

private class PersistedAuthSessionState(override val appAuthState: AuthState) : AuthSessionState {
  override val isAuthorized: Boolean
    get() = appAuthState.isAuthorized

  override fun updateAuthorization(
      response: AuthorizationResponse?,
      exception: AuthorizationException?
  ) {
    appAuthState.update(response, exception)
  }

  override fun updateToken(response: TokenResponse?, exception: AuthorizationException?) {
    appAuthState.update(response, exception)
  }

  override fun serialize(): String = appAuthState.jsonSerializeString()

  companion object {
    fun deserialize(value: String?): PersistedAuthSessionState {
      if (value == null) {
        return PersistedAuthSessionState(AuthState())
      }
      return try {
        PersistedAuthSessionState(AuthState.jsonDeserialize(value))
      } catch (_: Exception) {
        PersistedAuthSessionState(AuthState())
      }
    }
  }
}

private class RealAppAuthGateway : AppAuthGateway {
  private var authorizationService: AuthorizationService? = null

  override fun discover(
      callback: (AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit
  ) {
    AuthorizationServiceConfiguration.fetchFromIssuer(
        Uri.parse(ProductConfig.authentikIssuerUrl), callback)
  }

  override fun startAuthorization(
      context: Context,
      configuration: AuthorizationServiceConfiguration
  ) {
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

  override fun authorizationResult(intent: Intent): AuthorizationResult? {
    val response = AuthorizationResponse.fromIntent(intent)
    val exception = AuthorizationException.fromIntent(intent)
    return if (response == null && exception == null) null
    else AuthorizationResult(response, exception)
  }

  override fun exchangeCode(
      context: Context,
      response: AuthorizationResponse,
      callback: (TokenResponse?, AuthorizationException?) -> Unit
  ) {
    val service = authorizationService ?: AuthorizationService(context)
    service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, exception ->
      service.dispose()
      authorizationService = null
      callback(tokenResponse, exception)
    }
  }

  override fun freshToken(
      context: Context,
      state: AuthSessionState,
      callback: (String?, AuthorizationException?) -> Unit
  ) {
    val service = AuthorizationService(context)
    state.appAuthState.performActionWithFreshTokens(service) { accessToken, _, exception ->
      service.dispose()
      callback(accessToken, exception)
    }
  }

  override fun dispose() {
    authorizationService?.dispose()
    authorizationService = null
  }

  private fun callbackPendingIntent(context: Context, action: String): PendingIntent {
    val intent = Intent(context, com.tailscale.ipn.MainActivity::class.java).setAction(action)
    return PendingIntent.getActivity(
        context, action.hashCode(), intent, callbackPendingIntentFlags())
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
