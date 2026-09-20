// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.components.StardomBackground
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors
import com.tailscale.ipn.ui.theme.StardomDimensions
import com.tailscale.ipn.ui.util.InstalledApp
import com.tailscale.ipn.ui.viewModel.SplitTunnelAppPickerViewModel

@Composable
fun SplitTunnelAppPickerView(
    backToSettings: BackNavigation,
    model: SplitTunnelAppPickerViewModel = viewModel(),
    language: AppLanguage = AppLanguage.RU,
) {
  val installedApps by model.installedApps.collectAsState()
  val selectedPackageNames by model.selectedPackageNames.collectAsState()
  val allowSelected by model.allowSelected.collectAsState()
  val builtInDisallowedPackageNames = remember { App.get().builtInDisallowedPackageNames }
  val mdmIncludedPackages by model.mdmIncludedPackages.collectAsState()
  val mdmExcludedPackages by model.mdmExcludedPackages.collectAsState()

  var searchQuery by remember { mutableStateOf("") }

  val filteredApps =
      remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
          installedApps
        } else {
          val query = searchQuery.trim().lowercase()
          installedApps.filter {
            it.name.lowercase().contains(query) || it.packageName.lowercase().contains(query)
          }
        }
      }

  val packageManager = model.installedAppsManager.packageManager
  val iconSize = 40.dp
  val iconSizePx = with(LocalDensity.current) { iconSize.roundToPx() }
  val iconCache = remember(iconSizePx) { mutableMapOf<String, ImageBitmap?>() }

  Scaffold(
      containerColor = StardomColors.Background,
      topBar = {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .height(StardomDimensions.TopBarHeight)
                    .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
              Box(
                  modifier =
                      Modifier.background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .clickable(onClickLabel = "Back") { backToSettings() }
                          .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = StardomLocalization.splitTunnelBackBtn(language),
                        color = StardomColors.TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = IbmPlexMono,
                        letterSpacing = 1.sp)
                  }

              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = StardomLocalization.splitTunnelingSection(language),
                    color = StardomColors.TextPrimary,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "МАРШРУТИЗАЦИЯ ТРАФИКА // SPLIT TUNNEL",
                    color = StardomColors.TextSecondary,
                    fontSize = 8.sp,
                    fontFamily = IbmPlexMono,
                    letterSpacing = 1.sp)
              }

              Box(modifier = Modifier.width(48.dp))
            }
      }) { innerPadding ->
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(StardomColors.Background)
                .padding(innerPadding)) {
      StardomBackground()

      Column(modifier = Modifier.fillMaxSize()) {
        // Mode Selector Tabs
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
              // Mode 1: Bypass (allowSelected = false)
              val isBypass = !allowSelected
              Box(
                  modifier =
                      Modifier.weight(1f)
                          .testTag("split_tunnel_mode_bypass")
                          .background(
                              if (isBypass) StardomColors.PanelSelected else StardomColors.Panel)
                          .border(
                              1.dp,
                              if (isBypass) StardomColors.BorderStrong
                              else StardomColors.BorderFaint)
                          .clickable {
                            if (!isBypass) {
                              model.performSelectionSwitch()
                            }
                          }
                          .padding(10.dp)) {
                    Column {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier.size(10.dp)
                                    .border(
                                        1.dp,
                                        if (isBypass) StardomColors.Selected
                                        else StardomColors.TextMuted),
                            contentAlignment = Alignment.Center) {
                              if (isBypass) {
                                Box(
                                    modifier =
                                        Modifier.size(4.dp)
                                            .background(StardomColors.Selected))
                              }
                            }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = StardomLocalization.splitTunnelModeBypass(language),
                            color =
                                if (isBypass) StardomColors.TextPrimary
                                else StardomColors.TextSecondary,
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp)
                      }
                      Spacer(modifier = Modifier.height(4.dp))
                      Text(
                          text = StardomLocalization.splitTunnelModeBypassDesc(language),
                          color = StardomColors.TextMuted,
                          fontFamily = IbmPlexMono,
                          fontSize = 8.sp,
                          lineHeight = 11.sp)
                    }
                  }

              // Mode 2: Only Selected (allowSelected = true)
              val isOnlySelected = allowSelected
              Box(
                  modifier =
                      Modifier.weight(1f)
                          .testTag("split_tunnel_mode_only_selected")
                          .background(
                              if (isOnlySelected) StardomColors.PanelSelected
                              else StardomColors.Panel)
                          .border(
                              1.dp,
                              if (isOnlySelected) StardomColors.BorderStrong
                              else StardomColors.BorderFaint)
                          .clickable {
                            if (!isOnlySelected) {
                              model.performSelectionSwitch()
                            }
                          }
                          .padding(10.dp)) {
                    Column {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier =
                                Modifier.size(10.dp)
                                    .border(
                                        1.dp,
                                        if (isOnlySelected) StardomColors.Selected
                                        else StardomColors.TextMuted),
                            contentAlignment = Alignment.Center) {
                              if (isOnlySelected) {
                                Box(
                                    modifier =
                                        Modifier.size(4.dp)
                                            .background(StardomColors.Selected))
                              }
                            }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = StardomLocalization.splitTunnelModeOnlySelected(language),
                            color =
                                if (isOnlySelected) StardomColors.TextPrimary
                                else StardomColors.TextSecondary,
                            fontFamily = SpaceGrotesk,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp)
                      }
                      Spacer(modifier = Modifier.height(4.dp))
                      Text(
                          text = StardomLocalization.splitTunnelModeOnlySelectedDesc(language),
                          color = StardomColors.TextMuted,
                          fontFamily = IbmPlexMono,
                          fontSize = 8.sp,
                          lineHeight = 11.sp)
                    }
                  }
            }

        // Terminal Search Bar
        Spacer(modifier = Modifier.height(10.dp))
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .background(StardomColors.Panel)
                    .border(1.dp, StardomColors.Border)
                    .padding(horizontal = 14.dp, vertical = 10.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = ">",
                    color = StardomColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = IbmPlexMono)
                Spacer(modifier = Modifier.width(10.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle =
                        TextStyle(
                            color = StardomColors.TextPrimary,
                            fontSize = 12.sp,
                            fontFamily = IbmPlexMono),
                    cursorBrush = SolidColor(StardomColors.TextPrimary),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("split_tunnel_search_input"),
                    decorationBox = { innerTextField ->
                      if (searchQuery.isEmpty()) {
                        Text(
                            text = StardomLocalization.splitTunnelSearchPlaceholder(language),
                            color = StardomColors.TextMuted,
                            fontSize = 10.sp,
                            fontFamily = IbmPlexMono,
                            letterSpacing = 1.sp)
                      }
                      innerTextField()
                    })
              }
            }

        val hasMdmPolicy =
            !mdmIncludedPackages.value.isNullOrBlank() ||
                !mdmExcludedPackages.value.isNullOrBlank()
        if (hasMdmPolicy) {
          Spacer(modifier = Modifier.height(6.dp))
          Box(
              modifier =
                  Modifier.fillMaxWidth()
                      .padding(horizontal = 20.dp)
                      .background(StardomColors.Panel)
                      .border(1.dp, StardomColors.BorderStrong)
                      .padding(10.dp)) {
                Text(
                    text = "// УПРАВЛЯЕТСЯ ОРГАНИЗАЦИЕЙ (MDM)",
                    color = StardomColors.TextSecondary,
                    fontFamily = IbmPlexMono,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp)
              }
        }

        // Telemetry Row
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
              Text(
                  text =
                      StardomLocalization.splitTunnelAppsCount(
                          language, filteredApps.size, selectedPackageNames.size),
                  color = StardomColors.TextMuted,
                  fontSize = 9.sp,
                  fontFamily = IbmPlexMono,
                  letterSpacing = 1.sp)
            }

        Spacer(modifier = Modifier.height(6.dp))

        // App List or Loading Spinner
        if (installedApps.isEmpty()) {
          Box(
              modifier = Modifier.fillMaxWidth().weight(1f),
              contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = StardomColors.TextPrimary,
                    strokeWidth = 2.dp)
              }
        } else {
          LazyColumn(
              modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filteredApps, key = { it.packageName }) { app ->
                  val isChecked = selectedPackageNames.contains(app.packageName)
                  val isBuiltIn =
                      !allowSelected && builtInDisallowedPackageNames.contains(app.packageName)
                  val isEnabled = !isBuiltIn

                  val iconBitmap =
                      remember(app.packageName, iconSizePx) {
                        iconCache.getOrPut(app.packageName) {
                          try {
                            packageManager
                                .getApplicationIcon(app.packageName)
                                .toBitmap(width = iconSizePx, height = iconSizePx)
                                .asImageBitmap()
                          } catch (_: Throwable) {
                            null
                          }
                        }
                      }

                  Box(
                      modifier =
                          Modifier.fillMaxWidth()
                              .testTag("split_tunnel_app_${app.packageName}")
                              .background(
                                  if (isChecked) StardomColors.PanelSelected
                                  else StardomColors.Panel)
                              .border(
                                  1.dp,
                                  if (isChecked) StardomColors.BorderStrong
                                  else StardomColors.BorderFaint)
                              .clickable(enabled = isEnabled) {
                                if (isChecked) {
                                  model.deselect(app.packageName)
                                } else {
                                  model.select(app.packageName)
                                }
                              }
                              .padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()) {
                              // App Icon Box
                              Box(
                                  modifier =
                                      Modifier.size(36.dp)
                                          .background(StardomColors.Background)
                                          .border(1.dp, StardomColors.BorderFaint),
                                  contentAlignment = Alignment.Center) {
                                    if (iconBitmap != null) {
                                      Image(
                                          bitmap = iconBitmap,
                                          contentDescription = null,
                                          modifier =
                                              Modifier.size(30.dp)
                                                  .clip(RoundedCornerShape(4.dp)))
                                    } else {
                                      Box(
                                          modifier =
                                              Modifier.size(24.dp)
                                                  .background(StardomColors.PanelSelected),
                                          contentAlignment = Alignment.Center) {
                                            Text(
                                                text = app.name.take(1).uppercase(),
                                                color = StardomColors.TextMuted,
                                                fontFamily = SpaceGrotesk,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold)
                                          }
                                    }
                                  }

                              Spacer(modifier = Modifier.width(12.dp))

                              // App Label & Package Name
                              Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.name,
                                    color =
                                        if (isEnabled) StardomColors.TextPrimary
                                        else StardomColors.TextMuted,
                                    fontFamily = SpaceGrotesk,
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = app.packageName,
                                    color = StardomColors.TextMuted,
                                    fontFamily = IbmPlexMono,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (isBuiltIn) {
                                  Spacer(modifier = Modifier.height(2.dp))
                                  Text(
                                      text = "// СИСТЕМНОЕ ИСКЛЮЧЕНИЕ",
                                      color = StardomColors.TextMuted,
                                      fontFamily = IbmPlexMono,
                                      fontSize = 8.sp,
                                      letterSpacing = 0.5.sp)
                                }
                              }

                              Spacer(modifier = Modifier.width(10.dp))

                              // Stardom Square Checkbox:
                              // Outer Box 16.dp, Inner Box 8.dp when checked
                              Box(
                                  modifier =
                                      Modifier.size(16.dp)
                                          .border(
                                              1.dp,
                                              if (isChecked) StardomColors.BorderStrong
                                              else StardomColors.BorderFaint),
                                  contentAlignment = Alignment.Center) {
                                    if (isChecked) {
                                      Box(
                                          modifier =
                                              Modifier.size(8.dp)
                                                  .background(
                                                      if (isEnabled) StardomColors.Selected
                                                      else StardomColors.TextMuted))
                                    }
                                  }
                            }
                      }
                }
              }
        }
      }
    }
  }
}
