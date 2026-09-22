// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import android.content.Context
import com.tailscale.ipn.util.TSLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

class UpdateClient(
    val dashboardBaseUrl: String,
    private val connectionFactory: (URL) -> HttpURLConnection = { url ->
      url.openConnection() as HttpURLConnection
    },
) {
  companion object {
    private const val TAG = "UpdateClient"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val BUFFER_SIZE = 32 * 1024
    private const val STORAGE_SAFETY_MARGIN_BYTES = 15 * 1024 * 1024L // 15MB
    fun getFinalApkFile(context: Context, manifest: UpdateManifest): File {
      val updatesDir = File(context.cacheDir, "updates")
      return File(updatesDir, "stardom-update-${manifest.versionCode}-${manifest.sha256.take(8).lowercase()}.apk")
    }

    fun getPartApkFile(context: Context, manifest: UpdateManifest): File {
      val updatesDir = File(context.cacheDir, "updates")
      return File(updatesDir, "stardom-update-${manifest.versionCode}-${manifest.sha256.take(8).lowercase()}.apk.part")
    }
  }
  fun getFinalApkFile(context: Context, manifest: UpdateManifest): File {
    return Companion.getFinalApkFile(context, manifest)
  }

  fun getPartApkFile(context: Context, manifest: UpdateManifest): File {
    return Companion.getPartApkFile(context, manifest)
  }


  fun fetchManifest(): Result<UpdateManifest> {
    var connection: HttpURLConnection? = null
    return try {
      val manifestUrlStr = "${dashboardBaseUrl.trimEnd('/')}/download/android/manifest.json"
      val url = URL(manifestUrlStr)
      if (!"https".equals(url.protocol, ignoreCase = true)) {
        return Result.failure(IllegalArgumentException("Manifest URL must be HTTPS: $manifestUrlStr"))
      }

      connection =
          connectionFactory(url).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
          }

      val statusCode = connection.responseCode
      if (statusCode != HttpURLConnection.HTTP_OK) {
        TSLog.w(TAG, "Manifest fetch failed with HTTP $statusCode from $manifestUrlStr")
        return Result.failure(IOException("HTTP error $statusCode from manifest endpoint"))
      }

      val bodyText =
          connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
      UpdateManifest.parseAndValidate(bodyText, dashboardBaseUrl)
    } catch (e: Exception) {
      TSLog.w(TAG, "Manifest fetch exception: ${e.message}")
      Result.failure(e)
    } finally {
      connection?.disconnect()
    }
  }

  suspend fun downloadApk(
      context: Context,
      manifest: UpdateManifest,
      onProgress: (bytesDownloaded: Long, totalBytes: Long, progress: Float) -> Unit = { _, _, _ -> },
  ): Result<File> {
    val updatesDir = File(context.cacheDir, "updates")
    if (!updatesDir.exists() && !updatesDir.mkdirs()) {
      return Result.failure(IOException("Failed to create updates directory: ${updatesDir.absolutePath}"))
    }

    // Verify storage space
    val usableSpace = updatesDir.usableSpace
    val requiredSpace = manifest.sizeBytes + STORAGE_SAFETY_MARGIN_BYTES
    if (usableSpace < requiredSpace) {
      return Result.failure(
          IOException(
              "Insufficient storage space: available ${usableSpace / (1024 * 1024)}MB, required ${requiredSpace / (1024 * 1024)}MB"))
    }

    val partFile = getPartApkFile(context, manifest)
    val finalFile = getFinalApkFile(context, manifest)

    // Clean up any other .apk or .part files in cacheDir/updates that don't match the current manifest
    updatesDir.listFiles()?.forEach { file ->
      if (file != finalFile && file != partFile) {
        file.delete()
      }
    }

    var connection: HttpURLConnection? = null
    var outStream: FileOutputStream? = null

    return try {
      val downloadUrlStr = manifest.resolveDownloadUrl(dashboardBaseUrl)
      val initialUrl = URL(downloadUrlStr)
      val targetUrl = resolveSafeRedirects(initialUrl)

      val existingLength = if (partFile.exists()) partFile.length() else 0L
      val useRange = existingLength > 0L && existingLength < manifest.sizeBytes

      connection =
          connectionFactory(targetUrl).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            if (useRange) {
              setRequestProperty("Range", "bytes=$existingLength-")
            }
          }

      val responseCode = connection.responseCode
      val isResumed: Boolean
      var bytesDownloadedSoFar: Long

      if (useRange && responseCode == HttpURLConnection.HTTP_PARTIAL) {
        val contentRange = connection.getHeaderField("Content-Range")
        if (contentRange != null && !contentRange.startsWith("bytes $existingLength-")) {
          // Range mismatch, restart from beginning
          partFile.delete()
          isResumed = false
          bytesDownloadedSoFar = 0L
          outStream = FileOutputStream(partFile, false)
        } else {
          isResumed = true
          bytesDownloadedSoFar = existingLength
          outStream = FileOutputStream(partFile, true)
        }
      } else if (responseCode == HttpURLConnection.HTTP_OK) {
        partFile.delete()
        isResumed = false
        bytesDownloadedSoFar = 0L
        outStream = FileOutputStream(partFile, false)
      } else {
        return Result.failure(IOException("Unexpected HTTP response: $responseCode from $targetUrl"))
      }

      // Initialize SHA-256 digest
      val digest = MessageDigest.getInstance("SHA-256")
      if (isResumed && partFile.exists() && partFile.length() > 0L) {
        FileInputStream(partFile).use { fis ->
          val buf = ByteArray(BUFFER_SIZE)
          var readSoFar = 0L
          while (readSoFar < existingLength) {
            val r = fis.read(buf)
            if (r == -1) break
            val toUpdate = minOf(r.toLong(), existingLength - readSoFar).toInt()
            digest.update(buf, 0, toUpdate)
            readSoFar += toUpdate
          }
        }
      }

      val inputStream = connection.inputStream
      val buffer = ByteArray(BUFFER_SIZE)
      var bytesRead: Int

      onProgress(bytesDownloadedSoFar, manifest.sizeBytes, (bytesDownloadedSoFar.toFloat() / manifest.sizeBytes).coerceIn(0f, 1f))

      while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        if (!currentCoroutineContext().isActive) {
          throw CancellationException("Download canceled by caller")
        }
        digest.update(buffer, 0, bytesRead)
        outStream.write(buffer, 0, bytesRead)
        bytesDownloadedSoFar += bytesRead
        onProgress(bytesDownloadedSoFar, manifest.sizeBytes, (bytesDownloadedSoFar.toFloat() / manifest.sizeBytes).coerceIn(0f, 1f))

        if (bytesDownloadedSoFar > manifest.sizeBytes) {
          throw IOException("Downloaded bytes exceeded manifest size: $bytesDownloadedSoFar > ${manifest.sizeBytes}")
        }
      }

      outStream.flush()
      outStream.close()
      outStream = null

      // Verify exact byte count
      if (partFile.length() != manifest.sizeBytes) {
        partFile.delete()
        return Result.failure(
            IOException("Download incomplete or size mismatch: ${partFile.length()} != ${manifest.sizeBytes}"))
      }

      // Verify SHA-256
      val computedSha256 = digest.digest().joinToString("") { "%02x".format(it) }
      if (!computedSha256.equals(manifest.sha256, ignoreCase = true)) {
        partFile.delete()
        TSLog.e(TAG, "SHA-256 mismatch: computed $computedSha256 != manifest ${manifest.sha256}")
        return Result.failure(
            SecurityException("SHA-256 digest mismatch for downloaded APK"))
      }

      // Atomically move to final file
      finalFile.delete()
      if (!partFile.renameTo(finalFile)) {
        partFile.copyTo(finalFile, overwrite = true)
        partFile.delete()
      }

      TSLog.d(TAG, "APK download successfully completed and verified: ${finalFile.absolutePath}")
      Result.success(finalFile)
    } catch (e: CancellationException) {
      TSLog.d(TAG, "Download canceled: removing partial file")
      runCatching { outStream?.close() }
      partFile.delete()
      throw e
    } catch (e: Exception) {
      TSLog.w(TAG, "Download failed: ${e.message}")
      runCatching { outStream?.close() }
      Result.failure(e)
    } finally {
      connection?.disconnect()
    }
  }

  private fun resolveSafeRedirects(initialUrl: URL): URL {
    var currentUrl = initialUrl
    val expectedHost = URI(dashboardBaseUrl.trimEnd('/')).host?.lowercase()
        ?: throw IllegalArgumentException("Invalid dashboard base URL host")

    var redirectCount = 0
    while (redirectCount < 3) {
      val connection = connectionFactory(currentUrl).apply {
        requestMethod = "HEAD"
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = false
      }

      try {
        val code = connection.responseCode
        if (code in 301..308) {
          val location = connection.getHeaderField("Location")
              ?: throw IOException("Redirect without Location header")
          val redirectUri = URI(location)
          val resolvedUri = if (redirectUri.isAbsolute) redirectUri else URI(currentUrl.toString()).resolve(redirectUri)

          if (!"https".equals(resolvedUri.scheme, ignoreCase = true)) {
            throw SecurityException("Redirect to non-HTTPS URL disallowed: $resolvedUri")
          }
          val redirectHost = resolvedUri.host?.lowercase()
          if (redirectHost != expectedHost) {
            throw SecurityException("Redirect across host disallowed: $redirectHost != $expectedHost")
          }
          currentUrl = resolvedUri.toURL()
          redirectCount++
        } else {
          break
        }
      } finally {
        connection.disconnect()
      }
    }
    return currentUrl
  }
}
