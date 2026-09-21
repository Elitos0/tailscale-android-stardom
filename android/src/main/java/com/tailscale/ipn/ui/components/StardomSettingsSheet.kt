// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.product.update.UpdateState
import com.tailscale.ipn.product.ondemand.OnDemandAction
import com.tailscale.ipn.ui.util.AppVersion
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.DnsProvider
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.model.VpnProtocol
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.StardomTechnicalFont
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StardomSettingsSheet(
    selectedProtocol: VpnProtocol = VpnProtocol.WIREGUARD,
    onSelectProtocol: (VpnProtocol) -> Unit = {},
    selectedDns: DnsProvider = DnsProvider.STARDOM_ZERO_KNOWLEDGE,
    onSelectDns: (DnsProvider) -> Unit = {},
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onOpenSplitTunneling: (AppLanguage) -> Unit = {},
    onOpenOnDemand: (AppLanguage) -> Unit = {},
    updateState: UpdateState = UpdateState.Idle,
    onCheckForUpdate: () -> Unit = {},
    onOpenUpdateDialog: () -> Unit = {},
) {
  val selectedPackageNames =
      try {
        UninitializedApp.get().selectedPackageNames()
      } catch (_: Throwable) {
        emptyList()
      }
  val splitTunnelCount = selectedPackageNames.size
  val allowSelected =
      try {
        UninitializedApp.get().allowSelectedPackages()
      } catch (_: Throwable) {
        false
      }
  val onDemandRepo = remember { runCatching { UninitializedApp.get().onDemandRepository }.getOrNull() }
  val onDemandConfig by onDemandRepo?.config?.collectAsState() ?: remember { mutableStateOf(null) }
  val onDemandEnabled = onDemandConfig?.enabled == true
  val onDemandBadge =
      StardomLocalization.onDemandCardBadge(
          selectedLanguage,
          onDemandEnabled,
          onDemandConfig?.cellularAction == OnDemandAction.CONNECT,
          onDemandConfig?.wifiAction == OnDemandAction.DISCONNECT)
  val badgeText =
      StardomLocalization.splitTunnelBypassBadge(
          selectedLanguage, splitTunnelCount, allowSelected)
  var isAppListExpanded by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val density = LocalDensity.current
  val iconSizePx = remember(density) { with(density) { 24.dp.roundToPx() } }
  val routedApps =
      remember(selectedPackageNames, iconSizePx, context) {
        val pm = context.packageManager
        selectedPackageNames.map { pkg ->
          val label =
              try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(appInfo).toString()
              } catch (_: Throwable) {
                pkg
              }
          val iconBitmap =
              try {
                pm.getApplicationIcon(pkg)
                    .toBitmap(width = iconSizePx, height = iconSizePx)
                    .asImageBitmap()
              } catch (_: Throwable) {
                null
              }
          RoutedAppInfo(packageName = pkg, label = label, iconBitmap = iconBitmap)
        }.sortedBy { it.label.lowercase() }
      }
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = StardomColors.Background,
      scrimColor = StardomColors.Background.copy(alpha = 0.88f),
      dragHandle = {
        Box(
            modifier =
                Modifier.padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(1.dp)
                    .background(StardomColors.BorderStrong))
      }) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 6.dp)) {
              // Header
              Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                  modifier = Modifier.fillMaxWidth()) {
                    Column {
                      Text(
                          text = StardomLocalization.settingsTitle(selectedLanguage),
                          color = StardomColors.TextPrimary,
                          fontSize = 13.sp,
                          fontWeight = FontWeight.Medium,
                          fontFamily = SpaceGrotesk,
                          letterSpacing = 1.2.sp)
                      Spacer(Modifier.height(3.dp))
                      Text(
                          text = StardomLocalization.settingsSubtitle(selectedLanguage),
                          color = StardomColors.TextSecondary,
                          fontSize = 9.sp,
                          fontFamily = StardomTechnicalFont(selectedLanguage),
                          letterSpacing = 1.sp)
                    }

                    Box(
                        modifier =
                            Modifier.background(StardomColors.Panel)
                                .border(1.dp, StardomColors.Border)
                                .clickable(onClickLabel = "Done") { onDismiss() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                          Text(
                              text = StardomLocalization.doneBtn(selectedLanguage),
                              color = StardomColors.TextSecondary,
                              fontSize = 9.sp,
                              fontFamily = StardomTechnicalFont(selectedLanguage),
                              letterSpacing = 1.sp)
                        }
                  }

              Spacer(modifier = Modifier.height(18.dp))

              // Section: Language Switcher (Functional)
              SettingsSectionHeader(title = StardomLocalization.languageSection(selectedLanguage))
              Row(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.fillMaxWidth()) {
                    AppLanguage.entries.forEach { lang ->
                      val isSelected = lang == selectedLanguage
                      Box(
                          contentAlignment = Alignment.Center,
                          modifier =
                              Modifier.weight(1f)
                                  .testTag("language_option_${lang.code}")
                                  .background(
                                      if (isSelected) StardomColors.PanelSelected
                                      else StardomColors.Panel)
                                  .border(
                                      1.dp,
                                      if (isSelected) StardomColors.BorderStrong
                                      else StardomColors.BorderFaint)
                                  .clickable(onClickLabel = "Language ${lang.title}") {
                                    onSelectLanguage(lang)
                                  }
                                  .padding(vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              Box(
                                  modifier =
                                      Modifier.size(10.dp)
                                          .border(
                                              1.dp,
                                              if (isSelected) StardomColors.Selected
                                              else StardomColors.TextMuted),
                                  contentAlignment = Alignment.Center) {
                                    if (isSelected) {
                                      Box(
                                          modifier =
                                              Modifier.size(4.dp)
                                                  .background(StardomColors.Selected))
                                    }
                                  }
                              Spacer(modifier = Modifier.width(10.dp))
                              Text(
                                  text = "${lang.title} [${lang.code}]",
                                  color =
                                      if (isSelected) StardomColors.TextPrimary
                                      else StardomColors.TextSecondary,
                                  fontSize = 11.sp,
                                  fontWeight = FontWeight.Medium,
                                  fontFamily = SpaceGrotesk,
                                  letterSpacing = 1.sp)
                            }
                          }
                    }
                  }

              Spacer(modifier = Modifier.height(18.dp))

              // Section: App Split Tunneling
              SettingsSectionHeader(
                  title = StardomLocalization.splitTunnelingSection(selectedLanguage))
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .testTag("split_tunneling_card")
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .clickable(onClickLabel = "Open split tunneling") {
                            onOpenSplitTunneling(selectedLanguage)
                          }
                          .padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                          Row(
                              verticalAlignment = Alignment.CenterVertically,
                              modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier =
                                        Modifier.size(10.dp)
                                            .border(
                                                1.dp,
                                                if (splitTunnelCount > 0)
                                                    StardomColors.Selected
                                                else StardomColors.TextMuted),
                                    contentAlignment = Alignment.Center) {
                                      if (splitTunnelCount > 0) {
                                        Box(
                                            modifier =
                                                Modifier.size(4.dp)
                                                    .background(StardomColors.Selected))
                                      }
                                    }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                  Text(
                                      text =
                                          StardomLocalization.splitTunnelingTitle(
                                              selectedLanguage),
                                      color = StardomColors.TextPrimary,
                                      fontSize = 12.sp,
                                      fontWeight = FontWeight.Medium,
                                      fontFamily = SpaceGrotesk)
                                  Spacer(Modifier.height(2.dp))
                                  Text(
                                      text = badgeText,
                                      color = StardomColors.TextMuted,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(selectedLanguage))
                                }
                              }

                          Text(
                              text =
                                  StardomLocalization.splitTunnelConfigureBtn(
                                      selectedLanguage),
                              color = StardomColors.TextPrimary,
                              fontSize = 9.sp,
                              fontFamily = StardomTechnicalFont(selectedLanguage),
                              letterSpacing = 1.sp)
                        }
                  }
              Spacer(modifier = Modifier.height(6.dp))

              // Expandable toggle bar
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .testTag("split_tunnel_toggle_bar")
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.BorderFaint)
                          .clickable(onClickLabel = "Toggle routed apps list") {
                            isAppListExpanded = !isAppListExpanded
                          }
                          .padding(horizontal = 12.dp, vertical = 8.dp),
                  contentAlignment = Alignment.CenterStart) {
                Text(
                    text =
                        if (isAppListExpanded)
                          StardomLocalization.splitTunnelCollapseList(
                              selectedLanguage, splitTunnelCount)
                        else
                          StardomLocalization.splitTunnelExpandList(
                              selectedLanguage, splitTunnelCount),
                    color = StardomColors.TextSecondary,
                    fontSize = 9.sp,
                    fontFamily = IbmPlexMono,
                    letterSpacing = 1.sp)
              }

              if (isAppListExpanded) {
                Spacer(modifier = Modifier.height(6.dp))
                if (routedApps.isEmpty()) {
                  Box(
                      modifier =
                          Modifier.fillMaxWidth()
                              .testTag("split_tunnel_empty_state")
                              .background(StardomColors.Panel)
                              .border(1.dp, StardomColors.BorderFaint)
                              .clickable(onClickLabel = "Configure apps") {
                                onOpenSplitTunneling(selectedLanguage)
                              }
                              .padding(16.dp),
                      contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                      Text(
                          text = StardomLocalization.splitTunnelEmptyList(selectedLanguage),
                          color = StardomColors.TextMuted,
                          fontSize = 10.sp,
                          fontFamily = SpaceGrotesk,
                          fontWeight = FontWeight.Medium,
                          letterSpacing = 1.sp)
                      Spacer(modifier = Modifier.height(4.dp))
                      Text(
                          text =
                              StardomLocalization.splitTunnelEmptyListHint(
                                  selectedLanguage),
                          color = StardomColors.TextSecondary,
                          fontSize = 9.sp,
                          fontFamily = StardomTechnicalFont(selectedLanguage),
                          letterSpacing = 0.5.sp)
                    }
                  }
                } else {
                  Column(
                      modifier =
                          Modifier.fillMaxWidth()
                              .testTag("split_tunnel_routed_apps_list")
                              .heightIn(max = 240.dp)
                              .verticalScroll(rememberScrollState()),
                      verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    routedApps.forEach { app ->
                      Box(
                          modifier =
                              Modifier.fillMaxWidth()
                                  .testTag("split_tunnel_routed_app_${app.packageName}")
                                  .background(StardomColors.Panel)
                                  .border(1.dp, StardomColors.BorderFaint)
                                  .padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()) {
                          Row(
                              verticalAlignment = Alignment.CenterVertically,
                              modifier = Modifier.weight(1f)) {
                                if (app.iconBitmap != null) {
                                  Image(
                                      bitmap = app.iconBitmap,
                                      contentDescription = null,
                                      modifier =
                                          Modifier.size(24.dp)
                                              .clip(RoundedCornerShape(4.dp)))
                                } else {
                                  Box(
                                      modifier =
                                          Modifier.size(24.dp)
                                              .background(StardomColors.PanelSelected),
                                      contentAlignment = Alignment.Center) {
                                    Text(
                                        text = app.label.take(1).uppercase(),
                                        color = StardomColors.TextMuted,
                                        fontFamily = SpaceGrotesk,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold)
                                  }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                  Text(
                                      text = app.label,
                                      color = StardomColors.TextPrimary,
                                      fontFamily = SpaceGrotesk,
                                      fontWeight = FontWeight.Medium,
                                      fontSize = 11.sp,
                                      maxLines = 1,
                                      overflow = TextOverflow.Ellipsis)
                                  Spacer(modifier = Modifier.height(1.dp))
                                  Text(
                                      text = app.packageName,
                                      color = StardomColors.TextMuted,
                                      fontFamily = IbmPlexMono,
                                      fontSize = 8.sp,
                                      maxLines = 1,
                                      overflow = TextOverflow.Ellipsis)
                                }
                              }

                          Spacer(modifier = Modifier.width(8.dp))

                          Text(
                              text =
                                  if (allowSelected) {
                                    "[${StardomLocalization.splitTunnelStatusTunneled(selectedLanguage)}]"
                                  } else {
                                    "[${StardomLocalization.splitTunnelStatusBypassed(selectedLanguage)}]"
                                  },
                              color =
                                  if (allowSelected) StardomColors.Selected
                                  else StardomColors.TextSecondary,
                              fontFamily = IbmPlexMono,
                              fontSize = 8.sp,
                              letterSpacing = 0.5.sp)
                        }
                      }
                    }
                  }
                }
              }
              Spacer(modifier = Modifier.height(18.dp))
              // Section: On Demand Automation
              SettingsSectionHeader(title = StardomLocalization.onDemandSection(selectedLanguage))
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .testTag("on_demand_card")
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .clickable(onClickLabel = "Open on demand settings") {
                            onOpenOnDemand(selectedLanguage)
                          }
                          .padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                          Row(
                              verticalAlignment = Alignment.CenterVertically,
                              modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier =
                                        Modifier.size(10.dp)
                                            .border(
                                                1.dp,
                                                if (onDemandEnabled)
                                                    StardomColors.Selected
                                                else StardomColors.TextMuted),
                                    contentAlignment = Alignment.Center) {
                                      if (onDemandEnabled) {
                                        Box(
                                            modifier =
                                                Modifier.size(4.dp)
                                                    .background(StardomColors.Selected))
                                      }
                                    }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                  Text(
                                      text = StardomLocalization.onDemandTitle(selectedLanguage),
                                      color = StardomColors.TextPrimary,
                                      fontSize = 12.sp,
                                      fontWeight = FontWeight.Medium,
                                      fontFamily = SpaceGrotesk)
                                  Spacer(Modifier.height(2.dp))
                                  Text(
                                      text = onDemandBadge,
                                      color =
                                          if (onDemandEnabled) StardomColors.Selected
                                          else StardomColors.TextMuted,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(selectedLanguage))
                                }
                              }

                          Text(
                              text = StardomLocalization.splitTunnelConfigureBtn(selectedLanguage),
                              color = StardomColors.TextPrimary,
                              fontSize = 9.sp,
                              fontFamily = StardomTechnicalFont(selectedLanguage),
                              letterSpacing = 1.sp)
                        }
                  }
              Spacer(modifier = Modifier.height(18.dp))



              // Section: Client Update
              SettingsSectionHeader(title = StardomLocalization.clientUpdateSection(selectedLanguage))
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .testTag("client_update_card")
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()) {
                          Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              Text(
                                  text = StardomLocalization.clientUpdateSection(selectedLanguage),
                                  color = StardomColors.TextPrimary,
                                  fontSize = 12.sp,
                                  fontWeight = FontWeight.Medium,
                                  fontFamily = SpaceGrotesk)

                              Spacer(Modifier.width(8.dp))

                              Text(
                                  text = "v${AppVersion.Short()}",
                                  color = StardomColors.TextSecondary,
                                  fontSize = 9.sp,
                                  fontFamily = StardomTechnicalFont(selectedLanguage))
                            }

                            Spacer(Modifier.height(3.dp))

                            val statusText =
                                when (updateState) {
                                  is UpdateState.Checking ->
                                      StardomLocalization.clientUpdateChecking(selectedLanguage)
                                  is UpdateState.UpdateAvailable ->
                                      "${StardomLocalization.clientUpdateAvailable(selectedLanguage)}: v${updateState.manifest.versionName}"
                                  is UpdateState.Downloaded ->
                                      "${StardomLocalization.clientUpdateAvailable(selectedLanguage)}: v${updateState.manifest.versionName}"
                                  is UpdateState.Downloading ->
                                      StardomLocalization.updateProgressText(
                                          selectedLanguage,
                                          "%.1f".format(updateState.bytesDownloaded.toDouble() / (1024 * 1024)),
                                          "%.1f".format(updateState.totalBytes.toDouble() / (1024 * 1024)),
                                          (updateState.progress * 100).toInt())
                                  is UpdateState.UpToDate ->
                                      StardomLocalization.clientUpdateUpToDate(selectedLanguage)
                                  is UpdateState.Error ->
                                      StardomLocalization.clientUpdateError(selectedLanguage)
                                  else ->
                                      StardomLocalization.clientUpdateUpToDate(selectedLanguage)
                                }

                            Text(
                                text = statusText,
                                color =
                                    when (updateState) {
                                      is UpdateState.UpdateAvailable, is UpdateState.Downloaded ->
                                          StardomColors.TextPrimary
                                      is UpdateState.Error -> StardomColors.Error
                                      else -> StardomColors.TextMuted
                                    },
                                fontSize = 9.sp,
                                 fontFamily = StardomTechnicalFont(selectedLanguage))
                           }

                          Spacer(Modifier.width(10.dp))

                          if (updateState is UpdateState.UpdateAvailable || updateState is UpdateState.Downloaded) {
                            Box(
                                modifier =
                                    Modifier.testTag("client_update_view_btn")
                                        .background(StardomColors.Selected)
                                        .border(1.dp, StardomColors.BorderStrong)
                                        .clickable(onClickLabel = "View Update") {
                                          onOpenUpdateDialog()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                                  Text(
                                      text = StardomLocalization.updateNowBtn(selectedLanguage),
                                      color = StardomColors.Background,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(selectedLanguage),
                                      fontWeight = FontWeight.Bold,
                                      letterSpacing = 0.5.sp)
                                }
                          } else {
                            Box(
                                modifier =
                                    Modifier.testTag("client_update_check_btn")
                                        .background(StardomColors.PanelSelected)
                                        .border(1.dp, StardomColors.Border)
                                        .clickable(
                                            enabled = updateState !is UpdateState.Checking,
                                            onClickLabel = "Check for Update") {
                                          onCheckForUpdate()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                                  Text(
                                      text =
                                          if (updateState is UpdateState.Checking)
                                              StardomLocalization.clientUpdateChecking(selectedLanguage)
                                          else
                                              StardomLocalization.clientUpdateCheckBtn(selectedLanguage),
                                      color =
                                          if (updateState is UpdateState.Checking)
                                              StardomColors.TextMuted
                                          else
                                              StardomColors.TextPrimary,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(selectedLanguage),
                                      letterSpacing = 0.5.sp)
                                }
                          }
                        }
                  }
              Spacer(modifier = Modifier.height(20.dp))
            }
      }
}

@Composable
private fun SettingsSectionHeader(title: String) {
  Text(
      text = title,
      color = StardomColors.TextSecondary,
      fontFamily = IbmPlexMono,
      fontSize = 9.sp,
      letterSpacing = 2.sp,
      modifier = Modifier.padding(bottom = 8.dp))
}

private data class RoutedAppInfo(
    val packageName: String,
    val label: String,
    val iconBitmap: ImageBitmap?,
)
