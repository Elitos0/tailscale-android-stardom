// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnEntitlementControllerTest {
  @Test
  fun everyStartOriginRequiresAFreshActiveDecision() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = AccessState.Unavailable)
    val controller = controller(decisions, FakeRuntime())

    VpnStartOrigin.entries.forEach { origin -> assertFalse(controller.authorizeStart(origin)) }

    assertEquals(VpnStartOrigin.entries, decisions.refreshOrigins)
  }

  @Test
  fun backendLoginRunningDoesNotStartVpnEntitlementMonitoring() = runTest {
    val originalBackendState = Notifier.state.value
    var revocations = 0
    val runtime = VpnRuntimeStateTracker { revocations++ }
    val decisions = FakeDecisionSource(defaultDecision = AccessState.Unavailable)
    controller(decisions, runtime)

    try {
      Notifier.setState(Ipn.State.Running)
      runCurrent()
      advanceTimeBy(REFRESH_INTERVAL.multipliedBy(2).toMillis())
      runCurrent()

      assertEquals(VpnRuntimeState.Idle, runtime.state.value)
      assertEquals(0, decisions.refreshCalls)
      assertEquals(0, revocations)
    } finally {
      Notifier.setState(originalBackendState)
    }
  }

  @Test
  fun runtimeTrackerChangesOnlyOnExplicitTunnelLifecycleEvents() {
    val runtime = VpnRuntimeStateTracker {}

    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
    runtime.markStarting()
    assertEquals(VpnRuntimeState.Starting, runtime.state.value)
    runtime.markRunning()
    assertEquals(VpnRuntimeState.Running, runtime.state.value)
    runtime.markIdle()
    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
  }

  @Test
  fun deniedIdleStartDoesNotMutateVpnRuntime() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = AccessState.Disabled)
    val runtime = FakeRuntime()
    val controller = controller(decisions, runtime)

    assertFalse(controller.authorizeStart(VpnStartOrigin.PermissionResult))
    assertFalse(controller.authorizeStart(VpnStartOrigin.AppStart))
    assertFalse(controller.authorizeStart(VpnStartOrigin.ServiceStart))

    assertEquals(0, runtime.revocations)
  }

  @Test
  fun serviceBoundaryCanFailClosedAnIdleDeniedStartExactlyOnce() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = AccessState.Disabled)
    val runtime = FakeRuntime()
    val controller = controller(decisions, runtime)

    assertFalse(controller.authorizeStart(VpnStartOrigin.AlwaysOn))
    controller.revokeRejectedRuntimeStart()
    controller.revokeRejectedRuntimeStart()

    assertEquals(1, runtime.revocations)
  }

  @Test
  fun successfulIdleDecisionArmsANewRunAfterRejectedServiceStart() = runTest {
    val decisions = FakeDecisionSource(AccessState.Disabled, ACTIVE, AccessState.Unavailable)
    val runtime = FakeRuntime()
    val controller = controller(decisions, runtime)

    assertFalse(controller.authorizeStart(VpnStartOrigin.AlwaysOn))
    controller.revokeRejectedRuntimeStart()
    assertTrue(controller.authorizeStart(VpnStartOrigin.AppStart))
    runtime.state.value = VpnRuntimeState.Starting
    assertFalse(controller.authorizeStart(VpnStartOrigin.ServiceStart))

    assertEquals(2, runtime.revocations)
  }

  @Test
  fun repeatedDeniedChecksWhileRunningRevokeOnlyOnce() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = AccessState.Disabled)
    val runtime = FakeRuntime(initialState = VpnRuntimeState.Running)
    val controller = controller(decisions, runtime)

    assertFalse(controller.authorizeStart(VpnStartOrigin.AppStart))
    assertFalse(controller.authorizeStart(VpnStartOrigin.ServiceStart))
    assertFalse(controller.authorizeStart(VpnStartOrigin.AlwaysOn))

    assertEquals(1, runtime.revocations)
  }

  @Test
  fun successfulCheckDoesNotRearmRevocationWhileTheSameRunRemainsActive() = runTest {
    val decisions = FakeDecisionSource(AccessState.Disabled, ACTIVE, AccessState.Unavailable)
    val runtime = FakeRuntime(initialState = VpnRuntimeState.Running)
    val controller = controller(decisions, runtime)

    assertFalse(controller.authorizeStart(VpnStartOrigin.AppStart))
    assertTrue(controller.authorizeStart(VpnStartOrigin.ServiceStart))
    assertFalse(controller.authorizeStart(VpnStartOrigin.ServiceRestart))

    assertEquals(1, runtime.revocations)
  }

  @Test
  fun everyStartOriginMayProceedAfterItsOwnFreshActiveDecision() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = ACTIVE)
    val controller = controller(decisions, FakeRuntime())

    VpnStartOrigin.entries.forEach { origin -> assertTrue(controller.authorizeStart(origin)) }

    assertEquals(VpnStartOrigin.entries, decisions.refreshOrigins)
  }

  @Test
  fun concurrentStartChecksAreSerialized() = runTest {
    val firstRefreshStarted = CompletableDeferred<Unit>()
    val releaseFirstRefresh = CompletableDeferred<Unit>()
    val decisions =
        FakeDecisionSource(defaultDecision = ACTIVE).apply {
          beforeRefresh = {
            if (refreshCalls == 1) {
              firstRefreshStarted.complete(Unit)
              releaseFirstRefresh.await()
            }
          }
        }
    val controller = controller(decisions, FakeRuntime())

    val first = async { controller.authorizeStart(VpnStartOrigin.PermissionRequest) }
    firstRefreshStarted.await()
    val second = async { controller.authorizeStart(VpnStartOrigin.PermissionResult) }
    runCurrent()

    assertEquals(1, decisions.maxConcurrentRefreshes)
    releaseFirstRefresh.complete(Unit)
    assertTrue(first.await())
    assertTrue(second.await())
    assertEquals(1, decisions.maxConcurrentRefreshes)
  }

  @Test
  fun permissionResultRechecksAndRejectsAccessRevokedAfterPermissionLaunch() = runTest {
    val decisions = FakeDecisionSource(ACTIVE, AccessState.Disabled)
    val controller = controller(decisions, FakeRuntime())

    assertTrue(controller.authorizeStart(VpnStartOrigin.PermissionRequest))
    assertFalse(controller.authorizeStart(VpnStartOrigin.PermissionResult))

    assertEquals(2, decisions.refreshCalls)
  }

  @Test
  fun nonAuthorizedAuthentikStatesRejectWithoutRefreshingPolicy() = runTest {
    listOf(
            AuthentikState.SignedOut,
            AuthentikState.Authorizing,
            AuthentikState.ReauthenticationRequired,
        )
        .forEach { authentikState ->
          val decisions = FakeDecisionSource(defaultDecision = ACTIVE)
          decisions.authentik.value = authentikState
          val controller = controller(decisions, FakeRuntime())

          assertFalse(controller.authorizeStart(VpnStartOrigin.AppStart))
          assertEquals(0, decisions.refreshCalls)
        }
  }

  @Test
  fun authentikRevokedDuringPolicyRefreshRejectsTheResultSynchronously() = runTest {
    val decisions =
        FakeDecisionSource(defaultDecision = ACTIVE).apply {
          beforeRefresh = { authentik.value = AuthentikState.ReauthenticationRequired }
        }
    val controller = controller(decisions, FakeRuntime())

    assertFalse(controller.authorizeStart(VpnStartOrigin.PermissionResult))

    assertEquals(1, decisions.refreshCalls)
  }

  @Test
  fun serviceRestartRechecksAndRejectsAccessRevokedAfterAppRestartDispatch() = runTest {
    val decisions = FakeDecisionSource(ACTIVE, AccessState.Disabled)
    val controller = controller(decisions, FakeRuntime())

    assertTrue(controller.authorizeStart(VpnStartOrigin.AppRestart))
    assertFalse(controller.authorizeStart(VpnStartOrigin.ServiceRestart))

    assertEquals(2, decisions.refreshCalls)
  }

  @Test
  fun requestBoundaryRechecksAfterServiceAuthorizationBeforeRequestVpn() = runTest {
    val decisions = FakeDecisionSource(ACTIVE, AccessState.Disabled)
    val controller = controller(decisions, FakeRuntime())
    val requestBoundary = VpnRequestBoundary(controller)
    var requests = 0

    assertTrue(controller.authorizeStart(VpnStartOrigin.ServiceStart))
    val requested = requestBoundary.requestIfAuthorized(VpnStartOrigin.ServiceStart) { requests++ }

    assertFalse(requested)
    assertEquals(0, requests)
    assertEquals(2, decisions.refreshCalls)
  }

  @Test
  fun enteringStartingStateWithNoActiveDecisionFailsClosedExactlyOnce() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = AccessState.Unavailable)
    val runtime = FakeRuntime()
    controller(decisions, runtime)

    runtime.state.value = VpnRuntimeState.Starting
    runCurrent()

    assertEquals(1, runtime.revocations)
    decisions.access.value = AccessState.Disabled
    decisions.authentik.value = AuthentikState.ReauthenticationRequired
    runCurrent()
    assertEquals(1, runtime.revocations)
  }

  @Test
  fun activeToDisabledThenUnavailableStopsRunningTunnelExactlyOnce() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = ACTIVE)
    val runtime = FakeRuntime(initialState = VpnRuntimeState.Running)
    controller(decisions, runtime)
    runCurrent()

    decisions.access.value = AccessState.Disabled
    runCurrent()
    decisions.access.value = AccessState.Unavailable
    runCurrent()

    assertEquals(1, runtime.revocations)
  }

  @Test
  fun reauthenticationRequirementStopsRunningTunnelWithoutWaitingForPolicyPoll() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = ACTIVE)
    val runtime = FakeRuntime(initialState = VpnRuntimeState.Running)
    controller(decisions, runtime)
    runCurrent()

    decisions.authentik.value = AuthentikState.ReauthenticationRequired
    runCurrent()

    assertEquals(1, runtime.revocations)
  }

  @Test
  fun runningTunnelRefreshesImmediatelyAndEveryThirtySeconds() = runTest {
    val decisions = FakeDecisionSource(ACTIVE, AccessState.Disabled)
    val runtime = FakeRuntime(initialState = VpnRuntimeState.Running)
    controller(decisions, runtime)

    runCurrent()
    assertEquals(1, decisions.refreshCalls)
    assertEquals(0, runtime.revocations)

    advanceTimeBy(REFRESH_INTERVAL.toMillis())
    runCurrent()

    assertEquals(2, decisions.refreshCalls)
    assertEquals(1, runtime.revocations)
  }

  @Test
  fun appResumeRefreshesOnlyWhenTheTunnelIsStartingOrRunning() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = AccessState.Disabled)
    val runtime = FakeRuntime()
    val controller = controller(decisions, runtime)

    controller.refreshRuntimeEntitlement()
    assertEquals(0, decisions.refreshCalls)

    runtime.state.value = VpnRuntimeState.Running
    controller.refreshRuntimeEntitlement()

    assertEquals(1, decisions.refreshCalls)
    assertEquals(1, runtime.revocations)
  }

  @Test
  fun runtimeRefreshRejectsActiveAccessWhenAuthentikIsNoLongerAuthorized() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = ACTIVE)
    decisions.authentik.value = AuthentikState.ReauthenticationRequired
    val runtime = FakeRuntime(initialState = VpnRuntimeState.Running)
    val controller = controller(decisions, runtime)

    controller.refreshRuntimeEntitlement()

    assertEquals(0, decisions.refreshCalls)
    assertEquals(1, runtime.revocations)
  }

  @Test
  fun aRestoredActiveDecisionAllowsALaterRevocationToStopANewRun() = runTest {
    val decisions = FakeDecisionSource(defaultDecision = ACTIVE)
    val runtime = FakeRuntime(initialState = VpnRuntimeState.Running)
    controller(decisions, runtime)
    runCurrent()

    decisions.access.value = AccessState.Disabled
    runCurrent()
    assertEquals(1, runtime.revocations)

    runtime.state.value = VpnRuntimeState.Idle
    decisions.access.value = ACTIVE
    runCurrent()
    runtime.state.value = VpnRuntimeState.Starting
    runCurrent()
    decisions.access.value = AccessState.Unavailable
    runCurrent()

    assertEquals(2, runtime.revocations)
  }

  private fun kotlinx.coroutines.test.TestScope.controller(
      decisions: FakeDecisionSource,
      runtime: VpnEntitlementRuntime,
  ) =
      VpnEntitlementController(
          decisionSource = decisions,
          runtime = runtime,
          scope = backgroundScope,
          refreshInterval = REFRESH_INTERVAL,
      )

  companion object {
    private val ACTIVE = AccessState.Active(setOf("allowed-node"))
    private val REFRESH_INTERVAL = Duration.ofSeconds(30)
  }
}

