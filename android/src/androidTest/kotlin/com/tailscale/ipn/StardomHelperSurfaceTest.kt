// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailscale.ipn.ui.view.stardomMainFlowIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StardomHelperSurfaceTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val packageManager = context.packageManager

  @Test
  fun ipnReceiverIsNotExportedInInstalledApplication() {
    val info =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          packageManager.getReceiverInfo(
              ComponentName(context, IPNReceiver::class.java),
              PackageManager.ComponentInfoFlags.of(0),
          )
        } else {
          @Suppress("DEPRECATION")
          packageManager.getReceiverInfo(ComponentName(context, IPNReceiver::class.java), 0)
        }

    assertFalse(info.exported)
  }

  @Test
  fun integrationLoginReceiverIsAbsentFromApplicationTest() {
    assertThrows(PackageManager.NameNotFoundException::class.java) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getReceiverInfo(
            ComponentName(context.packageName, "com.tailscale.ipn.IntegrationLoginReceiver"),
            PackageManager.ComponentInfoFlags.of(0),
        )
      } else {
        @Suppress("DEPRECATION")
        packageManager.getReceiverInfo(
            ComponentName(context.packageName, "com.tailscale.ipn.IntegrationLoginReceiver"), 0)
      }
    }
  }

  @Test
  fun publicConnectAndUseExitActionsHaveNoImplicitBroadcastRoute() {
    listOf(
            "com.tailscale.ipn.CONNECT_VPN",
            "com.tailscale.ipn.DISCONNECT_VPN",
            "com.tailscale.ipn.USE_EXIT_NODE",
        )
        .forEach { action ->
          val matches =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryBroadcastReceivers(
                    Intent(action), PackageManager.ResolveInfoFlags.of(0))
              } else {
                @Suppress("DEPRECATION") packageManager.queryBroadcastReceivers(Intent(action), 0)
              }
          assertTrue("implicit broadcast route remains for $action", matches.isEmpty())
        }
  }

  @Test
  fun disconnectedTaildropRoutesToPolicyControlledMainActivity() {
    val intent = stardomMainFlowIntent(context)

    assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
    assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }
}
