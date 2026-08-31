// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AuthSessionRepositoryTest {
  private val context = mock<Context>()
  private val intent = callbackIntent("com.stardom.vpn.AUTH_CALLBACK")

  @Test
  fun discoveryFailureReturnsErrorWithoutStartingAuthorization() {
    val gateway =
        FakeAppAuthGateway(discoveryException = AuthorizationException.GeneralErrors.NETWORK_ERROR)
    val completion = mutableListOf<Result<Unit>>()
    val repository = AuthSessionRepository(InMemoryAuthStateStorage(), FakeSessionState(), gateway)

    repository.startAuthorization(context, completion::add)

    assertEquals(1, completion.size)
    assertTrue(completion.single().isFailure)
    assertEquals(0, gateway.authorizationStarts)
  }

  @Test
  fun persistedAuthorizedSessionStartsInAuthorizedState() {
    val repository =
        AuthSessionRepository(
            InMemoryAuthStateStorage("state"),
            FakeSessionState(isAuthorized = true),
            FakeAppAuthGateway())

    assertEquals(AuthentikState.Authorized, repository.authentikState.value)
  }

  @Test
  fun authorizationStartsByEmittingAuthorizing() {
    val gateway = FakeAppAuthGateway(deferDiscovery = true)
    val repository = AuthSessionRepository(InMemoryAuthStateStorage(), FakeSessionState(), gateway)

    repository.startAuthorization(context) {}

    assertEquals(AuthentikState.Authorizing, repository.authentikState.value)
  }

  @Test
  fun authorizationCodeExchangePersistsStateAndCompletesLogin() {
    val storage = InMemoryAuthStateStorage()
    val state = FakeSessionState()
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>())
    val completion = mutableListOf<Result<Unit>>()
    val repository = AuthSessionRepository(storage, state, gateway)

    repository.startAuthorization(context, completion::add)
    repository.handleAuthorizationIntent(context, intent)

    assertEquals(1, gateway.authorizationStarts)
    assertEquals(1, state.authorizationUpdates)
    assertEquals(1, state.tokenUpdates)
    assertEquals(listOf("state", "state"), storage.writes)
    assertTrue(completion.single().isSuccess)
    assertEquals(AuthentikState.Authorized, repository.authentikState.value)
  }

  @Test
  fun recoveredAuthorizationNotifiesCallerWhenOriginalCompletionIsGone() {
    val state = FakeSessionState()
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>())
    val transactions = InMemoryAuthorizationTransactionStorage()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 0))
    var recovered = 0
    val repository =
        AuthSessionRepository(InMemoryAuthStateStorage(), state, gateway, transactions) { 0 }

    repository.handleAuthorizationIntent(context, intent) { recovered++ }

    assertEquals(1, recovered)
    assertEquals(1, state.tokenUpdates)
  }

  @Test
  fun callbackWithoutAPendingTransactionDoesNotExchangeAuthorizationCode() {
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>())
    val repository = AuthSessionRepository(InMemoryAuthStateStorage(), FakeSessionState(), gateway)

    repository.handleAuthorizationIntent(context, intent)

    assertEquals(0, gateway.codeExchanges)
  }

  @Test
  fun duplicateCallbackExchangesCodeOnlyOnce() {
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>())
    val transactions = InMemoryAuthorizationTransactionStorage()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 1))
    val repository =
        AuthSessionRepository(
            InMemoryAuthStateStorage(), FakeSessionState(), gateway, transactions) {
              1
            }

    repository.handleAuthorizationIntent(context, intent)
    repository.handleAuthorizationIntent(context, intent)

    assertEquals(1, gateway.codeExchanges)
  }

  @Test
  fun mismatchedCallbackDoesNotConsumeCurrentTransaction() {
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>(),
            matchesPendingAuthorization = false)
    val transactions = InMemoryAuthorizationTransactionStorage()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 1))
    val repository =
        AuthSessionRepository(
            InMemoryAuthStateStorage(), FakeSessionState(), gateway, transactions) {
              1
            }

    repository.handleAuthorizationIntent(context, intent)
    gateway.matchesPendingAuthorization = true
    repository.handleAuthorizationIntent(context, intent)

    assertEquals(1, gateway.codeExchanges)
  }

  @Test
  fun callbackWithUnexpectedActionDoesNotConsumeOrMutateCurrentTransaction() {
    val state = FakeSessionState()
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>())
    val transactions = InMemoryAuthorizationTransactionStorage()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 1))
    val repository =
        AuthSessionRepository(InMemoryAuthStateStorage(), state, gateway, transactions) { 1 }

    repository.handleAuthorizationIntent(context, callbackIntent("unexpected-callback-action"))

    assertEquals(0, state.authorizationUpdates)
    assertEquals(0, state.tokenUpdates)
    assertEquals(0, gateway.codeExchanges)

    repository.handleAuthorizationIntent(context, intent)

    assertEquals(1, gateway.codeExchanges)
  }

  @Test
  fun stateMismatchErrorDoesNotConsumeOrMutateCurrentTransaction() {
    val state = FakeSessionState()
    val gateway =
        FakeAppAuthGateway(
            authorizationResult =
                AuthorizationResult(
                    null, AuthorizationException.AuthorizationRequestErrors.STATE_MISMATCH),
            tokenResponse = mock<TokenResponse>())
    val transactions = InMemoryAuthorizationTransactionStorage()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 1))
    val repository =
        AuthSessionRepository(InMemoryAuthStateStorage(), state, gateway, transactions) { 1 }

    repository.handleAuthorizationIntent(context, intent)

    assertEquals(0, state.authorizationUpdates)
    assertEquals(0, state.tokenUpdates)
    assertEquals(0, gateway.codeExchanges)

    gateway.authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null)
    repository.handleAuthorizationIntent(context, intent)

    assertEquals(1, gateway.codeExchanges)
  }

  @Test
  fun explicitCancelConsumesCurrentTransactionAndFinishesAuthorization() {
    val state = FakeSessionState()
    val gateway =
        FakeAppAuthGateway(
            authorizationResult =
                AuthorizationResult(
                    null, AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW))
    val transactions = InMemoryAuthorizationTransactionStorage()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 1))
    val repository =
        AuthSessionRepository(InMemoryAuthStateStorage(), state, gateway, transactions) { 1 }

    repository.handleAuthorizationIntent(
        context, callbackIntent("com.stardom.vpn.AUTH_CANCELLED"), onFinished = {})

    assertEquals(1, state.authorizationUpdates)
    assertEquals(AuthentikState.SignedOut, repository.authentikState.value)
    assertEquals(0, gateway.codeExchanges)
    gateway.authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null)
    repository.handleAuthorizationIntent(context, intent)

    assertEquals(0, gateway.codeExchanges)
  }

  @Test
  fun staleCallbackDoesNotExchangeAuthorizationCode() {
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>())
    val transactions = InMemoryAuthorizationTransactionStorage()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 0))
    val repository =
        AuthSessionRepository(
            InMemoryAuthStateStorage(), FakeSessionState(), gateway, transactions) {
              5 * 60 * 1000L + 1
            }

    repository.handleAuthorizationIntent(context, intent)

    assertEquals(0, gateway.codeExchanges)
  }

  @Test
  fun recoveredCallbackRecordsRetryableFixedHeadscaleContinuation() {
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>())
    val transactions = InMemoryAuthorizationTransactionStorage()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 1))
    val repository =
        AuthSessionRepository(
            InMemoryAuthStateStorage(), FakeSessionState(), gateway, transactions) {
              1
            }

    repository.handleAuthorizationIntent(context, intent)

    assertTrue(repository.hasFixedHeadscaleContinuation())
    assertTrue(repository.hasFixedHeadscaleContinuation())
    repository.ackFixedHeadscaleContinuation()
    assertEquals(false, repository.hasFixedHeadscaleContinuation())
  }

  @Test
  fun transientRefreshFailurePreservesPersistedSession() {
    val storage = InMemoryAuthStateStorage("state")
    val gateway =
        FakeAppAuthGateway(freshException = AuthorizationException.GeneralErrors.NETWORK_ERROR)
    val completion = mutableListOf<Result<String>>()
    val repository = AuthSessionRepository(storage, FakeSessionState(isAuthorized = true), gateway)

    repository.withFreshBearerToken(context, completion::add)

    assertTrue(completion.single().isFailure)
    assertEquals("state", storage.read())
    assertEquals(0, storage.clearCalls)
    assertEquals(AuthentikState.Authorized, repository.authentikState.value)
  }

  @Test
  fun invalidGrantRefreshFailureClearsPersistedSession() {
    val storage = InMemoryAuthStateStorage("state")
    val gateway =
        FakeAppAuthGateway(freshException = AuthorizationException.TokenRequestErrors.INVALID_GRANT)
    val completion = mutableListOf<Result<String>>()
    val repository = AuthSessionRepository(storage, FakeSessionState(isAuthorized = true), gateway)

    repository.withFreshBearerToken(context, completion::add)

    assertTrue(completion.single().isFailure)
    assertNull(storage.read())
    assertEquals(1, storage.clearCalls)
    assertEquals(AuthentikState.ReauthenticationRequired, repository.authentikState.value)
  }

  @Test
  fun invalidTokenRefreshFailureRequiresReauthentication() {
    val storage = InMemoryAuthStateStorage("state")
    val gateway =
        FakeAppAuthGateway(
            freshException =
                AuthorizationException(
                    AuthorizationException.TYPE_OAUTH_TOKEN_ERROR,
                    0,
                    "invalid_token",
                    null,
                    null,
                    null))
    val completion = mutableListOf<Result<String>>()
    val repository = AuthSessionRepository(storage, FakeSessionState(isAuthorized = true), gateway)

    repository.withFreshBearerToken(context, completion::add)

    assertTrue(completion.single().isFailure)
    assertNull(storage.read())
    assertEquals(AuthentikState.ReauthenticationRequired, repository.authentikState.value)
  }

  @Test
  fun explicitClearEmitsSignedOut() {
    val repository =
        AuthSessionRepository(
            InMemoryAuthStateStorage("state"),
            FakeSessionState(isAuthorized = true),
            FakeAppAuthGateway())

    repository.clearSession()

    assertEquals(AuthentikState.SignedOut, repository.authentikState.value)
  }

  @Test
  fun authorizationCallbackIsOneShotAndMutableOnAndroid12AndLater() {
    val flags = callbackPendingIntentFlags(Build.VERSION_CODES.S)

    assertTrue(flags and PendingIntent.FLAG_ONE_SHOT != 0)
    assertTrue(flags and PendingIntent.FLAG_MUTABLE != 0)
    assertEquals(0, flags and PendingIntent.FLAG_UPDATE_CURRENT)
  }

  @Test
  fun authorizationCallbackRemainsCompatibleBeforeAndroid12() {
    val flags = callbackPendingIntentFlags(Build.VERSION_CODES.R)

    assertTrue(flags and PendingIntent.FLAG_ONE_SHOT != 0)
    assertEquals(0, flags and PendingIntent.FLAG_MUTABLE)
  }

  @Test
  fun clearDuringDiscoveryDoesNotOpenAuthorization() {
    val gateway = FakeAppAuthGateway(deferDiscovery = true)
    val completion = mutableListOf<Result<Unit>>()
    val repository = AuthSessionRepository(InMemoryAuthStateStorage(), FakeSessionState(), gateway)

    repository.startAuthorization(context, completion::add)
    repository.clearSession()
    gateway.completeDiscovery()

    assertEquals(0, gateway.authorizationStarts)
    assertEquals(1, completion.size)
    assertTrue(completion.single().isFailure)
    assertEquals(AuthentikState.SignedOut, repository.authentikState.value)
  }

  @Test
  fun clearDuringCodeExchangeDoesNotRestoreAuthorizedSession() {
    val storage = InMemoryAuthStateStorage()
    val state = FakeSessionState()
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>(),
            deferCodeExchange = true)
    val completion = mutableListOf<Result<Unit>>()
    var finished = 0
    val repository = AuthSessionRepository(storage, state, gateway)

    repository.startAuthorization(context, completion::add)
    repository.handleAuthorizationIntent(context, intent, onFinished = { finished++ })
    repository.clearSession()
    gateway.completeCodeExchange()

    assertEquals(1, state.authorizationUpdates)
    assertEquals(0, state.tokenUpdates)
    assertEquals(listOf("state"), storage.writes)
    assertNull(storage.read())
    assertEquals(1, completion.size)
    assertTrue(completion.single().isFailure)
    assertEquals(1, finished)
    assertEquals(AuthentikState.SignedOut, repository.authentikState.value)
  }

  @Test
  fun clearDuringFreshTokenDoesNotRestoreAuthorizedSession() {
    val storage = InMemoryAuthStateStorage("state")
    val gateway = FakeAppAuthGateway(freshToken = "token", deferFreshToken = true)
    val completion = mutableListOf<Result<String>>()
    val repository = AuthSessionRepository(storage, FakeSessionState(isAuthorized = true), gateway)

    repository.withFreshBearerToken(context, completion::add)
    repository.clearSession()
    gateway.completeFreshToken()

    assertEquals(1, completion.size)
    assertTrue(completion.single().isFailure)
    assertEquals("Auth session changed", completion.single().exceptionOrNull()?.message)
    assertTrue(storage.writes.isEmpty())
    assertNull(storage.read())
    assertEquals(AuthentikState.SignedOut, repository.authentikState.value)
  }

  @Test
  fun replacementAuthorizationSurvivesOldDeferredCodeExchange() {
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>(),
            deferCodeExchange = true)
    val firstCompletion = mutableListOf<Result<Unit>>()
    val repository = AuthSessionRepository(InMemoryAuthStateStorage(), FakeSessionState(), gateway)

    repository.startAuthorization(context, firstCompletion::add)
    repository.handleAuthorizationIntent(context, intent)
    repository.startAuthorization(context) {}
    gateway.completeCodeExchange()

    assertEquals(1, firstCompletion.size)
    assertEquals("Auth session changed", firstCompletion.single().exceptionOrNull()?.message)
    assertEquals(2, gateway.authorizationStarts)
    assertTrue(gateway.isAuthorizationActive)
    assertEquals(2, gateway.disposeCalls)
  }

  @Test
  fun replacementStartFailsPriorCompletionAndClearsPendingState() {
    val gateway = FakeAppAuthGateway(deferDiscovery = true)
    val transactions = InMemoryAuthorizationTransactionStorage()
    val firstCompletion = mutableListOf<Result<Unit>>()
    val repository =
        AuthSessionRepository(
            InMemoryAuthStateStorage(), FakeSessionState(), gateway, transactions) {
              1
            }

    repository.startAuthorization(context, firstCompletion::add)
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 1))
    transactions.markFixedHeadscaleContinuation()
    repository.startAuthorization(context) {}

    assertEquals(1, firstCompletion.size)
    assertEquals("Auth session changed", firstCompletion.single().exceptionOrNull()?.message)
    assertFalse(transactions.consumeIf { true })
    assertFalse(repository.hasFixedHeadscaleContinuation())

    gateway.completeDiscovery()
    assertEquals(0, gateway.authorizationStarts)
    gateway.completeDiscovery()
    assertEquals(1, gateway.authorizationStarts)
  }

  @Test
  fun requireReauthenticationInvalidatesDeferredCodeExchange() {
    val state = FakeSessionState()
    val gateway =
        FakeAppAuthGateway(
            authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
            tokenResponse = mock<TokenResponse>(),
            deferCodeExchange = true)
    val completion = mutableListOf<Result<Unit>>()
    var finished = 0
    val repository = AuthSessionRepository(InMemoryAuthStateStorage(), state, gateway)

    repository.startAuthorization(context, completion::add)
    repository.handleAuthorizationIntent(context, intent, onFinished = { finished++ })
    repository.requireReauthentication()
    gateway.completeCodeExchange()

    assertEquals(1, completion.size)
    assertEquals("Auth session changed", completion.single().exceptionOrNull()?.message)
    assertEquals(0, state.tokenUpdates)
    assertEquals(1, finished)
    assertFalse(gateway.isAuthorizationActive)
    assertEquals(AuthentikState.ReauthenticationRequired, repository.authentikState.value)
  }

  @Test
  fun clearRemovesPendingTransactionAndContinuation() {
    val transactions = InMemoryAuthorizationTransactionStorage()
    val gateway = FakeAppAuthGateway()
    transactions.write(PendingAuthorizationTransaction(gateway.authorizationRequest, 1))
    transactions.markFixedHeadscaleContinuation()
    val repository =
        AuthSessionRepository(
            InMemoryAuthStateStorage(), FakeSessionState(), gateway, transactions) {
              1
            }

    repository.clearSession()

    assertFalse(transactions.consumeIf { true })
    assertFalse(repository.hasFixedHeadscaleContinuation())
  }

  @Test
  fun callbackUriQueryAndFragmentAreStrippedFromLogs() {
    val loggedMessages = mutableListOf<String>()
    val originalLog = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper =
        mock<LibtailscaleWrapper>().also {
          whenever(it.sendLog(anyString(), anyString())).thenAnswer { invocation ->
            val msg = invocation.getArgument<String>(1)
            loggedMessages.add(msg)
            null
          }
        }
    try {
      val secretAuthCode = "super-secret-auth-code-12345"
      val secretState = "secret-state-67890"
      val mockBuilder = mock<Uri.Builder>()
      val sanitizedUri = mock<Uri>()
      whenever(sanitizedUri.toString()).thenReturn("stardom://auth/callback")
      whenever(mockBuilder.clearQuery()).thenReturn(mockBuilder)
      whenever(mockBuilder.fragment(null)).thenReturn(mockBuilder)
      whenever(mockBuilder.build()).thenReturn(sanitizedUri)

      val rawUri = mock<Uri>()
      whenever(rawUri.toString())
          .thenReturn(
              "stardom://auth/callback?code=$secretAuthCode&state=$secretState#secret-fragment")
      whenever(rawUri.buildUpon()).thenReturn(mockBuilder)
      whenever(rawUri.scheme).thenReturn("stardom")
      whenever(rawUri.host).thenReturn("auth")
      whenever(rawUri.path).thenReturn("/callback")

      val intentWithSecret =
          mock<Intent>().also {
            whenever(it.action).thenReturn("com.stardom.vpn.AUTH_CALLBACK")
            whenever(it.data).thenReturn(rawUri)
            whenever(it.getStringExtra(AUTH_TRANSACTION_STATE_EXTRA)).thenReturn("state-extra")
          }
      val repository =
          AuthSessionRepository(
              InMemoryAuthStateStorage(), FakeSessionState(), FakeAppAuthGateway())
      repository.handleAuthorizationIntent(context, intentWithSecret)

      val callbackLog = loggedMessages.firstOrNull { it.contains("OIDC callback received") }
      assertNotNull("Expected OIDC callback log", callbackLog)
      assertTrue("Expected callback operation metadata", callbackLog!!.contains("hasData=true"))
      assertFalse("Log must not contain secret auth code", callbackLog.contains(secretAuthCode))
      assertFalse("Log must not contain query state", callbackLog.contains(secretState))
      assertFalse("Log must not contain fragment", callbackLog.contains("secret-fragment"))
    } finally {
      TSLog.libtailscaleWrapper = originalLog
    }
  }

  @Test
  fun codeExchangeAccessTokenLogsOnlyPresenceAndLength() {
    val loggedMessages = mutableListOf<String>()
    val originalLog = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper =
        mock<LibtailscaleWrapper>().also {
          whenever(it.sendLog(anyString(), anyString())).thenAnswer { invocation ->
            val msg = invocation.getArgument<String>(1)
            loggedMessages.add(msg)
            null
          }
        }
    try {
      val secretToken = "super-secret-oidc-access-token-987654321"
      val tokenResponse = mock<TokenResponse>()
      val field = TokenResponse::class.java.getDeclaredField("accessToken")
      field.isAccessible = true
      field.set(tokenResponse, secretToken)
      val gateway =
          FakeAppAuthGateway(
              authorizationResult = AuthorizationResult(mock<AuthorizationResponse>(), null),
              tokenResponse = tokenResponse)
      val repository =
          AuthSessionRepository(InMemoryAuthStateStorage(), FakeSessionState(), gateway)
      repository.startAuthorization(context) {}
      repository.handleAuthorizationIntent(context, intent)

      val exchangeLog = loggedMessages.firstOrNull { it.contains("OIDC code exchange succeeded") }
      assertNotNull("Expected code exchange log", exchangeLog)
      assertTrue(
          "Expected token presence metadata", exchangeLog!!.contains("accessTokenPresent=true"))
      assertFalse("Log must not contain secret token", exchangeLog.contains(secretToken))
      assertFalse("Log must not contain token prefix", exchangeLog.contains("supe"))
      assertFalse("Log must not contain token suffix", exchangeLog.contains("4321"))
    } finally {
      TSLog.libtailscaleWrapper = originalLog
    }
  }

  @Test
  fun tokenRefreshAccessTokenLogsOnlyPresenceAndLength() {
    val loggedMessages = mutableListOf<String>()
    val originalLog = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper =
        mock<LibtailscaleWrapper>().also {
          whenever(it.sendLog(anyString(), anyString())).thenAnswer { invocation ->
            val msg = invocation.getArgument<String>(1)
            loggedMessages.add(msg)
            null
          }
        }
    try {
      val secretToken = "xyzsecret_bearer_token_11223344"
      val gateway = FakeAppAuthGateway(freshToken = secretToken)
      val repository =
          AuthSessionRepository(
              InMemoryAuthStateStorage("state"), FakeSessionState(isAuthorized = true), gateway)
      repository.withFreshBearerToken(context) {}

      val refreshLog = loggedMessages.firstOrNull { it.contains("OIDC token refresh succeeded") }
      assertNotNull("Expected token refresh log", refreshLog)
      assertTrue(
          "Expected token presence metadata", refreshLog!!.contains("accessTokenPresent=true"))
      assertFalse("Log must not contain secret token", refreshLog.contains(secretToken))
      assertFalse("Log must not contain token prefix", refreshLog.contains("xyzsecret"))
      assertFalse("Log must not contain token suffix", refreshLog.contains("3344"))
    } finally {
      TSLog.libtailscaleWrapper = originalLog
    }
  }
}

