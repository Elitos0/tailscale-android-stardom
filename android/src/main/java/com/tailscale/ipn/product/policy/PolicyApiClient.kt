// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import com.tailscale.ipn.product.ProductConfig
import com.tailscale.ipn.util.TSLog
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
    val startTime = nowMillis()
    val urlString = "${baseUrl.trimEnd('/')}/v1/me"
    val authHeader = redactBearerToken(token)
    TSLog.d(
        TAG, "PolicyApiClient.load request: GET $urlString, headers: [Authorization: $authHeader]")
    return try {
      val url = URL(urlString)
      if (url.protocol != "https" || url.host.isBlank()) {
        val duration = nowMillis() - startTime
        TSLog.w(
            TAG,
            "PolicyApiClient.load invalid URL: $urlString duration=${duration}ms -> Unavailable")
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

      val statusCode = connection.responseCode
      val duration = nowMillis() - startTime
      val result =
          when (statusCode) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
              val bodyPreview =
                  runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                      .getOrNull()
                      .orEmpty()
              TSLog.d(
                  TAG,
                  "PolicyApiClient.load response: status=$statusCode duration=${duration}ms body=${bodyPreview.take(300)} result=Unauthorized")
              PolicyLoadResult.Unauthorized
            }
            HttpURLConnection.HTTP_FORBIDDEN -> {
              val bodyPreview =
                  runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                      .getOrNull()
                      .orEmpty()
              TSLog.d(
                  TAG,
                  "PolicyApiClient.load response: status=$statusCode duration=${duration}ms body=${bodyPreview.take(300)} result=Disabled")
              PolicyLoadResult.Disabled
            }
            HttpURLConnection.HTTP_OK -> {
              val bodyText = connection.inputStream.bufferedReader().use { it.readText() }
              val parsed = parseActiveAccess(bodyText)
              TSLog.d(
                  TAG,
                  "PolicyApiClient.load response: status=$statusCode duration=${duration}ms body=${bodyText.take(300)} result=$parsed")
              parsed
            }
            else -> {
              val bodyPreview =
                  runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                      .getOrNull()
                      .orEmpty()
              TSLog.d(
                  TAG,
                  "PolicyApiClient.load response: status=$statusCode duration=${duration}ms body=${bodyPreview.take(300)} result=Unavailable")
              PolicyLoadResult.Unavailable
            }
          }
      result
    } catch (e: Exception) {
      val duration = nowMillis() - startTime
      TSLog.e(
          TAG,
          "PolicyApiClient.load request failed: GET $urlString duration=${duration}ms error=${e.message}",
          e)
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
    val startTime = nowMillis()
    val urlString = "${baseUrl.trimEnd('/')}/v1/node-auth-key"
    val authHeader = redactBearerToken(token)
    TSLog.d(
        TAG,
        "PolicyApiClient.fetchNodeAuthKey request: POST $urlString, headers: [Authorization: $authHeader, Content-Type: application/json]")
    return try {
      val url = URL(urlString)
      if (url.protocol != "https" || url.host.isBlank()) {
        val duration = nowMillis() - startTime
        TSLog.w(
            TAG, "PolicyApiClient.fetchNodeAuthKey invalid URL: $urlString duration=${duration}ms")
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

      val statusCode = connection.responseCode
      val duration = nowMillis() - startTime
      if (statusCode == HttpURLConnection.HTTP_OK) {
        val bodyText = connection.inputStream.bufferedReader().use { it.readText() }
        val authKey = JSON.decodeFromString<NodeAuthKeyResponse>(bodyText).authKey
        val redactedAuthKey =
            if (authKey.length > 8) "${authKey.take(4)}...${authKey.takeLast(4)}" else "***"
        TSLog.d(
            TAG,
            "PolicyApiClient.fetchNodeAuthKey response: status=$statusCode duration=${duration}ms body=${bodyText.take(300)} parsedAuthKey=$redactedAuthKey")
        Result.success(authKey)
      } else {
        val bodyPreview =
            runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }
                .getOrNull()
                .orEmpty()
        TSLog.e(
            TAG,
            "PolicyApiClient.fetchNodeAuthKey response error: status=$statusCode duration=${duration}ms body=${bodyPreview.take(300)}")
        Result.failure(IllegalStateException("Policy API returned $statusCode"))
      }
    } catch (e: Exception) {
      val duration = nowMillis() - startTime
      TSLog.e(
          TAG,
          "PolicyApiClient.fetchNodeAuthKey request failed: POST $urlString duration=${duration}ms error=${e.message}",
          e)
      Result.failure(e)
    } finally {
      connection?.let {
        runCatching { it.errorStream?.close() }
        it.disconnect()
      }
    }
  }

  private fun parseActiveAccess(bodyText: String): PolicyLoadResult {
    val response = JSON.decodeFromString<MeResponse>(bodyText)
    return when (response.access) {
      "active" -> {
        val validUntil =
            response.validUntil?.let {
              runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            } ?: (nowMillis() + DEFAULT_VALID_TTL_MILLIS)
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

  private fun redactBearerToken(token: String): String {
    return if (token.length > 8) {
      "Bearer ${token.take(4)}...${token.takeLast(4)}"
    } else if (token.isNotEmpty()) {
      "Bearer ..."
    } else {
      "Bearer (empty)"
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
    const val TAG = "PolicyApiClient"
    const val REQUEST_TIMEOUT_MILLIS = 5_000
    const val DEFAULT_VALID_TTL_MILLIS = 60_000L
    val JSON = Json { ignoreUnknownKeys = true }
  }
}
