// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExitNodeMutationBoundaryTest {
  @Test
  fun clearExecutesWhileSignedOutAndClearsManualAndAutoFields() = runTest {
    val decisions = MutationDecisionSource(AccessState.Disabled)
    decisions.authentik.value = AuthentikState.SignedOut
    val writer = MutationWriter()
    val controller = controller(decisions, writer = writer)

    val result = controller.mutateExitNode(ExitNodeMutation.Clear())

    assertTrue(result.isSuccess)
    assertEquals(0, decisions.refreshCalls)
    assertTrue(writer.writes.single().ExitNodeIDSet == true)
    assertTrue(writer.writes.single().AutoExitNodeSet == true)
    assertNull(writer.writes.single().ExitNodeID)
    assertNull(writer.writes.single().AutoExitNode)
    assertTrue(controller.authorizeExitNodeMutation(null))
    assertTrue(controller.authorizeExitNodeMutation("  "))
  }

  @Test
  fun manualSelectionRequiresFreshEffectiveMembershipAndOwnsPayload() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    val writer = MutationWriter()
    val controller = controller(decisions, writer = writer)

    val allowed = controller.mutateExitNode(ExitNodeMutation.Manual(" node-a "))
    val denied = controller.mutateExitNode(ExitNodeMutation.Manual("node-b"))

    assertTrue(allowed.isSuccess)
    assertTrue(denied.isFailure)
    assertEquals(3, decisions.refreshCalls)
    assertEquals("node-a", writer.writes.single().ExitNodeID)
    assertTrue(writer.writes.single().AutoExitNodeSet == true)
  }

  @Test
  fun nonblankSelectionFailsClosedWhenAuthentikIsNotAuthorized() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    decisions.authentik.value = AuthentikState.ReauthenticationRequired
    val writer = MutationWriter()
    val controller = controller(decisions, writer = writer)

    val result = controller.mutateExitNode(ExitNodeMutation.Manual("node-a"))

    assertTrue(result.isFailure)
    assertTrue(writer.writes.isEmpty())
    assertEquals(0, decisions.refreshCalls)
    assertFalse(controller.authorizeExitNodeMutation("node-a"))
  }

  @Test
  fun nativeAutoIsDistinctFromManualAutoAnyId() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    val writer = MutationWriter()
    val controller = controller(decisions, writer = writer)

    val auto = controller.mutateExitNode(ExitNodeMutation.Auto())
    val fakeManual = controller.mutateExitNode(ExitNodeMutation.Manual("auto:any"))

    assertTrue(auto.isSuccess)
    assertTrue(fakeManual.isFailure)
    assertEquals("any", writer.writes.single().AutoExitNode)
    assertTrue(writer.writes.single().ExitNodeIDSet == true)
  }

  @Test
  fun allowListShrinkBeforeFinalDispatchCannotCommitAuthorizedNode() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    val writer = MutationWriter()
    val controller =
        controller(
            decisions,
            writer = writer,
            beforeWrite = { decisions.access.value = AccessState.Active(setOf("node-b")) },
        )

    val result = controller.mutateExitNode(ExitNodeMutation.Manual("node-a"))

    assertTrue(result.isFailure)
    assertTrue(writer.writes.isEmpty())
  }

  @Test
  fun pendingLocalApiWriteDoesNotHoldDecisionMutex() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    val writer = MutationWriter(autoComplete = false)
    val controller = controller(decisions, writer = writer)
    val mutation = async { controller.mutateExitNode(ExitNodeMutation.Manual("node-a")) }
    runCurrent()

    val startAuthorization = async { controller.authorizeStart(VpnStartOrigin.AppStart) }
    runCurrent()

    assertTrue(startAuthorization.isCompleted)
    assertTrue(startAuthorization.await())
    assertFalse(mutation.isCompleted)
    writer.completeNext(Result.success(Unit))
    assertTrue(mutation.await().isSuccess)
  }

  @Test
  fun synchronousThrowAndDuplicateCallbackCannotCorruptCompletion() = runTest {
    val throwing = MutationWriter(throwSynchronously = true)
    val duplicate = MutationWriter(completeTwice = true)
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))

    assertTrue(
        controller(decisions, writer = throwing)
            .mutateExitNode(ExitNodeMutation.Manual("node-a"))
            .isFailure)
    assertTrue(
        controller(decisions, writer = duplicate)
            .mutateExitNode(ExitNodeMutation.Manual("node-a"))
            .isSuccess)
  }

  @Test
  fun cancellationDoesNotLetLaterMutationOvertakeInFlightPatch() = runTest {
    val writer = MutationWriter(autoComplete = false)
    val controller =
        controller(
            MutationDecisionSource(AccessState.Active(setOf("node-a", "node-b"))),
            writer = writer,
        )
    val first = launch { controller.mutateExitNode(ExitNodeMutation.Manual("node-a")) }
    runCurrent()
    first.cancel()
    val second = async { controller.mutateExitNode(ExitNodeMutation.Manual("node-b")) }
    runCurrent()

    assertEquals(listOf("node-a"), writer.writes.map { it.ExitNodeID })
    writer.completeNext(Result.success(Unit))
    runCurrent()
    assertEquals(listOf("node-a", "node-b"), writer.writes.map { it.ExitNodeID })
    writer.completeNext(Result.success(Unit))

    first.join()
    assertTrue(second.await().isSuccess)
  }

  @Test
  fun allowListShrinkAfterPatchDispatchRollsBackManualAndRevokesRuntime() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    val runtime = MutationRuntime(VpnRuntimeState.Running)
    val writer =
        MutationWriter(
            beforeComplete = { index ->
              if (index == 0) decisions.access.value = AccessState.Active(setOf("node-b"))
            })
    val controller = controller(decisions, writer = writer, runtime = runtime)

    val result = controller.mutateExitNode(ExitNodeMutation.Manual("node-a"))

    assertTrue(result.isFailure)
    assertEquals(listOf("node-a", null), writer.writes.map { it.ExitNodeID })
    assertEquals(1, runtime.revocations)
  }

  @Test
  fun postPatchRevocationPreservesNativeAutoModeWhileRevokingRuntime() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    val runtime = MutationRuntime(VpnRuntimeState.Running)
    val writer =
        MutationWriter(
            beforeComplete = { index ->
              if (index == 0) decisions.access.value = AccessState.Unavailable
            })
    val controller = controller(decisions, writer = writer, runtime = runtime)

    val result = controller.mutateExitNode(ExitNodeMutation.Auto())

    assertTrue(result.isFailure)
    assertEquals(1, writer.writes.size)
    assertEquals("any", writer.writes.single().AutoExitNode)
    assertEquals(1, runtime.revocations)
  }

  @Test
  fun policySourceErrorFailsClosedWithoutDispatchingMutation() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    decisions.refreshError = IllegalStateException("policy unavailable")
    val writer = MutationWriter()
    val controller = controller(decisions, writer = writer)

    val result = controller.mutateExitNode(ExitNodeMutation.Manual("node-a"))

    assertTrue(result.isFailure)
    assertTrue(writer.writes.isEmpty())
    assertFalse(controller.authorizeExitNodeMutation("node-a"))
  }

  @Test
  fun concurrentMutationsDispatchInCallbackOrder() = runTest {
    val writer = MutationWriter(autoComplete = false)
    val controller =
        controller(
            MutationDecisionSource(AccessState.Active(setOf("node-a", "node-b"))),
            writer = writer,
        )
    val first = async { controller.mutateExitNode(ExitNodeMutation.Manual("node-a")) }
    runCurrent()
    val second = async { controller.mutateExitNode(ExitNodeMutation.Manual("node-b")) }
    runCurrent()

    assertEquals(listOf("node-a"), writer.writes.map { it.ExitNodeID })
    writer.completeNext(Result.success(Unit))
    runCurrent()
    assertEquals(listOf("node-a", "node-b"), writer.writes.map { it.ExitNodeID })
    writer.completeNext(Result.success(Unit))
    assertTrue(first.await().isSuccess)
    assertTrue(second.await().isSuccess)
  }

  @Test
  fun lostCallbackTimesOutRevokesAndRequiresSuccessfulClearBeforeAnotherSelection() = runTest {
    val runtime = MutationRuntime(VpnRuntimeState.Running)
    val writer = MutationWriter(autoComplete = false)
    val controller =
        controller(
            MutationDecisionSource(AccessState.Active(setOf("node-a"))),
            writer = writer,
            runtime = runtime,
            mutationTimeout = Duration.ofSeconds(1),
        )
    val timedOut = async { controller.mutateExitNode(ExitNodeMutation.Manual("node-a")) }
    runCurrent()
    advanceTimeBy(1_001)
    runCurrent()

    assertTrue(timedOut.await().exceptionOrNull() is ExitNodeMutationTimeoutException)
    assertEquals(1, runtime.revocations)
    assertTrue(controller.mutateExitNode(ExitNodeMutation.Manual("node-a")).isFailure)
    assertTrue(controller.mutateExitNode(ExitNodeMutation.Clear()).isFailure)

    writer.completeNext(Result.success(Unit)) // late callback from the timed-out selection
    runCurrent()
    // Compensating path may stop-then-clear (and possibly retry); at least one Clear must land.
    assertTrue(writer.writes.size >= 2)
    assertTrue(writer.writes.any { it.ExitNodeIDSet == true && it.ExitNodeID == null })
    // Drain any in-flight compensating callbacks.
    while (writer.pendingCallbacks() > 0) {
      writer.completeNext(Result.success(Unit))
      runCurrent()
    }

    val restored = async { controller.mutateExitNode(ExitNodeMutation.Manual("node-a")) }
    runCurrent()
    if (writer.pendingCallbacks() > 0) writer.completeNext(Result.success(Unit))
    assertTrue(restored.await().isSuccess)
  }


  @Test
  fun clearDeniedWhileRuntimeRunning() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    val writer = MutationWriter()
    val runtime = MutationRuntime(VpnRuntimeState.Running)
    val controller = controller(decisions, writer = writer, runtime = runtime)

    val result = controller.mutateExitNode(ExitNodeMutation.Clear())

    assertTrue(result.isFailure)
    assertTrue(writer.writes.isEmpty())
  }

  @Test
  fun stopThenClearClearsWhenFenceMakesIdle() = runTest {
    val decisions = MutationDecisionSource(AccessState.Active(setOf("node-a")))
    val writer = MutationWriter()
    val runtime = MutationRuntime(VpnRuntimeState.Running)
    val fence = VpnStopFence {
      runtime.state.value = VpnRuntimeState.Idle
      Result.success(Unit)
    }
    val controller =
        VpnEntitlementController(
            decisionSource = decisions,
            runtime = runtime,
            scope = backgroundScope,
            refreshInterval = Duration.ofSeconds(30),
            exitNodeMutationPolicyGuard =
                ExitNodeMutationPolicyGuard { _, mutation -> mutation is ExitNodeMutation.Clear },
            stopFence = fence,
            exitNodePreferenceWriter = writer,
        )

    val result = controller.stopThenClearExitNode(VpnStopReason.ExitNodeDisallowed)

    assertTrue(result.isSuccess)
    assertEquals(1, writer.writes.size)
    assertNull(writer.writes.single().ExitNodeID)
  }

  private fun kotlinx.coroutines.test.TestScope.controller(
      decisions: MutationDecisionSource,
      writer: ExitNodePreferenceWriter,
      beforeWrite: suspend () -> Unit = {},
      runtime: MutationRuntime = MutationRuntime(),
      mutationTimeout: Duration = Duration.ofSeconds(35),
  ) =
      VpnEntitlementController(
          decisionSource = decisions,
          runtime = runtime,
          scope = backgroundScope,
          refreshInterval = Duration.ofSeconds(30),
          exitNodeMutationPolicyGuard =
              ExitNodeMutationPolicyGuard { active, mutation ->
                when (mutation) {
                  is ExitNodeMutation.Auto -> active.allowedExitNodeIds.isNotEmpty()
                  is ExitNodeMutation.Manual ->
                      mutation.nodeId.trim() in active.allowedExitNodeIds &&
                          mutation.nodeId.trim() != "auto:any"
                  is ExitNodeMutation.Clear -> true
                }
              },
          beforeExitNodeMutationWrite = beforeWrite,
          exitNodeMutationTimeout = mutationTimeout,
          exitNodePreferenceWriter = writer,
      )
}

