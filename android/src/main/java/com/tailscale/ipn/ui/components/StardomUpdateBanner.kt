// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.product.update.UpdateManifest
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors

@Composable
fun StardomUpdateBanner(
    manifest: UpdateManifest,
    language: AppLanguage,
    onDetails: () -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isForced: Boolean = false,
) {
  Box(
      modifier =
          modifier
              .fillMaxWidth()
              .testTag("stardom_update_banner")
              .background(StardomColors.Panel)
              .border(1.dp, StardomColors.BorderStrong)
              .padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
              Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f)) {
                    Box(
                        modifier =
                            Modifier.size(6.dp)
                                .background(
                                    if (isForced) StardomColors.Error else StardomColors.Selected))

                    Spacer(Modifier.width(10.dp))

                    Column {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = StardomLocalization.updateBannerTitle(language),
                            color = StardomColors.TextPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = SpaceGrotesk,
                            letterSpacing = 1.sp)

                        Spacer(Modifier.width(6.dp))

                        Text(
                            text = "v${manifest.versionName}",
                            color = StardomColors.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = IbmPlexMono)
                      }

                      Spacer(Modifier.height(2.dp))

                      Text(
                          text = StardomLocalization.updateBannerSubtitle(language, manifest.versionName),
                          color = StardomColors.TextMuted,
                          fontSize = 8.5.sp,
                          fontFamily = IbmPlexMono,
                          maxLines = 1)
                    }
                  }

              Spacer(Modifier.width(8.dp))

              Row(verticalAlignment = Alignment.CenterVertically) {
                // Details button
                Box(
                    modifier =
                        Modifier.testTag("update_banner_details_btn")
                            .border(1.dp, StardomColors.Border)
                            .background(StardomColors.PanelSelected)
                            .clickable(onClickLabel = "Update Details") { onDetails() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)) {
                      Text(
                          text = StardomLocalization.updateDetailsBtn(language),
                          color = StardomColors.TextSecondary,
                          fontSize = 8.5.sp,
                          fontFamily = IbmPlexMono,
                          letterSpacing = 0.5.sp)
                    }

                Spacer(Modifier.width(6.dp))

                // Update button
                Box(
                    modifier =
                        Modifier.testTag("update_banner_update_btn")
                            .border(1.dp, StardomColors.BorderStrong)
                            .background(StardomColors.Selected)
                            .clickable(onClickLabel = "Update Now") { onUpdate() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)) {
                      Text(
                          text = StardomLocalization.updateNowBtn(language),
                          color = StardomColors.Background,
                          fontSize = 8.5.sp,
                          fontFamily = IbmPlexMono,
                          fontWeight = FontWeight.Bold,
                          letterSpacing = 0.5.sp)
                    }

                if (!isForced) {
                  Spacer(Modifier.width(6.dp))

                  // Dismiss button
                  Box(
                      modifier =
                          Modifier.testTag("update_banner_dismiss_btn")
                              .clickable(onClickLabel = "Dismiss Update Banner") { onDismiss() }
                              .padding(4.dp)) {
                        Text(
                            text = "✕",
                            color = StardomColors.TextMuted,
                            fontSize = 10.sp,
                            fontFamily = IbmPlexMono)
                      }
                }
              }
            }
      }
}
