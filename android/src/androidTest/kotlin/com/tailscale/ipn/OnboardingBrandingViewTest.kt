package com.tailscale.ipn

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.view.IntroView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingBrandingViewTest {
  @get:Rule val composeRule = createAndroidComposeRule<OnboardingBrandingTestActivity>()

  @Test
  fun introViewRendersTheStardomLogoAndCopy() {
    composeRule.setContent { AppTheme { IntroView(onContinue = {}) } }

    assertStardomLogo()
    composeRule
        .onNodeWithText("Stardom VPN securely connects this device to your private network.")
        .assertIsDisplayed()
    composeRule
        .onNodeWithText(
            "Stardom VPN uses your account, device name, OS version, and IP address to connect and manage this device. Connection events may be recorded for security and support.")
        .assertIsDisplayed()
  }

  private fun assertStardomLogo() {
    composeRule.onNodeWithContentDescription("Stardom VPN").assertIsDisplayed()
  }
}
