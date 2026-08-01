// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.TokenResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
  fun recoveredCallbackRecordsOneShotFixedHeadscaleContinuation() {
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

    assertTrue(repository.consumeFixedHeadscaleContinuation())
    assertEquals(false, repository.consumeFixedHeadscaleContinuation())
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
}

private fun callbackIntent(action: String): Intent =
    mock<Intent>().also { whenever(it.action).thenReturn(action) }

private class FakeAppAuthGateway(
    private val discoveryException: AuthorizationException? = null,
    var authorizationResult: AuthorizationResult? = null,
    private val tokenResponse: TokenResponse? = null,
    private val freshToken: String? = null,
    private val freshException: AuthorizationException? = null,
    private val deferDiscovery: Boolean = false,
    var matchesPendingAuthorization: Boolean = true,
) : AppAuthGateway {
  var authorizationStarts = 0
  var codeExchanges = 0
  val authorizationRequest = mock<AuthorizationRequest>()
  private val validAuthorizationResponse = mock<AuthorizationResponse>()

  override fun discover(
      callback: (AuthorizationServiceConfiguration?, AuthorizationException?) -> Unit
  ) {
    if (!deferDiscovery) {
      callback(if (discoveryException == null) mock() else null, discoveryException)
    }
  }

  override fun createAuthorizationRequest(
      configuration: AuthorizationServiceConfiguration
  ): AuthorizationRequest = authorizationRequest

  override fun startAuthorization(context: Context, request: AuthorizationRequest) {
    authorizationStarts++
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
    callback(tokenResponse, null)
  }

  override fun freshToken(
      context: Context,
      state: AuthSessionState,
      callback: (String?, AuthorizationException?) -> Unit
  ) {
    callback(freshToken, freshException)
  }

  override fun dispose() {}
}

private class FakeSessionState(override val isAuthorized: Boolean = false) : AuthSessionState {
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

private class InMemoryAuthStateStorage(private var value: String? = null) : AuthStateStorage {
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

  override fun consumeFixedHeadscaleContinuation(): Boolean =
      fixedHeadscaleContinuation.also { fixedHeadscaleContinuation = false }
}
