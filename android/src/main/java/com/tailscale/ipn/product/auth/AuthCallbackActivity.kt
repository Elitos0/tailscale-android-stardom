// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.tailscale.ipn.App
import com.tailscale.ipn.MainActivity

class AuthCallbackActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    openMainActivity()
    (application as App)
        .stardomSessionController
        .handleAuthorizationIntent(
            this, intent, onRecoveredAuthorization = ::openMainActivity, onFinished = { finish() })
  }

  private fun openMainActivity() {
    startActivity(
        Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER))
  }
}
