// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util

import android.content.Context
import android.util.Log
import libtailscale.Libtailscale

object TSLog {
  const val TAG = "STARDOM"

  private var appContext: Context? = null
  var libtailscaleWrapper = LibtailscaleWrapper()

  fun init(context: Context) {
    appContext = context.applicationContext
  }

  @JvmStatic
  @JvmOverloads
  fun d(tag: String? = TAG, message: String) {
    val resolvedTag = tag ?: TAG
    Log.i(resolvedTag, message)
    libtailscaleWrapper.sendLog(resolvedTag, message)
  }

  @JvmStatic
  @JvmOverloads
  fun i(tag: String? = TAG, message: String, throwable: Throwable? = null) {
    val resolvedTag = tag ?: TAG
    if (throwable == null) {
      Log.i(resolvedTag, message)
      libtailscaleWrapper.sendLog(resolvedTag, message)
    } else {
      Log.i(resolvedTag, "$message: ${throwable.localizedMessage ?: throwable.message}", throwable)
      libtailscaleWrapper.sendLog(
          resolvedTag, "$message ${throwable.localizedMessage ?: throwable.message}")
    }
  }

  @JvmStatic
  @JvmOverloads
  fun w(tag: String? = TAG, message: String, throwable: Throwable? = null) {
    val resolvedTag = tag ?: TAG
    if (throwable == null) {
      Log.w(resolvedTag, message)
      Log.i(resolvedTag, message)
      libtailscaleWrapper.sendLog(resolvedTag, message)
    } else {
      Log.w(resolvedTag, message, throwable)
      Log.i(resolvedTag, "$message: ${throwable.localizedMessage ?: throwable.message}", throwable)
      libtailscaleWrapper.sendLog(
          resolvedTag, "$message ${throwable.localizedMessage ?: throwable.message}")
    }
  }

  @JvmStatic
  @JvmOverloads
  fun v(tag: String? = TAG, message: String) {
    val resolvedTag = tag ?: TAG
    Log.i(resolvedTag, message)
    libtailscaleWrapper.sendLog(resolvedTag, message)
  }

  // Overloaded function without Throwable because Java does not support default parameters
  @JvmStatic
  fun e(tag: String?, message: String) {
    val resolvedTag = tag ?: TAG
    Log.e(resolvedTag, message)
    Log.i(resolvedTag, message)
    libtailscaleWrapper.sendLog(resolvedTag, message)
  }

  @JvmStatic
  fun e(tag: String?, message: String, throwable: Throwable? = null) {
    val resolvedTag = tag ?: TAG
    if (throwable == null) {
      Log.e(resolvedTag, message)
      Log.i(resolvedTag, message)
      libtailscaleWrapper.sendLog(resolvedTag, message)
    } else {
      Log.e(resolvedTag, message, throwable)
      Log.i(resolvedTag, "$message: ${throwable.localizedMessage ?: throwable.message}", throwable)
      libtailscaleWrapper.sendLog(
          resolvedTag, "$message ${throwable.localizedMessage ?: throwable.message}")
    }
  }

  private fun isUnstableRelease(): Boolean {
    val ctx = appContext ?: return false
    return try {
      val versionName = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
      val middleNumber = versionName?.split(".")?.getOrNull(1)?.toIntOrNull()
      middleNumber?.let { it % 2 == 1 } ?: false
    } catch (_: Exception) {
      false
    }
  }

  class LibtailscaleWrapper {
    public fun sendLog(tag: String?, message: String) {
      val logTag = tag ?: ""
      try {
        Libtailscale.sendLog((logTag + ": " + message).toByteArray(Charsets.UTF_8))
      } catch (_: Throwable) {
        // Ignored in unit test environments where native libtailscale is unavailable
      }
    }
  }
}
