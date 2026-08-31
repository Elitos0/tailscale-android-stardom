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
    val path = "/v1/me"
    val requestUrl = sanitizeUrl("${baseUrl.trimEnd('/')}$path")
    TSLog.d(
        TAG, "PolicyApiClient.load request: method=GET url=$requestUrl Authorization=<redacted>")
    return try {
      val url = URL("${baseUrl.trimEnd('/')}$path")
      if (url.protocol != "https" || url.host.isBlank()) {
        val duration = nowMillis() - startTime
        TSLog.w(
            TAG,
            "PolicyApiClient.load response: method=GET url=$requestUrl status=invalid duration=${duration}ms bodyLength=0 result=Unavailable")
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
      val bodyText = readResponseBody(connection, statusCode)
      val bodyLength = bodyText.toByteArray(Charsets.UTF_8).size
      val duration = nowMillis() - startTime
      val result =
          when (statusCode) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> PolicyLoadResult.Unauthorized
            HttpURLConnection.HTTP_FORBIDDEN -> PolicyLoadResult.Disabled
            HttpURLConnection.HTTP_OK ->
                runCatching { parseActiveAccess(bodyText) }
                    .getOrElse { PolicyLoadResult.Unavailable }
            else -> PolicyLoadResult.Unavailable
          }
      TSLog.d(
          TAG,
          "PolicyApiClient.load response: method=GET url=${sanitizeUrl(url.toString())} status=$statusCode duration=${duration}ms bodyLength=$bodyLength result=${resultSummary(result)}")
      result
    } catch (e: Exception) {
      val duration = nowMillis() - startTime
      TSLog.e(
          TAG,
          "PolicyApiClient.load request failed: method=GET url=$requestUrl duration=${duration}ms error=${e::class.java.simpleName}")
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
    val path = "/v1/node-auth-key"
    val requestUrl = sanitizeUrl("${baseUrl.trimEnd('/')}$path")
    TSLog.d(
        TAG,
        "PolicyApiClient.fetchNodeAuthKey request: method=POST url=$requestUrl Authorization=<redacted>")
    return try {
      val url = URL("${baseUrl.trimEnd('/')}$path")
      if (url.protocol != "https" || url.host.isBlank()) {
        val duration = nowMillis() - startTime
        TSLog.w(
            TAG,
            "PolicyApiClient.fetchNodeAuthKey response: method=POST url=$requestUrl status=invalid duration=${duration}ms bodyLength=0 result=Failure")
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
      val bodyText = readResponseBody(connection, statusCode)
      val bodyLength = bodyText.toByteArray(Charsets.UTF_8).size
      val duration = nowMillis() - startTime
      when {
        statusCode == HttpURLConnection.HTTP_UNAUTHORIZED -> {
          TSLog.d(
              TAG,
              "PolicyApiClient.fetchNodeAuthKey response: method=POST url=${sanitizeUrl(url.toString())} status=$statusCode duration=${duration}ms bodyLength=$bodyLength result=Unauthorized")
          Result.failure(PolicyApiUnauthorizedException())
        }
        statusCode == HttpURLConnection.HTTP_OK -> {
          val parsed = runCatching { JSON.decodeFromString<NodeAuthKeyResponse>(bodyText) }
          val authKey =
              parsed
                  .getOrElse {
                    TSLog.e(
                        TAG,
                        "PolicyApiClient.fetchNodeAuthKey response: method=POST url=${sanitizeUrl(url.toString())} status=$statusCode duration=${duration}ms bodyLength=$bodyLength result=Failure error=${it::class.java.simpleName}")
                    return Result.failure(it)
                  }
                  .authKey
          TSLog.d(
              TAG,
              "PolicyApiClient.fetchNodeAuthKey response: method=POST url=${sanitizeUrl(url.toString())} status=$statusCode duration=${duration}ms bodyLength=$bodyLength result=Success")
          Result.success(authKey)
        }
        else -> {
          TSLog.e(
              TAG,
              "PolicyApiClient.fetchNodeAuthKey response: method=POST url=${sanitizeUrl(url.toString())} status=$statusCode duration=${duration}ms bodyLength=$bodyLength result=Failure")
          Result.failure(IllegalStateException("Policy API returned $statusCode"))
        }
      }
    } catch (e: Exception) {
      val duration = nowMillis() - startTime
      TSLog.e(
          TAG,
          "PolicyApiClient.fetchNodeAuthKey request failed: method=POST url=$requestUrl duration=${duration}ms error=${e::class.java.simpleName}")
      Result.failure(e)
    } finally {
      connection?.let {
        runCatching { it.errorStream?.close() }
        it.disconnect()
      }
    }
  }

  private fun readResponseBody(connection: HttpURLConnection, statusCode: Int): String {
    val stream =
        if (statusCode >= HttpURLConnection.HTTP_BAD_REQUEST) {
          connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
        } else {
          runCatching { connection.inputStream }.getOrNull() ?: connection.errorStream
        }
    return stream?.bufferedReader().use { it?.readText() ?: "" }
  }

  private fun sanitizeUrl(value: String): String =
      runCatching {
            URL(value).let { url ->
              buildString {
                append(url.protocol)
                append("://")
                append(url.host)
                if (url.port != -1) append(":${url.port}")
                append(url.path)
              }
            }
          }
          .getOrElse { "<invalid-url>" }

  private fun resultSummary(result: PolicyLoadResult): String =
      when (result) {
        is PolicyLoadResult.Active ->
            "Active policyVersion=${result.policyVersion} allowedExitNodeCount=${result.allowedExitNodeIds.size}"
        PolicyLoadResult.Disabled -> "Disabled"
        PolicyLoadResult.Unauthorized -> "Unauthorized"
        PolicyLoadResult.Unavailable -> "Unavailable"
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
    const val REQUEST_TIMEOUT_MILLIS = 15_000
    const val DEFAULT_VALID_TTL_MILLIS = 5 * 60 * 1000L
    val JSON = Json { ignoreUnknownKeys = true }
  }
}

class PolicyApiUnauthorizedException : IllegalStateException("Policy API unauthorized")
