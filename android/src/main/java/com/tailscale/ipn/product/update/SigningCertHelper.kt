// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest

object SigningCertHelper {

  const val STARDOM_RELEASE_CERT_SHA256 =
      "ab82b98c73ab1b955d5d682fc2013a342f3800f0a19392d3614eced3cf7ce94c"

  fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun getInstalledAppCertSha256(context: Context): List<String> {
    val pm = context.packageManager
    val packageName = context.packageName
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
      extractCertificatesFromSigningInfo(info.signingInfo)
    } else {
      @Suppress("DEPRECATION")
      val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
      @Suppress("DEPRECATION")
      extractCertificatesFromSignatures(info.signatures)
    }
  }

  fun getArchiveCertSha256(packageInfo: PackageInfo): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      extractCertificatesFromSigningInfo(packageInfo.signingInfo)
    } else {
      @Suppress("DEPRECATION")
      extractCertificatesFromSignatures(packageInfo.signatures)
    }
  }

  private fun extractCertificatesFromSigningInfo(signingInfo: android.content.pm.SigningInfo?): List<String> {
    if (signingInfo == null) return emptyList()
    val signatures: Array<Signature>? =
        if (signingInfo.hasMultipleSigners()) {
          signingInfo.apkContentsSigners
        } else {
          signingInfo.signingCertificateHistory
        }
    return signatures?.map { sha256Hex(it.toByteArray()) } ?: emptyList()
  }

  private fun extractCertificatesFromSignatures(signatures: Array<Signature>?): List<String> {
    return signatures?.map { sha256Hex(it.toByteArray()) } ?: emptyList()
  }

  fun verifyCertificatesMatch(installedCerts: List<String>, archiveCerts: List<String>): Boolean {
    if (installedCerts.isEmpty() || archiveCerts.isEmpty()) return false
    val installedSet = installedCerts.map { it.lowercase() }.toSet()
    val archiveSet = archiveCerts.map { it.lowercase() }.toSet()
    if (installedSet.intersect(archiveSet).isNotEmpty()) return true
    if (com.tailscale.ipn.BuildConfig.DEBUG && archiveSet.contains(STARDOM_RELEASE_CERT_SHA256)) {
      return true
    }
    return false
  }
}
