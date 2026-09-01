// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthFirstOnboardingTest {
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
  fun cleanInstallOffersStardomLoginBeforeVpnPermission() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    scenario = ActivityScenario.launch(MainActivity::class.java)

    StardomInstrumentationState.assertForegroundProductUi(device)
    // Intro / Get Started is bypassed completely on cold launch
    assertNull(device.findObject(By.text(context.getString(R.string.getStarted))))

    // Stardom main screen centered sign-in modal is displayed directly
    val loginButton =
        device.wait(Until.findObject(By.text("ВОЙТИ В СИСТЕМУ ❯")), 5_000)
            ?: device.wait(Until.findObject(By.text("SIGN IN ❯")), 5_000)
    assertNotNull(loginButton)

    assertNull(device.findObject(By.text(context.getString(R.string.auth_key_title))))
    assertNull(device.findObject(By.text(context.getString(R.string.mullvad_exit_nodes))))
    assertNull(device.findObject(By.text(context.getString(R.string.give_permissions))))
    assertNull(device.findObject(By.text(context.getString(R.string.vpn_permission_needed))))
    StardomInstrumentationState.assertForegroundProductUi(device)
  }

  @Test
  fun onboardingResourcesUseStardomBranding() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val onboarding =
        listOf(
            context.getString(R.string.welcome1),
            context.getString(R.string.welcome2),
            context.getString(R.string.welcome_to_tailscale),
            context.getString(R.string.give_permissions),
            context.getString(R.string.login_to_join_your_tailnet),
            context.getString(R.string.vpn_explainer),
            context.getString(R.string.multiple_vpn_explainer))

    assertTrue(onboarding.all { it.contains("Stardom VPN") })
    assertTrue(onboarding.none { it.contains("Tailscale") })
    assertNotNull(context.getDrawable(R.drawable.stardom_launcher))
  }
}
