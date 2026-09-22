// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.util.TSLog
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UpdateRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    val dashboardBaseUrl: String,
    private val client: UpdateClient = UpdateClient(dashboardBaseUrl),
    private val installer: UpdateInstaller = UpdateInstaller(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentVersionCodeProvider: () -> Long = { BuildConfig.VERSION_CODE.toLong() },
    private val nowMillisProvider: () -> Long = { System.currentTimeMillis() },
    private val currentSdkIntProvider: () -> Int = { android.os.Build.VERSION.SDK_INT },
) {
  companion object {
    private const val TAG = "UpdateRepository"
    private const val PREFS_NAME = "stardom_updater_prefs"
    private const val KEY_LAST_CHECK_MILLIS = "last_check_epoch_millis"
    private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L // 24 hours
  }

  private val prefs: SharedPreferences by lazy {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
  }

  private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
  val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

  private var downloadJob: Job? = null

  fun checkOnForeground() {
    val lastCheck = prefs.getLong(KEY_LAST_CHECK_MILLIS, 0L)
    val now = nowMillisProvider()
    if (now - lastCheck < CHECK_INTERVAL_MILLIS) {
      TSLog.d(TAG, "Foreground update check skipped: checked ${(now - lastCheck) / 1000}s ago")
      return
    }
    TSLog.d(TAG, "Triggering throttled 24h foreground update check")
    checkForUpdate(isManual = false)
  }

  fun checkForUpdate(isManual: Boolean = true) {
    scope.launch {
      if (isManual) {
        _updateState.value = UpdateState.Checking
      }

      val result = withContext(ioDispatcher) { client.fetchManifest() }

      result.fold(
          onSuccess = { manifest ->
            prefs.edit().putLong(KEY_LAST_CHECK_MILLIS, nowMillisProvider()).apply()
            val currentCode = currentVersionCodeProvider()
            val currentSdk = currentSdkIntProvider()

            if (manifest.isUpdateAvailable(currentCode, currentSdk)) {
              val isForced = manifest.isMandatory(currentCode, currentSdk)
              val cachedApk = client.getFinalApkFile(context, manifest)

              if (cachedApk.exists() && runCatching { installer.verifyArchive(cachedApk, manifest).getOrThrow() }.isSuccess) {
                _updateState.value = UpdateState.Downloaded(manifest, cachedApk, isForced)
              } else {
                if (cachedApk.exists()) {
                  cachedApk.delete()
                }
                _updateState.value = UpdateState.UpdateAvailable(manifest, isForced)
              }
            } else {
              _updateState.value = if (isManual) UpdateState.UpToDate else UpdateState.Idle
            }
          },
          onFailure = { error ->
            TSLog.w(TAG, "Update check failed: ${error.message}")
            if (isManual) {
              _updateState.value =
                  UpdateState.Error(
                      message = error.message ?: "Failed to check for updates",
                      canRetry = true,
                      isForced = false)
            } else {
              // Background check failure stays Idle without error banner on VPN screen
              _updateState.value = UpdateState.Idle
            }
          })
    }
  }

  fun startDownload() {
    val currentState = _updateState.value
    val (manifest, isForced) =
        when (currentState) {
          is UpdateState.UpdateAvailable -> currentState.manifest to currentState.isForced
          is UpdateState.Downloaded -> currentState.manifest to currentState.isForced
          is UpdateState.Error -> (currentState.manifest ?: return) to currentState.isForced
          else -> return
        }

    downloadJob?.cancel()
    _updateState.value =
        UpdateState.Downloading(
            manifest = manifest,
            progress = 0f,
            bytesDownloaded = 0L,
            totalBytes = manifest.sizeBytes,
            isForced = isForced)

    downloadJob =
        scope.launch {
          val result =
              withContext(ioDispatcher) {
                client.downloadApk(context, manifest) { downloaded, total, progress ->
                  _updateState.value =
                      UpdateState.Downloading(
                          manifest = manifest,
                          progress = progress,
                          bytesDownloaded = downloaded,
                          totalBytes = total,
                          isForced = isForced)
                }
              }

          result.fold(
              onSuccess = { apkFile ->
                val verifyResult = withContext(ioDispatcher) { installer.verifyArchive(apkFile, manifest) }
                verifyResult.fold(
                    onSuccess = {
                      _updateState.value = UpdateState.Downloaded(manifest, apkFile, isForced)
                    },
                    onFailure = { verifyError ->
                      _updateState.value =
                          UpdateState.Error(
                              message = verifyError.message ?: "Archive verification failed",
                              canRetry = true,
                              isForced = isForced,
                              manifest = manifest)
                    })
              },
              onFailure = { downloadError ->
                _updateState.value =
                    UpdateState.Error(
                        message = downloadError.message ?: "Download failed",
                        canRetry = true,
                        isForced = isForced,
                        manifest = manifest)
              })
        }
  }

  fun cancelDownload() {
    downloadJob?.cancel()
    downloadJob = null
    val currentState = _updateState.value
    if (currentState is UpdateState.Downloading) {
      _updateState.value = UpdateState.UpdateAvailable(currentState.manifest, currentState.isForced)
    }
  }

  fun install(activity: Activity) {
    val currentState = _updateState.value
    if (currentState !is UpdateState.Downloaded) {
      TSLog.w(TAG, "Cannot install: current state is not Downloaded ($currentState)")
      return
    }

    if (!installer.canRequestPackageInstalls()) {
      TSLog.d(TAG, "Unknown app install permission missing; directing user to settings")
      installer.launchUnknownSourcesSettings(activity)
      return
    }

    _updateState.value = UpdateState.Installing(currentState.manifest, currentState.isForced)
    val result = installer.launchInstaller(activity, currentState.apkFile)
    result.onFailure { error ->
      TSLog.e(TAG, "Failed to launch installer: ${error.message}")
      _updateState.value =
          UpdateState.Error(
              message = error.message ?: "Failed to launch installer",
              canRetry = true,
              isForced = currentState.isForced,
              manifest = currentState.manifest)
    }
  }

  fun onActivityResume(activity: Activity) {
    val currentState = _updateState.value
    if (currentState is UpdateState.Installing) {
      val cachedApk = client.getFinalApkFile(context, currentState.manifest)
      if (cachedApk.exists() && runCatching { installer.verifyArchive(cachedApk, currentState.manifest).getOrThrow() }.isSuccess) {
        _updateState.value = UpdateState.Downloaded(currentState.manifest, cachedApk, currentState.isForced)
      } else {
        _updateState.value = UpdateState.UpdateAvailable(currentState.manifest, currentState.isForced)
      }
    }
  }

  fun dismissUpdate() {
    val currentState = _updateState.value
    if (currentState is UpdateState.UpdateAvailable && !currentState.isForced) {
      _updateState.value = UpdateState.Idle
    }
  }
}
