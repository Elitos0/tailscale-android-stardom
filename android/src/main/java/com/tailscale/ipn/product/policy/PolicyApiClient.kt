// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.ProductConfig
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class PolicyApiClient(
    private val baseUrl: String = ProductConfig.policyApiBaseUrl,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
      url.openConnection() as HttpURLConnection
    }
) {
  fun load(token: String): AccessState {
    var connection: HttpURLConnection? = null
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/v1/me")
      if (url.protocol != "https") {
        return AccessState.Unavailable
      }
      connection =
          connectionFactory(url).apply {
            requestMethod = "GET"
            connectTimeout = REQUEST_TIMEOUT_MILLIS
            readTimeout = REQUEST_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
          }

      when (connection.responseCode) {
        HttpURLConnection.HTTP_FORBIDDEN -> AccessState.Disabled
        HttpURLConnection.HTTP_OK -> parseActiveAccess(connection)
        else -> AccessState.Unavailable
      }
    } catch (_: Exception) {
      AccessState.Unavailable
    } finally {
      connection?.let {
        runCatching { it.errorStream?.close() }
        it.disconnect()
      }
    }
  }

  private fun parseActiveAccess(connection: HttpURLConnection): AccessState {
    val response =
        connection.inputStream.bufferedReader().use { reader ->
          JSON.decodeFromString<MeResponse>(reader.readText())
        }
    return when (response.access) {
      "active" -> AccessState.Active(response.allowedExitNodes.map { it.stableNodeId }.toSet())
      "disabled" -> AccessState.Disabled
      else -> AccessState.Unavailable
    }
  }

  @Serializable
  private data class MeResponse(
      val access: String,
      val allowedExitNodes: List<AllowedExitNode>,
  )

  @Serializable private data class AllowedExitNode(val stableNodeId: String)

  private companion object {
    const val REQUEST_TIMEOUT_MILLIS = 5_000
    val JSON = Json { ignoreUnknownKeys = true }
  }
}
