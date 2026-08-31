// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.product.ProductConfig
import com.tailscale.ipn.product.policy.DesiredExitMode
import com.tailscale.ipn.product.policy.DesiredExitModeStore
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
class IpnViewModelLoginTest {
  private class FakeDesiredExitModeStore : DesiredExitModeStore {
    private val _mode = MutableStateFlow<DesiredExitMode?>(null)
    override val mode: StateFlow<DesiredExitMode?> = _mode

    override fun set(mode: DesiredExitMode) {
      _mode.value = mode
    }

    override fun clear() {
      _mode.value = null
    }
  }

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
    var editPrefsMaskedPrefs: Ipn.MaskedPrefs? = null
    var editPrefsResult: Result<Ipn.Prefs> = Result.success(Ipn.Prefs())
    var editPrefsCallCount = 0

    var startOptions: Ipn.Options? = null
    var startResult: Result<Unit> = Result.success(Unit)
    var startCallCount = 0

    var startLoginInteractiveResult: Result<Unit> = Result.success(Unit)
    var startLoginInteractiveCallCount = 0

    val invocationOrder = mutableListOf<String>()

    override fun editPrefs(prefs: Ipn.MaskedPrefs, responseHandler: (Result<Ipn.Prefs>) -> Unit) {
      editPrefsCallCount++
      editPrefsMaskedPrefs = prefs
      invocationOrder.add("editPrefs")
      responseHandler(editPrefsResult)
    }

    override fun start(options: Ipn.Options, responseHandler: (Result<Unit>) -> Unit) {
      startCallCount++
      startOptions = options
      invocationOrder.add("start")
      responseHandler(startResult)
    }

