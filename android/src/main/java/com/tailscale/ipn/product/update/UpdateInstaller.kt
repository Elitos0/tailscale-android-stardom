// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tailscale.ipn.util.TSLog
import java.io.File
import java.security.MessageDigest
class UpdateInstaller(
    private val context: Context,
    private val installedCertProvider: () -> List<String> = {
      SigningCertHelper.getInstalledAppCertSha256(context)
    },
    private val archiveCertProvider: (android.content.pm.PackageInfo) -> List<String> = {
      SigningCertHelper.getArchiveCertSha256(it)
    },
) {

  companion object {
    private const val TAG = "UpdateInstaller"
    const val MIME_TYPE_APK = "application/vnd.android.package-archive"
  }

  fun verifyArchive(apkFile: File, manifest: UpdateManifest): Result<Unit> {
    return runCatching {
      if (!apkFile.exists()) {
        throw IllegalArgumentException("APK file does not exist: ${apkFile.absolutePath}")
      }
      if (apkFile.length() != manifest.sizeBytes) {
        apkFile.delete()
        throw SecurityException(
            "APK file size mismatch: ${apkFile.length()} != ${manifest.sizeBytes}")
      }

      val digest = MessageDigest.getInstance("SHA-256")
      apkFile.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        var bytesRead: Int
        while (input.read(buffer).also { bytesRead = it } != -1) {
          digest.update(buffer, 0, bytesRead)
        }
      }
      val computedSha = digest.digest().joinToString("") { "%02x".format(it) }
      if (!computedSha.equals(manifest.sha256, ignoreCase = true)) {
        apkFile.delete()
        throw SecurityException("SHA-256 mismatch for APK: $computedSha != ${manifest.sha256}")
      }

      val pm = context.packageManager
      val flags =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
          } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
          }

      val archiveInfo =
          pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
              ?: run {
                apkFile.delete()
                throw SecurityException("Failed to parse APK archive: ${apkFile.absolutePath}")
              }

      if (archiveInfo.packageName != manifest.packageName) {
        apkFile.delete()
        throw SecurityException(
            "Package name mismatch: ${archiveInfo.packageName} != ${manifest.packageName}")
      }

      val archiveVersionCode =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode
          } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode.toLong()
          }

      if (archiveVersionCode != manifest.versionCode) {
        apkFile.delete()
        throw SecurityException(
            "Version code mismatch: $archiveVersionCode != ${manifest.versionCode}")
      }

      // Check signing certificate match
      val installedCerts = installedCertProvider()
      if (installedCerts.isEmpty()) {
        apkFile.delete()
        TSLog.e(TAG, "Installed application signing certificates are missing or could not be retrieved")
        throw SecurityException("Installed application signing certificates are missing or could not be retrieved")
      }

      val archiveCerts = archiveCertProvider(archiveInfo)
      if (archiveCerts.isEmpty()) {
        apkFile.delete()
        TSLog.e(TAG, "Archive signing certificates are missing or malformed")
        throw SecurityException("Archive signing certificates are missing or malformed")
      }

      val matches = SigningCertHelper.verifyCertificatesMatch(installedCerts, archiveCerts)
      if (!matches) {
        apkFile.delete()
        TSLog.e(
            TAG,
            "Certificate mismatch: archive certs $archiveCerts do not match installed $installedCerts")
        throw SecurityException("Signing certificate mismatch between update and installed app")
      }

      TSLog.d(TAG, "APK archive successfully verified against manifest and installed application")
    }
  }

  fun canRequestPackageInstalls(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      context.packageManager.canRequestPackageInstalls()
    } else {
      true
    }
  }

  fun launchUnknownSourcesSettings(activity: Activity) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val intent =
          Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${activity.packageName}")
          }
      activity.startActivity(intent)
    }
  }

  fun launchInstaller(activity: Activity, apkFile: File): Result<Unit> {
    return runCatching {
      val authority = "${activity.packageName}.fileprovider"
      val contentUri = FileProvider.getUriForFile(activity, authority, apkFile)
      apkFile.setReadable(true, false)

      val intent =
          Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, MIME_TYPE_APK)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            clipData = ClipData.newRawUri("stardom_update_apk", contentUri)
          }

      val resolvedActivities =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
          } else {
            @Suppress("DEPRECATION")
            activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
          }
      for (resolveInfo in resolvedActivities) {
        val targetPackage = resolveInfo.activityInfo?.packageName
        if (!targetPackage.isNullOrBlank()) {
          activity.grantUriPermission(targetPackage, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
      }
      activity.startActivity(intent)
      TSLog.d(TAG, "Launched package installer for content URI: $contentUri")
    }
  }
}
