// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Context
import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.product.policy.PolicyApiClient
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.view.ErrorDialogType
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.eq

@OptIn(ExperimentalCoroutinesApi::class)
class CustomLoginViewModelTest {
  private val dispatcher = StandardTestDispatcher()
  private lateinit var originalLogWrapper: LibtailscaleWrapper

  @Before
  fun setUp() {
    Dispatchers.setMain(dispatcher)
    originalLogWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper =
        mock<LibtailscaleWrapper>().also {
          doNothing().`when`(it).sendLog(anyString(), anyString())
        }
  }

  @After
  fun tearDown() {
    TSLog.libtailscaleWrapper = originalLogWrapper
    Dispatchers.resetMain()
  }

  private class FakeClient(scope: CoroutineScope) : Client(scope) {
    var editPrefsResult: Result<Ipn.Prefs> = Result.success(Ipn.Prefs())
    var startResult: Result<Unit> = Result.success(Unit)
    var startLoginInteractiveResult: Result<Unit> = Result.success(Unit)

    override fun editPrefs(prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
      responseHandler(editPrefsResult)
    }

    override fun start(options: Ipn.Options, responseHandler: (Result<Unit>) -> Unit) {
      responseHandler(startResult)
    }

    override fun startLoginInteractive(responseHandler: (Result<Unit>) -> Unit) {
      responseHandler(startLoginInteractiveResult)
    }
  }

  private class FakeHttpURLConnection(private val status: Int, body: String) :
      HttpURLConnection(URL("https://policy.invalid/v1/node-auth-key")) {
    private val response = body.toByteArray()

    override fun connect() {}

    override fun disconnect() {}

    override fun getInputStream() = ByteArrayInputStream(response)

    override fun getResponseCode(): Int = status

    override fun usingProxy(): Boolean = false
  }

  @Test
  fun loginWithAuthKeyEmptyKeySetsInvalidKeyErrorAndDoesNotStartVpn() = runTest {
    var vpnStarts = 0
    var successes = 0
    val viewModel =
        LoginWithAuthKeyViewModel(
            observeUserProfiles = false,
            clientProvider = { FakeClient(it) },
            vpnStarter = { vpnStarts++ },
        )

    viewModel.setAuthKey("") { successes++ }

    assertEquals(ErrorDialogType.INVALID_AUTH_KEY, viewModel.errorDialog.value)
    assertEquals(0, vpnStarts)
    assertEquals(0, successes)
  }

  @Test
  fun loginWithAuthKeyFailureSetsAddProfileFailedAndDoesNotStartVpn() = runTest {
    var vpnStarts = 0
    var successes = 0
    val viewModel =
        LoginWithAuthKeyViewModel(
            observeUserProfiles = false,
            clientProvider = { scope ->
              FakeClient(scope).apply {
                startLoginInteractiveResult =
                    Result.failure(IllegalStateException("login rejected"))
              }
            },
            vpnStarter = { vpnStarts++ },
        )

    viewModel.setAuthKey("tskey-auth-sample123") { successes++ }

    assertEquals(ErrorDialogType.ADD_PROFILE_FAILED, viewModel.errorDialog.value)
    assertEquals(0, vpnStarts)
    assertEquals(0, successes)
  }

  @Test
  fun loginWithAuthKeySuccessStartsVpnAndInvokesOnSuccess() = runTest {
    var vpnStarts = 0
    var successes = 0
    val viewModel =
        LoginWithAuthKeyViewModel(
            observeUserProfiles = false,
            clientProvider = { FakeClient(it) },
            vpnStarter = { vpnStarts++ },
        )

    viewModel.setAuthKey("tskey-auth-sample123") { successes++ }

    assertNull(viewModel.errorDialog.value)
    assertEquals(1, vpnStarts)
    assertEquals(1, successes)
  }

  @Test
  fun loginWithCustomControlURLAuthorizationFailureSetsErrorAndDoesNotStartVpn() = runTest {
    val authSession = mock<AuthSessionRepository>()
    val context = mock<Context>()
    doAnswer { invocation ->
          val callback = invocation.getArgument<(Result<Unit>) -> Unit>(1)
          callback(Result.failure(IllegalStateException("auth failed")))
          null
        }
        .`when`(authSession)
        .startAuthorization(eq(context), any())

    var vpnStarts = 0
    var successes = 0
    val viewModel =
        LoginWithCustomControlURLViewModel(
            authSessionRepository = authSession,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            observeUserProfiles = false,
            clientProvider = { FakeClient(it) },
            vpnStarter = { vpnStarts++ },
        )

    viewModel.setControlURL(context) { successes++ }
    advanceUntilIdle()

    assertEquals(ErrorDialogType.ADD_PROFILE_FAILED, viewModel.errorDialog.value)
    assertEquals(0, vpnStarts)
    assertEquals(0, successes)
  }