    override fun startLoginInteractive(responseHandler: (Result<Unit>) -> Unit) {
      startLoginInteractiveCallCount++
      invocationOrder.add("startLoginInteractive")
      responseHandler(startLoginInteractiveResult)
    }
  }

  @Test
  fun loginWithAuthKeySetsLoggedOutFalseAndWantRunningFalseThenPassesWantRunningTrueAndAuthKeyToStart() =
      runTest {
        var foregroundStarted = false
        var fakeClient: FakeClient? = null
        val desiredExitModeStore = FakeDesiredExitModeStore()
        val viewModel =
            IpnViewModel(
                observeUserProfiles = false,
                clientProvider = { scope -> FakeClient(scope).also { fakeClient = it } },
                foregroundServiceLauncher = { foregroundStarted = true },
                desiredExitModeStoreProvider = { desiredExitModeStore },
            )

        var completionResult: Result<Unit>? = null
        viewModel.loginWithAuthKey(
            authKey = "tskey-auth-test-key-12345",
            controlURL = "https://headscale.example.com",
        ) { result ->
          completionResult = result
        }

        assertTrue(foregroundStarted)
        val client = checkNotNull(fakeClient)
        assertEquals(1, client.editPrefsCallCount)
        val editedPrefs = checkNotNull(client.editPrefsMaskedPrefs)
        assertFalse("WantRunning in editPrefs must be false", editedPrefs.WantRunning ?: true)
        assertFalse("LoggedOut in editPrefs must be false", editedPrefs.LoggedOut ?: true)
        assertEquals(DesiredExitMode.Auto, desiredExitModeStore.mode.value)
        assertEquals("https://headscale.example.com", editedPrefs.ControlURL)
        assertEquals("any", editedPrefs.AutoExitNode)

        assertEquals(1, client.startCallCount)
        val startOpts = checkNotNull(client.startOptions)
        assertEquals("tskey-auth-test-key-12345", startOpts.AuthKey)
        assertTrue(
            "UpdatePrefs.WantRunning must be true", startOpts.UpdatePrefs?.WantRunning == true)

        // Auth key login completes the full registration contract via startLoginInteractive
        assertEquals(1, client.startLoginInteractiveCallCount)
        assertEquals(listOf("editPrefs", "start", "startLoginInteractive"), client.invocationOrder)
        assertTrue("Completion result must be success", completionResult?.isSuccess == true)
      }

  @Test
  fun loginWithAuthKeyDefaultsToProductConfigControlUrl() = runTest {
    var fakeClient: FakeClient? = null
    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            clientProvider = { scope -> FakeClient(scope).also { fakeClient = it } },
            foregroundServiceLauncher = {},
        )

    viewModel.loginWithAuthKey(authKey = "tskey-auth-default-control")

    val client = checkNotNull(fakeClient)
    assertEquals(ProductConfig.headscaleControlUrl, client.editPrefsMaskedPrefs?.ControlURL)
  }

  @Test
  fun loginWithoutAuthKeyInvokesStartLoginInteractive() = runTest {
    var fakeClient: FakeClient? = null
    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            clientProvider = { scope -> FakeClient(scope).also { fakeClient = it } },
            foregroundServiceLauncher = {},
        )

    var completionResult: Result<Unit>? = null
    viewModel.login { result -> completionResult = result }

    val client = checkNotNull(fakeClient)
    assertEquals(1, client.editPrefsCallCount)
    assertFalse(client.editPrefsMaskedPrefs?.WantRunning ?: true)
    assertNull(client.editPrefsMaskedPrefs?.LoggedOut)

    assertEquals(1, client.startCallCount)
    assertNull(client.startOptions?.AuthKey)
    assertTrue(client.startOptions?.UpdatePrefs?.WantRunning == true)

    assertEquals(1, client.startLoginInteractiveCallCount)
    assertEquals(listOf("editPrefs", "start", "startLoginInteractive"), client.invocationOrder)
    assertTrue(completionResult?.isSuccess == true)
  }

  @Test
  fun loginWithAuthKeyShortCircuitsWhenEditPrefsFails() = runTest {
    var fakeClient: FakeClient? = null
    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            clientProvider = { scope ->
              FakeClient(scope)
                  .apply {
                    editPrefsResult = Result.failure(IllegalStateException("editPrefs error"))
                  }
                  .also { fakeClient = it }
            },
            foregroundServiceLauncher = {},
        )

    var completionResult: Result<Unit>? = null
    viewModel.loginWithAuthKey("tskey-auth-fail") { result -> completionResult = result }

    val client = checkNotNull(fakeClient)
    assertEquals(1, client.editPrefsCallCount)
    assertEquals(0, client.startCallCount)
    assertEquals(0, client.startLoginInteractiveCallCount)
    assertTrue("Completion result must be failure", completionResult?.isFailure == true)
    assertEquals("editPrefs error", completionResult?.exceptionOrNull()?.message)
  }

  @Test
  fun loginWithAuthKeyShortCircuitsWhenStartFails() = runTest {
    var fakeClient: FakeClient? = null
    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            clientProvider = { scope ->
              FakeClient(scope)
                  .apply { startResult = Result.failure(IllegalStateException("start error")) }
                  .also { fakeClient = it }
            },
            foregroundServiceLauncher = {},
        )

    var completionResult: Result<Unit>? = null
    viewModel.loginWithAuthKey("tskey-auth-fail") { result -> completionResult = result }

    val client = checkNotNull(fakeClient)
    assertEquals(1, client.editPrefsCallCount)
    assertEquals(1, client.startCallCount)
    assertEquals(0, client.startLoginInteractiveCallCount)
    assertTrue("Completion result must be failure", completionResult?.isFailure == true)
    assertEquals("start error", completionResult?.exceptionOrNull()?.message)
  }

  @Test
  fun loginInteractiveFailsWhenStartLoginInteractiveFails() = runTest {
    var fakeClient: FakeClient? = null
    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            clientProvider = { scope ->
              FakeClient(scope)
                  .apply {
                    startLoginInteractiveResult =
                        Result.failure(IllegalStateException("interactive login error"))
                  }
                  .also { fakeClient = it }
            },
            foregroundServiceLauncher = {},
        )

    var completionResult: Result<Unit>? = null
    viewModel.login { result -> completionResult = result }

    val client = checkNotNull(fakeClient)
    assertEquals(1, client.editPrefsCallCount)
    assertEquals(1, client.startCallCount)
    assertEquals(1, client.startLoginInteractiveCallCount)
    assertTrue(completionResult?.isFailure == true)
    assertEquals("interactive login error", completionResult?.exceptionOrNull()?.message)
  }

  @Test
  fun loginWithAuthKeyFailsWhenStartLoginInteractiveFails() = runTest {
    var fakeClient: FakeClient? = null
    val viewModel =
        IpnViewModel(
            observeUserProfiles = false,
            clientProvider = { scope ->
              FakeClient(scope)
                  .apply {
                    startLoginInteractiveResult =
                        Result.failure(IllegalStateException("auth key interactive login error"))
                  }
                  .also { fakeClient = it }
            },
            foregroundServiceLauncher = {},
        )

    var completionResult: Result<Unit>? = null
    viewModel.loginWithAuthKey("tskey-auth-fail") { result -> completionResult = result }

    val client = checkNotNull(fakeClient)
    assertEquals(1, client.editPrefsCallCount)
    assertEquals(1, client.startCallCount)
    assertEquals(1, client.startLoginInteractiveCallCount)
    assertTrue(completionResult?.isFailure == true)
    assertEquals("auth key interactive login error", completionResult?.exceptionOrNull()?.message)
  }
}
