// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.auth.FakeAppAuthGateway
import com.tailscale.ipn.product.auth.FakeSessionState
import com.tailscale.ipn.product.auth.InMemoryAuthStateStorage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import net.openid.appauth.AuthorizationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class AccessRepositoryTest {
  @Test
  fun clearRemovesPreviouslyActiveAccess() {
    val repository =
        repository(status = 200, body = "{\"access\":\"active\",\"allowedExitNodes\":[]}")

    repository.load("token")

    assertEquals(AccessState.Active(emptySet()), repository.state.value)
    repository.clear()

    assertEquals(AccessState.Unavailable, repository.state.value)
  }

  @Test
  fun forbiddenResponseDisablesAccess() {
    val repository = repository(status = 403)

    assertEquals(AccessState.Disabled, repository.load("token"))
  }

  @Test
  fun networkFailureMakesAccessUnavailable() {
    val repository =
        AccessRepository(PolicyApiClient(connectionFactory = { throw IOException("offline") }))

    assertEquals(AccessState.Unavailable, repository.load("token"))
  }

  @Test
  fun nonHttpsBaseUrlMakesAccessUnavailableWithoutOpeningAConnection() {
    var openedConnection = false
    val repository =
        AccessRepository(
            PolicyApiClient(
                baseUrl = "http://policy.invalid",
                connectionFactory = {
                  openedConnection = true
                  FakeHttpURLConnection(200, "")
                }))

    assertEquals(AccessState.Unavailable, repository.load("token"))
    assertFalse(openedConnection)
  }

  @Test
  fun malformedHttpsBaseUrlMakesAccessUnavailableWithoutOpeningAConnection() {
    var openedConnection = false
    val repository =
        AccessRepository(
            PolicyApiClient(
                baseUrl = "https://",
                connectionFactory = {
                  openedConnection = true
                  FakeHttpURLConnection(200, "")
                }))

    assertEquals(AccessState.Unavailable, repository.load("token"))
    assertFalse(openedConnection)
  }

  @Test
  fun unauthorizedResponseClearsUnexpiredCache() {
    val now = 1_000_000L
    val cache =
        object : AccessPolicyCacheStore {
          var policy: CachedAccessPolicy? = CachedAccessPolicy("v1", now + 60_000, setOf("node-a"))

          override fun read() = policy

          override fun write(policy: CachedAccessPolicy) {
            this.policy = policy
          }

          override fun clear() {
            policy = null
          }
        }
    val repository =
        AccessRepository(
            PolicyApiClient(connectionFactory = { FakeHttpURLConnection(401, "") }),
            cacheStore = cache,
            nowMillis = { now },
        )

    assertEquals(AccessState.Unavailable, repository.load("token"))
    assertNull(cache.policy)
  }

  @Test
  fun serverErrorMakesAccessUnavailable() {
    val repository = repository(status = 500)

    assertEquals(AccessState.Unavailable, repository.load("token"))
  }

  @Test
  fun malformedResponseMakesAccessUnavailable() {
    val repository = repository(status = 200, body = "not json")

    assertEquals(AccessState.Unavailable, repository.load("token"))
  }

  @Test
  fun activeResponseExposesAllowedStableNodeIds() {
    val repository =
        repository(
            status = 200,
            body =
                """{"access":"active","allowedExitNodes":[{"stableNodeId":"node-b","label":"Beta"},{"stableNodeId":"node-a","label":"Alpha"}],"ignored":"value"}""")

    assertEquals(AccessState.Active(setOf("node-a", "node-b")), repository.load("token"))
  }

  @Test
  fun unexpiredCacheSurvivesTransientFailure() {
    val now = 1_000_000L
    val cache =
        object : AccessPolicyCacheStore {
          var policy: CachedAccessPolicy? = CachedAccessPolicy("v1", now + 60_000, setOf("node-a"))

          override fun read() = policy

          override fun write(policy: CachedAccessPolicy) {
            this.policy = policy
          }

          override fun clear() {
            policy = null
          }
        }
    val repository =
        AccessRepository(
            PolicyApiClient(connectionFactory = { throw IOException("offline") }),
            cacheStore = cache,
            nowMillis = { now },
        )
    assertEquals(AccessState.Active(setOf("node-a")), repository.load("token"))
  }

  @Test
  fun forbiddenClearsCache() {
    val now = 1_000_000L
    val cache =
        object : AccessPolicyCacheStore {
          var policy: CachedAccessPolicy? = CachedAccessPolicy("v1", now + 60_000, setOf("node-a"))

          override fun read() = policy

          override fun write(policy: CachedAccessPolicy) {
            this.policy = policy
          }

          override fun clear() {
            policy = null
          }
        }
    val repository =
        AccessRepository(
            PolicyApiClient(connectionFactory = { FakeHttpURLConnection(403, "") }),
            cacheStore = cache,
            nowMillis = { now },
        )
    assertEquals(AccessState.Disabled, repository.load("token"))
    assertNull(cache.policy)
  }

  @Test
  fun refreshWithRevokedTokenClearsCacheAndReturnsUnavailable() = runBlocking {
    val now = 1_000_000L
    val cache =
        object : AccessPolicyCacheStore {
          var policy: CachedAccessPolicy? = CachedAccessPolicy("v1", now + 60_000, setOf("node-a"))

          override fun read() = policy

          override fun write(policy: CachedAccessPolicy) {
            this.policy = policy
          }

          override fun clear() {
            policy = null
          }
        }
    val authSessionRepository =
        AuthSessionRepository(
            InMemoryAuthStateStorage("state"),
            FakeSessionState(isAuthorized = true),
            FakeAppAuthGateway(
                freshException = AuthorizationException.TokenRequestErrors.INVALID_GRANT))
    val repository =
        AccessRepository(
            PolicyApiClient(connectionFactory = { throw IOException("offline") }),
            cacheStore = cache,
            nowMillis = { now },
        )
    val state = repository.refresh(mock(), authSessionRepository)
    assertEquals(AccessState.Unavailable, state)
    assertNull(cache.policy)
  }

  @Test
  fun refreshWithSignedOutClearsCacheAndReturnsUnavailable() = runBlocking {
    val now = 1_000_000L
    val cache =
        object : AccessPolicyCacheStore {
          var policy: CachedAccessPolicy? = CachedAccessPolicy("v1", now + 60_000, setOf("node-a"))

          override fun read() = policy

          override fun write(policy: CachedAccessPolicy) {
            this.policy = policy
          }

          override fun clear() {
            policy = null
          }
        }
    val authSessionRepository =
        AuthSessionRepository(
            InMemoryAuthStateStorage(null),
            FakeSessionState(isAuthorized = false),
            FakeAppAuthGateway())
    val repository =
        AccessRepository(
            PolicyApiClient(connectionFactory = { throw IOException("offline") }),
            cacheStore = cache,
            nowMillis = { now },
        )
    val state = repository.refresh(mock(), authSessionRepository)
    assertEquals(AccessState.Unavailable, state)
    assertNull(cache.policy)
  }

  @Test
  fun refreshWithNetworkErrorPreservesValidCache() = runBlocking {
    val now = 1_000_000L
    val cache =
        object : AccessPolicyCacheStore {
          var policy: CachedAccessPolicy? = CachedAccessPolicy("v1", now + 60_000, setOf("node-a"))

          override fun read() = policy

          override fun write(policy: CachedAccessPolicy) {
            this.policy = policy
          }

          override fun clear() {
            policy = null
          }
        }
    val authSessionRepository =
        AuthSessionRepository(
            InMemoryAuthStateStorage("state"),
            FakeSessionState(isAuthorized = true),
            FakeAppAuthGateway(freshException = AuthorizationException.GeneralErrors.NETWORK_ERROR))
    val repository =
        AccessRepository(
            PolicyApiClient(connectionFactory = { throw IOException("offline") }),
            cacheStore = cache,
            nowMillis = { now },
        )
    val state = repository.refresh(mock(), authSessionRepository)
    assertEquals(AccessState.Active(setOf("node-a")), state)
    assertEquals(setOf("node-a"), cache.policy?.allowedExitNodeIds)
  }

  @Test
  fun refreshUnauthorizedRequiresReauthentication() = runBlocking {
    val now = 1_000_000L
    val cache =
        object : AccessPolicyCacheStore {
          var policy: CachedAccessPolicy? = CachedAccessPolicy("v1", now + 60_000, setOf("node-a"))

          override fun read() = policy

          override fun write(policy: CachedAccessPolicy) {
            this.policy = policy
          }

          override fun clear() {
            policy = null
          }
        }
    val authStorage = InMemoryAuthStateStorage("state")
    val authSessionRepository =
        AuthSessionRepository(
            authStorage,
            FakeSessionState(isAuthorized = true),
            FakeAppAuthGateway(freshToken = "token"))
    val repository =
        AccessRepository(
            PolicyApiClient(connectionFactory = { FakeHttpURLConnection(401, "") }),
            cacheStore = cache,
            nowMillis = { now },
        )

    val state = repository.refresh(mock(), authSessionRepository)

    assertEquals(AccessState.Unavailable, state)
    assertNull(cache.policy)
    assertNull(authStorage.read())
    assertEquals(
        AuthentikState.ReauthenticationRequired, authSessionRepository.authentikState.value)
  }

  @Test
  fun fetchNodeAuthKeyReturnsAuthKeyOn200() {
    val client =
        PolicyApiClient(
            connectionFactory = {
              FakeHttpURLConnection(200, "{\"authKey\":\"hskey-auth-sample-123\"}")
            })
    val result = client.fetchNodeAuthKey("valid-token")
    assertTrue(result.isSuccess)
    assertEquals("hskey-auth-sample-123", result.getOrNull())
  }

  @Test
  fun fetchNodeAuthKeyReturnsTypedUnauthorizedOn401() {
    val client = PolicyApiClient(connectionFactory = { FakeHttpURLConnection(401, "Unauthorized") })
    val result = client.fetchNodeAuthKey("invalid-token")
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is PolicyApiUnauthorizedException)
  }

  @Test
  fun fetchNodeAuthKeyReturnsFailureOnNetworkException() {
    val client = PolicyApiClient(connectionFactory = { throw IOException("connection reset") })
    val result = client.fetchNodeAuthKey("token")
    assertTrue(result.isFailure)
  }

  @Test
  fun fetchNodeAuthKeyDoesNotLogPlaintextAuthKey() {
    val loggedMessages = mutableListOf<String>()
    val originalLog = com.tailscale.ipn.util.TSLog.libtailscaleWrapper
    com.tailscale.ipn.util.TSLog.libtailscaleWrapper =
        mock<com.tailscale.ipn.util.TSLog.LibtailscaleWrapper>().also {
          org.mockito.Mockito.`when`(
                  it.sendLog(
                      org.mockito.ArgumentMatchers.anyString(),
                      org.mockito.ArgumentMatchers.anyString()))
              .thenAnswer { invocation ->
                val msg = invocation.getArgument<String>(1)
                loggedMessages.add(msg)
                null
              }
        }
    try {
      val secretKey = "hskey-auth-super-secret-preauth-key-987654321"
      val client =
          PolicyApiClient(
              connectionFactory = { FakeHttpURLConnection(200, "{\"authKey\":\"$secretKey\"}") })
      val result = client.fetchNodeAuthKey("valid-bearer-token-123456789")
      assertTrue(result.isSuccess)
      assertEquals(secretKey, result.getOrNull())
      for (msg in loggedMessages) {
        assertFalse(
            "Log message should not contain plaintext authKey: $msg", msg.contains(secretKey))
        assertFalse(
            "Log message should not contain bearer token: $msg",
            msg.contains("valid-bearer-token-123456789"))
      }
      val requestLog = loggedMessages.first { it.contains("fetchNodeAuthKey request") }
      assertTrue(requestLog.contains("Authorization=<redacted>"))
    } finally {
      com.tailscale.ipn.util.TSLog.libtailscaleWrapper = originalLog
    }
  }

  @Test
  fun policyApiClientLoadRedactsBearerTokenAndOmitsRawResponseBody() {
    val loggedMessages = mutableListOf<String>()
    val originalLog = com.tailscale.ipn.util.TSLog.libtailscaleWrapper
    com.tailscale.ipn.util.TSLog.libtailscaleWrapper =
        mock<com.tailscale.ipn.util.TSLog.LibtailscaleWrapper>().also {
          org.mockito.Mockito.`when`(
                  it.sendLog(
                      org.mockito.ArgumentMatchers.anyString(),
                      org.mockito.ArgumentMatchers.anyString()))
              .thenAnswer { invocation ->
                val msg = invocation.getArgument<String>(1)
                loggedMessages.add(msg)
                null
              }
        }
    try {
      val secretBearerToken = "super-secret-bearer-token-9988776655"
      val secretResponseBody =
          "{\"access\":\"active\",\"allowedExitNodes\":[],\"secret_internal_data\":\"confidential\"}"
      val client =
          PolicyApiClient(connectionFactory = { FakeHttpURLConnection(200, secretResponseBody) })
      val result = client.load(secretBearerToken)
      assertTrue(result is PolicyLoadResult.Active)

      val requestLog = loggedMessages.firstOrNull { it.contains("PolicyApiClient.load request") }
      assertNotNull("Expected request log", requestLog)
      assertTrue(
          "Request log should contain a fixed redacted marker",
          requestLog!!.contains("Authorization=<redacted>"))
      assertFalse("Request log should contain secret token", requestLog.contains(secretBearerToken))
      assertFalse("Request log should contain token prefix", requestLog.contains("supe"))
      assertFalse("Request log should contain token suffix", requestLog.contains("6655"))

      val responseLog = loggedMessages.firstOrNull { it.contains("PolicyApiClient.load response") }
      assertNotNull("Expected response log", responseLog)
      assertFalse(
          "Response log should not contain raw response body",
          responseLog!!.contains("secret_internal_data"))
      assertFalse("Response log should not contain raw JSON", responseLog.contains("confidential"))
      assertTrue(
          "Response log should retain safe metadata",
          responseLog.contains("status=200") &&
              responseLog.contains("duration=") &&
              responseLog.contains("bodyLength=${secretResponseBody.toByteArray().size}") &&
              responseLog.contains("policyVersion=unknown") &&
              responseLog.contains("allowedExitNodeCount=0"))
    } finally {
      com.tailscale.ipn.util.TSLog.libtailscaleWrapper = originalLog
    }
  }

  @Test
  fun policyApiClientLoadErrorOmitsResponseBody() {
    val loggedMessages = mutableListOf<String>()
    val originalLog = com.tailscale.ipn.util.TSLog.libtailscaleWrapper
    com.tailscale.ipn.util.TSLog.libtailscaleWrapper =
        mock<com.tailscale.ipn.util.TSLog.LibtailscaleWrapper>().also {
          org.mockito.Mockito.`when`(
                  it.sendLog(
                      org.mockito.ArgumentMatchers.anyString(),
                      org.mockito.ArgumentMatchers.anyString()))
              .thenAnswer { invocation ->
                val msg = invocation.getArgument<String>(1)
                loggedMessages.add(msg)
                null
              }
        }
    try {
      val secretErrorBody =
          "{\"error\":\"invalid_grant\",\"error_description\":\"secret payload details\"}"
      val client =
          PolicyApiClient(connectionFactory = { FakeHttpURLConnection(401, secretErrorBody) })
      val result = client.load("some-token")
      assertEquals(PolicyLoadResult.Unauthorized, result)

      val responseLog = loggedMessages.firstOrNull { it.contains("PolicyApiClient.load response") }
      assertNotNull("Expected response log", responseLog)
      assertFalse(
          "Response log should not contain error body payload",
          responseLog!!.contains("secret payload details"))
      assertTrue(
          "Response log should retain status and result",
          responseLog.contains("status=401") && responseLog.contains("result=Unauthorized"))
    } finally {
      com.tailscale.ipn.util.TSLog.libtailscaleWrapper = originalLog
    }
  }
}

private fun repository(status: Int, body: String = ""): AccessRepository {
  return AccessRepository(
      PolicyApiClient(connectionFactory = { FakeHttpURLConnection(status, body) }))
}

private class FakeHttpURLConnection(private val status: Int, body: String) :
    HttpURLConnection(URL("https://policy.invalid/v1/me")) {
  private val response = body.toByteArray()

  override fun connect() {}

  override fun disconnect() {}

  override fun getInputStream() = ByteArrayInputStream(response)

  override fun getResponseCode(): Int = status

  override fun usingProxy(): Boolean = false
}
