// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock

class UpdateInstallerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val context: Context = mock()
  private val packageManager: PackageManager = mock()

  private val testManifest =
      UpdateManifest(
          schemaVersion = 1,
          packageName = "com.stardom.vpn",
          versionCode = 110102920L,
          versionName = "1.101.292",
          downloadUrl = "https://dashboard.elitoswork.ru/download/android",
          publishedAt = "2026-09-20T12:00:00Z",
          forceUpdate = false,
          minSupportedVersionCode = 110102000L,
          minOsVersion = 26,
          sizeBytes = 100L,
          sha256 = "cd00e292c5970d3c5e2f0ffa5171e555bc46bfc4faddfb4a418b6840b86e79a3",
          changelog = mapOf("ru" to "Обновление", "en" to "Update"),
      )

  @Before
  fun setUp() {
    `when`(context.packageManager).thenReturn(packageManager)
    `when`(context.packageName).thenReturn("com.stardom.vpn")
  }

  private fun createTempApk(sizeBytes: Long = 100L): File {
    val file = tempFolder.newFile("test-update.apk")
    file.writeBytes(ByteArray(sizeBytes.toInt()) { 0 })
    return file
  }

  @Test
  fun verifyArchiveFailsWhenInstalledCertsMissing() {
    val apkFile = createTempApk()
    val installer =
        UpdateInstaller(
            context = context,
            installedCertProvider = { emptyList() },
            archiveCertProvider = { listOf("12345678") })

    val archivePackageInfo =
        PackageInfo().apply {
          packageName = "com.stardom.vpn"
          @Suppress("DEPRECATION")
          versionCode = 110102920
        }
    `when`(packageManager.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(archivePackageInfo)

    val result = installer.verifyArchive(apkFile, testManifest)
    assertTrue("Verification must fail when installed certs are missing", result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(
        "Error must specify missing installed certificates: ${error?.message}",
        error is SecurityException &&
            error.message?.contains("Installed application signing certificates") == true)
    assertFalse("File must be deleted on verification failure", apkFile.exists())
  }

  @Test
  fun verifyArchiveFailsWhenArchiveCertsMissing() {
    val apkFile = createTempApk()
    val installer =
        UpdateInstaller(
            context = context,
            installedCertProvider = { listOf("12345678") },
            archiveCertProvider = { emptyList() })

    val archivePackageInfo =
        PackageInfo().apply {
          packageName = "com.stardom.vpn"
          @Suppress("DEPRECATION")
          versionCode = 110102920
        }
    `when`(packageManager.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(archivePackageInfo)

    val result = installer.verifyArchive(apkFile, testManifest)
    assertTrue("Verification must fail when archive certs are missing", result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(
        "Error must specify missing archive certificates: ${error?.message}",
        error is SecurityException &&
            error.message?.contains("Archive signing certificates") == true)
    assertFalse("File must be deleted on verification failure", apkFile.exists())
  }

  @Test
  fun verifyArchiveFailsWhenSigningCertificatesMismatch() {
    val apkFile = createTempApk()
    val installer =
        UpdateInstaller(
            context = context,
            installedCertProvider = { listOf("11111111") },
            archiveCertProvider = { listOf("22222222") })

    val archivePackageInfo =
        PackageInfo().apply {
          packageName = "com.stardom.vpn"
          @Suppress("DEPRECATION")
          versionCode = 110102920
        }
    `when`(packageManager.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(archivePackageInfo)

    val result = installer.verifyArchive(apkFile, testManifest)
    assertTrue("Verification must fail on certificate mismatch", result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(
        "Error must be SecurityException for cert mismatch: ${error?.message}",
        error is SecurityException &&
            error.message?.contains("Signing certificate mismatch") == true)
    assertFalse("File must be deleted on verification failure", apkFile.exists())
  }

  @Test
  fun verifyArchiveSucceedsWhenCertificatesMatch() {
    val apkFile = createTempApk()
    val installer =
        UpdateInstaller(
            context = context,
            installedCertProvider = { listOf("1234567890abcdef") },
            archiveCertProvider = { listOf("1234567890abcdef") })

    val archivePackageInfo =
        PackageInfo().apply {
          packageName = "com.stardom.vpn"
          @Suppress("DEPRECATION")
          versionCode = 110102920
        }
    `when`(packageManager.getPackageArchiveInfo(anyString(), anyInt())).thenReturn(archivePackageInfo)

    val result = installer.verifyArchive(apkFile, testManifest)
    assertTrue(
        "Verification must succeed when certificates match: ${result.exceptionOrNull()}",
        result.isSuccess)
    assertTrue("File must NOT be deleted on success", apkFile.exists())
  }

  @Test
  fun verifyArchiveFailsWhenSha256Mismatch() {
    val apkFile = createTempApk()
    val installer =
        UpdateInstaller(
            context = context,
            installedCertProvider = { listOf("1234567890abcdef") },
            archiveCertProvider = { listOf("1234567890abcdef") })

    val mismatchedManifest = testManifest.copy(sha256 = "0000000000000000000000000000000000000000000000000000000000000000")

    val result = installer.verifyArchive(apkFile, mismatchedManifest)
    assertTrue("Verification must fail on SHA-256 mismatch", result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(
        "Error must be SecurityException for sha256 mismatch: ${error?.message}",
        error is SecurityException && error.message?.contains("SHA-256 mismatch") == true)
    assertFalse("File must be deleted on SHA-256 failure", apkFile.exists())
  }

  @Test
  fun verifyArchiveFailsWhenSizeMismatch() {
    val apkFile = createTempApk(sizeBytes = 50L)
    val installer = UpdateInstaller(context = context)

    val result = installer.verifyArchive(apkFile, testManifest)
    assertTrue("Verification must fail on size mismatch", result.isFailure)
    val error = result.exceptionOrNull()
    assertTrue(
        "Error must be SecurityException for size mismatch: ${error?.message}",
        error is SecurityException && error.message?.contains("size mismatch") == true)
    assertFalse("File must be deleted on size failure", apkFile.exists())
  }
}
