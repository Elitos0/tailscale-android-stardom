// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import android.content.Context
import com.tailscale.ipn.product.auth.AuthSessionRepository
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AccessRepository(private val policyApiClient: PolicyApiClient = PolicyApiClient()) {
  private val refreshMutex = Mutex()
  private val _state = MutableStateFlow<AccessState>(AccessState.Unavailable)
  val state: StateFlow<AccessState> = _state.asStateFlow()

  fun load(token: String): AccessState = policyApiClient.load(token)

  suspend fun refresh(context: Context, authSessionRepository: AuthSessionRepository): AccessState {
    return refreshMutex.withLock {
      val state =
          freshToken(context, authSessionRepository)
              .fold(
                  onSuccess = { token -> withContext(Dispatchers.IO) { load(token) } },
                  onFailure = { AccessState.Unavailable })
      _state.value = state
      state
    }
  }

  private suspend fun freshToken(
      context: Context,
      authSessionRepository: AuthSessionRepository
  ): Result<String> = suspendCancellableCoroutine { continuation ->
    authSessionRepository.withFreshBearerToken(context) { result ->
      if (continuation.isActive) {
        continuation.resume(result)
      }
    }
  }
}
