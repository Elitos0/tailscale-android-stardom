// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors

@Composable
fun StardomLoginModal(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.RU
) {
  // Full-screen dark scrim dimming everything behind it
  Box(
      modifier =
          modifier
              .fillMaxSize()
              .background(Color.Black.copy(alpha = 0.82f))
              .clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                  onClick = {})
              .testTag("login_scrim"),
      contentAlignment = Alignment.Center) {
        // Centered modal island
        Box(
            modifier =
                Modifier.fillMaxWidth(0.92f)
                    .testTag("login_dialog")
                    .background(StardomColors.Background)
                    .border(1.dp, StardomColors.BorderStrong)
                    .padding(24.dp)) {
              Column(
                  horizontalAlignment = Alignment.CenterHorizontally,
                  verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Header badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()) {
                          Box(modifier = Modifier.size(6.dp).background(StardomColors.Selected))
                          Spacer(modifier = Modifier.width(10.dp))
                          Text(
                              text =
                                  if (language == AppLanguage.RU) "STARDOM // АВТОРИЗАЦИЯ"
                                  else "STARDOM // AUTHENTICATION",
                              color = StardomColors.TextPrimary,
                              fontSize = 12.sp,
                              fontWeight = FontWeight.Medium,
                              fontFamily = SpaceGrotesk,
                              letterSpacing = 1.5.sp)
                        }

                    // Subtitle / explainer
                    Text(
                        text =
                            if (language == AppLanguage.RU)
                                "Для подключения к орбитальной сети выполните вход в учетную запись"
                            else
                                "Sign in to authenticate your node and establish secure orbital routing",
                        color = StardomColors.TextSecondary,
                        fontFamily = IbmPlexMono,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        letterSpacing = 0.5.sp,
                        textAlign = TextAlign.Center)

                    Spacer(modifier = Modifier.height(4.dp))

                    // Direct SIGN IN action button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag("login_button")
                                .background(StardomColors.Selected)
                                .clickable(onClickLabel = "Sign In") { onSignIn() }
                                .padding(vertical = 12.dp)) {
                          Text(
                              text =
                                  if (language == AppLanguage.RU) "ВОЙТИ В СИСТЕМУ ❯"
                                  else "SIGN IN ❯",
                              color = StardomColors.Background,
                              fontFamily = SpaceGrotesk,
                              fontWeight = FontWeight.Bold,
                              fontSize = 12.sp,
                              letterSpacing = 1.2.sp)
                        }
                  }
            }
      }
}