private fun callbackIntent(action: String): Intent =
    mock<Intent>().also { whenever(it.action).thenReturn(action) }

internal class FakeAppAuthGateway(
    private val discoveryException: AuthorizationException? = null,
    var authorizationResult: AuthorizationResult? = null,
    private val tokenResponse: TokenResponse? = null,
    private val freshToken: String? = null,
    private val freshException: AuthorizationException? = null,
    private val deferDiscovery: Boolean = false,
    private val deferCodeExchange: Boolean = false,
    private val deferFreshToken: Boolean = false,
    var matchesPendingAuthorization: Boolean = true,
) : AppAuthGateway {
  var authorizationStarts = 0
  var codeExchanges = 0
  var disposeCalls = 0
  var isAuthorizationActive = false
  val authorizationRequest = mock<AuthorizationRequest>()
  private val validAuthorizationResponse = mock<AuthorizationResponse>()
  private val deferredDiscoveries =
      mutableListOf<(AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit>()
  private var deferredCodeExchange: ((TokenResponse?, AuthorizationException?) -> Unit)? = null
  private var deferredFreshToken: ((String?, AuthorizationException?) -> Unit)? = null

  override fun discover(
      callback: (AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit
  ) {
    if (deferDiscovery) {
      deferredDiscoveries += callback
    } else {
      callback(if (discoveryException == null) mock() else null, discoveryException)
    }
  }

  fun completeDiscovery() {
    val callback = deferredDiscoveries.removeAt(0)
    callback(if (discoveryException == null) mock() else null, discoveryException)
  }

  override fun createAuthorizationRequest(
      configuration: AuthorizationServiceConfiguration
  ): AuthorizationRequest = authorizationRequest

  override fun startAuthorization(context: Context, request: AuthorizationRequest) {
    authorizationStarts++
    isAuthorizationActive = true
  }

  override fun authorizationResult(intent: Intent): AuthorizationResult? =
      authorizationResult?.let { result ->
        AuthorizationResult(result.response?.let { validAuthorizationResponse }, result.exception)
      }

  override fun matchesPendingAuthorization(
      intent: Intent,
      response: AuthorizationResponse?,
      pendingRequest: AuthorizationRequest,
  ): Boolean = matchesPendingAuthorization

  override fun exchangeCode(
      context: Context,
      response: AuthorizationResponse,
      callback: (TokenResponse?, AuthorizationException?) -> Unit
  ) {
    codeExchanges++
    if (deferCodeExchange) {
      deferredCodeExchange = callback
    } else {
      callback(tokenResponse, null)
    }
  }

  fun completeCodeExchange() {
    val callback = checkNotNull(deferredCodeExchange)
    deferredCodeExchange = null
    callback(tokenResponse, null)
  }

  override fun freshToken(
      context: Context,
      state: AuthSessionState,
      callback: (String?, AuthorizationException?) -> Unit
  ) {
    if (deferFreshToken) {
      deferredFreshToken = callback
    } else {
      callback(freshToken, freshException)
    }
  }

  fun completeFreshToken() {
    val callback = checkNotNull(deferredFreshToken)
    deferredFreshToken = null
    callback(freshToken, freshException)
  }

  override fun dispose() {
    disposeCalls++
    isAuthorizationActive = false
  }
}

internal class FakeSessionState(override val isAuthorized: Boolean = false) : AuthSessionState {
  override val appAuthState: AuthState = mock()
  var authorizationUpdates = 0
  var tokenUpdates = 0

  override fun updateAuthorization(
      response: AuthorizationResponse?,
      exception: AuthorizationException?
  ) {
    authorizationUpdates++
  }

  override fun updateToken(response: TokenResponse?, exception: AuthorizationException?) {
    tokenUpdates++
  }

  override fun serialize(): String = "state"
}

internal class InMemoryAuthStateStorage(private var value: String? = null) : AuthStateStorage {
  val writes = mutableListOf<String>()
  var clearCalls = 0

  override fun read(): String? = value

  override fun write(value: String) {
    this.value = value
    writes += value
  }

  override fun clear() {
    value = null
    clearCalls++
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
