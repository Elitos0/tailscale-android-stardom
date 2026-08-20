// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.policy.AccessState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StardomAccessBootstrapTest {
  @Test
  fun persistedAuthorizedStartupRefreshesWithoutMainViewResume() = runTest {
    val authentik = MutableStateFlow<AuthentikState>(AuthentikState.Authorized)
    var refreshes = 0
    val bootstrap =
        StardomAccessBootstrap(
            authentikState = authentik,
            scope = backgroundScope,
            refreshAccess = {
              refreshes++
              AccessState.Active(setOf("node-a"))
            },
        )

    assertEquals(true, bootstrap.start() != null)
    runCurrent()

    assertEquals(1, refreshes)
  }

  @Test
  fun signedOutStartupDoesNotRefresh() = runTest {
    var refreshes = 0
    val bootstrap =
        StardomAccessBootstrap(
            authentikState = MutableStateFlow<AuthentikState>(AuthentikState.SignedOut),
            scope = backgroundScope,
            refreshAccess = {
              refreshes++
              AccessState.Unavailable
            },
        )

    assertNull(bootstrap.start())
    runCurrent()

    assertEquals(0, refreshes)
  }

  @Test
  fun reauthenticationRequiredStartupDoesNotRefresh() = runTest {
    var refreshes = 0
    val bootstrap =
        StardomAccessBootstrap(
            authentikState =
                MutableStateFlow<AuthentikState>(AuthentikState.ReauthenticationRequired),
            scope = backgroundScope,
            refreshAccess = {
              refreshes++
              AccessState.Unavailable
            },
        )

    assertNull(bootstrap.start())
    runCurrent()

    assertEquals(0, refreshes)
  }

  @Test
  fun unavailableResultDoesNotRetryOrDispatchOtherWork() = runTest {
    val events = mutableListOf<String>()
    val bootstrap =
        StardomAccessBootstrap(
            authentikState = MutableStateFlow<AuthentikState>(AuthentikState.Authorized),
            scope = backgroundScope,
            refreshAccess = {
              events += "refresh"
              AccessState.Unavailable
            },
        )

    bootstrap.start()
    runCurrent()

    assertEquals(listOf("refresh"), events)
  }

  @Test
  fun repeatedStartDispatchesAtMostOneRefresh() = runTest {
    var refreshes = 0
    val bootstrap =
        StardomAccessBootstrap(
            authentikState = MutableStateFlow<AuthentikState>(AuthentikState.Authorized),
            scope = backgroundScope,
            refreshAccess = {
              refreshes++
              AccessState.Active(emptySet())
            },
        )

    bootstrap.start()
    bootstrap.start()
    runCurrent()

    assertEquals(1, refreshes)
  }

  @Test
  fun productStartupClearsStickyWantRunningBeforeAccessRefresh() = runTest {
    val events = mutableListOf<String>()

    startStardomProductObservers(
        startPolicyObserver = { events += "policy" },
        startFallbackObserver = { events += "fallback" },
        clearStickyWantRunning = { events += "fence" },
        startAccessBootstrap = { events += "access" },
    )

    assertEquals(listOf("policy", "fallback", "fence", "access"), events)
  }

  @Test
  fun processStartVpnFenceClearsWantRunningOnce() {
    var clears = 0
    val fence = StardomProcessStartVpnFence { complete ->
      clears++
      complete(Result.success(Unit))
    }

    assertEquals(true, fence.apply())
    assertEquals(false, fence.apply())
    assertEquals(1, clears)
  }
}
