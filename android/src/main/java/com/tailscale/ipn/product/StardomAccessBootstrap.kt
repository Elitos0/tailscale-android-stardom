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
 * This coordinator is deliberately independent from the VPN, notification, and UI lifecycles:
 * it only reads the persisted Authentik state and publishes the result through the supplied
 * refresh function. A process-start attempt is one-shot, including a non-authorized attempt.
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
 * Keeps product observer startup ordering explicit and testable: policy, fallback, then access.
 */
fun startStardomProductObservers(
    startPolicyObserver: () -> Unit,
    startFallbackObserver: () -> Unit,
    startAccessBootstrap: () -> Unit,
) {
  startPolicyObserver()
  startFallbackObserver()
  startAccessBootstrap()
}
