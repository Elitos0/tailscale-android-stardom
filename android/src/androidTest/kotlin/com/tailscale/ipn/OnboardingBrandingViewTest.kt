// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailscale.ipn.product.ui.ConnectionStage
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.theme.AppTheme
import com.tailscale.ipn.ui.view.ConnectView
import com.tailscale.ipn.ui.view.IntroView
import com.tailscale.ipn.ui.view.StartingView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingBrandingViewTest {
  @get:Rule val composeRule = createAndroidComposeRule<OnboardingBrandingTestActivity>()

  @Test
  fun onboardingViewsRenderTheStardomLogo() {
    val screen = mutableStateOf(Screen.Starting)

    composeRule.setContent {
      AppTheme {
        when (screen.value) {
          Screen.Starting -> StartingView()
          Screen.Intro -> IntroView(onContinue = {})
          Screen.SignIn -> connectView(ConnectionStage.SignIn)
          Screen.VpnPermission -> connectView(ConnectionStage.RequestVpnPermission)
        }
      }
    }

    assertStardomLogo()

    composeRule.runOnIdle { screen.value = Screen.Intro }
    composeRule
        .onNodeWithText("Stardom VPN securely connects this device to your private network.")
        .assertIsDisplayed()
    composeRule
        .onNodeWithText(
            "Stardom VPN uses your account, device name, OS version, and IP address to connect and manage this device. Connection events may be recorded for security and support.")
        .assertIsDisplayed()
    assertStardomLogo()

    composeRule.runOnIdle { screen.value = Screen.SignIn }
    composeRule.onNodeWithText("Log in to Stardom VPN to continue.").assertIsDisplayed()
    assertStardomLogo()

    composeRule.runOnIdle { screen.value = Screen.VpnPermission }
    composeRule
        .onNodeWithText("Allow Stardom VPN to create a VPN connection on this device.")
        .assertIsDisplayed()
    assertStardomLogo()
  }

  @Test
  fun vpnPermissionRequestRequiresExplicitConnectClick() {
    var permissionRequests = 0

    composeRule.setContent {
      AppTheme {
        ConnectView(
            state = Ipn.State.Running,
            connectionStage = ConnectionStage.RequestVpnPermission,
            user = null,
            connectAction = {},
            refreshAccess = {},
            loginAction = {},
            loginAtUrlAction = {},
            selfNode = null,
            showVPNPermissionLauncher = { permissionRequests++ })
      }
    }

    composeRule.onNodeWithText("Connect").assertIsDisplayed()
    composeRule.runOnIdle { assertEquals(0, permissionRequests) }

    composeRule.onNodeWithText("Connect").performClick()
    composeRule.runOnIdle { assertEquals(1, permissionRequests) }
  }

  @Composable
  private fun connectView(connectionStage: ConnectionStage) {
    ConnectView(
        state = Ipn.State.NeedsLogin,
        connectionStage = connectionStage,
        user = null,
        connectAction = {},
        refreshAccess = {},
        loginAction = {},
        loginAtUrlAction = {},
        selfNode = null,
        showVPNPermissionLauncher = {})
  }

  private fun assertStardomLogo() {
    composeRule.onNodeWithContentDescription("Stardom VPN").assertIsDisplayed()
  }

  private enum class Screen {
    Starting,
    Intro,
    SignIn,
    VpnPermission,
  }
}
