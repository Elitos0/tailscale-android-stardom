// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.WorkManager
import com.tailscale.ipn.product.auth.AuthentikState
import com.tailscale.ipn.product.policy.AccessState
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

object StardomInstrumentationState {
  fun cleanBeforeActivityLaunch() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val app = context.applicationContext as App

    WorkManager.getInstance(context).cancelAllWork().result.get(10, TimeUnit.SECONDS)
    context.getSystemService(NotificationManager::class.java).cancelAll()
    instrumentation.runOnMainSync {
      app.stardomSessionController.clearSession()
      context.getSharedPreferences("introScreen", Context.MODE_PRIVATE).edit().clear().commit()
    }

    assertEquals(AuthentikState.SignedOut, app.stardomSessionController.authentikState.value)
    assertEquals(AccessState.Unavailable, app.stardomSessionController.accessState.value)
  }

  fun assertForegroundProductUi(device: UiDevice) {
    val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
    assertTrue(device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000))
    assertEquals(packageName, device.currentPackageName)
  }
}
