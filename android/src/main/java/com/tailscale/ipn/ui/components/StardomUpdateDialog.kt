// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.product.update.UpdateManifest
import com.tailscale.ipn.product.update.UpdateState
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.theme.StardomTechnicalFont
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors

@Composable
fun StardomUpdateDialog(
    updateState: UpdateState,
    currentVersionName: String,
    language: AppLanguage,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
) {
  val manifest: UpdateManifest? =
      when (updateState) {
        is UpdateState.UpdateAvailable -> updateState.manifest
        is UpdateState.Downloading -> updateState.manifest
        is UpdateState.Downloaded -> updateState.manifest
        is UpdateState.Installing -> updateState.manifest
        is UpdateState.Error -> updateState.manifest
        else -> null
      }

  val isForced: Boolean =
      when (updateState) {
        is UpdateState.UpdateAvailable -> updateState.isForced
        is UpdateState.Downloading -> updateState.isForced
        is UpdateState.Downloaded -> updateState.isForced
        is UpdateState.Installing -> updateState.isForced
        is UpdateState.Error -> updateState.isForced
        else -> false
      }

  BackHandler(enabled = true) {
    if (!isForced && updateState !is UpdateState.Downloading) {
      onDismiss()
    }
  }

  // Full-screen overlay with semi-transparent background
  Box(
      modifier =
          Modifier.fillMaxSize()
              .testTag("stardom_update_dialog_scrim")
              .background(StardomColors.Background.copy(alpha = 0.92f))
              .clickable(enabled = !isForced && updateState !is UpdateState.Downloading) { onDismiss() }
              .padding(20.dp),
      contentAlignment = Alignment.Center) {
        // Modal dialog content box
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .testTag("stardom_update_dialog")
                    .background(StardomColors.Panel)
                    .border(1.dp, StardomColors.BorderStrong)
                    .clickable(enabled = false) {}
                    .padding(20.dp)) {
              Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()) {
                      Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                          Text(
                              text = StardomLocalization.updateDialogTitle(language),
                              color = StardomColors.TextPrimary,
                              fontSize = 13.sp,
                              fontWeight = FontWeight.Medium,
                              fontFamily = SpaceGrotesk,
                              letterSpacing = 1.2.sp)

                          if (isForced) {
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier =
                                    Modifier.background(StardomColors.PanelSelected)
                                        .border(1.dp, StardomColors.Error)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)) {
                                  Text(
                                      text = StardomLocalization.updateDialogMandatoryBadge(language),
                                      color = StardomColors.Error,
                                      fontSize = 8.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      letterSpacing = 0.5.sp)
                                }
                          }
                        }

                        Spacer(Modifier.height(4.dp))

                        val targetVersion = manifest?.versionName ?: ""
                        Text(
                            text =
                                StardomLocalization.updateCurrentVersion(
                                    language, currentVersionName, targetVersion),
                            color = StardomColors.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = StardomTechnicalFont(language),
                            letterSpacing = 0.5.sp)
                      }

                      if (!isForced && updateState !is UpdateState.Downloading) {
                        Box(
                            modifier =
                                Modifier.clickable(onClickLabel = "Close") { onDismiss() }
                                    .padding(4.dp)) {
                              Text(
                                  text = "✕",
                                  color = StardomColors.TextSecondary,
                                  fontSize = 12.sp,
                                  fontFamily = StardomTechnicalFont(language))
                            }
                      }
                    }

                Spacer(Modifier.height(16.dp))

                // Changelog Card
                if (manifest != null) {
                  Text(
                      text = StardomLocalization.updateChangelogHeader(language),
                      color = StardomColors.TextSecondary,
                      fontSize = 9.sp,
                      fontFamily = StardomTechnicalFont(language),
                      letterSpacing = 1.sp)

                  Spacer(Modifier.height(6.dp))

                  val changelogText = manifest.localizedChangelog(language.code)
                  Box(
                      modifier =
                          Modifier.fillMaxWidth()
                              .fillMaxHeight(0.35f)
                              .background(StardomColors.Background)
                              .border(1.dp, StardomColors.BorderFaint)
                              .padding(10.dp)) {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                          Text(
                              text = changelogText,
                              color = StardomColors.TextPrimary,
                              fontSize = 10.sp,
                              fontFamily = StardomTechnicalFont(language),
                              lineHeight = 15.sp)
                        }
                      }

                  Spacer(Modifier.height(10.dp))

                  val sizeMb = "%.1f".format(manifest.sizeBytes.toDouble() / (1024 * 1024))
                  Text(
                      text = "${StardomLocalization.updateSizeHeader(language, sizeMb)} • SHA-256: ${manifest.sha256.take(8)}...${manifest.sha256.takeLast(8)}",
                      color = StardomColors.TextMuted,
                      fontSize = 8.5.sp,
                      fontFamily = StardomTechnicalFont(language))

                  Spacer(Modifier.height(16.dp))
                }

                // Progress Bar or State Message
                when (updateState) {
                  is UpdateState.Downloading -> {
                    val downloadedMb =
                        "%.1f".format(updateState.bytesDownloaded.toDouble() / (1024 * 1024))
                    val totalMb =
                        "%.1f".format(updateState.totalBytes.toDouble() / (1024 * 1024))
                    val percent = (updateState.progress * 100).toInt()

                    Text(
                        text =
                            StardomLocalization.updateProgressText(
                                language, downloadedMb, totalMb, percent),
                        color = StardomColors.TextPrimary,
                        fontSize = 9.sp,
                        fontFamily = StardomTechnicalFont(language))

                    Spacer(Modifier.height(8.dp))

                    // Progress Track
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .height(4.dp)
                                .background(StardomColors.BorderFaint)) {
                          Box(
                              modifier =
                                  Modifier.fillMaxWidth(updateState.progress.coerceIn(0f, 1f))
                                      .height(4.dp)
                                      .background(StardomColors.Selected))
                        }

                    Spacer(Modifier.height(16.dp))
                  }
                  is UpdateState.Error -> {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(StardomColors.Background)
                                .border(1.dp, StardomColors.Error)
                                .padding(10.dp)) {
                          Text(
                              text = "ОШИБКА // ERROR: ${updateState.message}",
                              color = StardomColors.Error,
                              fontSize = 9.5.sp,
                              fontFamily = StardomTechnicalFont(language))
                        }
                    Spacer(Modifier.height(16.dp))
                  }
                  is UpdateState.Downloaded -> {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .background(StardomColors.Background)
                                .border(1.dp, StardomColors.BorderStrong)
                                .padding(10.dp)) {
                          Text(
                              text = "ПАКЕТ ПРОВЕРЕН // SHA-256 VALIDATED • ГОТОВ К УСТАНОВКЕ",
                              color = StardomColors.TextPrimary,
                              fontSize = 9.sp,
                              fontFamily = StardomTechnicalFont(language))
                        }
                    Spacer(Modifier.height(16.dp))
                  }
                  else -> {}
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                      when (updateState) {
                        is UpdateState.UpdateAvailable -> {
                          if (!isForced) {
                            Box(
                                modifier =
                                    Modifier.testTag("update_dialog_later_btn")
                                        .border(1.dp, StardomColors.Border)
                                        .clickable(onClickLabel = "Later") { onDismiss() }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)) {
                                  Text(
                                      text = StardomLocalization.updateLaterBtn(language),
                                      color = StardomColors.TextSecondary,
                                      fontSize = 9.5.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      letterSpacing = 1.sp)
                                }
                            Spacer(Modifier.width(10.dp))
                          }

                          Box(
                              modifier =
                                  Modifier.testTag("update_dialog_download_btn")
                                      .background(StardomColors.Selected)
                                      .border(1.dp, StardomColors.BorderStrong)
                                      .clickable(onClickLabel = "Start Download") {
                                        onStartDownload()
                                      }
                                      .padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    text = StardomLocalization.updateNowBtn(language),
                                    color = StardomColors.Background,
                                    fontSize = 9.5.sp,
                                    fontFamily = StardomTechnicalFont(language),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp)
                              }
                        }
                        is UpdateState.Downloading -> {
                          Box(
                              modifier =
                                  Modifier.testTag("update_dialog_cancel_btn")
                                      .border(1.dp, StardomColors.Border)
                                      .clickable(onClickLabel = "Cancel Download") {
                                        onCancelDownload()
                                      }
                                      .padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(
                                    text = StardomLocalization.updateCancelBtn(language),
                                    color = StardomColors.TextSecondary,
                                    fontSize = 9.5.sp,
                                    fontFamily = StardomTechnicalFont(language),
                                    letterSpacing = 1.sp)
                              }
                        }
                        is UpdateState.Downloaded -> {
                          Box(
                              modifier =
                                  Modifier.testTag("update_dialog_install_btn")
                                      .background(StardomColors.Selected)
                                      .border(1.dp, StardomColors.BorderStrong)
                                      .clickable(onClickLabel = "Install Update") { onInstall() }
                                      .padding(horizontal = 18.dp, vertical = 10.dp)) {
                                Text(
                                    text = StardomLocalization.updateInstallBtn(language),
                                    color = StardomColors.Background,
                                    fontSize = 10.sp,
                                    fontFamily = StardomTechnicalFont(language),
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp)
                              }
                        }
                        is UpdateState.Error -> {
                          if (!isForced) {
                            Box(
                                modifier =
                                    Modifier.border(1.dp, StardomColors.Border)
                                        .clickable(onClickLabel = "Dismiss") { onDismiss() }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)) {
                                  Text(
                                      text = StardomLocalization.closeBtn(language),
                                      color = StardomColors.TextSecondary,
                                      fontSize = 9.5.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      letterSpacing = 1.sp)
                                }
                            Spacer(Modifier.width(10.dp))
                          }

                          if (updateState.canRetry) {
                            Box(
                                modifier =
                                    Modifier.testTag("update_dialog_retry_btn")
                                        .background(StardomColors.Selected)
                                        .border(1.dp, StardomColors.BorderStrong)
                                        .clickable(onClickLabel = "Retry") { onRetry() }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)) {
                                  Text(
                                      text = StardomLocalization.updateRetryBtn(language),
                                      color = StardomColors.Background,
                                      fontSize = 9.5.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      fontWeight = FontWeight.Bold,
                                      letterSpacing = 1.sp)
                                }
                          }
                        }
                        else -> {}
                      }
                    }
              }
            }
      }
}
