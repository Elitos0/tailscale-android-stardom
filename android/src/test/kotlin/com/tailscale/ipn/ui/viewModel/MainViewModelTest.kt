package com.tailscale.ipn.ui.viewModel

import android.content.Context
import android.content.Intent
import com.tailscale.ipn.R
import com.tailscale.ipn.product.StardomSessionController
import com.tailscale.ipn.product.auth.AppAuthGateway
import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.product.auth.AuthSessionState
import com.tailscale.ipn.product.auth.AuthStateStorage
import com.tailscale.ipn.product.auth.AuthorizationResult
import com.tailscale.ipn.product.policy.AccessRepository
import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.product.policy.PolicyApiClient
import com.tailscale.ipn.product.policy.VpnEntitlementController
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnLocal
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
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

  private fun createAppViewModelMock(): AppViewModel {
    val appViewModel = mock<AppViewModel>()
    `when`(appViewModel.vpnActive).thenReturn(MutableStateFlow(false))
    `when`(appViewModel.vpnPrepared).thenReturn(MutableStateFlow(true))
    return appViewModel
  }

  @Test
  fun toggleStateRemainsOffWhenVpnIsInactiveEvenIfBackendIsRunning() = runTest {
    val vpnActiveFlow = MutableStateFlow(false)
    val vpnPreparedFlow = MutableStateFlow(true)
    val appViewModel = mock<AppViewModel>()
    `when`(appViewModel.vpnActive).thenReturn(vpnActiveFlow)
    `when`(appViewModel.vpnPrepared).thenReturn(vpnPreparedFlow)
    val vpnEntitlementController = mock<VpnEntitlementController>()

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
        )

    Notifier.setState(Ipn.State.NoState)
    advanceUntilIdle()
    assertFalse(viewModel.vpnToggleState.value)

    Notifier.setState(Ipn.State.Starting)
    advanceUntilIdle()
    assertFalse(viewModel.vpnToggleState.value)

    Notifier.setState(Ipn.State.Running)
    advanceUntilIdle()
    assertFalse(viewModel.vpnToggleState.value)
    assertEquals(R.string.stopped, viewModel.stateRes.value)
  }

  @Test
  fun toggleStateTurnsOnWhenVpnActiveAndBackendRunning() = runTest {
    val vpnActiveFlow = MutableStateFlow(false)
    val vpnPreparedFlow = MutableStateFlow(true)
    val appViewModel = mock<AppViewModel>()
    `when`(appViewModel.vpnActive).thenReturn(vpnActiveFlow)
    `when`(appViewModel.vpnPrepared).thenReturn(vpnPreparedFlow)
    val vpnEntitlementController = mock<VpnEntitlementController>()

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
        )

    Notifier.setState(Ipn.State.Running)
    vpnActiveFlow.value = true
    advanceUntilIdle()

    assertTrue(viewModel.vpnToggleState.value)
    assertEquals(R.string.connected, viewModel.stateRes.value)

    vpnActiveFlow.value = false
    advanceUntilIdle()

    assertFalse(viewModel.vpnToggleState.value)
    assertEquals(R.string.stopped, viewModel.stateRes.value)
  }

  @Test
  fun toggleVpnInitiatesStartSequenceWhenBackendRunningAndVpnInactive() = runTest {
    val vpnActiveFlow = MutableStateFlow(false)
    val vpnPreparedFlow = MutableStateFlow(true)
    val appViewModel = mock<AppViewModel>()
    `when`(appViewModel.vpnActive).thenReturn(vpnActiveFlow)
    `when`(appViewModel.vpnPrepared).thenReturn(vpnPreparedFlow)
    val vpnEntitlementController = mock<VpnEntitlementController>()
    `when`(
            vpnEntitlementController.authorizeStart(
                com.tailscale.ipn.product.policy.VpnStartOrigin.PermissionRequest))
        .thenReturn(false)

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
            vpnStarter = {},
        )

    Notifier.setState(Ipn.State.Running)
    advanceUntilIdle()

    viewModel.toggleVpn(desiredState = true)
    advanceUntilIdle()

    org.mockito.Mockito.verify(vpnEntitlementController)
        .authorizeStart(com.tailscale.ipn.product.policy.VpnStartOrigin.PermissionRequest)
  }

  private class TestFakeClient(scope: CoroutineScope) : Client(scope) {
    var editPrefsResult: Result<Ipn.Prefs> = Result.success(Ipn.Prefs())
    var startResult: Result<Unit> = Result.success(Unit)
    var startLoginInteractiveResult: Result<Unit> = Result.success(Unit)
    var profilesResult: Result<List<IpnLocal.LoginProfile>> = Result.success(emptyList())
    var currentProfileResult: Result<IpnLocal.LoginProfile> =
        Result.success(
            IpnLocal.LoginProfile(
                ID = "prof-1",
                Name = "Commander",
                Key = "key-1",
                UserProfile = Tailcfg.UserProfile(ID = 1, LoginName = "commander@stardom.network"),
                LocalUserID = "user-1"))

    override fun editPrefs(prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
      responseHandler(editPrefsResult)
    }

    override fun start(options: Ipn.Options, responseHandler: (Result<Unit>) -> Unit) {
      responseHandler(startResult)
    }

    override fun startLoginInteractive(responseHandler: (Result<Unit>) -> Unit) {
      responseHandler(startLoginInteractiveResult)
    }

    override fun profiles(responseHandler: (Result<List<IpnLocal.LoginProfile>>) -> Unit) {
      responseHandler(profilesResult)
    }

    override fun currentProfile(responseHandler: (Result<IpnLocal.LoginProfile>) -> Unit) {
      responseHandler(currentProfileResult)
    }
  }

  private class FakeAuthSessionRepo(var tokenResult: Result<String> = Result.success("token-123")) :
      AuthSessionRepository(
          storage =
              object : AuthStateStorage {
                var value: String? = null

                override fun read(): String? = value

                override fun write(value: String) {
                  this.value = value
                }

                override fun clear() {
                  value = null
                }
              },
          authState =
              object : AuthSessionState {
                override val isAuthorized: Boolean = true
                override val appAuthState = net.openid.appauth.AuthState()

                override fun updateAuthorization(
                    response: net.openid.appauth.AuthorizationResponse?,
                    exception: net.openid.appauth.AuthorizationException?
                ) {}

                override fun updateToken(
                    response: net.openid.appauth.TokenResponse?,
                    exception: net.openid.appauth.AuthorizationException?
                ) {}

                override fun serialize(): String = "{}"
              },
          appAuth =
              object : AppAuthGateway {
                override fun discover(
                    callback:
                        (
                            net.openid.appauth.AuthorizationServiceConfiguration?,
                            net.openid.appauth.AuthorizationException?) -> Unit
                ) {}

                override fun createAuthorizationRequest(
                    configuration: net.openid.appauth.AuthorizationServiceConfiguration
                ): net.openid.appauth.AuthorizationRequest =
                    mock<net.openid.appauth.AuthorizationRequest>()

                override fun startAuthorization(
                    context: Context,
                    request: net.openid.appauth.AuthorizationRequest
                ) {}

                override fun authorizationResult(intent: Intent): AuthorizationResult? = null

                override fun matchesPendingAuthorization(
                    intent: Intent,
                    response: net.openid.appauth.AuthorizationResponse?,
                    pendingRequest: net.openid.appauth.AuthorizationRequest
                ): Boolean = true

                override fun exchangeCode(
                    context: Context,
                    response: net.openid.appauth.AuthorizationResponse,
                    callback:
                        (
                            net.openid.appauth.TokenResponse?,
                            net.openid.appauth.AuthorizationException?) -> Unit
                ) {}

                override fun freshToken(
                    context: Context,
                    state: AuthSessionState,
                    callback: (String?, net.openid.appauth.AuthorizationException?) -> Unit
                ) {}

                override fun dispose() {}
              }) {
    override fun withFreshBearerToken(context: Context, onResult: (Result<String>) -> Unit) {
      onResult(tokenResult)
    }
  }

  private class FakeStardomSessionController(
      authRepo: AuthSessionRepository,
      accessRepo: AccessRepository = AccessRepository(),
  ) : StardomSessionController(authRepo, accessRepo) {
    var refreshedAccessState: AccessState = AccessState.Active(setOf("exit-node-1"))
    var ackCalled = false

    override suspend fun refreshAccess(context: Context, force: Boolean): AccessState {
      return refreshedAccessState
    }

    override fun ackFixedHeadscaleContinuation() {
      ackCalled = true
    }
  }

  private class FakePolicyApiClient : PolicyApiClient() {
    var nodeAuthKeyResult: Result<String> = Result.success("tskey-auth-node-key")

    override fun fetchNodeAuthKey(token: String): Result<String> {
      return nodeAuthKeyResult
    }
  }

  @Test
  fun executeStardomLoginPipelineTransitionsThroughLoadingToReadyWithoutRetry() = runTest {
    val context = mock<Context>()
    val authRepo = FakeAuthSessionRepo(tokenResult = Result.success("token-123"))
    val sessionController = FakeStardomSessionController(authRepo)
    sessionController.refreshedAccessState = AccessState.Active(setOf("exit-node-1"))

    val policyApiClient = FakePolicyApiClient()
    policyApiClient.nodeAuthKeyResult = Result.success("tskey-auth-node-key")

    var vpnStarts = 0
    val appViewModel = createAppViewModelMock()
    val vpnEntitlementController = mock<VpnEntitlementController>()
    var fakeClient: TestFakeClient? = null

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
            clientProvider = { scope -> TestFakeClient(scope).also { fakeClient = it } },
            vpnStarter = { vpnStarts++ },
        )

    assertFalse("Initial loading state must be false", viewModel.isLoginLoading.value)
    assertFalse("Initial auth error must be false", viewModel.authError.value)

    var completionResult: Result<Unit>? = null
    viewModel.executeStardomLoginPipeline(
        context = context,
        sessionController = sessionController,
        policyApiClient = policyApiClient,
        onComplete = { completionResult = it },
    )

    assertTrue(
        "Loading state must be true while pipeline is running", viewModel.isLoginLoading.value)
    advanceUntilIdle()

    assertTrue("Pipeline result must be success", completionResult?.isSuccess == true)
    assertFalse("Loading state must reset to false after success", viewModel.isLoginLoading.value)
    assertFalse("Auth error must be false after success", viewModel.authError.value)
    assertEquals(
        "User profile must be updated",
        "commander@stardom.network",
        viewModel.loggedInUser.value?.UserProfile?.LoginName)
    assertEquals("VPN must not be auto-started after login", 0, vpnStarts)
    assertTrue("ackFixedHeadscaleContinuation must be called", sessionController.ackCalled)
  }

  @Test
  fun executeStardomLoginPipelineFailsWhenBearerTokenRefreshFails() = runTest {
    val context = mock<Context>()
    val authRepo =
        FakeAuthSessionRepo(tokenResult = Result.failure(IllegalStateException("No bearer token")))
    val sessionController = FakeStardomSessionController(authRepo)

    val policyApiClient = FakePolicyApiClient()
    val appViewModel = createAppViewModelMock()
    val vpnEntitlementController = mock<VpnEntitlementController>()

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
        )

    var completionResult: Result<Unit>? = null
    viewModel.executeStardomLoginPipeline(
        context = context,
        sessionController = sessionController,
        policyApiClient = policyApiClient,
        onComplete = { completionResult = it },
    )

    advanceUntilIdle()

    assertTrue("Pipeline result must be failure", completionResult?.isFailure == true)
    assertFalse("Loading state must reset to false after failure", viewModel.isLoginLoading.value)
    assertTrue("Auth error must be true on failure", viewModel.authError.value)
    assertFalse("ackFixedHeadscaleContinuation must not be called", sessionController.ackCalled)
  }

  @Test
  fun executeStardomLoginPipelineFailsWhenNodeAuthKeyFetchFails() = runTest {
    val context = mock<Context>()
    val authRepo = FakeAuthSessionRepo(tokenResult = Result.success("token-123"))
    val sessionController = FakeStardomSessionController(authRepo)

    val policyApiClient =
        FakePolicyApiClient().apply {
          nodeAuthKeyResult = Result.failure(IllegalStateException("Policy API 500"))
        }

    val appViewModel = createAppViewModelMock()
    val vpnEntitlementController = mock<VpnEntitlementController>()

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
        )

    var completionResult: Result<Unit>? = null
    viewModel.executeStardomLoginPipeline(
        context = context,
        sessionController = sessionController,
        policyApiClient = policyApiClient,
        onComplete = { completionResult = it },
    )

    advanceUntilIdle()

    assertTrue("Pipeline result must be failure", completionResult?.isFailure == true)
    assertFalse("Loading state must reset to false after failure", viewModel.isLoginLoading.value)
    assertTrue("Auth error must be true on failure", viewModel.authError.value)
    assertFalse("ackFixedHeadscaleContinuation must not be called", sessionController.ackCalled)
  }

  @Test
  fun executeStardomLoginPipelineFailsWhenHeadscaleLoginFails() = runTest {
    val context = mock<Context>()
    val authRepo = FakeAuthSessionRepo(tokenResult = Result.success("token-123"))
    val sessionController = FakeStardomSessionController(authRepo)

    val policyApiClient = FakePolicyApiClient()
    val appViewModel = createAppViewModelMock()
    val vpnEntitlementController = mock<VpnEntitlementController>()

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
            clientProvider = { scope ->
              TestFakeClient(scope).also {
                it.startLoginInteractiveResult =
                    Result.failure(IllegalStateException("Headscale down"))
              }
            },
        )

    var completionResult: Result<Unit>? = null
    viewModel.executeStardomLoginPipeline(
        context = context,
        sessionController = sessionController,
        policyApiClient = policyApiClient,
        onComplete = { completionResult = it },
    )

    advanceUntilIdle()

    assertTrue("Pipeline result must be failure", completionResult?.isFailure == true)
    assertFalse("Loading state must reset to false after failure", viewModel.isLoginLoading.value)
    assertTrue("Auth error must be true on failure", viewModel.authError.value)
    assertFalse("ackFixedHeadscaleContinuation must not be called", sessionController.ackCalled)
  }

  @Test
  fun executeStardomLoginPipelineFailsWhenPolicyRefreshUnavailable() = runTest {
    val context = mock<Context>()
    val authRepo = FakeAuthSessionRepo(tokenResult = Result.success("token-123"))
    val sessionController =
        FakeStardomSessionController(authRepo).apply {
          refreshedAccessState = AccessState.Unavailable
        }

    val policyApiClient = FakePolicyApiClient()
    val appViewModel = createAppViewModelMock()
    val vpnEntitlementController = mock<VpnEntitlementController>()

    val viewModel =
        MainViewModel(
            appViewModel = appViewModel,
            vpnEntitlementController = vpnEntitlementController,
            observeUserProfiles = false,
            clientProvider = { scope -> TestFakeClient(scope) },
        )

    var completionResult: Result<Unit>? = null
    viewModel.executeStardomLoginPipeline(
        context = context,
        sessionController = sessionController,
        policyApiClient = policyApiClient,
        onComplete = { completionResult = it },
    )

    advanceUntilIdle()

    assertTrue("Pipeline result must be failure", completionResult?.isFailure == true)
    assertFalse("Loading state must reset to false after failure", viewModel.isLoginLoading.value)
    assertTrue("Auth error must be true on failure", viewModel.authError.value)
    assertFalse("ackFixedHeadscaleContinuation must not be called", sessionController.ackCalled)
  }
}
