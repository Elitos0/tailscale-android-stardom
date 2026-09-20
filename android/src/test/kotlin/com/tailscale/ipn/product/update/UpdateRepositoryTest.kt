// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRepositoryTest {

  private val testDispatcher = StandardTestDispatcher()
  private val testScope = TestScope(testDispatcher)

  private val context: Context = mock()
  private val sharedPreferences: SharedPreferences = mock()
  private val editor: SharedPreferences.Editor = mock()
  private val client: UpdateClient = mock()
  private val installer: UpdateInstaller = mock()

  private var currentEpochMillis: Long = 1_000_000_000L
  private var currentVersionCode: Long = 110102910L
  private var currentSdkInt: Int = 34

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
          sizeBytes = 45123456L,
          sha256 = "4a7b3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b",
          changelog = mapOf("ru" to "Обновление", "en" to "Update"),
      )

  @Before
  fun setUp() {
    `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(sharedPreferences)
    `when`(sharedPreferences.edit()).thenReturn(editor)
    `when`(editor.putLong(anyString(), any())).thenReturn(editor)
    `when`(context.cacheDir).thenReturn(File("/tmp"))
  }

  private fun createRepository(): UpdateRepository {
    return UpdateRepository(
        context = context,
        scope = testScope,
        dashboardBaseUrl = "https://dashboard.elitoswork.ru",
        client = client,
        installer = installer,
        ioDispatcher = testDispatcher,
        currentVersionCodeProvider = { currentVersionCode },
        nowMillisProvider = { currentEpochMillis },
        currentSdkIntProvider = { currentSdkInt },
    )
  }

  @Test
  fun checkForUpdateManualSuccessSetsUpdateAvailable() = runTest(testDispatcher) {
    `when`(client.fetchManifest()).thenReturn(Result.success(testManifest))

    val repo = createRepository()
    repo.checkForUpdate(isManual = true)
    advanceUntilIdle()

    val state = repo.updateState.value
    assertTrue("State should be UpdateAvailable", state is UpdateState.UpdateAvailable)
    val available = state as UpdateState.UpdateAvailable
    assertEquals(testManifest, available.manifest)
    assertFalse("Update should be optional", available.isForced)
  }

  @Test
  fun checkForUpdateMandatoryWhenForceUpdateIsTrue() = runTest(testDispatcher) {
    val forcedManifest = testManifest.copy(forceUpdate = true)
    `when`(client.fetchManifest()).thenReturn(Result.success(forcedManifest))

    val repo = createRepository()
    repo.checkForUpdate(isManual = true)
    advanceUntilIdle()

    val state = repo.updateState.value
    assertTrue("State should be UpdateAvailable", state is UpdateState.UpdateAvailable)
    val available = state as UpdateState.UpdateAvailable
    assertTrue("Update must be forced when forceUpdate=true", available.isForced)
  }

  @Test
  fun checkForUpdateMandatoryWhenCurrentVersionBelowMinSupported() = runTest(testDispatcher) {
    val minSupportedManifest = testManifest.copy(forceUpdate = false, minSupportedVersionCode = 110103000L)
    `when`(client.fetchManifest()).thenReturn(Result.success(minSupportedManifest))

    val repo = createRepository()
    repo.checkForUpdate(isManual = true)
    advanceUntilIdle()

    val state = repo.updateState.value
    assertTrue("State should be UpdateAvailable", state is UpdateState.UpdateAvailable)
    val available = state as UpdateState.UpdateAvailable
    assertTrue("Update must be forced when currentVersionCode < minSupportedVersionCode", available.isForced)
  }

  @Test
  fun checkForUpdateEqualOrLowerVersionSetsUpToDateOnManual() = runTest(testDispatcher) {
    currentVersionCode = 110102920L // Equal to manifest
    `when`(client.fetchManifest()).thenReturn(Result.success(testManifest))

    val repo = createRepository()
    repo.checkForUpdate(isManual = true)
    advanceUntilIdle()

    assertEquals(UpdateState.UpToDate, repo.updateState.value)
  }

  @Test
  fun checkForUpdateEqualOrLowerVersionSetsIdleOnBackground() = runTest(testDispatcher) {
    currentVersionCode = 110102920L // Equal to manifest
    `when`(client.fetchManifest()).thenReturn(Result.success(testManifest))

    val repo = createRepository()
    repo.checkForUpdate(isManual = false)
    advanceUntilIdle()

    assertEquals(UpdateState.Idle, repo.updateState.value)
  }
  @Test
  fun checkForUpdateWhenDeviceBelowMinOsVersionDoesNotOfferUpdate() = runTest(testDispatcher) {
    currentSdkInt = 25 // Device on Android 7.1 (API 25)
    val manifestWithMinOs26 = testManifest.copy(minOsVersion = 26, forceUpdate = true, minSupportedVersionCode = 110103000L)
    `when`(client.fetchManifest()).thenReturn(Result.success(manifestWithMinOs26))

    val repo = createRepository()

    // Manual check: should be UpToDate (non-disruptive, update not offered)
    repo.checkForUpdate(isManual = true)
    advanceUntilIdle()
    assertEquals(UpdateState.UpToDate, repo.updateState.value)

    // Background check: should be Idle (non-disruptive, no error flash)
    repo.checkForUpdate(isManual = false)
    advanceUntilIdle()
    assertEquals(UpdateState.Idle, repo.updateState.value)
  }

  @Test
  fun checkForUpdateFailureSetsErrorOnManual() = runTest(testDispatcher) {
    `when`(client.fetchManifest()).thenReturn(Result.failure(IOException("Network timeout")))

    val repo = createRepository()
    repo.checkForUpdate(isManual = true)
    advanceUntilIdle()

    val state = repo.updateState.value
    assertTrue("State should be Error on manual failure", state is UpdateState.Error)
    val error = state as UpdateState.Error
    assertEquals("Network timeout", error.message)
    assertFalse(error.isForced)
  }

  @Test
  fun checkForUpdateFailureStaysIdleOnBackgroundWithoutFlashingError() = runTest(testDispatcher) {
    `when`(client.fetchManifest()).thenReturn(Result.failure(IOException("Network timeout")))

    val repo = createRepository()
    repo.checkForUpdate(isManual = false)
    advanceUntilIdle()

    // Crucial requirement: background check failure must stay Idle without an error flash on VPN screen
    assertEquals(UpdateState.Idle, repo.updateState.value)
  }

  @Test
  fun checkOnForegroundThrottledToOncePer24Hours() = runTest(testDispatcher) {
    val lastCheckMillis = 1_000_000_000L
    `when`(sharedPreferences.getLong(anyString(), any())).thenReturn(lastCheckMillis)

    // Current time is 1 hour later (< 24h)
    currentEpochMillis = lastCheckMillis + 3600 * 1000L

    val repo = createRepository()
    repo.checkOnForeground()
    advanceUntilIdle()

    // Client should NOT have been invoked
    assertEquals(UpdateState.Idle, repo.updateState.value)

    // Current time is 25 hours later (> 24h)
    currentEpochMillis = lastCheckMillis + 25 * 3600 * 1000L
    `when`(client.fetchManifest()).thenReturn(Result.success(testManifest))

    repo.checkOnForeground()
    advanceUntilIdle()

    // Client should now have been invoked
    assertTrue(repo.updateState.value is UpdateState.UpdateAvailable)
  }

  @Test
  fun dismissUpdateClearsOptionalUpdateOnly() = runTest(testDispatcher) {
    // Optional update can be dismissed
    `when`(client.fetchManifest()).thenReturn(Result.success(testManifest))
    val repo = createRepository()
    repo.checkForUpdate(isManual = true)
    advanceUntilIdle()

    assertTrue(repo.updateState.value is UpdateState.UpdateAvailable)
    repo.dismissUpdate()
    assertEquals(UpdateState.Idle, repo.updateState.value)

    // Forced update CANNOT be dismissed
    val forcedManifest = testManifest.copy(forceUpdate = true)
    `when`(client.fetchManifest()).thenReturn(Result.success(forcedManifest))
    repo.checkForUpdate(isManual = true)
    advanceUntilIdle()

    assertTrue((repo.updateState.value as UpdateState.UpdateAvailable).isForced)
    repo.dismissUpdate()
    assertTrue("Forced update must not be dismissed", repo.updateState.value is UpdateState.UpdateAvailable)
  }
}
