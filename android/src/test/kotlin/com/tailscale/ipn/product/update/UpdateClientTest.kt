// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import android.content.Context
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.`when`
import org.mockito.kotlin.mock

class UpdateClientTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val context: Context = mock()

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
          sha256 = "4a7b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b",
          changelog = mapOf("ru" to "Обновление", "en" to "Update"),
      )

  @Before
  fun setUp() {
    `when`(context.cacheDir).thenReturn(tempFolder.root)
  }

  @Test
  fun getFinalApkFileProducesManifestSpecificName() {
    val client = UpdateClient("https://dashboard.elitoswork.ru")
    val finalFile = client.getFinalApkFile(context, testManifest)

    val expectedFileName = "stardom-update-110102920-4a7b3c2d.apk"
    assertEquals(expectedFileName, finalFile.name)
    assertEquals(File(tempFolder.root, "updates/$expectedFileName"), finalFile)
  }

  @Test
  fun getPartApkFileProducesManifestSpecificName() {
    val client = UpdateClient("https://dashboard.elitoswork.ru")
    val partFile = client.getPartApkFile(context, testManifest)

    val expectedFileName = "stardom-update-110102920-4a7b3c2d.apk.part"
    assertEquals(expectedFileName, partFile.name)
    assertEquals(File(tempFolder.root, "updates/$expectedFileName"), partFile)
  }

  @Test
  fun staticCompanionMethodsMatchInstanceMethods() {
    val client = UpdateClient("https://dashboard.elitoswork.ru")
    assertEquals(
        UpdateClient.getFinalApkFile(context, testManifest),
        client.getFinalApkFile(context, testManifest))
    assertEquals(
        UpdateClient.getPartApkFile(context, testManifest),
        client.getPartApkFile(context, testManifest))
  }
}
