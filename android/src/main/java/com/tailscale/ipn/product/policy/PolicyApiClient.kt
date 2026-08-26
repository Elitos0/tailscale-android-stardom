// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.ProductConfig
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface PolicyLoadResult {
  data class Active(
      val allowedExitNodeIds: Set<String>,
      val policyVersion: String,
      val validUntilEpochMillis: Long,
  ) : PolicyLoadResult

  data object Disabled : PolicyLoadResult

  data object Unauthorized : PolicyLoadResult

  data object Unavailable : PolicyLoadResult
}

class PolicyApiClient(
    private val baseUrl: String = ProductConfig.policyApiBaseUrl,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
      url.openConnection() as HttpURLConnection
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  fun load(token: String): PolicyLoadResult {
    var connection: HttpURLConnection? = null
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/v1/me")
      if (url.protocol != "https" || url.host.isBlank()) {
        return PolicyLoadResult.Unavailable
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
        HttpURLConnection.HTTP_UNAUTHORIZED -> PolicyLoadResult.Unauthorized
        HttpURLConnection.HTTP_FORBIDDEN -> PolicyLoadResult.Disabled
        HttpURLConnection.HTTP_OK -> parseActiveAccess(connection)
        else -> PolicyLoadResult.Unavailable
      }
    } catch (_: Exception) {
      PolicyLoadResult.Unavailable
    } finally {
      connection?.let {
        runCatching { it.errorStream?.close() }
        it.disconnect()
      }
    }
  }

  fun fetchNodeAuthKey(token: String): Result<String> {
    var connection: HttpURLConnection? = null
    return try {
      val url = URL("${baseUrl.trimEnd('/')}/v1/node-auth-key")
      if (url.protocol != "https" || url.host.isBlank()) {
        return Result.failure(IllegalStateException("Invalid Policy API URL"))
      }
      connection =
          connectionFactory(url).apply {
            requestMethod = "POST"
            connectTimeout = REQUEST_TIMEOUT_MILLIS
            readTimeout = REQUEST_TIMEOUT_MILLIS
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
          }

      if (connection.responseCode == HttpURLConnection.HTTP_OK) {
        val authKey =
            connection.inputStream.bufferedReader().use { reader ->
              JSON.decodeFromString<NodeAuthKeyResponse>(reader.readText()).authKey
            }
        Result.success(authKey)
      } else {
        Result.failure(IllegalStateException("Policy API returned ${connection.responseCode}"))
      }
    } catch (e: Exception) {
      Result.failure(e)
    } finally {
      connection?.let {
        runCatching { it.errorStream?.close() }
        it.disconnect()
      }
    }
  }

  private fun parseActiveAccess(connection: HttpURLConnection): PolicyLoadResult {
    val response =
        connection.inputStream.bufferedReader().use { reader ->
          JSON.decodeFromString<MeResponse>(reader.readText())
        }
    return when (response.access) {
      "active" -> {
        val validUntil =
            response.validUntil?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: (nowMillis() + DEFAULT_VALID_TTL_MILLIS)
        val version = response.policyVersion?.takeIf { it.isNotBlank() } ?: "unknown"
        PolicyLoadResult.Active(
            allowedExitNodeIds = response.allowedExitNodes.map { it.stableNodeId }.toSet(),
            policyVersion = version,
            validUntilEpochMillis = validUntil,
        )
      }
      "disabled" -> PolicyLoadResult.Disabled
      else -> PolicyLoadResult.Unavailable
    }
  }

  @Serializable
  private data class MeResponse(
      val access: String,
      val allowedExitNodes: List<AllowedExitNode> = emptyList(),
      val policyVersion: String? = null,
      val validUntil: String? = null,
  )

  @Serializable private data class AllowedExitNode(val stableNodeId: String)

  @Serializable private data class NodeAuthKeyResponse(val authKey: String)
  private companion object {
    const val REQUEST_TIMEOUT_MILLIS = 5_000
    const val DEFAULT_VALID_TTL_MILLIS = 60_000L
    val JSON = Json { ignoreUnknownKeys = true }
  }
}
