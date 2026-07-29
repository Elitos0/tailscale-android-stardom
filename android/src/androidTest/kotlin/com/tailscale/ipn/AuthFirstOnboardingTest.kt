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
    assertNull(device.findObject(By.text("Connection request")))
    assertNull(device.findObject(By.text("VPN access is unavailable")))
  }
}