  @Test
  fun loginWithCustomControlURLTokenFailureSetsErrorAndDoesNotStartVpn() = runTest {
    val authSession = mock<AuthSessionRepository>()
    val context = mock<Context>()
    doAnswer { invocation ->
          val callback = invocation.getArgument<(Result<Unit>) -> Unit>(1)
          callback(Result.success(Unit))
          null
        }
        .`when`(authSession)
        .startAuthorization(eq(context), any())

    doAnswer { invocation ->
          val callback = invocation.getArgument<(Result<String>) -> Unit>(1)
          callback(Result.failure(IllegalStateException("token expired")))
          null
        }
        .`when`(authSession)
        .withFreshBearerToken(eq(context), any())

    var vpnStarts = 0
    var successes = 0
    val viewModel =
        LoginWithCustomControlURLViewModel(
            authSessionRepository = authSession,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            observeUserProfiles = false,
            clientProvider = { FakeClient(it) },
            vpnStarter = { vpnStarts++ },
        )

    viewModel.setControlURL(context) { successes++ }
    advanceUntilIdle()

    assertEquals(ErrorDialogType.ADD_PROFILE_FAILED, viewModel.errorDialog.value)
    assertEquals(0, vpnStarts)
    assertEquals(0, successes)
  }

  @Test
  fun loginWithCustomControlURLUnauthorizedKeyFetchRequiresReauthAndDoesNotStartVpn() = runTest {
    val authSession = mock<AuthSessionRepository>()
    val policyApiClient =
        PolicyApiClient(
            baseUrl = "https://policy.invalid",
            connectionFactory = { FakeHttpURLConnection(401, "") },
        )
    val context = mock<Context>()

    doAnswer { invocation ->
          val callback = invocation.getArgument<(Result<Unit>) -> Unit>(1)
          callback(Result.success(Unit))
          null
        }
        .`when`(authSession)
        .startAuthorization(eq(context), any())

    doAnswer { invocation ->
          val callback = invocation.getArgument<(Result<String>) -> Unit>(1)
          callback(Result.success("fresh-token"))
          null
        }
        .`when`(authSession)
        .withFreshBearerToken(eq(context), any())

    var vpnStarts = 0
    var successes = 0
    val viewModel =
        LoginWithCustomControlURLViewModel(
            authSessionRepository = authSession,
            policyApiClient = policyApiClient,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            observeUserProfiles = false,
            clientProvider = { FakeClient(it) },
            vpnStarter = { vpnStarts++ },
        )

    viewModel.setControlURL(context) { successes++ }
    advanceUntilIdle()

    verify(authSession).requireReauthentication()
    assertEquals(ErrorDialogType.ADD_PROFILE_FAILED, viewModel.errorDialog.value)
    assertEquals(0, vpnStarts)
    assertEquals(0, successes)
  }

  @Test
  fun loginWithCustomControlURLSuccessStartsVpnAndInvokesOnSuccess() = runTest {
    val authSession = mock<AuthSessionRepository>()
    val policyApiClient =
        PolicyApiClient(
            baseUrl = "https://policy.invalid",
            connectionFactory = {
              FakeHttpURLConnection(200, "{\"authKey\":\"tskey-auth-generated-12345\"}")
            },
        )
    val context = mock<Context>()

    doAnswer { invocation ->
          val callback = invocation.getArgument<(Result<Unit>) -> Unit>(1)
          callback(Result.success(Unit))
          null
        }
        .`when`(authSession)
        .startAuthorization(eq(context), any())

    doAnswer { invocation ->
          val callback = invocation.getArgument<(Result<String>) -> Unit>(1)
          callback(Result.success("fresh-token"))
          null
        }
        .`when`(authSession)
        .withFreshBearerToken(eq(context), any())

    var vpnStarts = 0
    var successes = 0
    val viewModel =
        LoginWithCustomControlURLViewModel(
            authSessionRepository = authSession,
            policyApiClient = policyApiClient,
            ioDispatcher = dispatcher,
            mainDispatcher = dispatcher,
            observeUserProfiles = false,
            clientProvider = { FakeClient(it) },
            vpnStarter = { vpnStarts++ },
        )

    viewModel.setControlURL(context) { successes++ }
    advanceUntilIdle()

    assertNull(viewModel.errorDialog.value)
    assertEquals(1, vpnStarts)
    assertEquals(1, successes)
  }
}
