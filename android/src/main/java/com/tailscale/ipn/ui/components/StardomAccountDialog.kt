// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.tailscale.ipn.ui.model.AccountProfile
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors

@Composable
fun StardomAccountDialog(
    profile: AccountProfile,
    onDismiss: () -> Unit,
    onLogout: (() -> Unit)? = null,
    language: AppLanguage = AppLanguage.RU
) {
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current

  Dialog(onDismissRequest = onDismiss) {
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .testTag("account_dialog")
                .background(StardomColors.Background)
                .border(1.dp, StardomColors.BorderStrong)
                .padding(20.dp)) {
          Column {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(StardomColors.Selected))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = StardomLocalization.accountDialogTitle(language),
                        color = StardomColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = SpaceGrotesk,
                        letterSpacing = 1.2.sp)
                  }

                  Box(
                      modifier =
                          Modifier.background(StardomColors.Panel)
                              .border(1.dp, StardomColors.Border)
                              .clickable(onClickLabel = "Close") { onDismiss() }
                              .padding(horizontal = 8.dp, vertical = 3.dp)) {
                        Text(
                            text = "✕",
                            color = StardomColors.TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = IbmPlexMono)
                      }
                }

            Spacer(modifier = Modifier.height(16.dp))

            // Account ID Badge
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(StardomColors.Panel)
                        .border(1.dp, StardomColors.Border)
                        .padding(12.dp)) {
                  Column {
                    Text(
                        text = StardomLocalization.accountIdentity(language),
                        color = StardomColors.TextMuted,
                        fontSize = 8.sp,
                        fontFamily = IbmPlexMono,
                        letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = profile.accountId,
                        color = StardomColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = SpaceGrotesk,
                        letterSpacing = 1.5.sp)
                  }
                }

            if (profile.isStub) {
              Spacer(modifier = Modifier.height(8.dp))
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .background(StardomColors.PanelSelected)
                          .border(1.dp, StardomColors.BorderFaint)
                          .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = StardomLocalization.accountStubNotice(language),
                        color = StardomColors.TextSecondary,
                        fontSize = 8.sp,
                        fontFamily = IbmPlexMono,
                        letterSpacing = 0.5.sp)
                  }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Key Data Pairs
            val quotaText =
                if (profile.totalQuota.equals("UNLIMITED", ignoreCase = true)) {
                  StardomLocalization.accountUnlimited(language)
                } else {
                  profile.totalQuota
                }

            AccountMetricRow(
                label = StardomLocalization.accountTier(language), value = profile.tier)
            AccountMetricRow(
                label = StardomLocalization.accountPublicKey(language), value = profile.publicKey)
            AccountMetricRow(
                label = StardomLocalization.accountActiveDevices(language),
                value =
                    "${profile.activeDevices} / ${profile.maxDevices} ${StardomLocalization.accountDevicesSuffix(language)}")
            AccountMetricRow(
                label = StardomLocalization.accountDataTransferred(language),
                value = "${profile.bandwidthUsedGb} GB / $quotaText")
            AccountMetricRow(
                label = StardomLocalization.accountValidUntil(language), value = profile.validUntil)

            Spacer(modifier = Modifier.height(18.dp))

            // Action Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()) {
                  Box(
                      contentAlignment = Alignment.Center,
                      modifier =
                          Modifier.weight(1f)
                              .background(StardomColors.Panel)
                              .border(1.dp, StardomColors.Border)
                              .clickable(onClickLabel = "Copy Key") {
                                clipboardManager.setText(AnnotatedString(profile.publicKey))
                                Toast.makeText(
                                        context,
                                        StardomLocalization.keyCopiedToast(language),
                                        Toast.LENGTH_SHORT)
                                    .show()
                              }
                              .padding(vertical = 12.dp)) {
                        Text(
                            text = StardomLocalization.copyKeyBtn(language),
                            color = StardomColors.TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = SpaceGrotesk,
                            letterSpacing = 1.sp)
                      }

                  Box(
                      contentAlignment = Alignment.Center,
                      modifier =
                          Modifier.weight(1f)
                              .background(StardomColors.Selected)
                              .clickable(onClickLabel = "Confirm") { onDismiss() }
                              .padding(vertical = 12.dp)) {
                        Text(
                            text = StardomLocalization.confirmBtn(language),
                            color = StardomColors.Background,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = SpaceGrotesk,
                            letterSpacing = 1.sp)
                      }
                }

            if (onLogout != null) {
              Spacer(modifier = Modifier.height(8.dp))
              Box(
                  contentAlignment = Alignment.Center,
                  modifier =
                      Modifier.fillMaxWidth()
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.BorderFaint)
                          .clickable(onClickLabel = "Log out") {
                            onLogout()
                            onDismiss()
                          }
                          .padding(vertical = 10.dp)) {
                    Text(
                        text = StardomLocalization.logoutBtn(language),
                        color = StardomColors.Error,
                        fontSize = 10.sp,
                        fontFamily = IbmPlexMono,
                        letterSpacing = 1.sp)
                  }
            }
          }
        }
  }
}

@Composable
private fun AccountMetricRow(label: String, value: String) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            text = label,
            color = StardomColors.TextMuted,
            fontSize = 9.sp,
            fontFamily = IbmPlexMono)
        Text(
            text = value,
            color = StardomColors.TextSecondary,
            fontSize = 10.sp,
            fontFamily = IbmPlexMono)
      }
}
