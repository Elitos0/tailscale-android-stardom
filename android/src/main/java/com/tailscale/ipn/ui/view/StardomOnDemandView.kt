// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.Manifest
import com.tailscale.ipn.App
import android.net.Uri
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.location.LocationManagerCompat
import com.tailscale.ipn.sanitizeSsid
import com.tailscale.ipn.util.TSLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StardomOnDemandView(
    onBack: () -> Unit,
    language: AppLanguage = AppLanguage.RU,
    repository: OnDemandRepository? = null,
) {
  val repo = repository ?: remember { UninitializedApp.get().onDemandRepository }
  val context = LocalContext.current
  val config by repo.config.collectAsState()
  val knownSsids by repo.knownSsids.collectAsState()
  val activeNetwork by NetworkChangeCallback.activeNetworkSnapshot.collectAsState()
  val coroutineScope = rememberCoroutineScope()
  var isScanning by remember { mutableStateOf(false) }
  var scanStatusMessage by remember { mutableStateOf<String?>(null) }
  var nearbySsids by remember { mutableStateOf<List<String>>(emptyList()) }

  val wifiManager = remember {
    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
  }

  val locationManager = remember {
    context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
  }
  fun isLocationEnabled(): Boolean =
      locationManager != null && LocationManagerCompat.isLocationEnabled(locationManager)
  var isLocationServicesEnabled by remember { mutableStateOf(isLocationEnabled()) }

  var hasLocationPermission by remember {
    mutableStateOf(
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    )
  }
  var hasBackgroundLocation by remember {
    mutableStateOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
          true
        }
    )
  }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        hasLocationPermission =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        isLocationServicesEnabled = isLocationEnabled()
        hasBackgroundLocation =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
              true
            }
        NetworkChangeCallback.refreshActiveNetwork()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val permissionLauncher =
      rememberLauncherForActivityResult(
          ActivityResultContracts.RequestMultiplePermissions()
      ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        isLocationServicesEnabled = isLocationEnabled()
        hasBackgroundLocation =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
              ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
              true
            }
        NetworkChangeCallback.refreshActiveNetwork()
      }
  val bgLocationLauncher =
      rememberLauncherForActivityResult(
          ActivityResultContracts.RequestPermission()
      ) { granted ->
        hasBackgroundLocation = granted
        NetworkChangeCallback.refreshActiveNetwork()
      }

  val activeScanCleanup = remember { mutableStateOf<(() -> Unit)?>(null) }
  DisposableEffect(Unit) {
    onDispose {
      activeScanCleanup.value?.invoke()
      activeScanCleanup.value = null
    }
  }

  val currentSsid =
      activeNetwork.ssid ?: run {
        try {
          val info = wifiManager?.connectionInfo
          val raw = info?.ssid
          if (raw != null && raw != "<unknown ssid>" && raw != "0x") {
            raw.removePrefix("\"").removeSuffix("\"")
          } else null
        } catch (_: Throwable) {
          null
        }
      }

  var isDropdownExpanded by remember { mutableStateOf(false) }
  var manualSsidText by remember { mutableStateOf("") }

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
                          val newEnabled = !config.enabled
                          repo.updateEnabled(newEnabled)
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
                        Spacer(Modifier.width(12.dp))
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

            Spacer(Modifier.height(14.dp))

            // Section 01: Cellular Network Panel
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(StardomColors.Panel)
                        .border(1.dp, StardomColors.Border)
                        .padding(14.dp)) {
                  Column {
                    Text(
                        text =
                            if (language == AppLanguage.RU) "01 // МОБИЛЬНАЯ СЕТЬ"
                            else "01 // CELLULAR NETWORK",
                        color = StardomColors.TextSecondary,
                        fontFamily = IbmPlexMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = StardomLocalization.onDemandCellularRuleDesc(language),
                        color = StardomColors.TextMuted,
                        fontSize = 9.sp,
                        fontFamily = StardomTechnicalFont(language))

                    Spacer(Modifier.height(12.dp))

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
                                                else StardomColors.Background)
                                            .border(
                                                1.dp,
                                                if (isSelected) StardomColors.BorderStrong
                                                else StardomColors.BorderFaint)
                                            .clickable(enabled = config.enabled) {
                                              repo.setCellularAction(action)
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp)) {
                                      Row(
                                          verticalAlignment = Alignment.CenterVertically,
                                          horizontalArrangement = Arrangement.Center) {
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
                                            letterSpacing = 0.5.sp,
                                            maxLines = 1)
                                      }
                                    }
                              }
                        }
                  }
                }

            Spacer(Modifier.height(14.dp))

            // Section 02: Wi-Fi Networks Panel
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(StardomColors.Panel)
                        .border(1.dp, StardomColors.Border)
                        .padding(14.dp)) {
                  Column {
                    Text(
                        text =
                            if (language == AppLanguage.RU) "02 // БЕСПРОВОДНАЯ СЕТЬ WI-FI"
                            else "02 // WI-FI NETWORKS",
                        color = StardomColors.TextSecondary,
                        fontFamily = IbmPlexMono,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.5.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = StardomLocalization.onDemandWifiRuleDesc(language),
                        color = StardomColors.TextMuted,
                        fontSize = 9.sp,
                        fontFamily = StardomTechnicalFont(language))

                    Spacer(Modifier.height(12.dp))

                    // Wi-Fi Action Selector (Disconnect / Connect / Do Nothing)
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
                                                else StardomColors.Background)
                                            .border(
                                                1.dp,
                                                if (isSelected) StardomColors.BorderStrong
                                                else StardomColors.BorderFaint)
                                            .clickable(enabled = config.enabled) {
                                              repo.setWifiAction(action)
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp)) {
                                      Row(
                                          verticalAlignment = Alignment.CenterVertically,
                                          horizontalArrangement = Arrangement.Center) {
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
                                            letterSpacing = 0.5.sp,
                                            maxLines = 1)
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
                                  WifiRuleScope.ALL ->
                                      StardomLocalization.onDemandWifiScopeAll(language)
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
                                            else StardomColors.Background)
                                        .border(
                                            1.dp,
                                            if (isSelected) StardomColors.BorderStrong
                                            else StardomColors.BorderFaint)
                                        .clickable(enabled = config.enabled) {
                                          repo.setWifiScope(scope)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 6.dp)) {
                                  Row(
                                      verticalAlignment = Alignment.CenterVertically,
                                      horizontalArrangement = Arrangement.Center) {
                                    TechnicalRadioIndicator(selected = isSelected)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = scopeLabel,
                                        color =
                                            if (!config.enabled) StardomColors.TextMuted
                                            else if (isSelected) StardomColors.TextPrimary
                                            else StardomColors.TextSecondary,
                                        fontSize = 8.sp,
                                        fontFamily = StardomTechnicalFont(language),
                                        letterSpacing = 0.5.sp,
                                        maxLines = 1)
                                  }
                                }
                          }
                        }

                    // Scope = ONLY_SELECTED details (Location Notice + Dropdown)
                    if (config.wifiScope == WifiRuleScope.ONLY_SELECTED) {
                      // Location Notice Card (appears if permission missing OR location services disabled)
                      if (!hasLocationPermission || !isLocationServicesEnabled) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .testTag("location_permission_notice")
                                    .background(StardomColors.Panel)
                                    .border(1.dp, StardomColors.BorderStrong)
                                    .padding(14.dp)) {
                              Column(
                                  modifier = Modifier.fillMaxWidth(),
                                  horizontalAlignment = Alignment.CenterHorizontally) {
                                if (!hasLocationPermission) {
                                  Text(
                                      text =
                                          "${StardomLocalization.onDemandLocationAccessDenied(language)}\n${StardomLocalization.onDemandLocationPermissionNotice(language)}",
                                      color = StardomColors.TextSecondary,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      lineHeight = 14.sp,
                                      textAlign = TextAlign.Center,
                                      modifier = Modifier.fillMaxWidth())
                                  Spacer(Modifier.height(10.dp))
                                  Box(
                                      contentAlignment = Alignment.Center,
                                      modifier =
                                          Modifier.testTag("grant_location_permission_btn")
                                              .background(StardomColors.Background)
                                              .border(1.dp, StardomColors.BorderStrong)
                                              .clickable {
                                                permissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                        Manifest.permission.ACCESS_COARSE_LOCATION))
                                              }
                                              .padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        Text(
                                            text =
                                                StardomLocalization.onDemandLocationPermissionGrant(
                                                    language),
                                            color = StardomColors.TextPrimary,
                                            fontSize = 9.sp,
                                            fontFamily = StardomTechnicalFont(language),
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp)
                                      }
                                } else {
                                  Text(
                                      text =
                                          "${StardomLocalization.onDemandLocationAccessGranted(language)}\n${StardomLocalization.onDemandLocationServicesDisabled(language)}",
                                      color = StardomColors.TextSecondary,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      lineHeight = 14.sp,
                                      textAlign = TextAlign.Center,
                                      modifier = Modifier.fillMaxWidth())
                                  Spacer(Modifier.height(10.dp))
                                  Box(
                                      contentAlignment = Alignment.Center,
                                      modifier =
                                          Modifier.testTag("enable_location_services_btn")
                                              .background(StardomColors.Background)
                                              .border(1.dp, StardomColors.BorderStrong)
                                              .clickable {
                                                try {
                                                  context.startActivity(
                                                      Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                                                } catch (e: Exception) {
                                                  TSLog.w("OnDemandWifi", "Failed to launch location settings: $e")
                                                }
                                              }
                                              .padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        Text(
                                            text =
                                                StardomLocalization.onDemandEnableLocationBtn(
                                                    language),
                                            color = StardomColors.TextPrimary,
                                            fontSize = 9.sp,
                                            fontFamily = StardomTechnicalFont(language),
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp)
                                      }
                                }
                              }
                            }
                      }

                      // Background Location Notice Card (appears if location permission granted but background location missing)
                      if (hasLocationPermission && !hasBackgroundLocation) {
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .testTag("bg_location_permission_notice")
                                    .background(StardomColors.Panel)
                                    .border(1.dp, StardomColors.BorderStrong)
                                    .padding(14.dp)) {
                              Column(
                                  modifier = Modifier.fillMaxWidth(),
                                  horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = StardomLocalization.onDemandBgLocationNotice(language),
                                    color = StardomColors.TextSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = StardomTechnicalFont(language),
                                    lineHeight = 14.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(10.dp))
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier.testTag("grant_bg_location_permission_btn")
                                            .background(StardomColors.Background)
                                            .border(1.dp, StardomColors.BorderStrong)
                                            .clickable {
                                              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                  data = Uri.fromParts("package", context.packageName, null)
                                                }
                                                context.startActivity(intent)
                                              } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                              }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 8.dp)) {
                                      Text(
                                          text =
                                              StardomLocalization.onDemandBgLocationGrantBtn(
                                                  language),
                                          color = StardomColors.TextPrimary,
                                          fontSize = 9.sp,
                                          fontFamily = StardomTechnicalFont(language),
                                          fontWeight = FontWeight.Bold,
                                          letterSpacing = 0.5.sp)
                                    }
                              }
                            }
                      }

                      Spacer(Modifier.height(12.dp))

                      // Wi-Fi Network Selection Dropdown / Expandable Menu
                      Box(
                          modifier =
                              Modifier.fillMaxWidth()
                                  .testTag("wifi_network_dropdown_trigger")
                                  .background(StardomColors.PanelSelected)
                                  .border(1.dp, StardomColors.Border)
                                  .clickable { isDropdownExpanded = !isDropdownExpanded }
                                  .padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()) {
                                  Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text =
                                            if (language == AppLanguage.RU)
                                                "ВЫБОР СЕТИ // SSID"
                                            else "SELECT NETWORK // SSID",
                                        color = StardomColors.TextPrimary,
                                        fontSize = 10.sp,
                                        fontFamily = SpaceGrotesk,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 1.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text =
                                            if (language == AppLanguage.RU)
                                                "ВЫБРАНО: ${config.selectedSsids.size}"
                                            else "SELECTED: ${config.selectedSsids.size}",
                                        color =
                                            if (config.selectedSsids.isNotEmpty())
                                                StardomColors.Selected
                                            else StardomColors.TextMuted,
                                        fontSize = 9.sp,
                                        fontFamily = StardomTechnicalFont(language))
                                  }

                                  Text(
                                      text =
                                          if (isDropdownExpanded)
                                              if (language == AppLanguage.RU) "[ СВЕРНУТЬ ▲ ]"
                                              else "[ COLLAPSE ▲ ]"
                                          else if (language == AppLanguage.RU)
                                              "[ ВЫБРАТЬ СЕТЬ ▼ ]"
                                          else "[ SELECT NETWORK ▼ ]",
                                      color = StardomColors.TextSecondary,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      letterSpacing = 1.sp)
                                }
                          }

                      if (isDropdownExpanded) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier =
                                Modifier.fillMaxWidth()
                                    .background(StardomColors.Background)
                                    .border(1.dp, StardomColors.Border)
                                    .padding(12.dp)) {
                              Column {
                                // Блок 1: ТЕКУЩЕЕ ПОДКЛЮЧЕНИЕ (Current Wi-Fi)
                                Text(
                                    text =
                                        StardomLocalization.onDemandCurrentNetworkHeader(
                                            language),
                                    color = StardomColors.TextSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = IbmPlexMono,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp)
                                Spacer(Modifier.height(6.dp))

                                if (!currentSsid.isNullOrBlank()) {
                                  val alreadyAdded = config.selectedSsids.contains(currentSsid)
                                  Row(
                                      modifier =
                                          Modifier.fillMaxWidth()
                                              .testTag("current_ssid_row")
                                              .background(StardomColors.Panel)
                                              .border(
                                                  1.dp,
                                                  if (alreadyAdded) StardomColors.BorderFaint
                                                  else StardomColors.BorderStrong)
                                              .padding(horizontal = 10.dp, vertical = 8.dp),
                                      verticalAlignment = Alignment.CenterVertically,
                                      horizontalArrangement = Arrangement.SpaceBetween) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f, fill = false)) {
                                          Text(
                                              text = "●",
                                              color = StardomColors.Selected,
                                              fontSize = 9.sp,
                                              fontFamily = IbmPlexMono)
                                          Spacer(Modifier.width(6.dp))
                                          Text(
                                              text = currentSsid,
                                              color = StardomColors.TextPrimary,
                                              fontSize = 10.sp,
                                              fontFamily = SpaceGrotesk,
                                              fontWeight = FontWeight.Medium)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        if (alreadyAdded) {
                                          Text(
                                              text =
                                                  StardomLocalization.onDemandAddedBadge(
                                                      language),
                                              color = StardomColors.Selected,
                                              fontSize = 8.sp,
                                              fontFamily = StardomTechnicalFont(language),
                                              fontWeight = FontWeight.Bold)
                                        } else {
                                          Box(
                                              modifier =
                                                  Modifier.testTag("add_current_ssid_btn")
                                                      .background(StardomColors.PanelSelected)
                                                      .border(1.dp, StardomColors.BorderStrong)
                                                      .clickable(enabled = config.enabled) {
                                                        repo.addSsid(currentSsid)
                                                      }
                                                      .padding(
                                                          horizontal = 8.dp, vertical = 4.dp)) {
                                                    Text(
                                                        text =
                                                            StardomLocalization
                                                                 .onDemandAddAction(language),
                                                        color = StardomColors.TextPrimary,
                                                        fontSize = 8.sp,
                                                        fontFamily =
                                                            StardomTechnicalFont(language),
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.5.sp)
                                                  }
                                        }
                                      }
                                } else {
                                  Row(
                                      modifier =
                                          Modifier.fillMaxWidth()
                                              .testTag("current_ssid_unknown_row")
                                              .background(StardomColors.Panel)
                                              .border(1.dp, StardomColors.BorderFaint)
                                              .padding(horizontal = 10.dp, vertical = 8.dp),
                                      verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text =
                                            StardomLocalization.onDemandNoCurrentSsid(
                                                language),
                                        color = StardomColors.TextMuted,
                                        fontSize = 9.sp,
                                        fontFamily = StardomTechnicalFont(language))
                                  }
                                }

                                Spacer(Modifier.height(10.dp))
                                Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .height(1.dp)
                                            .background(StardomColors.BorderFaint))
                                Spacer(Modifier.height(10.dp))

                                // Блок 2: ИЗВЕСТНЫЕ СЕТИ STARDOM (Known Networks History)
                                Text(
                                    text =
                                        StardomLocalization.onDemandKnownNetworksHeader(
                                            language),
                                    color = StardomColors.TextSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = IbmPlexMono,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp)
                                Spacer(Modifier.height(6.dp))

                                val filteredKnown = remember(knownSsids, currentSsid) {
                                  knownSsids.filter { it != currentSsid }.sorted()
                                }

                                if (filteredKnown.isNotEmpty()) {
                                  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    filteredKnown.forEach { ssid ->
                                      val alreadyInSelected = config.selectedSsids.contains(ssid)
                                      Row(
                                          modifier =
                                              Modifier.fillMaxWidth()
                                                  .testTag("known_ssid_$ssid")
                                                  .background(StardomColors.Panel)
                                                  .border(1.dp, StardomColors.BorderFaint)
                                                  .padding(
                                                      horizontal = 10.dp, vertical = 6.dp),
                                          verticalAlignment = Alignment.CenterVertically,
                                          horizontalArrangement = Arrangement.SpaceBetween) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f, fill = false)) {
                                              Text(
                                                  text = "•",
                                                  color = StardomColors.TextSecondary,
                                                  fontSize = 11.sp,
                                                  fontFamily = IbmPlexMono)
                                              Spacer(Modifier.width(6.dp))
                                              Text(
                                                  text = ssid,
                                                  color = StardomColors.TextPrimary,
                                                  fontSize = 10.sp,
                                                  fontFamily = SpaceGrotesk,
                                                  fontWeight = FontWeight.Medium)
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                              if (alreadyInSelected) {
                                                Text(
                                                    text =
                                                        StardomLocalization.onDemandAddedBadge(
                                                            language),
                                                    color = StardomColors.Selected,
                                                    fontSize = 8.sp,
                                                    fontFamily =
                                                        StardomTechnicalFont(language),
                                                    fontWeight = FontWeight.Bold)
                                              } else {
                                                Box(
                                                    modifier =
                                                        Modifier.testTag("add_known_ssid_$ssid")
                                                            .background(StardomColors.PanelSelected)
                                                            .border(
                                                                1.dp,
                                                                StardomColors.BorderStrong)
                                                            .clickable(enabled = config.enabled) {
                                                              repo.addSsid(ssid)
                                                            }
                                                            .padding(
                                                                horizontal = 8.dp,
                                                                vertical = 4.dp)) {
                                                      Text(
                                                          text =
                                                              StardomLocalization
                                                                  .onDemandAddAction(
                                                                      language),
                                                          color = StardomColors.TextPrimary,
                                                          fontSize = 8.sp,
                                                          fontFamily =
                                                              StardomTechnicalFont(language),
                                                          fontWeight = FontWeight.Bold,
                                                          letterSpacing = 0.5.sp)
                                                    }
                                              }
                                              Box(
                                                  modifier =
                                                      Modifier.testTag(
                                                              "remove_known_ssid_$ssid")
                                                          .clickable {
                                                            repo.removeKnownSsid(ssid)
                                                          }
                                                          .padding(
                                                              horizontal = 4.dp,
                                                              vertical = 2.dp)) {
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
                                } else {
                                  Text(
                                      text = StardomLocalization.onDemandNoKnownNetworks(language),
                                      color = StardomColors.TextMuted,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      modifier = Modifier.padding(vertical = 2.dp))
                                }

                                Spacer(Modifier.height(10.dp))
                                Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .height(1.dp)
                                            .background(StardomColors.BorderFaint))
                                Spacer(Modifier.height(10.dp))

                                // Блок 3: СЕТИ ПОБЛИЗОСТИ (Nearby Scan)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                  Text(
                                      text =
                                          StardomLocalization.onDemandNearbyNetworksHeader(
                                              language),
                                      color = StardomColors.TextSecondary,
                                      fontSize = 9.sp,
                                      fontFamily = IbmPlexMono,
                                      fontWeight = FontWeight.Medium,
                                      letterSpacing = 1.sp)
                                  Box(
                                      modifier =
                                          Modifier.testTag("scan_nearby_wifi_btn")
                                              .background(
                                                  if (isScanning) StardomColors.Panel
                                                  else StardomColors.PanelSelected)
                                              .border(
                                                  1.dp,
                                                  if (isScanning) StardomColors.BorderFaint
                                                  else StardomColors.BorderStrong)
                                              .clickable(enabled = !isScanning) {
                                                if (!hasLocationPermission) {
                                                  scanStatusMessage =
                                                      StardomLocalization.onDemandScanNoPermission(
                                                          language)
                                                  return@clickable
                                                }
                                                if (!isLocationServicesEnabled) {
                                                  scanStatusMessage =
                                                      StardomLocalization.onDemandScanLocationOff(
                                                          language)
                                                  return@clickable
                                                }

                                                scanStatusMessage = null
                                                isScanning = true

                                                activeScanCleanup.value?.invoke()

                                                val isReceiverRegistered = AtomicBoolean(false)
                                                var receiver: BroadcastReceiver? = null
                                                var timeoutJob: Job? = null

                                                fun unregister() {
                                                  if (isReceiverRegistered.compareAndSet(true, false)) {
                                                    try {
                                                      receiver?.let { context.unregisterReceiver(it) }
                                                    } catch (_: Throwable) {}
                                                    activeScanCleanup.value = null
                                                  }
                                                }

                                                receiver = object : BroadcastReceiver() {
                                                  override fun onReceive(c: Context?, intent: Intent?) {
                                                    if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                                                      timeoutJob?.cancel()
                                                      val scanList = try {
                                                        @Suppress("DEPRECATION")
                                                        wifiManager?.scanResults
                                                      } catch (e: Exception) {
                                                        TSLog.w("OnDemandWifi", "scanResults read exception: $e")
                                                        null
                                                      }
                                                      val validSsids = scanList
                                                          ?.mapNotNull { sanitizeSsid(it.SSID) }
                                                          ?.distinct()
                                                          ?.sorted()
                                                          .orEmpty()
                                                      nearbySsids = validSsids
                                                      isScanning = false
                                                      if (validSsids.isEmpty()) {
                                                        scanStatusMessage =
                                                            StardomLocalization.onDemandNoNearbyNetworks(
                                                                language)
                                                      } else {
                                                        scanStatusMessage = null
                                                      }
                                                      unregister()
                                                    }
                                                  }
                                                }

                                                val intentFilter =
                                                    IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                                                try {
                                                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    ContextCompat.registerReceiver(
                                                        context,
                                                        receiver,
                                                        intentFilter,
                                                        ContextCompat.RECEIVER_NOT_EXPORTED,
                                                    )
                                                  } else {
                                                    @Suppress("UnspecifiedRegisterReceiverFlag")
                                                    context.registerReceiver(receiver, intentFilter)
                                                  }
                                                  isReceiverRegistered.set(true)
                                                } catch (e: Exception) {
                                                  TSLog.w("OnDemandWifi", "registerReceiver failed: $e")
                                                  isScanning = false
                                                  scanStatusMessage =
                                                      StardomLocalization.onDemandScanBlocked(language)
                                                  return@clickable
                                                }

                                                activeScanCleanup.value = {
                                                  unregister()
                                                  timeoutJob?.cancel()
                                                }

                                                val started = try {
                                                  @Suppress("DEPRECATION")
                                                  wifiManager?.startScan() ?: false
                                                } catch (e: Exception) {
                                                  TSLog.w("OnDemandWifi", "startScan exception: $e")
                                                  false
                                                }

                                                if (!started) {
                                                  isScanning = false
                                                  scanStatusMessage =
                                                      StardomLocalization.onDemandScanBlocked(language)
                                                  unregister()
                                                  TSLog.w("OnDemandWifi", "startScan returned false (throttled)")
                                                  return@clickable
                                                }

                                                timeoutJob = coroutineScope.launch {
                                                  delay(6000)
                                                  if (isScanning) {
                                                    unregister()
                                                    val fallbackSsids = try {
                                                      @Suppress("DEPRECATION")
                                                      wifiManager?.scanResults
                                                          ?.mapNotNull { sanitizeSsid(it.SSID) }
                                                          ?.distinct()
                                                          ?.sorted()
                                                          .orEmpty()
                                                    } catch (e: Exception) {
                                                      emptyList()
                                                    }
                                                    if (fallbackSsids.isNotEmpty()) {
                                                      nearbySsids = fallbackSsids
                                                      scanStatusMessage = null
                                                    } else {
                                                      scanStatusMessage =
                                                          StardomLocalization.onDemandScanTimeout(
                                                              language)
                                                    }
                                                    isScanning = false
                                                  }
                                                }
                                              }
                                              .padding(horizontal = 8.dp, vertical = 4.dp)) {
                                        Text(
                                            text =
                                                if (isScanning)
                                                    StardomLocalization.onDemandScanning(language)
                                                else
                                                    StardomLocalization.onDemandScanNearbyBtn(
                                                        language),
                                            color =
                                                if (isScanning) StardomColors.TextMuted
                                                else StardomColors.TextPrimary,
                                            fontSize = 8.sp,
                                            fontFamily = StardomTechnicalFont(language),
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp)
                                      }
                                }
                                Spacer(Modifier.height(6.dp))

                                if (scanStatusMessage != null && !isScanning) {
                                  Text(
                                      text = scanStatusMessage ?: "",
                                      color = StardomColors.TextMuted,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      modifier = Modifier.padding(vertical = 2.dp))
                                }

                                if (nearbySsids.isNotEmpty()) {
                                  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    nearbySsids.forEach { ssid ->
                                      val alreadyInSelected = config.selectedSsids.contains(ssid)
                                      val isKnown = knownSsids.contains(ssid)
                                      Row(
                                          modifier =
                                              Modifier.fillMaxWidth()
                                                  .testTag("nearby_ssid_$ssid")
                                                  .background(StardomColors.Panel)
                                                  .border(1.dp, StardomColors.BorderFaint)
                                                  .padding(
                                                      horizontal = 10.dp, vertical = 6.dp),
                                          verticalAlignment = Alignment.CenterVertically,
                                          horizontalArrangement = Arrangement.SpaceBetween) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f, fill = false)) {
                                              Text(
                                                  text = "•",
                                                  color = StardomColors.TextSecondary,
                                                  fontSize = 11.sp,
                                                  fontFamily = IbmPlexMono)
                                              Spacer(Modifier.width(6.dp))
                                              Text(
                                                  text = ssid,
                                                  color = StardomColors.TextPrimary,
                                                  fontSize = 10.sp,
                                                  fontFamily = SpaceGrotesk,
                                                  fontWeight = FontWeight.Medium)
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            if (alreadyInSelected) {
                                              Text(
                                                  text =
                                                      StardomLocalization.onDemandAddedBadge(
                                                          language),
                                                  color = StardomColors.Selected,
                                                  fontSize = 8.sp,
                                                  fontFamily =
                                                      StardomTechnicalFont(language),
                                                  fontWeight = FontWeight.Bold)
                                            } else if (isKnown) {
                                              Row(
                                                  verticalAlignment = Alignment.CenterVertically,
                                                  horizontalArrangement =
                                                      Arrangement.spacedBy(8.dp)) {
                                                Text(
                                                    text =
                                                        StardomLocalization.onDemandKnownBadge(
                                                            language),
                                                    color = StardomColors.TextSecondary,
                                                    fontSize = 8.sp,
                                                    fontFamily =
                                                        StardomTechnicalFont(language),
                                                    fontWeight = FontWeight.Bold)
                                                Box(
                                                    modifier =
                                                        Modifier.testTag(
                                                                "add_nearby_ssid_$ssid")
                                                            .background(
                                                                StardomColors.PanelSelected)
                                                            .border(
                                                                1.dp,
                                                                StardomColors.BorderStrong)
                                                            .clickable(
                                                                enabled = config.enabled) {
                                                              repo.addSsid(ssid)
                                                            }
                                                            .padding(
                                                                horizontal = 8.dp,
                                                                vertical = 4.dp)) {
                                                      Text(
                                                          text =
                                                              StardomLocalization
                                                                  .onDemandAddAction(
                                                                      language),
                                                          color = StardomColors.TextPrimary,
                                                          fontSize = 8.sp,
                                                          fontFamily =
                                                              StardomTechnicalFont(language),
                                                          fontWeight = FontWeight.Bold,
                                                          letterSpacing = 0.5.sp)
                                                    }
                                              }
                                            } else {
                                              Box(
                                                  modifier =
                                                      Modifier.testTag("add_nearby_ssid_$ssid")
                                                          .background(StardomColors.PanelSelected)
                                                          .border(
                                                              1.dp,
                                                              StardomColors.BorderStrong)
                                                          .clickable(enabled = config.enabled) {
                                                            repo.addSsid(ssid)
                                                          }
                                                          .padding(
                                                              horizontal = 8.dp,
                                                              vertical = 4.dp)) {
                                                    Text(
                                                        text =
                                                            StardomLocalization
                                                                .onDemandAddAction(language),
                                                        color = StardomColors.TextPrimary,
                                                        fontSize = 8.sp,
                                                        fontFamily =
                                                            StardomTechnicalFont(language),
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.5.sp)
                                                  }
                                            }
                                          }
                                    }
                                  }
                                }

                                Spacer(Modifier.height(10.dp))
                                Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .height(1.dp)
                                            .background(StardomColors.BorderFaint))
                                Spacer(Modifier.height(10.dp))

                                // Блок 4: ДОБАВИТЬ ВРУЧНУЮ
                                Text(
                                    text = StardomLocalization.onDemandAddManualHeader(language),
                                    color = StardomColors.TextSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = IbmPlexMono,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp)
                                Spacer(Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                  Box(
                                      modifier =
                                          Modifier.weight(1f)
                                              .background(StardomColors.Panel)
                                              .border(1.dp, StardomColors.Border)
                                              .padding(horizontal = 10.dp, vertical = 8.dp)) {
                                        BasicTextField(
                                            value = manualSsidText,
                                            onValueChange = { manualSsidText = it },
                                            textStyle =
                                                TextStyle(
                                                    color = StardomColors.TextPrimary,
                                                    fontSize = 10.sp,
                                                    fontFamily =
                                                        StardomTechnicalFont(language)),
                                            singleLine = true,
                                            cursorBrush = SolidColor(StardomColors.Selected),
                                            keyboardOptions =
                                                KeyboardOptions(imeAction = ImeAction.Done),
                                            keyboardActions =
                                                KeyboardActions(
                                                    onDone = {
                                                      val trimmed = manualSsidText.trim()
                                                      if (trimmed.isNotEmpty() &&
                                                          config.enabled) {
                                                        repo.addSsid(trimmed)
                                                        manualSsidText = ""
                                                      }
                                                    }),
                                            modifier =
                                                Modifier.fillMaxWidth()
                                                    .testTag("manual_ssid_input"),
                                            decorationBox = { innerTextField ->
                                              if (manualSsidText.isEmpty()) {
                                                Text(
                                                    text =
                                                        StardomLocalization
                                                            .onDemandAddSsidManual(language),
                                                    color = StardomColors.TextMuted,
                                                    fontSize = 9.sp,
                                                    fontFamily =
                                                        StardomTechnicalFont(language))
                                              }
                                              innerTextField()
                                            })
                                      }

                                  Box(
                                      contentAlignment = Alignment.Center,
                                      modifier =
                                          Modifier.background(
                                                  if (manualSsidText.isNotBlank() &&
                                                      config.enabled)
                                                      StardomColors.PanelSelected
                                                  else StardomColors.Panel)
                                              .border(
                                                  1.dp,
                                                  if (manualSsidText.isNotBlank() &&
                                                      config.enabled)
                                                      StardomColors.BorderStrong
                                                  else StardomColors.Border)
                                              .clickable(
                                                  enabled =
                                                      manualSsidText.isNotBlank() &&
                                                          config.enabled) {
                                                    val trimmed = manualSsidText.trim()
                                                    if (trimmed.isNotEmpty()) {
                                                      repo.addSsid(trimmed)
                                                      manualSsidText = ""
                                                    }
                                                  }
                                              .padding(horizontal = 12.dp, vertical = 8.dp)
                                              .testTag("add_manual_ssid_btn")) {
                                        Text(
                                            text =
                                                StardomLocalization.onDemandAddBtn(
                                                    language),
                                            color =
                                                if (manualSsidText.isNotBlank() &&
                                                    config.enabled)
                                                    StardomColors.TextPrimary
                                                else StardomColors.TextMuted,
                                            fontSize = 9.sp,
                                            fontFamily = StardomTechnicalFont(language),
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp)
                                      }
                                }

                                Spacer(Modifier.height(10.dp))
                                Box(
                                    modifier =
                                        Modifier.fillMaxWidth()
                                            .height(1.dp)
                                            .background(StardomColors.BorderFaint))
                                Spacer(Modifier.height(10.dp))

                                // Блок 5: ВЫБРАННЫЕ ДЛЯ ПРАВИЛА СЕТИ (X)
                                Text(
                                    text =
                                        StardomLocalization.onDemandSelectedSsidsHeader(
                                            language, config.selectedSsids.size),
                                    color = StardomColors.TextSecondary,
                                    fontSize = 9.sp,
                                    fontFamily = IbmPlexMono,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 1.sp)
                                Spacer(Modifier.height(6.dp))

                                if (config.selectedSsids.isEmpty()) {
                                  Text(
                                      text =
                                          StardomLocalization.onDemandNoSelectedSsids(language),
                                      color = StardomColors.TextMuted,
                                      fontSize = 9.sp,
                                      fontFamily = StardomTechnicalFont(language),
                                      modifier = Modifier.padding(vertical = 4.dp))
                                } else {
                                  Column(
                                      verticalArrangement = Arrangement.spacedBy(4.dp),
                                      modifier = Modifier.padding(top = 2.dp)) {
                                    config.selectedSsids.forEach { ssid ->
                                      Row(
                                          modifier =
                                              Modifier.fillMaxWidth()
                                                  .testTag("selected_ssid_$ssid")
                                                  .background(StardomColors.Panel)
                                                  .border(1.dp, StardomColors.BorderFaint)
                                                  .padding(
                                                      horizontal = 10.dp, vertical = 6.dp),
                                          verticalAlignment = Alignment.CenterVertically,
                                          horizontalArrangement =
                                              Arrangement.SpaceBetween) {
                                            Row(
                                                verticalAlignment =
                                                    Alignment.CenterVertically,
                                                modifier = Modifier.weight(1f)) {
                                              Text(
                                                  text = "•",
                                                  color = StardomColors.Selected,
                                                  fontSize = 11.sp,
                                                  fontFamily = IbmPlexMono)
                                              Spacer(Modifier.width(6.dp))
                                              Text(
                                                  text = ssid,
                                                  color = StardomColors.TextPrimary,
                                                  fontSize = 10.sp,
                                                  fontFamily = SpaceGrotesk,
                                                  fontWeight = FontWeight.Medium)
                                            }
                                            Box(
                                                modifier =
                                                    Modifier.clickable(
                                                            enabled = config.enabled) {
                                                          repo.removeSsid(ssid)
                                                        }
                                                        .padding(
                                                            horizontal = 6.dp, vertical = 2.dp)) {
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
                            }
                      }
                    }
                  }
                }

            // Section 03: Unlisted Wi-Fi Rule (shown when wifiScope is ONLY_SELECTED)
            if (config.wifiScope == WifiRuleScope.ONLY_SELECTED) {
              Spacer(Modifier.height(14.dp))

              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .padding(14.dp)) {
                    Column {
                      Text(
                          text =
                              if (language == AppLanguage.RU) "03 // ПРОЧИЕ СЕТИ WI-FI"
                              else "03 // UNLISTED WI-FI NETWORKS",
                          color = StardomColors.TextSecondary,
                          fontFamily = IbmPlexMono,
                          fontSize = 10.sp,
                          fontWeight = FontWeight.Medium,
                          letterSpacing = 1.5.sp)
                      Spacer(Modifier.height(4.dp))
                      Text(
                          text = StardomLocalization.onDemandUnlistedWifiDesc(language),
                          color = StardomColors.TextMuted,
                          fontSize = 9.sp,
                          fontFamily = StardomTechnicalFont(language))

                      Spacer(Modifier.height(12.dp))

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
                                              .testTag(
                                                  "unlisted_wifi_action_${action.name.lowercase()}")
                                              .background(
                                                  if (isSelected) StardomColors.PanelSelected
                                                  else StardomColors.Background)
                                              .border(
                                                  1.dp,
                                                  if (isSelected) StardomColors.BorderStrong
                                                  else StardomColors.BorderFaint)
                                              .clickable(enabled = config.enabled) {
                                                repo.setUnlistedWifiAction(action)
                                              }
                                              .padding(vertical = 10.dp, horizontal = 4.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center) {
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
                                              letterSpacing = 0.5.sp,
                                              maxLines = 1)
                                        }
                                      }
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
