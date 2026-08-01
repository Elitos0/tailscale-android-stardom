// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.mdm.SettingState
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.ui.model.Ipn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AllowedSuggestedExitNodePolicyTest {
  @Test
  fun authorizedActivePolicyNormalizesSortsAndDeduplicatesCandidates() {
    assertEquals(
        listOf("node-a", "node-b"),
        AllowedSuggestedExitNodePolicyMapper.map(
            authentikState = AuthentikState.Authorized,
            accessState = AccessState.Active(setOf(" node-b ", "", "node-a", "  ")),
            mdm = ManagedAllowedSuggestedExitNodes.Unset,
        ),
    )
  }

  @Test
  fun nonAuthorizedAndNonActiveStatesAreExplicitlyEmpty() {
    val nonAuthorized =
        listOf(
            AuthentikState.SignedOut,
            AuthentikState.Authorizing,
            AuthentikState.ReauthenticationRequired,
        )
    nonAuthorized.forEach { authentik ->
      assertEquals(
          emptyList<String>(),
          AllowedSuggestedExitNodePolicyMapper.map(
              authentik,
              AccessState.Active(setOf("node-a")),
              ManagedAllowedSuggestedExitNodes.Unset,
          ),
      )
    }

    listOf(AccessState.Disabled, AccessState.Unavailable, AccessState.Active(emptySet())).forEach {
        access ->
      assertEquals(
          emptyList<String>(),
          AllowedSuggestedExitNodePolicyMapper.map(
              AuthentikState.Authorized,
              access,
              ManagedAllowedSuggestedExitNodes.Unset,
          ),
      )
    }
  }

  @Test
  fun explicitlyConfiguredMdmListIntersectsProductCandidates() {
    val access = AccessState.Active(setOf("node-a", "node-b", "node-c"))

    assertEquals(
        listOf("node-a", "node-b", "node-c"),
        AllowedSuggestedExitNodePolicyMapper.map(
            AuthentikState.Authorized,
            access,
            ManagedAllowedSuggestedExitNodes.Unset,
        ),
    )
    assertEquals(
        listOf("node-a", "node-c"),
        AllowedSuggestedExitNodePolicyMapper.map(
            AuthentikState.Authorized,
            access,
            ManagedAllowedSuggestedExitNodes.Configured(
                listOf(" node-c ", "node-a", "node-a", "", "foreign-node")),
        ),
    )
    assertEquals(
        emptyList<String>(),
        AllowedSuggestedExitNodePolicyMapper.map(
            AuthentikState.Authorized,
            access,
            ManagedAllowedSuggestedExitNodes.Configured(emptyList()),
        ),
    )
    assertEquals(
        emptyList<String>(),
        AllowedSuggestedExitNodePolicyMapper.map(
            AuthentikState.Authorized,
            access,
            ManagedAllowedSuggestedExitNodes.Configured(null),
        ),
    )
  }

  @Test
  fun bridgeReturnsExplicitEmptyJsonForSourceAndSerializationFailures() {
    val sourceFailure = fixture(candidateMapper = { _, _, _ -> error("policy source failed") })
    val serializationFailure = fixture(jsonEncoder = { error("serialization failed") })

    assertEquals("[]", sourceFailure.controller.currentCandidatesJSON())
    assertEquals("[]", serializationFailure.controller.currentCandidatesJSON())
  }

  @Test
  fun bridgeNeverUsesUnsetSemanticsForEveryFailClosedState() {
    val fixture = fixture()

    assertEquals("[]", fixture.controller.currentCandidatesJSON())
    fixture.authentik.value = AuthentikState.Authorized
    fixture.access.value = AccessState.Disabled
    assertEquals("[]", fixture.controller.currentCandidatesJSON())
    fixture.access.value = AccessState.Unavailable
    assertEquals("[]", fixture.controller.currentCandidatesJSON())
    fixture.access.value = AccessState.Active(emptySet())
    assertEquals("[]", fixture.controller.currentCandidatesJSON())
    fixture.authentik.value = AuthentikState.ReauthenticationRequired
    fixture.access.value = AccessState.Active(setOf("node-a"))
    assertEquals("[]", fixture.controller.currentCandidatesJSON())
  }

  @Test
  fun synchronousBridgeReadHasNoObserverSideEffects() {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
        )

    assertEquals("[\"node-b\"]", fixture.controller.currentCandidatesJSON())
    assertEquals(0, fixture.notifications)
    assertEquals(0, fixture.revocations)
  }

  @Test
  fun syspolicyBridgeSpecialCasesOnlyTheStardomCandidateKey() {
    var mdmReads = 0

    assertEquals(
        "[]",
        SyspolicyStringArrayJSONBridge.get(
            ALLOWED_SUGGESTED_EXIT_NODES_KEY,
            productCandidatesJSON = { error("source failed") },
            fallbackValue = {
              mdmReads++
              error("fallback must not be used")
            },
        ),
    )
    assertEquals(0, mdmReads)
    assertEquals(
        "[\"managed-node\"]",
        SyspolicyStringArrayJSONBridge.get(
            "HiddenNetworkDevices",
            productCandidatesJSON = { error("must not be used") },
            fallbackValue = { "[\"managed-node\"]" },
        ),
    )
    assertThrows(com.tailscale.ipn.mdm.MDMSettings.NoSuchKeyException::class.java) {
      SyspolicyStringArrayJSONBridge.get(
          "MissingKey",
          productCandidatesJSON = { "[]" },
          fallbackValue = { throw com.tailscale.ipn.mdm.MDMSettings.NoSuchKeyException() },
      )
    }
  }

  @Test
  fun observerNotifiesOnlyForDistinctEffectiveCandidateChanges() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
        )
    assertEquals("[\"node-a\"]", fixture.controller.currentCandidatesJSON())

    fixture.controller.start(backgroundScope)
    fixture.controller.start(backgroundScope)
    runCurrent()
    fixture.access.value = AccessState.Active(setOf(" node-a ", ""))
    runCurrent()
    fixture.mdm.value = SettingState(listOf("node-b", "node-a", "node-a", " "), true)
    runCurrent()
    fixture.access.value = AccessState.Active(setOf("node-a", "node-b"))
    runCurrent()
    fixture.access.value = AccessState.Active(setOf("node-b", "node-a"))
    runCurrent()

    assertEquals(1, fixture.notifications)
  }

  @Test
  fun observerCatchesCandidateChangeDuringLibtailscaleInitialization() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
        )
    assertEquals("[\"node-a\"]", fixture.controller.currentCandidatesJSON())

    fixture.access.value = AccessState.Active(setOf("node-b"))
    fixture.controller.start(backgroundScope)
    runCurrent()

    assertEquals(1, fixture.notifications)
  }

  @Test
  fun staleEffectiveAutoExitNodeStopsBeforeNativePolicyNotification() = runTest {
    val events = mutableListOf<String>()
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a", "node-b")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
            onNotify = { events += "notify" },
            onRevoke = { events += "revoke" },
        )
    fixture.controller.currentCandidatesJSON()
    fixture.controller.start(backgroundScope)
    runCurrent()

    fixture.access.value = AccessState.Active(setOf("node-b"))
    runCurrent()

    assertEquals(listOf("revoke", "notify"), events)
  }

  @Test
  fun blackholeMarkerAndManualModeAreSafeButMissingAutoEffectiveIdStopsVpn() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
        )
    fixture.controller.start(backgroundScope)
    runCurrent()

    fixture.prefs.value = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "auto:any")
    runCurrent()
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = null)
    runCurrent()
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "  ")
    runCurrent()
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = null, ExitNodeID = "node-a")
    runCurrent()

    assertEquals(1, fixture.revocations)
  }

  @Test
  fun synchronousStartGateFailsClosedUntilPrefsAndExplicitAutoResolutionAreKnown() {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
        )
    val active = AccessState.Active(setOf("node-a"))

    assertEquals(false, fixture.controller.isVpnStartAllowed(active))
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = null)
    assertEquals(false, fixture.controller.isVpnStartAllowed(active))
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = " ")
    assertEquals(false, fixture.controller.isVpnStartAllowed(active))
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "auto:any")
    assertEquals(true, fixture.controller.isVpnStartAllowed(active))
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a")
    assertEquals(true, fixture.controller.isVpnStartAllowed(active))
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = null, ExitNodeID = "foreign-node")
    assertEquals(false, fixture.controller.isVpnStartAllowed(active))
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = null, ExitNodeID = "node-a")
    assertEquals(true, fixture.controller.isVpnStartAllowed(active))
  }

  @Test
  fun disallowedManualSelectionIsClearedAndStopsRunningTunnel() = runTest {
    val events = mutableListOf<String>()
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(AutoExitNode = null, ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
            onRevoke = { events += "revoke" },
            onClear = { events += "clear" },
        )

    fixture.controller.start(backgroundScope)
    runCurrent()

    assertEquals(listOf("revoke", "clear"), events)
    assertEquals(1, fixture.revocations)
    assertEquals(1, fixture.clears)
  }

  @Test
  fun staleAutoSelectionRevokesButPreservesNativeAutoModeForFailover() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
        )

    fixture.controller.start(backgroundScope)
    runCurrent()

    assertEquals(1, fixture.revocations)
    assertEquals(0, fixture.clears)
  }

  @Test
  fun unknownPrefsAndMdmForcedManualSelectionAreNeverCleared() = runTest {
    val unknownPrefs =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
            prefs = null,
            runtimeState = VpnRuntimeState.Running,
        )
    val mdmForced =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
            canClear = false,
        )

    unknownPrefs.controller.start(backgroundScope)
    mdmForced.controller.start(backgroundScope)
    runCurrent()

    assertEquals(0, unknownPrefs.clears)
    assertEquals(0, mdmForced.clears)
    assertEquals(1, unknownPrefs.revocations)
    assertEquals(1, mdmForced.revocations)
  }

  @Test
  fun failedManualClearRetriesWithoutAnotherPolicyEmission() = runTest {
    var clearAttempts = 0
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(ExitNodeID = "node-a"),
            onClearResult = {
              clearAttempts++
              if (clearAttempts == 1) Result.failure(IllegalStateException("write failed"))
              else Result.success(Unit)
            },
        )

    fixture.controller.start(backgroundScope)
    runCurrent()
    advanceTimeBy(251)
    runCurrent()

    assertEquals(2, fixture.clears)
  }

  @Test
  fun persistentlyFailingManualClearHasABoundedRetryCount() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(ExitNodeID = "node-a"),
            onClearResult = { Result.failure(IllegalStateException("still failing")) },
        )

    fixture.controller.start(backgroundScope)
    runCurrent()
    advanceTimeBy(2_000)
    runCurrent()

    assertEquals(3, fixture.clears)
  }

  @Test
  fun safeTransitionResetsBoundedClearRetryBudgetForLaterRecurrence() = runTest {
    var attempts = 0
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(ExitNodeID = "node-a"),
            onClearResult = {
              attempts++
              if (attempts <= 4) Result.failure(IllegalStateException("transient"))
              else Result.success(Unit)
            },
        )
    fixture.controller.start(backgroundScope)
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()
    assertEquals(3, fixture.clears)

    fixture.prefs.value = Ipn.Prefs(ExitNodeID = "node-b")
    runCurrent()
    fixture.prefs.value = Ipn.Prefs(ExitNodeID = "node-a")
    runCurrent()
    advanceTimeBy(251)
    runCurrent()

    assertEquals(5, fixture.clears)
  }

  @Test
  fun removingMdmForcedExitNodeTriggersMutableManualClear() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(ExitNodeID = "node-a"),
            canClear = false,
        )
    fixture.controller.start(backgroundScope)
    runCurrent()
    assertEquals(0, fixture.clears)

    fixture.forcedExitNode.value = SettingState(null, false)
    runCurrent()

    assertEquals(1, fixture.clears)
  }

  @Test
  fun mdmIntersectionRejectsManualMutationOutsideEffectiveCandidates() {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a", "node-b")),
        )
    fixture.mdm.value = SettingState(listOf("node-b"), true)
    val active = AccessState.Active(setOf("node-a", "node-b"))

    assertEquals(
        false,
        fixture.controller.isExitNodeMutationAllowed(active, ExitNodeMutation.Manual("node-a")),
    )
    assertEquals(
        true,
        fixture.controller.isExitNodeMutationAllowed(active, ExitNodeMutation.Manual("node-b")),
    )
    assertEquals(
        true,
        fixture.controller.isExitNodeMutationAllowed(active, ExitNodeMutation.Auto()),
    )
    assertEquals(
        false,
        fixture.controller.isExitNodeMutationAllowed(active, ExitNodeMutation.Manual("auto:any")),
    )
  }

  @Test
  fun synchronousStartGateFailsClosedForPolicyAndSerializationFailures() {
    val active = AccessState.Active(setOf("node-a"))
    val sourceFailure =
        fixture(
            authentik = AuthentikState.Authorized,
            access = active,
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            candidateMapper = { _, _, _ -> error("source failed") },
        )
    val serializationFailure =
        fixture(
            authentik = AuthentikState.Authorized,
            access = active,
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            jsonEncoder = { error("serialization failed") },
        )

    assertEquals(false, sourceFailure.controller.isVpnStartAllowed(active))
    assertEquals(false, serializationFailure.controller.isVpnStartAllowed(active))
  }

  @Test
  fun idleUnsafeStateDoesNotDispatchAStopServiceFallback() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Idle,
        )

    fixture.controller.start(backgroundScope)
    runCurrent()

    assertEquals(0, fixture.revocations)
  }

  @Test
  fun missingPrefsRevokesAnAlreadyStartingOrRunningTunnel() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
            prefs = null,
            runtimeState = VpnRuntimeState.Running,
        )

    fixture.controller.start(backgroundScope)
    runCurrent()

    assertEquals(1, fixture.revocations)
  }

  @Test
  fun unsafeRepeatEmissionsAreDeduplicatedButANewRuntimeRunIsStopped() = runTest {
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
        )
    fixture.controller.start(backgroundScope)
    runCurrent()
    fixture.mdm.value = SettingState(listOf("node-b", "node-b"), true)
    runCurrent()
    fixture.access.value = AccessState.Active(setOf(" node-b "))
    runCurrent()

    assertEquals(1, fixture.revocations)

    fixture.runtime.value = VpnRuntimeSnapshot(VpnRuntimeState.Idle, generation = 1)
    runCurrent()
    fixture.runtime.value = VpnRuntimeSnapshot(VpnRuntimeState.Starting, generation = 2)
    runCurrent()

    assertEquals(2, fixture.revocations)
  }

  @Test
  fun conflatedIdleBetweenUnsafeRuntimeGenerationsStillRevokesTheNewRun() = runTest {
    val runtimeSnapshot =
        MutableStateFlow(VpnRuntimeSnapshot(VpnRuntimeState.Running, generation = 1))
    var revocations = 0
    val controller =
        AllowedSuggestedExitNodePolicyController(
            authentikState = MutableStateFlow(AuthentikState.Authorized),
            accessState = MutableStateFlow(AccessState.Active(setOf("node-b"))),
            mdmAllowedSuggestedExitNodes =
                MutableStateFlow(SettingState<List<String>?>(null, false)),
            prefs = MutableStateFlow(Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a")),
            runtimeSnapshot = runtimeSnapshot,
            notifyPolicyChanged = {},
            revokeDisallowedAutoExitNode = { revocations++ },
        )
    controller.start(backgroundScope)
    runCurrent()
    assertEquals(1, revocations)

    runtimeSnapshot.value = VpnRuntimeSnapshot(VpnRuntimeState.Idle, generation = 1)
    runtimeSnapshot.value = VpnRuntimeSnapshot(VpnRuntimeState.Starting, generation = 2)
    runCurrent()
    runtimeSnapshot.value = VpnRuntimeSnapshot(VpnRuntimeState.Running, generation = 2)
    runCurrent()

    assertEquals(2, revocations)
  }

  @Test
  fun policyOrSerializationFailureStopsAStaleAutoExitNode() = runTest {
    val sourceFailure =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
            candidateMapper = { _, _, _ -> error("policy source failed") },
        )
    val serializationFailure =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
            jsonEncoder = { error("serialization failed") },
        )

    sourceFailure.controller.start(backgroundScope)
    serializationFailure.controller.start(backgroundScope)
    runCurrent()

    assertEquals(1, sourceFailure.revocations)
    assertEquals(1, serializationFailure.revocations)
  }

  @Test
  fun callbackFailuresDoNotCancelTheObserverOrPermanentlyDeduplicateEnforcement() = runTest {
    var notifyAttempts = 0
    val notificationFixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
            onNotify = {
              notifyAttempts++
              if (notifyAttempts == 1) error("notify failed")
            },
        )
    notificationFixture.controller.currentCandidatesJSON()
    notificationFixture.controller.start(backgroundScope)
    runCurrent()
    notificationFixture.access.value = AccessState.Active(setOf("node-b"))
    runCurrent()
    notificationFixture.prefs.value = Ipn.Prefs(AutoExitNode = null)
    runCurrent()

    var revokeAttempts = 0
    val revocationFixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-b")),
            prefs = Ipn.Prefs(AutoExitNode = "any", ExitNodeID = "node-a"),
            runtimeState = VpnRuntimeState.Running,
            onRevoke = {
              revokeAttempts++
              if (revokeAttempts == 1) error("revoke failed")
            },
        )
    revocationFixture.controller.start(backgroundScope)
    runCurrent()
    revocationFixture.mdm.value = SettingState(listOf("node-b", "node-b"), true)
    runCurrent()

    assertEquals(2, notifyAttempts)
    assertEquals(2, revokeAttempts)
  }

  @Test
  fun staleSynchronousReadCannotOverwriteObserverCandidateBaseline() = runTest {
    val firstReadStarted = CountDownLatch(1)
    val releaseFirstRead = CountDownLatch(1)
    val mapperCalls = AtomicInteger()
    val fixture =
        fixture(
            authentik = AuthentikState.Authorized,
            access = AccessState.Active(setOf("node-a")),
            candidateMapper = { auth, access, mdm ->
              if (mapperCalls.incrementAndGet() == 1) {
                firstReadStarted.countDown()
                check(releaseFirstRead.await(1, TimeUnit.SECONDS))
              }
              AllowedSuggestedExitNodePolicyMapper.map(auth, access, mdm)
            },
        )
    val returnedJSON = AtomicReference<String>()
    val staleReader =
        thread(start = true) { returnedJSON.set(fixture.controller.currentCandidatesJSON()) }
    check(firstReadStarted.await(1, TimeUnit.SECONDS))

    fixture.access.value = AccessState.Active(setOf("node-b"))
    fixture.controller.start(backgroundScope)
    runCurrent()
    releaseFirstRead.countDown()
    staleReader.join(1_000)
    check(!staleReader.isAlive)
    fixture.prefs.value = Ipn.Prefs(AutoExitNode = null)
    runCurrent()

    assertEquals("[\"node-b\"]", returnedJSON.get())
    assertEquals(0, fixture.notifications)
  }

  private fun fixture(
      authentik: AuthentikState = AuthentikState.SignedOut,
      access: AccessState = AccessState.Unavailable,
      prefs: Ipn.Prefs? = null,
      runtimeState: VpnRuntimeState = VpnRuntimeState.Idle,
      candidateMapper:
          (AuthentikState, AccessState, ManagedAllowedSuggestedExitNodes) -> List<String> =
          AllowedSuggestedExitNodePolicyMapper::map,
      jsonEncoder: (List<String>) -> String =
          AllowedSuggestedExitNodePolicyController.Companion::encodeJSON,
      onNotify: () -> Unit = {},
      onRevoke: () -> Unit = {},
      onClear: () -> Unit = {},
      onClearResult: () -> Result<Unit> = { Result.success(Unit) },
      canClear: Boolean = true,
  ): Fixture {
    val authentikFlow = MutableStateFlow(authentik)
    val accessFlow = MutableStateFlow(access)
    val mdmFlow = MutableStateFlow(SettingState<List<String>?>(null, false))
    val forcedExitNodeFlow =
        MutableStateFlow(SettingState<String?>(if (canClear) null else "forced-node", !canClear))
    val prefsFlow = MutableStateFlow(prefs)
    val runtimeFlow =
        MutableStateFlow(
            VpnRuntimeSnapshot(
                runtimeState,
                generation = if (runtimeState == VpnRuntimeState.Idle) 0 else 1,
            ))
    var notifications = 0
    var revocations = 0
    var clears = 0
    val controller =
        AllowedSuggestedExitNodePolicyController(
            authentikState = authentikFlow,
            accessState = accessFlow,
            mdmAllowedSuggestedExitNodes = mdmFlow,
            mdmForcedExitNodeId = forcedExitNodeFlow,
            prefs = prefsFlow,
            runtimeSnapshot = runtimeFlow,
            notifyPolicyChanged = {
              notifications++
              onNotify()
            },
            revokeDisallowedAutoExitNode = {
              revocations++
              onRevoke()
            },
            clearDisallowedExitNode = { complete ->
              clears++
              onClear()
              complete(onClearResult())
            },
            candidateMapper = candidateMapper,
            jsonEncoder = jsonEncoder,
        )
    return Fixture(
        controller,
        authentikFlow,
        accessFlow,
        mdmFlow,
        forcedExitNodeFlow,
        prefsFlow,
        runtimeFlow,
        notificationCount = { notifications },
        revocationCount = { revocations },
        clearCount = { clears },
    )
  }

  private data class Fixture(
      val controller: AllowedSuggestedExitNodePolicyController,
      val authentik: MutableStateFlow<AuthentikState>,
      val access: MutableStateFlow<AccessState>,
      val mdm: MutableStateFlow<SettingState<List<String>?>>,
      val forcedExitNode: MutableStateFlow<SettingState<String?>>,
      val prefs: MutableStateFlow<Ipn.Prefs?>,
      val runtime: MutableStateFlow<VpnRuntimeSnapshot>,
      private val notificationCount: () -> Int,
      private val revocationCount: () -> Int,
      private val clearCount: () -> Int,
  ) {
    val notifications: Int
      get() = notificationCount()

    val revocations: Int
      get() = revocationCount()

    val clears: Int
      get() = clearCount()
  }
}
