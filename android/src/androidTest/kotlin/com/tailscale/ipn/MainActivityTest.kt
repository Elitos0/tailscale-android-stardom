// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.tailscale.ipn.product.auth.AUTH_TRANSACTION_STATE_EXTRA
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityTest {
  private var scenario: ActivityScenario<MainActivity>? = null

  @Before
  fun cleanBeforeLaunch() {
    StardomInstrumentationState.cleanBeforeActivityLaunch()
  }

  @After
  fun closeActivity() {
    scenario?.close()
  }

  @Test
  fun callbackShapedLaunchRemainsOnDeterministicStardomSignInFlow() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    val device = UiDevice.getInstance(instrumentation)
    val callbackShapedIntent =
        Intent(context, MainActivity::class.java)
            .setAction("com.stardom.vpn.AUTH_CALLBACK")
            .putExtra(AUTH_TRANSACTION_STATE_EXTRA, "adversarial-state")
            .putExtra("net.openid.appauth.AuthorizationResponse", "adversarial-response")

    scenario = ActivityScenario.launch(callbackShapedIntent)
    StardomInstrumentationState.assertForegroundProductUi(device)

    assertNull(device.findObject(By.text(context.getString(R.string.getStarted))))
    val loginButton =
        device.wait(Until.findObject(By.text("ВОЙТИ В СИСТЕМУ ❯")), 5_000)
            ?: device.wait(Until.findObject(By.text("SIGN IN ❯")), 5_000)
    assertNotNull(loginButton)
    assertNull(device.findObject(By.text(context.getString(R.string.auth_key_title))))
    StardomInstrumentationState.assertForegroundProductUi(device)
  }
}
