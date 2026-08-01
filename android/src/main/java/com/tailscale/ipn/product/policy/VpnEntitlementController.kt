// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.auth.AuthentikState
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class VpnStartOrigin {
  PermissionRequest,
  PermissionResult,
  AppStart,
  AppRestart,
  ServiceStart,
  ServiceRestart,
  AlwaysOn,
  StickyRestart,
}

enum class VpnRuntimeState {
  Idle,
  Starting,
  Running,
}

interface VpnEntitlementDecisionSource {
  val authentikState: StateFlow<AuthentikState>
  val accessState: StateFlow<AccessState>

  suspend fun refreshAccess(origin: VpnStartOrigin?): AccessState
}

interface VpnEntitlementRuntime {
  val state: StateFlow<VpnRuntimeState>

  fun revoke()
}

interface VpnStartAuthorizer {
  suspend fun authorizeStart(origin: VpnStartOrigin): Boolean
}

class VpnRequestBoundary(private val authorizer: VpnStartAuthorizer) {
  suspend fun requestIfAuthorized(origin: VpnStartOrigin, requestVpn: () -> Unit): Boolean {
    if (!authorizer.authorizeStart(origin)) return false
    requestVpn()
    return true
  }
}

class VpnEntitlementController(
    private val decisionSource: VpnEntitlementDecisionSource,
    private val runtime: VpnEntitlementRuntime,
    scope: CoroutineScope,
    refreshInterval: Duration = Duration.ofSeconds(30),
) : VpnStartAuthorizer {
  private val decisionMutex = Mutex()
  private val revocationMutex = Mutex()
  private val refreshIntervalMillis = refreshInterval.toMillis().coerceAtLeast(1)
  private var revokedForCurrentRun = false

  init {
    scope.launch {
      combine(
              decisionSource.authentikState,
              decisionSource.accessState,
              runtime.state,
          ) { authentik, access, runtimeState ->
            Triple(authentik, access, runtimeState)
          }
          .collect { (authentik, access, runtimeState) ->
            if (runtimeState == VpnRuntimeState.Idle) {
              resetRevocationGuard()
            } else if (authentik != AuthentikState.Authorized || access !is AccessState.Active) {
              revokeOnce()
            }
          }
    }
    scope.launch {
      runtime.state.collectLatest { runtimeState ->
        if (!runtimeState.isStartingOrRunning()) return@collectLatest
        refreshAndEnforce()
        while (true) {
          delay(refreshIntervalMillis)
          refreshAndEnforce()
        }
      }
    }
  }

  override suspend fun authorizeStart(origin: VpnStartOrigin): Boolean {
    val active = freshActiveAccess(origin) != null
    if (active && runtime.state.value == VpnRuntimeState.Idle) {
      resetRevocationGuard()
    } else if (!active && runtime.state.value.isStartingOrRunning()) {
      revokeOnce()
    }
    return active
  }

  suspend fun refreshRuntimeEntitlement() {
    if (runtime.state.value.isStartingOrRunning()) refreshAndEnforce()
  }

  suspend fun revokeRejectedRuntimeStart() {
    revokeOnce()
  }

  private suspend fun refreshAndEnforce() {
    if (freshActiveAccess(origin = null) == null && runtime.state.value.isStartingOrRunning()) {
      revokeOnce()
    }
  }

  private suspend fun freshActiveAccess(origin: VpnStartOrigin?): AccessState.Active? =
      decisionMutex.withLock {
        if (decisionSource.authentikState.value != AuthentikState.Authorized) return@withLock null
        val access = decisionSource.refreshAccess(origin)
        if (decisionSource.authentikState.value != AuthentikState.Authorized) return@withLock null
        access as? AccessState.Active
      }

  private suspend fun revokeOnce() {
    revocationMutex.withLock {
      if (revokedForCurrentRun) return
      revokedForCurrentRun = true
      runtime.revoke()
    }
  }

  private suspend fun resetRevocationGuard() {
    revocationMutex.withLock { revokedForCurrentRun = false }
  }

  private fun VpnRuntimeState.isStartingOrRunning(): Boolean =
      this == VpnRuntimeState.Starting || this == VpnRuntimeState.Running
}
