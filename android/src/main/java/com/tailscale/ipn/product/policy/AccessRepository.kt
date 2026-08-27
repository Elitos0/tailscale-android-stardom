// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.util.TSLog
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthorizationException
import org.json.JSONArray
import org.json.JSONObject

data class CachedAccessPolicy(
    val policyVersion: String,
    val validUntilEpochMillis: Long,
    val allowedExitNodeIds: Set<String>,
)

class AccessRepository(
    private val policyApiClient: PolicyApiClient = PolicyApiClient(),
    private val cacheStore: AccessPolicyCacheStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  private val refreshMutex = Mutex()
  private val _state = MutableStateFlow<AccessState>(AccessState.Unavailable)
  val state: StateFlow<AccessState> = _state.asStateFlow()

  init {
    cacheStore
        ?.read()
        ?.takeIf { it.validUntilEpochMillis > nowMillis() }
        ?.let { cached -> _state.value = AccessState.Active(cached.allowedExitNodeIds) }
  }

  fun load(token: String): AccessState {
    val next = resolve(policyApiClient.load(token))
    _state.value = next
    return next
  }

  fun clear() {
    cacheStore?.clear()
    _state.value = AccessState.Unavailable
  }

  suspend fun refresh(context: Context, authSessionRepository: AuthSessionRepository): AccessState {
    return refreshMutex.withLock {
      val state =
          freshToken(context, authSessionRepository)
              .fold(
                  onSuccess = { token ->
                    val result = withContext(Dispatchers.IO) { policyApiClient.load(token) }
                    if (result == PolicyLoadResult.Unauthorized) {
                      TSLog.e(
                          "AuthLifecycle", "policy refresh unauthorized; reauthentication required")
                      authSessionRepository.requireReauthentication()
                    }
                    resolve(result)
                  },
                  onFailure = {
                    TSLog.e("AuthLifecycle", "policy refresh failed: ${it.message}", it)
                    // Invalid credentials must drop cache; transport errors keep last-valid.
                    val isAuthRevoked =
                        it.message == "Signed out" ||
                            (it is AuthorizationException &&
                                (it.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR ||
                                    it.error in setOf("invalid_grant", "invalid_token")))
                    if (isAuthRevoked) {
                      cacheStore?.clear()
                      AccessState.Unavailable
                    } else {
                      cachedActiveOrUnavailable()
                    }
                  })
      _state.value = state
      state
    }
  }

  private fun resolve(result: PolicyLoadResult): AccessState =
      when (result) {
        is PolicyLoadResult.Active -> {
          cacheStore?.write(
              CachedAccessPolicy(
                  policyVersion = result.policyVersion,
                  validUntilEpochMillis = result.validUntilEpochMillis,
                  allowedExitNodeIds = result.allowedExitNodeIds,
              ))
          AccessState.Active(result.allowedExitNodeIds)
        }
        PolicyLoadResult.Disabled -> {
          cacheStore?.clear()
          AccessState.Disabled
        }
        PolicyLoadResult.Unauthorized -> {
          cacheStore?.clear()
          AccessState.Unavailable
        }
        PolicyLoadResult.Unavailable -> cachedActiveOrUnavailable()
      }

  private fun cachedActiveOrUnavailable(): AccessState {
    val cached = cacheStore?.read() ?: return AccessState.Unavailable
    return if (cached.validUntilEpochMillis > nowMillis()) {
      AccessState.Active(cached.allowedExitNodeIds)
    } else {
      cacheStore.clear()
      AccessState.Unavailable
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

interface AccessPolicyCacheStore {
  fun read(): CachedAccessPolicy?

  fun write(policy: CachedAccessPolicy)

  fun clear()
}

class EncryptedAccessPolicyCacheStore(context: Context) : AccessPolicyCacheStore {
  private val preferences: SharedPreferences =
      EncryptedSharedPreferences.create(
          context,
          PREFS,
          MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
          EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
          EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

  override fun read(): CachedAccessPolicy? {
    val raw = preferences.getString(KEY, null) ?: return null
    return runCatching {
          val json = JSONObject(raw)
          val ids = json.getJSONArray("allowedExitNodeIds")
          val set = buildSet {
            for (i in 0 until ids.length()) {
              add(ids.getString(i))
            }
          }
          CachedAccessPolicy(
              policyVersion = json.getString("policyVersion"),
              validUntilEpochMillis = json.getLong("validUntilEpochMillis"),
              allowedExitNodeIds = set,
          )
        }
        .getOrNull()
  }

  override fun write(policy: CachedAccessPolicy) {
    val ids = JSONArray()
    policy.allowedExitNodeIds.forEach { ids.put(it) }
    preferences
        .edit()
        .putString(
            KEY,
            JSONObject()
                .put("policyVersion", policy.policyVersion)
                .put("validUntilEpochMillis", policy.validUntilEpochMillis)
                .put("allowedExitNodeIds", ids)
                .toString())
        .apply()
  }

  override fun clear() {
    preferences.edit().remove(KEY).apply()
  }

  private companion object {
    const val PREFS = "stardom_access_policy_cache"
    const val KEY = "cached_access_policy"
  }
}
