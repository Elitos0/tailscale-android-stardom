// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Test

class AccessRepositoryTest {
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
  fun unauthorizedResponseMakesAccessUnavailable() {
    val repository = repository(status = 401)

    assertEquals(AccessState.Unavailable, repository.load("token"))
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
