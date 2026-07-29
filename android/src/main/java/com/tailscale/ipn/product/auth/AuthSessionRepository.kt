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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import org.json.JSONObject

private const val AUTH_STATE_KEY = "auth_state"
private const val AUTH_PENDING_TRANSACTION_KEY = "pending_authorization_transaction"
private const val AUTH_FIXED_HEADSCALE_CONTINUATION_KEY = "fixed_headscale_continuation"
private const val AUTH_PREFERENCES = "stardom_auth_session"
private const val AUTH_CALLBACK_ACTION = "com.stardom.vpn.AUTH_CALLBACK"
private const val AUTH_CANCEL_ACTION = "com.stardom.vpn.AUTH_CANCELLED"
internal const val AUTH_TRANSACTION_STATE_EXTRA = "com.stardom.vpn.AUTH_TRANSACTION_STATE"
private const val AUTH_REDIRECT_URI = "com.stardom.vpn:/oauth2redirect"
private const val OIDC_SCOPES = "openid profile email offline_access"
private const val AUTH_TRANSACTION_MAX_AGE_MILLIS = 5 * 60 * 1000L

internal fun callbackPendingIntentFlags(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    PendingIntent.FLAG_ONE_SHOT or
        if (sdkInt >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

interface AuthStateStorage {
  fun read(): String?

  fun write(value: String)

  fun clear()
}

interface AuthorizationTransactionStorage {
  fun write(transaction: PendingAuthorizationTransaction)

  fun consumeIf(predicate: (PendingAuthorizationTransaction) -> Boolean): Boolean

  fun markFixedHeadscaleContinuation()

  fun consumeFixedHeadscaleContinuation(): Boolean
}

data class PendingAuthorizationTransaction(
    val request: AuthorizationRequest,
    val createdAtMillis: Long,
)

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

  fun createAuthorizationRequest(
      configuration: AuthorizationServiceConfiguration
  ): AuthorizationRequest

  fun startAuthorization(context: Context, request: AuthorizationRequest)

  fun authorizationResult(intent: Intent): AuthorizationResult?

  fun matchesPendingAuthorization(
      intent: Intent,
      response: AuthorizationResponse?,
      pendingRequest: AuthorizationRequest,
  ): Boolean

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
    private val appAuth: AppAuthGateway,
    private val transactionStorage: AuthorizationTransactionStorage =
        InMemoryAuthorizationTransactionStorage(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  constructor(
      context: Context
  ) : this(context.applicationContext, EncryptedAuthStateStorage(context.applicationContext))

  private constructor(
      context: Context,
      storage: AuthStateStorage
  ) : this(
      storage,
      PersistedAuthSessionState.deserialize(storage.read()),
      RealAppAuthGateway(),
      EncryptedAuthorizationTransactionStorage(context))

  private var completion: ((Result<Unit>) -> Unit)? = null
  private val _authentikState = MutableStateFlow(authentikStateFor(authState))
  val authentikState: StateFlow<AuthentikState> = _authentikState.asStateFlow()

  val isSignedOut: Boolean
    get() = !authState.isAuthorized

  fun startAuthorization(context: Context, onComplete: (Result<Unit>) -> Unit) {
    completion = onComplete
    _authentikState.value = AuthentikState.Authorizing
    appAuth.discover { configuration, exception ->
      if (configuration == null) {
        _authentikState.value = AuthentikState.SignedOut
        finish(Result.failure(exception ?: IllegalStateException("Unable to discover OIDC issuer")))
      } else {
        val request = appAuth.createAuthorizationRequest(configuration)
        transactionStorage.write(PendingAuthorizationTransaction(request, nowMillis()))
        appAuth.startAuthorization(context, request)
      }
    }
  }

  fun handleAuthorizationIntent(
      context: Context,
      intent: Intent,
      onRecoveredAuthorization: () -> Unit = {},
      onFinished: () -> Unit = {},
  ) {
    val result =
        appAuth.authorizationResult(intent)
            ?: run {
              onFinished()
              return
            }
    if (!transactionStorage.consumeIf { pending ->
      nowMillis() - pending.createdAtMillis in 0..AUTH_TRANSACTION_MAX_AGE_MILLIS &&
          appAuth.matchesPendingAuthorization(intent, result.response, pending.request)
    }) {
      onFinished()
      return
    }
    authState.updateAuthorization(result.response, result.exception)
    persist()
    val response = result.response
    if (response == null) {
      _authentikState.value = AuthentikState.SignedOut
      finish(
          Result.failure(result.exception ?: IllegalStateException("Authorization was cancelled")))
      onFinished()
      return
    }

    appAuth.exchangeCode(context, response) { tokenResponse, tokenException ->
      authState.updateToken(tokenResponse, tokenException)
      persist()
      if (tokenResponse == null) {
        _authentikState.value = AuthentikState.SignedOut
        finish(
            Result.failure(tokenException ?: IllegalStateException("Unable to exchange OIDC code")))
      } else if (!finish(Result.success(Unit))) {
        _authentikState.value = AuthentikState.Authorized
        transactionStorage.markFixedHeadscaleContinuation()
        onRecoveredAuthorization()
      } else {
        _authentikState.value = AuthentikState.Authorized
      }
      onFinished()
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
          clearSession(AuthentikState.ReauthenticationRequired)
        }
        onResult(Result.failure(exception ?: IllegalStateException("Unable to refresh token")))
      } else {
        _authentikState.value = AuthentikState.Authorized
        onResult(Result.success(accessToken))
      }
    }
  }

  fun clearSession() {
    clearSession(AuthentikState.SignedOut)
  }

  fun consumeFixedHeadscaleContinuation(): Boolean =
      transactionStorage.consumeFixedHeadscaleContinuation()

  private fun clearSession(state: AuthentikState) {
    authState = PersistedAuthSessionState(AuthState())
    storage.clear()
    _authentikState.value = state
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

  private fun authentikStateFor(state: AuthSessionState): AuthentikState =
      if (state.isAuthorized) AuthentikState.Authorized else AuthentikState.SignedOut
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

internal fun callbackMatchesPendingAuthorization(
    intent: Intent,
    response: AuthorizationResponse?,
    pendingRequest: AuthorizationRequest,
): Boolean {
  if (intent.getStringExtra(AUTH_TRANSACTION_STATE_EXTRA) != pendingRequest.state) return false
  if (response == null) return true
  return responseMatchesPendingRequest(response, pendingRequest)
}

private fun responseMatchesPendingRequest(
    response: AuthorizationResponse,
    pending: AuthorizationRequest,
): Boolean {
  val request = response.request
  return response.state == pending.state &&
      request.state == pending.state &&
      request.clientId == pending.clientId &&
      request.redirectUri == pending.redirectUri &&
      request.responseType == pending.responseType &&
      request.codeVerifier == pending.codeVerifier &&
      request.codeVerifierChallenge == pending.codeVerifierChallenge &&
      request.codeVerifierChallengeMethod == pending.codeVerifierChallengeMethod
}

private class RealAppAuthGateway : AppAuthGateway {
  private var authorizationService: AuthorizationService? = null

  override fun discover(
      callback: (AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit
  ) {
    AuthorizationServiceConfiguration.fetchFromIssuer(
        Uri.parse(ProductConfig.authentikIssuerUrl), callback)
  }

  override fun createAuthorizationRequest(
      configuration: AuthorizationServiceConfiguration
  ): AuthorizationRequest =
      AuthorizationRequest.Builder(
              configuration,
              ProductConfig.policyApiOidcClientId,
              ResponseTypeValues.CODE,
              Uri.parse(AUTH_REDIRECT_URI))
          .setScope(OIDC_SCOPES)
          .build()

  override fun startAuthorization(context: Context, request: AuthorizationRequest) {
    authorizationService?.dispose()
    authorizationService = AuthorizationService(context)
    authorizationService!!.performAuthorizationRequest(
        request,
        callbackPendingIntent(context, AUTH_CALLBACK_ACTION, request),
        callbackPendingIntent(context, AUTH_CANCEL_ACTION, request))
  }

  override fun authorizationResult(intent: Intent): AuthorizationResult? {
    val response = AuthorizationResponse.fromIntent(intent)
    val exception = AuthorizationException.fromIntent(intent)
    return if (response == null && exception == null) null
    else AuthorizationResult(response, exception)
  }

  override fun matchesPendingAuthorization(
      intent: Intent,
      response: AuthorizationResponse?,
      pendingRequest: AuthorizationRequest,
  ): Boolean {
    return callbackMatchesPendingAuthorization(intent, response, pendingRequest)
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

  private fun callbackPendingIntent(
      context: Context,
      action: String,
      request: AuthorizationRequest,
  ): PendingIntent {
    val intent =
        Intent(context, AuthCallbackActivity::class.java)
            .setAction(action)
            .putExtra(AUTH_TRANSACTION_STATE_EXTRA, request.state)
    return PendingIntent.getActivity(
        context,
        31 * action.hashCode() + request.state.hashCode(),
        intent,
        callbackPendingIntentFlags())
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

private class EncryptedAuthorizationTransactionStorage(
    context: Context,
) : AuthorizationTransactionStorage {
  private val preferences =
      EncryptedSharedPreferences.create(
          context,
          AUTH_PREFERENCES,
          MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

  override fun write(transaction: PendingAuthorizationTransaction) {
    preferences
        .edit()
        .putString(
            AUTH_PENDING_TRANSACTION_KEY,
            JSONObject()
                .put("request", transaction.request.jsonSerializeString())
                .put("createdAtMillis", transaction.createdAtMillis)
                .toString())
        .commit()
  }

  override fun consumeIf(predicate: (PendingAuthorizationTransaction) -> Boolean): Boolean =
      synchronized(this) {
        val transaction =
            preferences.getString(AUTH_PENDING_TRANSACTION_KEY, null)?.let {
              runCatching {
                    val json = JSONObject(it)
                    PendingAuthorizationTransaction(
                        AuthorizationRequest.jsonDeserialize(json.getString("request")),
                        json.getLong("createdAtMillis"))
                  }
                  .getOrNull()
            } ?: return@synchronized false
        if (!predicate(transaction)) return@synchronized false
        preferences.edit().remove(AUTH_PENDING_TRANSACTION_KEY).commit()
      }

  override fun markFixedHeadscaleContinuation() {
    preferences.edit().putBoolean(AUTH_FIXED_HEADSCALE_CONTINUATION_KEY, true).commit()
  }

  override fun consumeFixedHeadscaleContinuation(): Boolean =
      synchronized(this) {
        if (!preferences.getBoolean(AUTH_FIXED_HEADSCALE_CONTINUATION_KEY, false))
            return@synchronized false
        preferences.edit().remove(AUTH_FIXED_HEADSCALE_CONTINUATION_KEY).commit()
      }
}

private class InMemoryAuthorizationTransactionStorage : AuthorizationTransactionStorage {
  private var transaction: PendingAuthorizationTransaction? = null
  private var fixedHeadscaleContinuation = false

  override fun write(transaction: PendingAuthorizationTransaction) {
    this.transaction = transaction
  }

  override fun consumeIf(predicate: (PendingAuthorizationTransaction) -> Boolean): Boolean {
    val pending = transaction ?: return false
    return predicate(pending).also { if (it) transaction = null }
  }

  override fun markFixedHeadscaleContinuation() {
    fixedHeadscaleContinuation = true
  }

  override fun consumeFixedHeadscaleContinuation(): Boolean =
      fixedHeadscaleContinuation.also { fixedHeadscaleContinuation = false }
}
