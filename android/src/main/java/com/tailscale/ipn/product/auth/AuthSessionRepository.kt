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
import com.tailscale.ipn.util.TSLog
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

  fun hasFixedHeadscaleContinuation(): Boolean

  fun ackFixedHeadscaleContinuation()

  fun clear()
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

open class AuthSessionRepository(
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

  private data class PendingCompletion(
      val generation: Long,
      val callback: (Result<Unit>) -> Unit,
  )

  private val sessionLock = Any()
  private var sessionGeneration = 0L
  private var completion: PendingCompletion? = null
  private val _authentikState = MutableStateFlow(authentikStateFor(authState))
  val authentikState: StateFlow<AuthentikState> = _authentikState.asStateFlow()

  val isSignedOut: Boolean
    get() = synchronized(sessionLock) { !authState.isAuthorized }

  fun startAuthorization(context: Context, onComplete: (Result<Unit>) -> Unit) {
    val (generation, previous) =
        synchronized(sessionLock) {
          sessionGeneration += 1
          appAuth.dispose()
          val previous = completion
          completion = PendingCompletion(sessionGeneration, onComplete)
          transactionStorage.clear()
          _authentikState.value = AuthentikState.Authorizing
          sessionGeneration to previous
        }
    TSLog.d("AuthLifecycle", "OIDC startAuthorization: generation=$generation")
    previous?.callback?.invoke(Result.failure(authSessionChanged()))

    TSLog.d("AuthLifecycle", "OIDC discover starting...")
    appAuth.discover { configuration, exception ->
      if (configuration == null) {
        TSLog.e(
            "AuthLifecycle",
            "OIDC discover failed: " + formatSafeDiscoveryDiagnostics(configuration, exception))
        completeAuthorization(
            generation,
            Result.failure(exception ?: IllegalStateException("Unable to discover OIDC issuer")),
            authorized = false)
        return@discover
      }
      TSLog.d(
          "AuthLifecycle",
          "OIDC discover succeeded: issuerHost=${configuration.authorizationEndpoint?.host ?: "unknown"}")

      synchronized(sessionLock) {
        if (generation != sessionGeneration) {
          TSLog.w(
              "AuthLifecycle",
              "OIDC startAuthorization discarded: stale generation ($generation != $sessionGeneration)")
          return@synchronized
        }
        val request = appAuth.createAuthorizationRequest(configuration)
        transactionStorage.write(PendingAuthorizationTransaction(request, nowMillis()))
        TSLog.d(
            "AuthLifecycle",
            "OIDC launch browser: issuerHost=${configuration.authorizationEndpoint?.host ?: "unknown"}")
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
    val generation = synchronized(sessionLock) { sessionGeneration }
    TSLog.d(
        "AuthLifecycle",
        "OIDC callback received: action=${intent.action} hasData=${intent.data != null} hasStateExtra=${intent.getStringExtra(AUTH_TRANSACTION_STATE_EXTRA) != null}")
    val result =
        appAuth.authorizationResult(intent)
            ?: run {
              TSLog.w("AuthLifecycle", "OIDC callback ignored: authorizationResult is null")
              onFinished()
              return
            }
    if (!transactionStorage.consumeIf { pending ->
      nowMillis() - pending.createdAtMillis in 0..AUTH_TRANSACTION_MAX_AGE_MILLIS &&
          callbackMatchesCurrentTransaction(intent, result, pending.request) &&
          (result.response == null ||
              appAuth.matchesPendingAuthorization(intent, result.response, pending.request))
    }) {
      TSLog.w("AuthLifecycle", "OIDC callback ignored: pending transaction not matched or expired")
      onFinished()
      return
    }

    val current =
        synchronized(sessionLock) {
          if (generation != sessionGeneration) {
            TSLog.w(
                "AuthLifecycle",
                "OIDC callback ignored: stale session generation ($generation != $sessionGeneration)")
            return@synchronized false
          }
          authState.updateAuthorization(result.response, result.exception)
          persistLocked()
          true
        }
    if (!current) {
      onFinished()
      return
    }

    val response = result.response
    if (response == null) {
      TSLog.e(
          "AuthLifecycle",
          "OIDC callback authorization failed or cancelled: error=${result.exception?.javaClass?.simpleName ?: "unknown"}")
      completeAuthorization(
          generation,
          Result.failure(result.exception ?: IllegalStateException("Authorization was cancelled")),
          authorized = false)
      onFinished()
      return
    }

    TSLog.d("AuthLifecycle", "OIDC code exchange start")
    appAuth.exchangeCode(context, response) { tokenResponse, tokenException ->
      val callbackIsCurrent =
          synchronized(sessionLock) {
            if (generation != sessionGeneration) {
              TSLog.w(
                  "AuthLifecycle",
                  "OIDC code exchange result ignored: stale session generation ($generation != $sessionGeneration)")
              return@synchronized false
            }
            authState.updateToken(tokenResponse, tokenException)
            persistLocked()
            true
          }
      if (callbackIsCurrent) {
        if (tokenResponse == null) {
          TSLog.e(
              "AuthLifecycle",
              "OIDC code exchange failed: error=${tokenException?.javaClass?.simpleName ?: "unknown"}")
          completeAuthorization(
              generation,
              Result.failure(
                  tokenException ?: IllegalStateException("Unable to exchange OIDC code")),
              authorized = false)
        } else {
          TSLog.d(
              "AuthLifecycle",
              "OIDC code exchange succeeded: accessTokenPresent=${tokenResponse.accessToken != null} idTokenPresent=${tokenResponse.idToken != null} refreshTokenPresent=${tokenResponse.refreshToken != null}")
          completeAuthorization(
              generation,
              Result.success(Unit),
              authorized = true,
              onRecoveredAuthorization = onRecoveredAuthorization)
        }
      }
      onFinished()
    }
  }

  open fun withFreshBearerToken(context: Context, onResult: (Result<String>) -> Unit) {
    val captured =
        synchronized(sessionLock) {
          if (!authState.isAuthorized) null else sessionGeneration to authState
        }
    if (captured == null) {
      TSLog.e("AuthLifecycle", "OIDC token refresh rejected: signed out")
      onResult(Result.failure(IllegalStateException("Signed out")))
      return
    }
    val (generation, state) = captured

    TSLog.d(
        "AuthLifecycle",
        "OIDC token refresh start: generation=$generation hasRefreshToken=${state.appAuthState.refreshToken != null}")
    appAuth.freshToken(context, state) { accessToken, exception ->
      var pendingCompletion: PendingCompletion? = null
      val result =
          synchronized(sessionLock) {
            if (generation != sessionGeneration) {
              TSLog.e(
                  "AuthLifecycle",
                  "OIDC token refresh rejected: stale session generation ($generation != $sessionGeneration)")
              Result.failure(authSessionChanged())
            } else if (exception != null || accessToken.isNullOrBlank()) {
              TSLog.e(
                  "AuthLifecycle",
                  "OIDC token refresh failed: error=${exception?.javaClass?.simpleName ?: "empty token"}")
              if (isInvalidOrRevokedCredential(exception)) {
                pendingCompletion = clearSessionLocked(AuthentikState.ReauthenticationRequired)
                appAuth.dispose()
              } else {
                persistLocked()
              }
              Result.failure(exception ?: IllegalStateException("Unable to refresh token"))
            } else {
              TSLog.d("AuthLifecycle", "OIDC token refresh succeeded: accessTokenPresent=true")
              persistLocked()
              _authentikState.value = AuthentikState.Authorized
              Result.success(accessToken)
            }
          }
      pendingCompletion?.callback?.invoke(Result.failure(authSessionChanged()))
      onResult(result)
    }
  }

  fun clearSession() {
    clearSession(AuthentikState.SignedOut)
  }

  fun requireReauthentication() {
    clearSession(AuthentikState.ReauthenticationRequired)
  }

  internal fun sanitizeUri(uri: Uri?): String? {
    if (uri == null) return null
    return try {
      uri.buildUpon().clearQuery().fragment(null).build().toString()
    } catch (_: Exception) {
      val scheme = uri.scheme
      val host = uri.host
      val path = uri.path.orEmpty()
      if (scheme != null && host != null) {
        "$scheme://$host$path"
      } else {
        uri.path ?: ""
      }
    }
  }

  fun hasFixedHeadscaleContinuation(): Boolean =
      synchronized(sessionLock) { transactionStorage.hasFixedHeadscaleContinuation() }

  fun ackFixedHeadscaleContinuation() =
      synchronized(sessionLock) { transactionStorage.ackFixedHeadscaleContinuation() }

  private fun clearSession(state: AuthentikState) {
    TSLog.d("AuthLifecycle", "OIDC clearSession: targetState=$state")
    val pendingCompletion =
        synchronized(sessionLock) { clearSessionLocked(state).also { appAuth.dispose() } }
    pendingCompletion?.callback?.invoke(Result.failure(authSessionChanged()))
  }

  private fun clearSessionLocked(state: AuthentikState): PendingCompletion? {
    sessionGeneration += 1
    authState = PersistedAuthSessionState(AuthState())
    storage.clear()
    transactionStorage.clear()
    _authentikState.value = state
    return completion.also { completion = null }
  }

  private fun completeAuthorization(
      generation: Long,
      result: Result<Unit>,
      authorized: Boolean,
      onRecoveredAuthorization: () -> Unit = {},
  ): Boolean {
    var callback: ((Result<Unit>) -> Unit)? = null
    var recovered = false
    val current =
        synchronized(sessionLock) {
          if (generation != sessionGeneration) return@synchronized false
          callback = completion?.takeIf { it.generation == generation }?.callback
          if (callback != null) completion = null
          _authentikState.value =
              if (authorized) AuthentikState.AuthorizedLoading else AuthentikState.SignedOut
          if (authorized && callback == null) {
            transactionStorage.markFixedHeadscaleContinuation()
            recovered = true
          }
          appAuth.dispose()
          true
        }
    if (!current) return false
    callback?.invoke(result)
    if (recovered) onRecoveredAuthorization()
    return true
  }

  fun markAuthorizationReady() {
    synchronized(sessionLock) {
      if (_authentikState.value == AuthentikState.AuthorizedLoading) {
        _authentikState.value = AuthentikState.Authorized
      }
    }
  }

  private fun persistLocked() {
    storage.write(authState.serialize())
  }

  private fun authSessionChanged(): IllegalStateException =
      IllegalStateException("Auth session changed")

  private fun isInvalidOrRevokedCredential(exception: AuthorizationException?): Boolean {
    return exception?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
        exception.error in setOf("invalid_grant", "invalid_token")
  }

  private fun authentikStateFor(state: AuthSessionState): AuthentikState =
      if (state.isAuthorized) AuthentikState.Authorized else AuthentikState.SignedOut
}

internal fun formatSafeDiscoveryDiagnostics(
    configuration: AuthorizationServiceConfiguration?,
    exception: AuthorizationException?,
): String {
  val errorUri = exception?.errorUri
  val errorUriHost = errorUri?.host?.ifEmpty { null }
  val errorUriPath = errorUri?.path?.ifEmpty { null }
  val errorUriFormatted =
      if (errorUriHost != null || errorUriPath != null) {
        "${errorUriHost.orEmpty()}${errorUriPath.orEmpty()}"
      } else {
        null
      }
  return "configurationNull=${configuration == null}" +
      " exceptionType=${exception?.type}" +
      " errorCode=${exception?.code}" +
      " oauthError=${exception?.error}" +
      " errorDescription=${exception?.errorDescription}" +
      " errorUriHost=${errorUriHost}" +
      " errorUriPath=${errorUriPath}" +
      " errorUri=${errorUriFormatted}"
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
  if (intent.action != AUTH_CALLBACK_ACTION) return false
  if (intent.getStringExtra(AUTH_TRANSACTION_STATE_EXTRA) != pendingRequest.state) return false
  if (response == null) return false
  return responseMatchesPendingRequest(response, pendingRequest)
}

private fun callbackMatchesCurrentTransaction(
    intent: Intent,
    result: AuthorizationResult,
    pendingRequest: AuthorizationRequest,
): Boolean {
  if (result.response != null && result.exception == null) {
    return intent.action == AUTH_CALLBACK_ACTION
  }
  return intent.action == AUTH_CANCEL_ACTION &&
      result.response == null &&
      isExplicitAuthorizationCancellation(result.exception) &&
      intent.getStringExtra(AUTH_TRANSACTION_STATE_EXTRA) == pendingRequest.state
}

private fun isExplicitAuthorizationCancellation(exception: AuthorizationException?): Boolean =
    exception?.type == AuthorizationException.TYPE_GENERAL_ERROR &&
        exception.code in
            setOf(
                AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code,
                AuthorizationException.GeneralErrors.PROGRAM_CANCELED_AUTH_FLOW.code)

private fun responseMatchesPendingRequest(
    response: AuthorizationResponse,
    pending: AuthorizationRequest,
): Boolean {
  val request = response.request
  return response.state == pending.state &&
      request.state == pending.state &&
      request.configuration.toJsonString() == pending.configuration.toJsonString() &&
      request.clientId == pending.clientId &&
      request.display == pending.display &&
      request.loginHint == pending.loginHint &&
      request.prompt == pending.prompt &&
      request.uiLocales == pending.uiLocales &&
      request.redirectUri == pending.redirectUri &&
      request.responseType == pending.responseType &&
      request.scope == pending.scope &&
      request.nonce == pending.nonce &&
      request.codeVerifier == pending.codeVerifier &&
      request.codeVerifierChallenge == pending.codeVerifierChallenge &&
      request.codeVerifierChallengeMethod == pending.codeVerifierChallengeMethod &&
      request.responseMode == pending.responseMode &&
      request.claims?.toString() == pending.claims?.toString() &&
      request.claimsLocales == pending.claimsLocales &&
      request.additionalParameters == pending.additionalParameters
}

internal class RealAppAuthGateway(
    private val issuerUri: Uri = Uri.parse(ProductConfig.authentikIssuerUrl),
    private val fetchConfiguration:
        (Uri, (AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit) -> Unit =
        { uri, callback ->
          AuthorizationServiceConfiguration.fetchFromIssuer(uri, callback)
        },
) : AppAuthGateway {
  private var authorizationService: AuthorizationService? = null

  override fun discover(
      callback: (AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit
  ) {
    fetchConfiguration(issuerUri) { configuration, exception ->
      if (configuration == null || exception != null) {
        TSLog.e(
            "AuthLifecycle",
            "OIDC gateway discover failed: " +
                formatSafeDiscoveryDiagnostics(configuration, exception))
      }
      callback(configuration, exception)
    }
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
    val service = AuthorizationService(context)
    service.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, exception ->
      service.dispose()
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
    synchronized(this) {
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
    synchronized(this) {
      preferences.edit().putBoolean(AUTH_FIXED_HEADSCALE_CONTINUATION_KEY, true).commit()
    }
  }

  override fun hasFixedHeadscaleContinuation(): Boolean =
      synchronized(this) { preferences.getBoolean(AUTH_FIXED_HEADSCALE_CONTINUATION_KEY, false) }

  override fun ackFixedHeadscaleContinuation() {
    synchronized(this) { preferences.edit().remove(AUTH_FIXED_HEADSCALE_CONTINUATION_KEY).commit() }
  }

  override fun clear() {
    synchronized(this) {
      preferences
          .edit()
          .remove(AUTH_PENDING_TRANSACTION_KEY)
          .remove(AUTH_FIXED_HEADSCALE_CONTINUATION_KEY)
          .commit()
    }
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

  override fun hasFixedHeadscaleContinuation(): Boolean = fixedHeadscaleContinuation

  override fun ackFixedHeadscaleContinuation() {
    fixedHeadscaleContinuation = false
  }

  override fun clear() {
    transaction = null
    fixedHeadscaleContinuation = false
  }
}