private class MutationDecisionSource(default: AccessState) : VpnEntitlementDecisionSource {
  val authentik = MutableStateFlow<AuthentikState>(AuthentikState.Authorized)
  override val authentikState: StateFlow<AuthentikState> = authentik
  val access = MutableStateFlow(default)
  override val accessState: StateFlow<AccessState> = access
  var refreshCalls = 0
  var refreshError: Throwable? = null

  override suspend fun refreshAccess(origin: VpnStartOrigin?): AccessState {
    refreshCalls++
    refreshError?.let { throw it }
    return access.value
  }
}

private class MutationRuntime(initialState: VpnRuntimeState = VpnRuntimeState.Idle) :
    VpnEntitlementRuntime {
  override val state = MutableStateFlow(initialState)
  var revocations = 0

  override fun revoke() {
    revocations++
    // Mirror production: after a stop fence completes the runtime is Idle so Clear is allowed.
    state.value = VpnRuntimeState.Idle
  }
}

private class MutationWriter(
    private val autoComplete: Boolean = true,
    private val throwSynchronously: Boolean = false,
    private val completeTwice: Boolean = false,
    private val beforeComplete: (Int) -> Unit = {},
) : ExitNodePreferenceWriter {
  val writes = mutableListOf<Ipn.MaskedPrefs>()
  private val callbacks = ArrayDeque<(Result<Unit>) -> Unit>()

  override fun write(prefs: Ipn.MaskedPrefs, onComplete: (Result<Unit>) -> Unit) {
    if (throwSynchronously) error("writer unavailable")
    val index = writes.size
    writes += prefs
    if (autoComplete) {
      beforeComplete(index)
      onComplete(Result.success(Unit))
      if (completeTwice) onComplete(Result.failure(IllegalStateException("duplicate")))
    } else {
      callbacks += onComplete
    }
  }

  fun completeNext(result: Result<Unit>) {
    callbacks.removeFirst()(result)
  }

  fun pendingCallbacks(): Int = callbacks.size
}
