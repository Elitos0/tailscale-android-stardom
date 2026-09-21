// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.tailscale.ipn.NetworkChangeCallback
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.product.ondemand.OnDemandAction
import com.tailscale.ipn.product.ondemand.OnDemandRepository
import com.tailscale.ipn.product.ondemand.WifiRuleScope
import com.tailscale.ipn.ui.components.StardomBackground
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors
import com.tailscale.ipn.ui.theme.StardomDimensions
import com.tailscale.ipn.ui.theme.StardomTechnicalFont

@Composable
fun StardomOnDemandView(
    onBack: () -> Unit,
    language: AppLanguage = AppLanguage.RU,
    repository: OnDemandRepository? = null,
) {
  val repo = repository ?: remember { UninitializedApp.get().onDemandRepository }
  val context = LocalContext.current
  val config by repo.config.collectAsState()
  val activeNetwork by NetworkChangeCallback.activeNetworkSnapshot.collectAsState()

  var hasLocationPermission by remember {
    mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    )
  }

  val permissionLauncher =
      rememberLauncherForActivityResult(
          contract = ActivityResultContracts.RequestPermission()
      ) { isGranted ->
        hasLocationPermission = isGranted
      }

  val currentSsid =
      activeNetwork.ssid ?: run {
        try {
          val wifiManager =
              context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
          val info = wifiManager?.connectionInfo
          val raw = info?.ssid
          if (raw != null && raw != "<unknown ssid>" && raw != "0x") {
            raw.removePrefix("\"").removeSuffix("\"")
          } else null
        } catch (_: Throwable) {
          null
        }
      }

  Scaffold(
      containerColor = StardomColors.Background,
      topBar = {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .height(StardomDimensions.TopBarHeight)
                    .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
              Box(
                  modifier =
                      Modifier.testTag("on_demand_back_btn")
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .clickable(onClickLabel = "Back") { onBack() }
                          .padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(
                        text = if (language == AppLanguage.RU) "← НАЗАД" else "← BACK",
                        color = StardomColors.TextSecondary,
                        fontSize = 9.sp,
                        fontFamily = StardomTechnicalFont(language),
                        letterSpacing = 1.sp)
                  }

              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = StardomLocalization.onDemandSection(language),
                    color = StardomColors.TextPrimary,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "АВТОМАТИЗАЦИЯ СЕТИ // ON DEMAND",
                    color = StardomColors.TextSecondary,
                    fontSize = 8.sp,
                    fontFamily = StardomTechnicalFont(language),
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

      Column(
          modifier =
              Modifier.fillMaxSize()
                  .verticalScroll(rememberScrollState())
                  .padding(horizontal = 20.dp, vertical = 12.dp)) {

            // Master Enable Card
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .testTag("on_demand_master_toggle")
                        .background(
                            if (config.enabled) StardomColors.PanelSelected
                            else StardomColors.Panel)
                        .border(
                            1.dp,
                            if (config.enabled) StardomColors.BorderStrong
                            else StardomColors.Border)
                        .clickable(onClickLabel = "Toggle On Demand") {
                          repo.updateEnabled(!config.enabled)
                        }
                        .padding(14.dp)) {
                  Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween,
                      modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                          Text(
                              text = StardomLocalization.onDemandEnableToggle(language),
                              color = StardomColors.TextPrimary,
                              fontSize = 13.sp,
                              fontFamily = SpaceGrotesk,
                              fontWeight = FontWeight.Bold,
                              letterSpacing = 1.sp)
                          Spacer(Modifier.height(4.dp))
                          Text(
                              text = StardomLocalization.onDemandSubtitle(language),
                              color = StardomColors.TextMuted,
                              fontSize = 9.sp,
                              fontFamily = StardomTechnicalFont(language))
                        }
                        Box(
                            modifier =
                                Modifier.size(18.dp)
                                    .border(
                                        1.dp,
                                        if (config.enabled) StardomColors.Selected
                                        else StardomColors.TextMuted),
                            contentAlignment = Alignment.Center) {
                              if (config.enabled) {
                                Box(
                                    modifier =
                                        Modifier.size(10.dp)
                                            .background(StardomColors.Selected))
                              }
                            }
                      }
                }

            Spacer(Modifier.height(20.dp))

            // Section Header: Rule 1 (Cellular)
            SettingsSectionHeader(title = StardomLocalization.onDemandCellularRuleTitle(language))
            Text(
                text = StardomLocalization.onDemandCellularRuleDesc(language),
                color = StardomColors.TextMuted,
                fontSize = 9.sp,
                fontFamily = StardomTechnicalFont(language),
                modifier = Modifier.padding(bottom = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  listOf(
                          OnDemandAction.CONNECT,
                          OnDemandAction.DISCONNECT,
                          OnDemandAction.DO_NOTHING)
                      .forEach { action ->
                        val isSelected = config.cellularAction == action
                        val actionLabel =
                            when (action) {
                              OnDemandAction.CONNECT ->
                                  StardomLocalization.onDemandActionConnect(language)
                              OnDemandAction.DISCONNECT ->
                                  StardomLocalization.onDemandActionDisconnect(language)
                              OnDemandAction.DO_NOTHING ->
                                  StardomLocalization.onDemandActionNothing(language)
                            }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier.weight(1f)
                                    .testTag("cellular_action_${action.name.lowercase()}")
                                    .background(
                                        if (isSelected) StardomColors.PanelSelected
                                        else StardomColors.Panel)
                                    .border(
                                        1.dp,
                                        if (isSelected) StardomColors.BorderStrong
                                        else StardomColors.BorderFaint)
                                    .clickable(enabled = config.enabled) {
                                      repo.setCellularAction(action)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)) {
                              Row(verticalAlignment = Alignment.CenterVertically) {
                                TechnicalRadioIndicator(selected = isSelected)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = actionLabel,
                                    color =
                                        if (!config.enabled) StardomColors.TextMuted
                                        else if (isSelected) StardomColors.TextPrimary
                                        else StardomColors.TextSecondary,
                                    fontSize = 8.sp,
                                    fontFamily = StardomTechnicalFont(language),
                                    letterSpacing = 0.5.sp)
                              }
                            }
                      }
                }

            Spacer(Modifier.height(20.dp))

            // Section Header: Rule 2 (Wi-Fi)
            SettingsSectionHeader(title = StardomLocalization.onDemandWifiRuleTitle(language))
            Text(
                text = StardomLocalization.onDemandWifiRuleDesc(language),
                color = StardomColors.TextMuted,
                fontSize = 9.sp,
                fontFamily = StardomTechnicalFont(language),
                modifier = Modifier.padding(bottom = 8.dp))

            // Wi-Fi Action Selector (Connect / Disconnect / Do Nothing)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  listOf(
                          OnDemandAction.DISCONNECT,
                          OnDemandAction.CONNECT,
                          OnDemandAction.DO_NOTHING)
                      .forEach { action ->
                        val isSelected = config.wifiAction == action
                        val actionLabel =
                            when (action) {
                              OnDemandAction.CONNECT ->
                                  StardomLocalization.onDemandActionConnect(language)
                              OnDemandAction.DISCONNECT ->
                                  StardomLocalization.onDemandActionDisconnect(language)
                              OnDemandAction.DO_NOTHING ->
                                  StardomLocalization.onDemandActionNothing(language)
                            }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier.weight(1f)
                                    .testTag("wifi_action_${action.name.lowercase()}")
                                    .background(
                                        if (isSelected) StardomColors.PanelSelected
                                        else StardomColors.Panel)
                                    .border(
                                        1.dp,
                                        if (isSelected) StardomColors.BorderStrong
                                        else StardomColors.BorderFaint)
                                    .clickable(enabled = config.enabled) {
                                      repo.setWifiAction(action)
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)) {
                              Row(verticalAlignment = Alignment.CenterVertically) {
                                TechnicalRadioIndicator(selected = isSelected)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = actionLabel,
                                    color =
                                        if (!config.enabled) StardomColors.TextMuted
                                        else if (isSelected) StardomColors.TextPrimary
                                        else StardomColors.TextSecondary,
                                    fontSize = 8.sp,
                                    fontFamily = StardomTechnicalFont(language),
                                    letterSpacing = 0.5.sp)
                              }
                            }
                      }
                }

            Spacer(Modifier.height(12.dp))

            // Wi-Fi Scope Selector (All vs Only Selected)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  listOf(WifiRuleScope.ALL, WifiRuleScope.ONLY_SELECTED).forEach { scope ->
                    val isSelected = config.wifiScope == scope
                    val scopeLabel =
                        when (scope) {
                          WifiRuleScope.ALL -> StardomLocalization.onDemandWifiScopeAll(language)
                          WifiRuleScope.ONLY_SELECTED ->
                              StardomLocalization.onDemandWifiScopeSelected(language)
                        }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier.weight(1f)
                                .testTag("wifi_scope_${scope.name.lowercase()}")
                                .background(
                                    if (isSelected) StardomColors.PanelSelected
                                    else StardomColors.Panel)
                                .border(
                                    1.dp,
                                    if (isSelected) StardomColors.BorderStrong
                                    else StardomColors.BorderFaint)
                                .clickable(enabled = config.enabled) {
                                  repo.setWifiScope(scope)
                                }
                                .padding(vertical = 10.dp, horizontal = 6.dp)) {
                          Row(verticalAlignment = Alignment.CenterVertically) {
                            TechnicalRadioIndicator(selected = isSelected)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = scopeLabel,
                                color =
                                    if (!config.enabled) StardomColors.TextMuted
                                    else if (isSelected) StardomColors.TextPrimary
                                    else StardomColors.TextSecondary,
                                fontSize = 8.sp,
                                fontFamily = StardomTechnicalFont(language),
                                letterSpacing = 0.5.sp)
                          }
                        }
                  }
                }

            // Scope = ONLY_SELECTED details
            if (config.wifiScope == WifiRuleScope.ONLY_SELECTED) {
              Spacer(Modifier.height(14.dp))

              // Permission notice if fine location is missing
              if (!hasLocationPermission) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .testTag("location_permission_notice")
                            .background(StardomColors.Panel)
                            .border(1.dp, StardomColors.BorderStrong)
                            .padding(12.dp)) {
                      Column {
                        Text(
                            text = StardomLocalization.onDemandLocationPermissionNotice(language),
                            color = StardomColors.TextSecondary,
                            fontSize = 9.sp,
                            fontFamily = StardomTechnicalFont(language),
                            lineHeight = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier =
                                Modifier.testTag("grant_location_permission_btn")
                                    .background(StardomColors.Selected)
                                    .border(1.dp, StardomColors.BorderStrong)
                                    .clickable {
                                      permissionLauncher.launch(
                                          Manifest.permission.ACCESS_FINE_LOCATION)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)) {
                              Text(
                                  text =
                                      StardomLocalization.onDemandLocationPermissionGrant(language),
                                  color = StardomColors.Background,
                                  fontSize = 9.sp,
                                  fontFamily = StardomTechnicalFont(language),
                                  fontWeight = FontWeight.Bold,
                                  letterSpacing = 0.5.sp)
                            }
                      }
                    }
                Spacer(Modifier.height(10.dp))
              }

              // Add current SSID button
              val canAddCurrent =
                  !currentSsid.isNullOrBlank() && !config.selectedSsids.contains(currentSsid)
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .testTag("add_current_ssid_btn")
                          .background(
                              if (canAddCurrent && config.enabled) StardomColors.PanelSelected
                              else StardomColors.Panel)
                          .border(
                              1.dp,
                              if (canAddCurrent && config.enabled) StardomColors.BorderStrong
                              else StardomColors.Border)
                          .clickable(enabled = canAddCurrent && config.enabled) {
                            currentSsid?.let { repo.addSsid(it) }
                          }
                          .padding(12.dp)) {
                    Text(
                        text =
                            if (currentSsid.isNullOrBlank())
                                StardomLocalization.onDemandNoCurrentSsid(language)
                            else StardomLocalization.onDemandAddCurrentSsid(language, currentSsid),
                        color =
                            if (canAddCurrent && config.enabled) StardomColors.TextPrimary
                            else StardomColors.TextMuted,
                        fontSize = 9.sp,
                        fontFamily = StardomTechnicalFont(language),
                        letterSpacing = 0.5.sp)
                  }

              Spacer(Modifier.height(14.dp))

              // Selected SSIDs list
              Text(
                  text =
                      StardomLocalization.onDemandSelectedSsidsHeader(
                          language, config.selectedSsids.size),
                  color = StardomColors.TextSecondary,
                  fontFamily = IbmPlexMono,
                  fontSize = 9.sp,
                  letterSpacing = 2.sp,
                  modifier = Modifier.padding(bottom = 8.dp))

              if (config.selectedSsids.isEmpty()) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(StardomColors.Panel)
                            .border(1.dp, StardomColors.BorderFaint)
                            .padding(12.dp)) {
                      Text(
                          text = StardomLocalization.onDemandNoSelectedSsids(language),
                          color = StardomColors.TextMuted,
                          fontSize = 9.sp,
                          fontFamily = StardomTechnicalFont(language))
                    }
              } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                  config.selectedSsids.forEach { ssid ->
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag("selected_ssid_$ssid")
                                .background(StardomColors.Panel)
                                .border(1.dp, StardomColors.Border)
                                .padding(horizontal = 12.dp, vertical = 8.dp)) {
                          Row(
                              verticalAlignment = Alignment.CenterVertically,
                              horizontalArrangement = Arrangement.SpaceBetween,
                              modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = ssid,
                                    color = StardomColors.TextPrimary,
                                    fontSize = 11.sp,
                                    fontFamily = SpaceGrotesk,
                                    fontWeight = FontWeight.Medium)

                                Box(
                                    modifier =
                                        Modifier.clickable(enabled = config.enabled) {
                                          repo.removeSsid(ssid)
                                        }
                                        .padding(4.dp)) {
                                      Text(
                                          text = "✕",
                                          color = StardomColors.Error,
                                          fontSize = 11.sp,
                                          fontFamily = IbmPlexMono,
                                          fontWeight = FontWeight.Bold)
                                    }
                              }
                        }
                  }
                }
              }

              Spacer(Modifier.height(20.dp))

              // Section Header: Rule 3 (Unlisted Wi-Fi)
              SettingsSectionHeader(
                  title = StardomLocalization.onDemandUnlistedWifiTitle(language))
              Text(
                  text = StardomLocalization.onDemandUnlistedWifiDesc(language),
                  color = StardomColors.TextMuted,
                  fontSize = 9.sp,
                  fontFamily = StardomTechnicalFont(language),
                  modifier = Modifier.padding(bottom = 8.dp))

              Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                            OnDemandAction.CONNECT,
                            OnDemandAction.DISCONNECT,
                            OnDemandAction.DO_NOTHING)
                        .forEach { action ->
                          val isSelected = config.unlistedWifiAction == action
                          val actionLabel =
                              when (action) {
                                OnDemandAction.CONNECT ->
                                    StardomLocalization.onDemandActionConnect(language)
                                OnDemandAction.DISCONNECT ->
                                    StardomLocalization.onDemandActionDisconnect(language)
                                OnDemandAction.DO_NOTHING ->
                                    StardomLocalization.onDemandActionNothing(language)
                              }
                          Box(
                              contentAlignment = Alignment.Center,
                              modifier =
                                  Modifier.weight(1f)
                                      .testTag("unlisted_wifi_action_${action.name.lowercase()}")
                                      .background(
                                          if (isSelected) StardomColors.PanelSelected
                                          else StardomColors.Panel)
                                      .border(
                                          1.dp,
                                          if (isSelected) StardomColors.BorderStrong
                                          else StardomColors.BorderFaint)
                                      .clickable(enabled = config.enabled) {
                                        repo.setUnlistedWifiAction(action)
                                      }
                                      .padding(vertical = 10.dp, horizontal = 4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                  TechnicalRadioIndicator(selected = isSelected)
                                  Spacer(Modifier.width(6.dp))
                                  Text(
                                      text = actionLabel,
                                      color =
                                          if (!config.enabled) StardomColors.TextMuted
                                          else if (isSelected) StardomColors.TextPrimary
                                          else StardomColors.TextSecondary,
                                      fontSize = 8.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      letterSpacing = 0.5.sp)
                                }
                              }
                        }
                  }
            }

            Spacer(Modifier.height(24.dp))
          }
    }
  }
}

@Composable
private fun TechnicalRadioIndicator(selected: Boolean) {
  Box(
      modifier =
          Modifier.size(10.dp)
              .border(
                  1.dp,
                  if (selected) StardomColors.Selected
                  else StardomColors.TextMuted),
      contentAlignment = Alignment.Center) {
        if (selected) {
          Box(
              modifier =
                  Modifier.size(4.dp)
                      .background(StardomColors.Selected))
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
