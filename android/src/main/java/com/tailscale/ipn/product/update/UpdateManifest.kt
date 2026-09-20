// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import java.net.URI
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UpdateManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("package_name") val packageName: String,
    @SerialName("version_code") val versionCode: Long,
    @SerialName("version_name") val versionName: String,
    @SerialName("download_url") val downloadUrl: String,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("force_update") val forceUpdate: Boolean = false,
    @SerialName("min_supported_version_code") val minSupportedVersionCode: Long = 0,
    @SerialName("min_os_version") val minOsVersion: Int = 26,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("sha256") val sha256: String,
    @SerialName("changelog") val changelog: Map<String, String> = emptyMap(),
) {
  companion object {
    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
    private val JSON = Json { ignoreUnknownKeys = false }

    fun parseAndValidate(jsonText: String, expectedDashboardBaseUrl: String): Result<UpdateManifest> =
        runCatching {
          val manifest = JSON.decodeFromString<UpdateManifest>(jsonText)
          manifest.validate(expectedDashboardBaseUrl)
          manifest
        }
  }

  fun validate(expectedDashboardBaseUrl: String) {
    require(schemaVersion == 1) { "Unsupported schema_version: $schemaVersion, expected 1" }
    require(packageName == "com.stardom.vpn") {
      "Unexpected package_name: $packageName, expected com.stardom.vpn"
    }
    require(versionCode > 0) { "version_code must be positive: $versionCode" }
    require(versionName.isNotBlank()) { "version_name must not be blank" }
    require(sizeBytes > 0) { "size_bytes must be positive: $sizeBytes" }
    require(SHA256_REGEX.matches(sha256)) {
      "sha256 must be a 64-character lowercase hex string: $sha256"
    }
    require(minOsVersion >= 1) { "min_os_version must be positive: $minOsVersion" }
    require(minSupportedVersionCode >= 0) {
      "min_supported_version_code must be non-negative: $minSupportedVersionCode"
    }

    // Validate publishedAt is a valid RFC3339 / ISO-8601 timestamp
    runCatching { Instant.parse(publishedAt) }
        .getOrElse {
          throw IllegalArgumentException(
              "published_at is not a valid RFC3339 timestamp: $publishedAt", it)
        }

    // Validate changelog: require both nonblank ru and en entries
    val ruChangelog = changelog["ru"]?.trim()
    val enChangelog = changelog["en"]?.trim()
    require(!ruChangelog.isNullOrBlank()) { "changelog must contain non-blank 'ru' entry" }
    require(!enChangelog.isNullOrBlank()) { "changelog must contain non-blank 'en' entry" }

    // Validate downloadUrl
    validateDownloadUrl(downloadUrl, expectedDashboardBaseUrl)
  }

  private fun validateDownloadUrl(urlStr: String, expectedDashboardBaseUrl: String) {
    val expectedUri = URI(expectedDashboardBaseUrl.trimEnd('/'))
    require("https".equals(expectedUri.scheme, ignoreCase = true)) {
      "Expected dashboard base URL must be HTTPS: $expectedDashboardBaseUrl"
    }
    val expectedHost =
        expectedUri.host?.lowercase()
            ?: throw IllegalArgumentException("Invalid dashboard base URL host")

    if (urlStr.startsWith("/")) {
      require(urlStr == "/download/android" || urlStr.startsWith("/download/android/")) {
        "Relative download_url must target /download/android: $urlStr"
      }
    } else {
      val uri =
          runCatching { URI(urlStr) }
              .getOrElse {
                throw IllegalArgumentException("Invalid download_url format: $urlStr", it)
              }
      require("https".equals(uri.scheme, ignoreCase = true)) {
        "download_url must use HTTPS: $urlStr"
      }
      val host = uri.host?.lowercase()
      require(host == expectedHost) {
        "download_url host '$host' must match dashboard host '$expectedHost'"
      }
      require(uri.path == "/download/android" || uri.path.startsWith("/download/android/")) {
        "download_url path must target /download/android: ${uri.path}"
      }
    }
  }

  fun resolveDownloadUrl(expectedDashboardBaseUrl: String): String {
    return if (downloadUrl.startsWith("/")) {
      "${expectedDashboardBaseUrl.trimEnd('/')}$downloadUrl"
    } else {
      downloadUrl
    }
  }

  fun isMandatory(
      currentVersionCode: Long,
      currentSdkInt: Int = android.os.Build.VERSION.SDK_INT,
  ): Boolean {
    if (currentSdkInt < minOsVersion) return false
    return forceUpdate || currentVersionCode < minSupportedVersionCode
  }

  fun isUpdateAvailable(
      currentVersionCode: Long,
      currentSdkInt: Int = android.os.Build.VERSION.SDK_INT,
  ): Boolean {
    return versionCode > currentVersionCode && currentSdkInt >= minOsVersion
  }

  fun localizedChangelog(langCode: String): String {
    return changelog[langCode]
        ?: changelog["ru"]
        ?: changelog["en"]
        ?: changelog.values.firstOrNull()
        ?: ""
  }
}
