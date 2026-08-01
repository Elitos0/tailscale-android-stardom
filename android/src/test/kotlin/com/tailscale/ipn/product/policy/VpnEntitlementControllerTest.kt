// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
    val lease = runtime.beginStarting()
    assertEquals(VpnRuntimeState.Starting, runtime.state.value)
    assertTrue(runtime.markRunning(lease))
    assertEquals(VpnRuntimeState.Running, runtime.state.value)
    assertTrue(runtime.finish(lease))
    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
  }

  @Test
  fun failedStartDispatchDoesNotDisableMonitoringForAnExistingRun() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val lease = runtime.beginStarting()
    runtime.markRunning(lease)
    val decisions = FakeDecisionSource(defaultDecision = ACTIVE)
    val controller = controller(decisions, runtime)
    val dispatchBoundary = VpnStartDispatchBoundary(controller)
    runCurrent()

    val result =
        dispatchBoundary.dispatchIfAuthorized(VpnStartOrigin.AppStart) {
          throw IllegalStateException("dispatch denied")
        }
    advanceTimeBy(REFRESH_INTERVAL.toMillis())
    runCurrent()

    assertTrue(result is VpnStartDispatchResult.Failed)
    assertEquals(VpnRuntimeState.Running, runtime.state.value)
    assertEquals(3, decisions.refreshCalls)
  }

  @Test
  fun wantRunningFailureEndsStartingRunAndRejectsServiceStart() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val writer = CapturingWantRunningWriter()
    val coordinator = VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, writer, backgroundScope)
    var requests = 0
    var rejections = 0

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { requests++ },
        rejectStart = { rejections++ },
    )
    assertEquals(VpnRuntimeState.Starting, runtime.state.value)
    writer.fail(IllegalStateException("patch failed"))
    runCurrent()

    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
    assertEquals(0, requests)
    assertEquals(1, rejections)
  }

  @Test
  fun synchronousWantRunningFailureEndsStartingRunAndRejectsServiceStart() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val writer = VpnWantRunningWriter { _, _ -> throw IllegalStateException("client unavailable") }
    val coordinator = VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, writer, backgroundScope)
    var requests = 0
    var rejections = 0

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { requests++ },
        rejectStart = { rejections++ },
    )

    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
    assertEquals(0, requests)
    assertEquals(1, rejections)
  }

  @Test
  fun destroyBeforeWantRunningCallbackPreventsStaleRequest() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val writer = CapturingWantRunningWriter()
    val coordinator = VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, writer, backgroundScope)
    var requests = 0
    var rejections = 0

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { requests++ },
        rejectStart = { rejections++ },
    )
    coordinator.close()
    writer.succeed()
    runCurrent()

    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
    assertEquals(0, requests)
    assertEquals(0, rejections)
  }

  @Test
  fun destroyAfterFinalAuthorizationBeforeRequestPreventsStaleRequest() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val writer = CapturingWantRunningWriter()
    lateinit var coordinator: VpnServiceRunCoordinator
    val authorizer = LambdaAuthorizer { coordinator.close() }
    coordinator = VpnServiceRunCoordinator(runtime, authorizer, writer, backgroundScope)
    var requests = 0

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { requests++ },
        rejectStart = {},
    )
    writer.succeed()
    runCurrent()

    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
    assertEquals(0, requests)
  }

  @Test
  fun oldServiceCallbackCannotMutateOrRequestAfterANewRunOwnsTracker() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val oldWriter = CapturingWantRunningWriter()
    val newWriter = CapturingWantRunningWriter()
    val oldCoordinator =
        VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, oldWriter, backgroundScope)
    val newCoordinator =
        VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, newWriter, backgroundScope)
    var oldRequests = 0
    var newRequests = 0

    oldCoordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { oldRequests++ },
        rejectStart = {},
    )
    newCoordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { newRequests++ },
        rejectStart = {},
    )
    assertFalse(newWriter.hasPendingCallback)
    oldCoordinator.close()
    newCoordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { newRequests++ },
        rejectStart = {},
    )
    oldWriter.succeed()
    newWriter.succeed()
    runCurrent()

    assertEquals(VpnRuntimeState.Starting, runtime.state.value)
    assertEquals(0, oldRequests)
    assertEquals(1, newRequests)
    assertTrue(newCoordinator.updateVpnStatus(true))
    assertEquals(VpnRuntimeState.Running, runtime.state.value)
  }

  @Test
  fun closeAfterPermitBeforeCallbackEntrySuppressesRequestAndFencesANewRun() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val oldWriter = CapturingWantRunningWriter()
    val newWriter = CapturingWantRunningWriter()
    val permitClaimed = CountDownLatch(1)
    val resumeHandoff = CountDownLatch(1)
    val closeCompleted = CountDownLatch(1)
    val disconnects = AtomicInteger()
    var oldRequests = 0
    var newRequests = 0
    lateinit var oldCoordinator: VpnServiceRunCoordinator
    val newCoordinator =
        VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, newWriter, backgroundScope)
    oldCoordinator =
        VpnServiceRunCoordinator(
            runtime,
            AlwaysAuthorizer,
            oldWriter,
            backgroundScope,
            beforeRequestHandoff = {
              permitClaimed.countDown()
              assertTrue(resumeHandoff.await(1, TimeUnit.SECONDS))
            },
        )
    val closer =
        thread(start = false, name = "vpn-close-before-handoff-test") {
          assertTrue(permitClaimed.await(1, TimeUnit.SECONDS))
          oldCoordinator.close {
            disconnects.incrementAndGet()
            newCoordinator.beginAuthorizedStart(
                VpnStartOrigin.ServiceStart,
                requestVpn = { newRequests++ },
                rejectStart = {},
            )
            assertFalse(newWriter.hasPendingCallback)
          }
          closeCompleted.countDown()
          resumeHandoff.countDown()
        }

    oldCoordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { oldRequests++ },
        rejectStart = {},
    )
    closer.start()
    oldWriter.succeed()
    runCurrent()
    assertTrue(closeCompleted.await(1, TimeUnit.SECONDS))
    closer.join(1_000)
    assertFalse(closer.isAlive)

    assertEquals(0, oldRequests)
    assertEquals(1, disconnects.get())
    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
    newCoordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { newRequests++ },
        rejectStart = {},
    )
    assertTrue(newWriter.hasPendingCallback)
    newWriter.succeed()
    runCurrent()
    assertEquals(1, newRequests)
  }

  @Test
  fun closeAfterCallbackEntryDefersDisconnectUntilRequestReturns() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val writer = CapturingWantRunningWriter()
    val disconnects = AtomicInteger()
    val closeReturned = CountDownLatch(1)
    lateinit var coordinator: VpnServiceRunCoordinator
    var requests = 0
    coordinator = VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, writer, backgroundScope)

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = {
          thread(start = true, name = "vpn-close-after-handoff-test") {
            coordinator.close { disconnects.incrementAndGet() }
            closeReturned.countDown()
          }
          assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
          assertEquals(0, disconnects.get())
          requests++
        },
        rejectStart = {},
    )
    writer.succeed()
    runCurrent()

    assertEquals(1, requests)
    assertEquals(1, disconnects.get())
    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
  }

  @Test
  fun runtimePolicyRevocationAfterHandoffDispatchesRegisteredFencedStop() = runTest {
    val fallbackCommands = AtomicInteger()
    val stopCommands = AtomicInteger()
    val wantRunningFalseWrites = AtomicInteger()
    val disconnects = AtomicInteger()
    val dispatcher = VpnStopCommandDispatcher { fallbackCommands.incrementAndGet() }
    val runtime =
        VpnRuntimeStateTracker(
            revokeVpn = dispatcher::dispatchStopCommand,
            rejectVpnStart = {},
        )
    val writer = CapturingWantRunningWriter()
    lateinit var coordinator: VpnServiceRunCoordinator
    var requests = 0
    coordinator = VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, writer, backgroundScope)
    val registration =
        dispatcher.register {
          stopCommands.incrementAndGet()
          wantRunningFalseWrites.incrementAndGet()
          coordinator.close { disconnects.incrementAndGet() }
        }

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = {
          runtime.revoke()
          assertEquals(1, stopCommands.get())
          assertEquals(1, wantRunningFalseWrites.get())
          assertEquals(0, disconnects.get())
          requests++
        },
        rejectStart = {},
    )
    writer.succeed()
    runCurrent()
    registration.unregister()

    assertEquals(1, requests)
    assertEquals(1, stopCommands.get())
    assertEquals(0, fallbackCommands.get())
    assertEquals(1, disconnects.get())
    assertEquals(VpnRuntimeState.Idle, runtime.state.value)
  }

  @Test
  fun idleRejectedStartUsesSeparateFailClosedCallback() {
    val activeStopCommands = AtomicInteger()
    val rejectedStarts = AtomicInteger()
    val runtime =
        VpnRuntimeStateTracker(
            revokeVpn = { activeStopCommands.incrementAndGet() },
            rejectVpnStart = { rejectedStarts.incrementAndGet() },
        )

    runtime.rejectStart()

    assertEquals(0, activeStopCommands.get())
    assertEquals(1, rejectedStarts.get())
  }

  @Test
  fun duplicateStartWhileWantRunningIsPendingIsCoalescedBeforeWriterFailure() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val writer = CapturingWantRunningWriter(throwOnCall = 2)
    val coordinator = VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, writer, backgroundScope)
    var requests = 0
    var duplicateRequests = 0
    var rejections = 0

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { requests++ },
        rejectStart = { rejections++ },
    )
    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceRestart,
        requestVpn = { duplicateRequests++ },
        rejectStart = { rejections++ },
    )

    assertEquals(1, writer.callCount)
    assertEquals(VpnRuntimeState.Starting, runtime.state.value)
    writer.succeed()
    runCurrent()
    assertEquals(1, requests)
    assertEquals(0, duplicateRequests)
    assertEquals(0, rejections)
  }

  @Test
  fun duplicateStartWhileRunningIsCoalescedBeforeAsyncWriterFailure() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val writer = CapturingWantRunningWriter()
    val coordinator = VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, writer, backgroundScope)
    var requests = 0
    var duplicateRequests = 0
    var rejections = 0

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = { requests++ },
        rejectStart = { rejections++ },
    )
    writer.succeed()
    runCurrent()
    assertTrue(coordinator.updateVpnStatus(true))
    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceRestart,
        requestVpn = { duplicateRequests++ },
        rejectStart = { rejections++ },
    )
    writer.fail(IllegalStateException("duplicate patch failed"))
    runCurrent()

    assertEquals(1, writer.callCount)
    assertEquals(1, requests)
    assertEquals(0, duplicateRequests)
    assertEquals(0, rejections)
    assertEquals(VpnRuntimeState.Running, runtime.state.value)
  }

  @Test
  fun requestCallbackDoesNotHoldLifecycleLocksDuringCrossThreadStatusUpdate() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val writer = CapturingWantRunningWriter()
    val coordinator = VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, writer, backgroundScope)
    var requests = 0

    coordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = {
          val statusUpdated = CountDownLatch(1)
          thread(start = true, name = "vpn-status-test") {
            coordinator.updateVpnStatus(true)
            statusUpdated.countDown()
          }
          assertTrue(statusUpdated.await(1, TimeUnit.SECONDS))
          requests++
        },
        rejectStart = {},
    )
    writer.succeed()
    runCurrent()

    assertEquals(1, requests)
    assertEquals(VpnRuntimeState.Running, runtime.state.value)
  }

  @Test
  fun requestOwnershipCannotBeSupersededWhileRequestCallbackIsInFlight() = runTest {
    val runtime = VpnRuntimeStateTracker {}
    val firstWriter = CapturingWantRunningWriter()
    val secondWriter = CapturingWantRunningWriter()
    val firstCoordinator =
        VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, firstWriter, backgroundScope)
    val secondCoordinator =
        VpnServiceRunCoordinator(runtime, AlwaysAuthorizer, secondWriter, backgroundScope)
    var firstRequests = 0
    var secondRequests = 0

    firstCoordinator.beginAuthorizedStart(
        VpnStartOrigin.ServiceStart,
        requestVpn = {
          secondCoordinator.beginAuthorizedStart(
              VpnStartOrigin.ServiceStart,
              requestVpn = { secondRequests++ },
              rejectStart = {},
          )
          assertFalse(secondWriter.hasPendingCallback)
          firstRequests++
        },
        rejectStart = {},
    )
    firstWriter.succeed()
    runCurrent()

    assertEquals(1, firstRequests)
    assertEquals(0, secondRequests)
    assertEquals(VpnRuntimeState.Starting, runtime.state.value)
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

private object AlwaysAuthorizer : VpnStartAuthorizer {
  override suspend fun authorizeStart(origin: VpnStartOrigin): Boolean = true
}

private class LambdaAuthorizer(private val beforeReturn: () -> Unit) : VpnStartAuthorizer {
  override suspend fun authorizeStart(origin: VpnStartOrigin): Boolean {
    beforeReturn()
    return true
  }
}

private class CapturingWantRunningWriter(private val throwOnCall: Int? = null) :
    VpnWantRunningWriter {
  private var callback: ((Result<Unit>) -> Unit)? = null

  var callCount = 0
    private set

  val hasPendingCallback: Boolean
    get() = callback != null

  override fun setWantRunning(wantRunning: Boolean, onComplete: (Result<Unit>) -> Unit) {
    assertTrue(wantRunning)
    callCount++
    if (callCount == throwOnCall) throw IllegalStateException("writer failed synchronously")
    check(callback == null) { "writer already has a pending callback" }
    callback = onComplete
  }

  fun succeed() {
    callback?.also { callback = null }?.invoke(Result.success(Unit))
  }

  fun fail(error: Throwable) {
    callback?.also { callback = null }?.invoke(Result.failure(error))
  }
}