private class FakeDecisionSource(vararg queuedDecisions: AccessState) :
    VpnEntitlementDecisionSource {
  constructor(defaultDecision: AccessState) : this(*emptyArray()) {
    this.defaultDecision = defaultDecision
    access.value = defaultDecision
  }

  private val queuedDecisions = ArrayDeque(queuedDecisions.toList())
  private var defaultDecision: AccessState = queuedDecisions.lastOrNull() ?: AccessState.Unavailable
  val authentik = MutableStateFlow<AuthentikState>(AuthentikState.Authorized)
  override val authentikState: StateFlow<AuthentikState> = authentik
  val access = MutableStateFlow(queuedDecisions.firstOrNull() ?: defaultDecision)
  override val accessState: StateFlow<AccessState> = access
  var refreshCalls = 0
    private set

  val refreshOrigins = mutableListOf<VpnStartOrigin?>()

  var beforeRefresh: suspend () -> Unit = {}
  private var concurrentRefreshes = 0
  var maxConcurrentRefreshes = 0
    private set

  override suspend fun refreshAccess(origin: VpnStartOrigin?): AccessState {
    refreshCalls++
    refreshOrigins += origin
    concurrentRefreshes++
    maxConcurrentRefreshes = maxOf(maxConcurrentRefreshes, concurrentRefreshes)
    return try {
      beforeRefresh()
      (queuedDecisions.removeFirstOrNull() ?: defaultDecision).also { access.value = it }
    } finally {
      concurrentRefreshes--
    }
  }
}

private class FakeRuntime(initialState: VpnRuntimeState = VpnRuntimeState.Idle) :
    VpnEntitlementRuntime {
  override val state = MutableStateFlow(initialState)
  var revocations = 0
    private set

  override fun revoke() {
    revocations++
  }
}
