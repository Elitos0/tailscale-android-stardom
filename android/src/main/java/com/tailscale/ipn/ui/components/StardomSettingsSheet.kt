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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.DnsProvider
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.model.VpnProtocol
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StardomSettingsSheet(
    selectedProtocol: VpnProtocol,
    onSelectProtocol: (VpnProtocol) -> Unit,
    selectedDns: DnsProvider = DnsProvider.STARDOM_ZERO_KNOWLEDGE,
    onSelectDns: (DnsProvider) -> Unit = {},
    selectedLanguage: AppLanguage,
    onSelectLanguage: (AppLanguage) -> Unit,
    sheetState: SheetState,
    onDismiss: () -> Unit
) {
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
                          fontFamily = IbmPlexMono,
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
                              fontFamily = IbmPlexMono,
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

              // Section: Protocol Engine (WireGuard functional, others disabled Coming Soon)
              SettingsSectionHeader(title = StardomLocalization.protocolSection(selectedLanguage))
              Column(
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                  modifier = Modifier.fillMaxWidth()) {
                    VpnProtocol.entries.forEach { proto ->
                      val isSelected = proto == selectedProtocol
                      val isEnabled = proto.enabled

                      Box(
                          modifier =
                              Modifier.fillMaxWidth()
                                  .testTag("proto_option_${proto.name}")
                                  .background(
                                      if (isSelected) StardomColors.PanelSelected
                                      else StardomColors.Panel)
                                  .border(
                                      1.dp,
                                      if (isSelected) StardomColors.BorderStrong
                                      else StardomColors.BorderFaint)
                                  .clickable(enabled = isEnabled) {
                                    if (isEnabled) onSelectProtocol(proto)
                                  }
                                  .padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()) {
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
                                    Column {
                                      Text(
                                          text = proto.displayName,
                                          color =
                                              when {
                                                !isEnabled -> StardomColors.TextMuted
                                                isSelected -> StardomColors.TextPrimary
                                                else -> StardomColors.TextSecondary
                                              },
                                          fontSize = 12.sp,
                                          fontWeight = FontWeight.Medium,
                                          fontFamily = SpaceGrotesk)
                                      Spacer(Modifier.height(2.dp))
                                      Text(
                                          text = "CIPHER: ${proto.cipher} • PORT: ${proto.port}",
                                          color = StardomColors.TextMuted,
                                          fontSize = 9.sp,
                                          fontFamily = IbmPlexMono)
                                    }
                                  }

                                  if (isSelected) {
                                    Text(
                                        text = StardomLocalization.activeStatus(selectedLanguage),
                                        color = StardomColors.TextPrimary,
                                        fontSize = 9.sp,
                                        fontFamily = IbmPlexMono,
                                        letterSpacing = 1.sp)
                                  } else if (!isEnabled) {
                                    Text(
                                        text =
                                            StardomLocalization.comingSoonStatus(selectedLanguage),
                                        color = StardomColors.TextMuted,
                                        fontSize = 9.sp,
                                        fontFamily = IbmPlexMono,
                                        letterSpacing = 1.sp)
                                  }
                                }
                          }
                    }
                  }

              Spacer(modifier = Modifier.height(18.dp))

              // Section: DNS Resolver (Informational / Route-Managed, others Coming Soon)
              SettingsSectionHeader(title = StardomLocalization.dnsSection(selectedLanguage))
              Column(
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                  modifier = Modifier.fillMaxWidth()) {
                    DnsProvider.entries.forEach { dns ->
                      val isDefault = dns == DnsProvider.STARDOM_ZERO_KNOWLEDGE

                      Box(
                          modifier =
                              Modifier.fillMaxWidth()
                                  .testTag("dns_option_${dns.name}")
                                  .background(
                                      if (isDefault) StardomColors.PanelSelected
                                      else StardomColors.Panel)
                                  .border(
                                      1.dp,
                                      if (isDefault) StardomColors.BorderStrong
                                      else StardomColors.BorderFaint)
                                  .padding(12.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()) {
                                  Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier =
                                            Modifier.size(10.dp)
                                                .border(
                                                    1.dp,
                                                    if (isDefault) StardomColors.Selected
                                                    else StardomColors.TextMuted),
                                        contentAlignment = Alignment.Center) {
                                          if (isDefault) {
                                            Box(
                                                modifier =
                                                    Modifier.size(4.dp)
                                                        .background(StardomColors.Selected))
                                          }
                                        }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                      Text(
                                          text = dns.displayName,
                                          color =
                                              if (isDefault) StardomColors.TextPrimary
                                              else StardomColors.TextMuted,
                                          fontSize = 12.sp,
                                          fontWeight = FontWeight.Medium,
                                          fontFamily = SpaceGrotesk)
                                      Spacer(Modifier.height(2.dp))
                                      Text(
                                          text = "IP: ${dns.address}",
                                          color = StardomColors.TextMuted,
                                          fontSize = 9.sp,
                                          fontFamily = IbmPlexMono)
                                    }
                                  }

                                  if (isDefault) {
                                    Text(
                                        text =
                                            StardomLocalization.dnsManagedStatus(selectedLanguage),
                                        color = StardomColors.TextSecondary,
                                        fontSize = 9.sp,
                                        fontFamily = IbmPlexMono,
                                        letterSpacing = 1.sp)
                                  } else {
                                    Text(
                                        text =
                                            StardomLocalization.comingSoonStatus(selectedLanguage),
                                        color = StardomColors.TextMuted,
                                        fontSize = 9.sp,
                                        fontFamily = IbmPlexMono,
                                        letterSpacing = 1.sp)
                                  }
                                }
                          }
                    }
                  }

              Spacer(modifier = Modifier.height(18.dp))

              // Section: Security Toggles (Coming Soon stubs)
              SettingsSectionHeader(title = StardomLocalization.securitySection(selectedLanguage))
              Column(
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                  modifier = Modifier.fillMaxWidth()) {
                    SecurityToggleStubItem(
                        title = StardomLocalization.killSwitchTitle(selectedLanguage),
                        description = StardomLocalization.killSwitchDesc(selectedLanguage),
                        language = selectedLanguage,
                        testTag = "security_toggle_kill_switch")

                    SecurityToggleStubItem(
                        title = StardomLocalization.dnsGuardTitle(selectedLanguage),
                        description = StardomLocalization.dnsGuardDesc(selectedLanguage),
                        language = selectedLanguage,
                        testTag = "security_toggle_dns_guard")

                    SecurityToggleStubItem(
                        title = StardomLocalization.obfuscationTitle(selectedLanguage),
                        description = StardomLocalization.obfuscationDesc(selectedLanguage),
                        language = selectedLanguage,
                        testTag = "security_toggle_obfuscation")

                    SecurityToggleStubItem(
                        title = StardomLocalization.autoWifiTitle(selectedLanguage),
                        description = StardomLocalization.autoWifiDesc(selectedLanguage),
                        language = selectedLanguage,
                        testTag = "security_toggle_auto_wifi")
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

@Composable
private fun SecurityToggleStubItem(
    title: String,
    description: String,
    language: AppLanguage,
    testTag: String
) {
  Box(
      modifier =
          Modifier.fillMaxWidth()
              .testTag(testTag)
              .background(StardomColors.Panel)
              .border(1.dp, StardomColors.BorderFaint)
              .padding(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = StardomColors.TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = SpaceGrotesk)
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    color = StardomColors.TextMuted,
                    fontSize = 9.sp,
                    fontFamily = IbmPlexMono)
              }

              Spacer(Modifier.width(12.dp))

              Text(
                  text = StardomLocalization.comingSoonStatus(language),
                  color = StardomColors.TextMuted,
                  fontSize = 9.sp,
                  fontFamily = IbmPlexMono,
                  letterSpacing = 1.sp)
            }
      }
}
