// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import androidx.test.ext.junit.rules.activityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthFirstOnboardingTest {
  @get:Rule val activityRule = activityScenarioRule<MainActivity>()

  @Test
  fun cleanInstallOffersStardomLoginBeforeVpnPermission() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val getStarted = device.wait(Until.findObject(By.text("Get Started")), 5_000)
    assertNotNull(getStarted)
    getStarted.click()

    assertNotNull(device.wait(Until.findObject(By.text("Log in")), 5_000))
    assertNotNull(device.wait(Until.findObject(By.desc("Stardom VPN")), 5_000))
    assertNull(device.findObject(By.text("Connection request")))
    assertNull(device.findObject(By.text("VPN access is unavailable")))
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
