// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product

import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.policy.AccessState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Performs the process-start Policy API refresh for an already-authorized Stardom session.
 *
 * This coordinator is deliberately independent from the VPN, notification, and UI lifecycles: it
 * only reads the persisted Authentik state and publishes the result through the supplied refresh
 * function. A process-start attempt is one-shot, including a non-authorized attempt.
 */
class StardomAccessBootstrap(
    private val authentikState: StateFlow<AuthentikState>,
    private val scope: CoroutineScope,
    private val refreshAccess: suspend () -> AccessState,
) {
  private val started = AtomicBoolean(false)

  /** Starts one authorized-only refresh, or returns null when no refresh is eligible. */
  fun start(): Job? {
    if (!started.compareAndSet(false, true)) return null
    if (authentikState.value != AuthentikState.Authorized) return null
    return scope.launch { refreshAccess() }
  }
}

/**
 * Clears sticky WantRunning left over from a previous process death.
 *
 * Opening the app (or process restart after force-stop) must never resume the Android VPN tunnel
 * just because LocalBackend prefs still say WantRunning=true. Always-On / explicit Connect remain
 * the only ways to start the tunnel after a fresh process.
 */
class StardomProcessStartVpnFence(
    private val clearWantRunning: (onComplete: (Result<Unit>) -> Unit) -> Unit,
) {
  private val applied = AtomicBoolean(false)

  /** Queues WantRunning=false once. Returns true if this call dispatched the clear. */
  fun apply(): Boolean {
    if (!applied.compareAndSet(false, true)) return false
    clearWantRunning { /* fire-and-forget; fence is one-shot regardless of callback */}
    return true
  }
}

/**
 * Keeps product observer startup ordering explicit and testable: policy, fallback, process-start
 * VPN fence, then access refresh.
 */
fun startStardomProductObservers(
    startPolicyObserver: () -> Unit,
    startFallbackObserver: () -> Unit,
    clearStickyWantRunning: () -> Unit,
    startAccessBootstrap: () -> Unit,
) {
  startPolicyObserver()
  startFallbackObserver()
  clearStickyWantRunning()
  startAccessBootstrap()
}
