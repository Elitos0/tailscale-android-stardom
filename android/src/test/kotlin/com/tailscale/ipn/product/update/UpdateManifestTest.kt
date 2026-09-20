// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManifestTest {

  private val validDashboardUrl = "https://dashboard.elitoswork.ru"
  private val validSha256 = "4a7b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b"

  private fun createJson(
      schemaVersion: Int = 1,
      packageName: String = "com.stardom.vpn",
      versionCode: Long = 110102920,
      versionName: String = "1.101.292",
      downloadUrl: String = "https://dashboard.elitoswork.ru/download/android",
      publishedAt: String = "2026-09-20T12:00:00Z",
      forceUpdate: Boolean = false,
      minSupportedVersionCode: Long = 110102000,
      minOsVersion: Int = 26,
      sizeBytes: Long = 45123456,
      sha256: String = validSha256,
      changelog: String = """{"ru":"Обновление","en":"Update"}""",
  ): String =
      """
      {
        "schema_version": $schemaVersion,
        "package_name": "$packageName",
        "version_code": $versionCode,
        "version_name": "$versionName",
        "download_url": "$downloadUrl",
        "published_at": "$publishedAt",
        "force_update": $forceUpdate,
        "min_supported_version_code": $minSupportedVersionCode,
        "min_os_version": $minOsVersion,
        "size_bytes": $sizeBytes,
        "sha256": "$sha256",
        "changelog": $changelog
      }
      """.trimIndent()

  @Test
  fun validManifestParsesSuccessfully() {
    val json = createJson()
    val result = UpdateManifest.parseAndValidate(json, validDashboardUrl)
    assertTrue("Valid manifest should parse successfully: ${result.exceptionOrNull()}", result.isSuccess)

    val manifest = result.getOrThrow()
    assertEquals(1, manifest.schemaVersion)
    assertEquals("com.stardom.vpn", manifest.packageName)
    assertEquals(110102920L, manifest.versionCode)
    assertEquals("1.101.292", manifest.versionName)
    assertEquals("https://dashboard.elitoswork.ru/download/android", manifest.downloadUrl)
    assertEquals("2026-09-20T12:00:00Z", manifest.publishedAt)
    assertFalse(manifest.forceUpdate)
    assertEquals(110102000L, manifest.minSupportedVersionCode)
    assertEquals(26, manifest.minOsVersion)
    assertEquals(45123456L, manifest.sizeBytes)
    assertEquals(validSha256, manifest.sha256)
    assertEquals("Обновление", manifest.localizedChangelog("ru"))
    assertEquals("Update", manifest.localizedChangelog("en"))
  }

  @Test
  fun relativeDownloadUrlParsesAndResolvesCorrectly() {
    val json = createJson(downloadUrl = "/download/android")
    val result = UpdateManifest.parseAndValidate(json, validDashboardUrl)
    assertTrue("Relative download_url should be accepted", result.isSuccess)

    val manifest = result.getOrThrow()
    assertEquals("/download/android", manifest.downloadUrl)
    assertEquals("https://dashboard.elitoswork.ru/download/android", manifest.resolveDownloadUrl(validDashboardUrl))
  }

  @Test
  fun invalidSchemaVersionRejected() {
    val json = createJson(schemaVersion = 2)
    val result = UpdateManifest.parseAndValidate(json, validDashboardUrl)
    assertTrue("schema_version != 1 must be rejected", result.isFailure)
  }

  @Test
  fun invalidPackageNameRejected() {
    val json = createJson(packageName = "com.evil.app")
    val result = UpdateManifest.parseAndValidate(json, validDashboardUrl)
    assertTrue("package_name != com.stardom.vpn must be rejected", result.isFailure)
  }

  @Test
  fun invalidVersionCodeRejected() {
    val json = createJson(versionCode = 0)
    val result = UpdateManifest.parseAndValidate(json, validDashboardUrl)
    assertTrue("version_code <= 0 must be rejected", result.isFailure)
  }

  @Test
  fun blankVersionNameRejected() {
    val json = createJson(versionName = "   ")
    val result = UpdateManifest.parseAndValidate(json, validDashboardUrl)
    assertTrue("blank version_name must be rejected", result.isFailure)
  }

  @Test
  fun invalidPublishedAtRejected() {
    val json = createJson(publishedAt = "invalid-date")
    val result = UpdateManifest.parseAndValidate(json, validDashboardUrl)
    assertTrue("invalid published_at must be rejected", result.isFailure)
  }

  @Test
  fun nonPositiveSizeBytesRejected() {
    val json = createJson(sizeBytes = 0)
    val result = UpdateManifest.parseAndValidate(json, validDashboardUrl)
    assertTrue("size_bytes <= 0 must be rejected", result.isFailure)
  }

  @Test
  fun invalidSha256Rejected() {
    // Too short
    val shortSha = UpdateManifest.parseAndValidate(createJson(sha256 = "abcd1234"), validDashboardUrl)
    assertTrue("short sha256 must be rejected", shortSha.isFailure)

    // Uppercase
    val upperSha = UpdateManifest.parseAndValidate(createJson(sha256 = validSha256.uppercase()), validDashboardUrl)
    assertTrue("uppercase sha256 must be rejected", upperSha.isFailure)

    // Non-hex
    val nonHexSha = UpdateManifest.parseAndValidate(createJson(sha256 = "g".repeat(64)), validDashboardUrl)
    assertTrue("non-hex sha256 must be rejected", nonHexSha.isFailure)
  }

  @Test
  fun emptyOrInvalidChangelogRejected() {
    val emptyChangelog = UpdateManifest.parseAndValidate(createJson(changelog = "{}"), validDashboardUrl)
    assertTrue("empty changelog must be rejected", emptyChangelog.isFailure)

    val noRuChangelog = UpdateManifest.parseAndValidate(createJson(changelog = """{"en":"Update"}"""), validDashboardUrl)
    assertTrue("changelog without ru must be rejected", noRuChangelog.isFailure)

    val noEnChangelog = UpdateManifest.parseAndValidate(createJson(changelog = """{"ru":"Обновление"}"""), validDashboardUrl)
    assertTrue("changelog without en must be rejected", noEnChangelog.isFailure)

    val blankRuChangelog = UpdateManifest.parseAndValidate(createJson(changelog = """{"ru":"  ","en":"Update"}"""), validDashboardUrl)
    assertTrue("changelog with blank ru must be rejected", blankRuChangelog.isFailure)

    val blankEnChangelog = UpdateManifest.parseAndValidate(createJson(changelog = """{"ru":"Обновление","en":"  "}"""), validDashboardUrl)
    assertTrue("changelog with blank en must be rejected", blankEnChangelog.isFailure)
  }

  @Test
  fun foreignOrInsecureDownloadUrlRejected() {
    // Plain HTTP
    val httpUrl = UpdateManifest.parseAndValidate(createJson(downloadUrl = "http://dashboard.elitoswork.ru/download/android"), validDashboardUrl)
    assertTrue("plain HTTP download_url must be rejected", httpUrl.isFailure)

    // Foreign host
    val foreignHost = UpdateManifest.parseAndValidate(createJson(downloadUrl = "https://evil.com/download/android"), validDashboardUrl)
    assertTrue("foreign host download_url must be rejected", foreignHost.isFailure)

    // Foreign path
    val foreignPath = UpdateManifest.parseAndValidate(createJson(downloadUrl = "https://dashboard.elitoswork.ru/malicious.apk"), validDashboardUrl)
    assertTrue("foreign path download_url must be rejected", foreignPath.isFailure)
  }

  @Test
  fun updateAvailableLogicEvaluatedCorrectly() {
    val manifest = UpdateManifest.parseAndValidate(createJson(versionCode = 110102920), validDashboardUrl).getOrThrow()

    // Higher version code -> update available
    assertTrue(manifest.isUpdateAvailable(110102910, currentSdkInt = 26))

    // Equal version code -> no update
    assertFalse(manifest.isUpdateAvailable(110102920, currentSdkInt = 26))

    // Lower version code (downgrade) -> no update
    assertFalse(manifest.isUpdateAvailable(110102930, currentSdkInt = 26))
  }

  @Test
  fun mandatoryUpdateLogicEvaluatedCorrectly() {
    // Case 1: forceUpdate = true
    val forcedManifest = UpdateManifest.parseAndValidate(createJson(forceUpdate = true, minSupportedVersionCode = 100), validDashboardUrl).getOrThrow()
    assertTrue("Must be mandatory when forceUpdate=true", forcedManifest.isMandatory(200, currentSdkInt = 26))

    // Case 2: current < minSupportedVersionCode
    val minSupportedManifest = UpdateManifest.parseAndValidate(createJson(forceUpdate = false, minSupportedVersionCode = 110102000), validDashboardUrl).getOrThrow()
    assertTrue("Must be mandatory when current < minSupported", minSupportedManifest.isMandatory(110101000, currentSdkInt = 26))
    assertFalse("Must be optional when current >= minSupported and forceUpdate=false", minSupportedManifest.isMandatory(110102500, currentSdkInt = 26))
  }

  @Test
  fun minOsVersionRejectionEvaluatedCorrectly() {
    val manifest = UpdateManifest.parseAndValidate(createJson(versionCode = 110102920, minOsVersion = 34, forceUpdate = true, minSupportedVersionCode = 110103000), validDashboardUrl).getOrThrow()

    // Device on API 33 (< minOsVersion 34)
    assertFalse("Must not offer update when currentSdk < minOsVersion", manifest.isUpdateAvailable(currentVersionCode = 110102910, currentSdkInt = 33))
    assertFalse("Must not force update when currentSdk < minOsVersion", manifest.isMandatory(currentVersionCode = 110102910, currentSdkInt = 33))

    // Device on API 34 (== minOsVersion 34)
    assertTrue("Must offer update when currentSdk >= minOsVersion", manifest.isUpdateAvailable(currentVersionCode = 110102910, currentSdkInt = 34))
    assertTrue("Must force update when currentSdk >= minOsVersion and forceUpdate=true", manifest.isMandatory(currentVersionCode = 110102910, currentSdkInt = 34))
  }
}
