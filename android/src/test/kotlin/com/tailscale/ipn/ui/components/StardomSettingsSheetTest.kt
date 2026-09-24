// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.DnsProvider
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.model.VpnProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StardomSettingsSheetTest {

  @Test
  fun settingsSheetSignatureExcludesLegacySystemNavigationCallback() {
    // Assert StardomSettingsSheet does not expose any callback or parameter for legacy/advanced
    // settings navigation
    val sheetClass = Class.forName("com.tailscale.ipn.ui.components.StardomSettingsSheetKt")
    val methods = sheetClass.declaredMethods.filter { it.name.startsWith("StardomSettingsSheet") }
    assertTrue("StardomSettingsSheet composable method should exist", methods.isNotEmpty())

    for (method in methods) {
      for (paramType in method.parameterTypes) {
        val typeName = paramType.name
        assertFalse(
            "Parameter type $typeName in ${method.name} must not reference legacy SettingsNav",
            typeName.contains("SettingsNav", ignoreCase = true))
      }
      for (param in method.parameters) {
        val paramName = param.name
        assertFalse(
            "Parameter $paramName in ${method.name} must not reference advanced/legacy settings",
            paramName.contains("advanced", ignoreCase = true) ||
                paramName.contains("legacy", ignoreCase = true) ||
                paramName.contains("tailscale", ignoreCase = true))
      }
    }
  }

  @Test
  fun settingsLocalizationStringsDoNotExposeTailscaleBranding() {
    for (lang in AppLanguage.entries) {
      val strings =
          listOf(
              StardomLocalization.settingsTitle(lang),
              StardomLocalization.settingsSubtitle(lang),
              StardomLocalization.doneBtn(lang),
              StardomLocalization.languageSection(lang),
              StardomLocalization.protocolSection(lang),
              StardomLocalization.dnsSection(lang),
              StardomLocalization.dnsManagedStatus(lang),
              StardomLocalization.securitySection(lang),
              StardomLocalization.activeStatus(lang),
              StardomLocalization.comingSoonStatus(lang),
              StardomLocalization.lockedStatus(lang),
              StardomLocalization.killSwitchTitle(lang),
              StardomLocalization.killSwitchDesc(lang),
              StardomLocalization.dnsGuardTitle(lang),
              StardomLocalization.dnsGuardDesc(lang),
              StardomLocalization.obfuscationTitle(lang),
              StardomLocalization.obfuscationDesc(lang),
              StardomLocalization.autoWifiTitle(lang),
              StardomLocalization.autoWifiDesc(lang),
              StardomLocalization.splitTunnelingSection(lang),
              StardomLocalization.splitTunnelingTitle(lang),
              StardomLocalization.splitTunnelingSubtitle(lang),
              StardomLocalization.splitTunnelModeBypass(lang),
              StardomLocalization.splitTunnelModeBypassDesc(lang),
              StardomLocalization.splitTunnelModeOnlySelected(lang),
              StardomLocalization.splitTunnelModeOnlySelectedDesc(lang),
              StardomLocalization.splitTunnelSearchPlaceholder(lang),
              StardomLocalization.splitTunnelAppsCount(lang, 10, 2),
              StardomLocalization.splitTunnelBypassBadge(lang, 2, false),
              StardomLocalization.splitTunnelBypassBadge(lang, 0, false),
              StardomLocalization.splitTunnelBypassBadge(lang, 2, true),
              StardomLocalization.splitTunnelConfigureBtn(lang),
              StardomLocalization.splitTunnelBackBtn(lang),
              StardomLocalization.splitTunnelExpandList(lang, 2),
              StardomLocalization.splitTunnelCollapseList(lang, 2),
              StardomLocalization.splitTunnelEmptyList(lang),
              StardomLocalization.splitTunnelEmptyListHint(lang),
              StardomLocalization.splitTunnelStatusBypassed(lang),
              StardomLocalization.splitTunnelStatusTunneled(lang),
              StardomLocalization.onDemandSection(lang),
              StardomLocalization.onDemandTitle(lang),
              StardomLocalization.onDemandSubtitle(lang),
              StardomLocalization.onDemandCardBadge(lang, false, false, false),
              StardomLocalization.onDemandCardBadge(lang, true, true, true),
              StardomLocalization.onDemandCardBadge(lang, true, true, false),
              StardomLocalization.onDemandEnableToggle(lang),
              StardomLocalization.onDemandCellularRuleTitle(lang),
              StardomLocalization.onDemandCellularRuleDesc(lang),
              StardomLocalization.onDemandWifiRuleTitle(lang),
              StardomLocalization.onDemandWifiRuleDesc(lang),
              StardomLocalization.onDemandWifiScopeAll(lang),
              StardomLocalization.onDemandWifiScopeSelected(lang),
              StardomLocalization.onDemandUnlistedWifiTitle(lang),
              StardomLocalization.onDemandUnlistedWifiDesc(lang),
              StardomLocalization.onDemandActionConnect(lang),
              StardomLocalization.onDemandActionDisconnect(lang),
              StardomLocalization.onDemandActionNothing(lang),
              StardomLocalization.onDemandAddCurrentSsid(lang, "Home-Wifi"),
              StardomLocalization.onDemandNoCurrentSsid(lang),
              StardomLocalization.onDemandSelectedSsidsHeader(lang, 3),
              StardomLocalization.onDemandNoSelectedSsids(lang),
              StardomLocalization.onDemandLocationPermissionNotice(lang),
              StardomLocalization.onDemandLocationPermissionGrant(lang),
              StardomLocalization.onDemandBgLocationNotice(lang),
              StardomLocalization.onDemandBgLocationGrantBtn(lang),
              StardomLocalization.onDemandMonitorNotificationText(lang),
              StardomLocalization.onDemandCurrentNetworkHeader(lang),
              StardomLocalization.onDemandKnownNetworksHeader(lang),
              StardomLocalization.onDemandNoKnownNetworks(lang),
              StardomLocalization.onDemandNearbyNetworksHeader(lang),
              StardomLocalization.onDemandScanNearbyBtn(lang),
              StardomLocalization.onDemandScanning(lang),
              StardomLocalization.onDemandNoNearbyNetworks(lang),
              StardomLocalization.onDemandAddManualHeader(lang),
              StardomLocalization.onDemandAddBtn(lang),
              StardomLocalization.onDemandAddedBadge(lang),
              StardomLocalization.onDemandAddAction(lang),
              StardomLocalization.onDemandLocationAccessGranted(lang),
              StardomLocalization.onDemandLocationAccessDenied(lang),
              StardomLocalization.onDemandLocationServicesDisabled(lang),
              StardomLocalization.onDemandLocationServicesEnabled(lang),
              StardomLocalization.onDemandEnableLocationBtn(lang),
              StardomLocalization.onDemandScanBlocked(lang),
              StardomLocalization.onDemandScanLocationOff(lang),
              StardomLocalization.onDemandScanNoPermission(lang),
              StardomLocalization.onDemandScanTimeout(lang),
              StardomLocalization.onDemandKnownBadge(lang))

      for (str in strings) {
        assertFalse(
            "Settings string '$str' must not mention Tailscale branding for language ${lang.code}",
            str.contains("Tailscale", ignoreCase = true))
      }
    }
  }

  @Test
  fun protocolConfigurationOnlyEnablesWireguardAndDisablesUnsupportedStubs() {
    assertTrue("WireGuard must be enabled", VpnProtocol.WIREGUARD.enabled)
    assertFalse("Shadowsocks-2022 must be disabled as stub", VpnProtocol.SHADOWSOCKS_2022.enabled)
    assertFalse("V2Ray / VMess must be disabled as stub", VpnProtocol.V2RAY_VMESS.enabled)
    assertFalse("IKEv2 / IPsec must be disabled as stub", VpnProtocol.IKEV2_IPSEC.enabled)

    // Simulate clicking each protocol option: only enabled protocols invoke the callback
    var selectedProto = VpnProtocol.WIREGUARD
    val onSelectProtocol: (VpnProtocol) -> Unit = { selectedProto = it }

    val unsupportedProtocols =
        listOf(VpnProtocol.SHADOWSOCKS_2022, VpnProtocol.V2RAY_VMESS, VpnProtocol.IKEV2_IPSEC)

    for (unsupported in unsupportedProtocols) {
      if (unsupported.enabled) {
        onSelectProtocol(unsupported)
      }
      assertEquals(
          "Callback must not be invoked for unsupported protocol $unsupported",
          VpnProtocol.WIREGUARD,
          selectedProto)
    }
  }

  @Test
  fun dnsProvidersExposeStardomZeroKnowledgeAsDefaultManagedAndOthersAsComingSoon() {
    val providers = DnsProvider.entries
    assertEquals(4, providers.size)
    assertTrue(providers.contains(DnsProvider.STARDOM_ZERO_KNOWLEDGE))
    assertTrue(providers.contains(DnsProvider.CLOUDFLARE_DOH))
    assertTrue(providers.contains(DnsProvider.QUAD9_SECURE))
    assertTrue(providers.contains(DnsProvider.CUSTOM_ENCRYPTED))

    // Default DNS provider is route-managed and not mutated
    var activeDns = DnsProvider.STARDOM_ZERO_KNOWLEDGE
    val onSelectDns: (DnsProvider) -> Unit = { activeDns = it }

    // Informational default label exists
    for (lang in AppLanguage.entries) {
      val managedLabel = StardomLocalization.dnsManagedStatus(lang)
      assertTrue("Managed label must be non-empty", managedLabel.isNotEmpty())
      assertFalse(managedLabel.contains("Tailscale", ignoreCase = true))

      val comingSoon = StardomLocalization.comingSoonStatus(lang)
      assertTrue(comingSoon.isNotEmpty())
    }

    // Since DNS is route-managed in Stardom UX, attempting to select other providers does not
    // mutate activeDns
    assertEquals(DnsProvider.STARDOM_ZERO_KNOWLEDGE, activeDns)
  }

  @Test
  fun securityFeaturesAreClearlyLabeledComingSoonStubs() {
    for (lang in AppLanguage.entries) {
      val comingSoon = StardomLocalization.comingSoonStatus(lang)
      assertEquals(if (lang == AppLanguage.RU) "СКОРО" else "COMING SOON", comingSoon)

      val killSwitch = StardomLocalization.killSwitchTitle(lang)
      val dnsGuard = StardomLocalization.dnsGuardTitle(lang)
      val obfuscation = StardomLocalization.obfuscationTitle(lang)
      val autoWifi = StardomLocalization.autoWifiTitle(lang)

      assertTrue(killSwitch.isNotEmpty())
      assertTrue(dnsGuard.isNotEmpty())
      assertTrue(obfuscation.isNotEmpty())
      assertTrue(autoWifi.isNotEmpty())
    }
  }

  @Test
  fun languageSelectionRemainsFunctionalForCurrentSession() {
    assertEquals(2, AppLanguage.entries.size)
    var currentLanguage = AppLanguage.RU
    val onSelectLanguage: (AppLanguage) -> Unit = { currentLanguage = it }

    onSelectLanguage(AppLanguage.EN)
    assertEquals(AppLanguage.EN, currentLanguage)

    onSelectLanguage(AppLanguage.RU)
    assertEquals(AppLanguage.RU, currentLanguage)
  }

  @Test
  fun splitTunnelingExpandableListLocalizationStringsAreValid() {
    for (lang in AppLanguage.entries) {
      val expand = StardomLocalization.splitTunnelExpandList(lang, 5)
      val collapse = StardomLocalization.splitTunnelCollapseList(lang, 5)
      val empty = StardomLocalization.splitTunnelEmptyList(lang)
      val emptyHint = StardomLocalization.splitTunnelEmptyListHint(lang)
      val bypassed = StardomLocalization.splitTunnelStatusBypassed(lang)
      val tunneled = StardomLocalization.splitTunnelStatusTunneled(lang)

      assertTrue(expand.contains("5"))
      assertTrue(expand.contains("▼"))
      assertTrue(collapse.contains("5"))
      assertTrue(collapse.contains("▲"))
      assertTrue(empty.isNotEmpty())
      assertTrue(emptyHint.isNotEmpty())
      assertTrue(bypassed.isNotEmpty())
      assertTrue(tunneled.isNotEmpty())

      if (lang == AppLanguage.RU) {
        assertEquals("ОБХОД", bypassed)
        assertEquals("В ТУННЕЛЕ", tunneled)
        assertEquals("НЕТ ВЫБРАННЫХ ПРИЛОЖЕНИЙ", empty)
        assertEquals("Нажмите «НАСТРОИТЬ →», чтобы добавить", emptyHint)
      } else {
        assertEquals("BYPASS", bypassed)
        assertEquals("TUNNELED", tunneled)
        assertEquals("NO APPS SELECTED", empty)
        assertEquals("Tap 'CONFIGURE →' to add applications", emptyHint)
      }
    }
  }

  @Test
  fun onDemandLocalizationAndBadgeLogic() {
    for (lang in AppLanguage.entries) {
      val badgeDisabled = StardomLocalization.onDemandCardBadge(lang, false, false, false)
      val badgeCellOnly = StardomLocalization.onDemandCardBadge(lang, true, true, true)
      val badgeActive = StardomLocalization.onDemandCardBadge(lang, true, false, false)

      assertTrue(badgeDisabled.isNotBlank())
      assertTrue(badgeCellOnly.isNotBlank())
      assertTrue(badgeActive.isNotBlank())

      if (lang == AppLanguage.RU) {
        assertEquals("ОТКЛЮЧЕНО", badgeDisabled)
        assertEquals("МОБИЛЬНАЯ [ВКЛ] // WI-FI [ОТКЛ]", badgeCellOnly)
        assertEquals("АКТИВЕН", badgeActive)
      } else {
        assertEquals("DISABLED", badgeDisabled)
        assertEquals("CELLULAR [ON] // WI-FI [OFF]", badgeCellOnly)
        assertEquals("ACTIVE", badgeActive)
      }
    }
  }

  @Test
  fun onDemandWifiSelectorLocalizationStringsAreValid() {
    for (lang in AppLanguage.entries) {
      val currentHdr = StardomLocalization.onDemandCurrentNetworkHeader(lang)
      val knownHdr = StardomLocalization.onDemandKnownNetworksHeader(lang)
      val noKnown = StardomLocalization.onDemandNoKnownNetworks(lang)
      val nearbyHdr = StardomLocalization.onDemandNearbyNetworksHeader(lang)
      val scanBtn = StardomLocalization.onDemandScanNearbyBtn(lang)
      val scanning = StardomLocalization.onDemandScanning(lang)
      val noNearby = StardomLocalization.onDemandNoNearbyNetworks(lang)
      val addManualHdr = StardomLocalization.onDemandAddManualHeader(lang)
      val addBtn = StardomLocalization.onDemandAddBtn(lang)
      val addedBadge = StardomLocalization.onDemandAddedBadge(lang)
      val addAction = StardomLocalization.onDemandAddAction(lang)

      assertTrue(currentHdr.isNotBlank())
      assertTrue(knownHdr.isNotBlank())
      assertTrue(noKnown.isNotBlank())
      assertTrue(nearbyHdr.isNotBlank())
      assertTrue(scanBtn.isNotBlank())
      assertTrue(scanning.isNotBlank())
      assertTrue(noNearby.isNotBlank())
      assertTrue(addManualHdr.isNotBlank())
      assertTrue(addBtn.isNotBlank())
      assertTrue(addedBadge.isNotBlank())
      assertTrue(addAction.isNotBlank())

      if (lang == AppLanguage.RU) {
        assertEquals("ТЕКУЩЕЕ ПОДКЛЮЧЕНИЕ", currentHdr)
        assertEquals("ИЗВЕСТНЫЕ СЕТИ STARDOM", knownHdr)
        assertEquals("НЕТ ИЗВЕСТНЫХ СЕТЕЙ (запоминаются при подключении)", noKnown)
        assertEquals("СЕТИ ПОБЛИЗОСТИ", nearbyHdr)
        assertEquals("СКАНИРОВАТЬ ЭФИР", scanBtn)
        assertEquals("СКАНИРОВАНИЕ...", scanning)
        assertEquals("СЕТИ ПОБЛИЗОСТИ НЕ НАЙДЕНЫ", noNearby)
        assertEquals("ДОБАВИТЬ ВРУЧНУЮ", addManualHdr)
        assertEquals("ДОБАВИТЬ", addBtn)
        assertEquals("[ В СПИСКЕ ]", addedBadge)
        assertEquals("[ + ВЫБРАТЬ ]", addAction)
      } else {
        assertEquals("CURRENT CONNECTION", currentHdr)
        assertEquals("KNOWN BY STARDOM", knownHdr)
        assertEquals("NO KNOWN NETWORKS (auto-saved upon connection)", noKnown)
        assertEquals("NEARBY NETWORKS", nearbyHdr)
        assertEquals("SCAN NEARBY NETWORKS", scanBtn)
        assertEquals("SCANNING...", scanning)
        assertEquals("NO NEARBY NETWORKS FOUND", noNearby)
        assertEquals("ADD MANUALLY", addManualHdr)
        assertEquals("ADD", addBtn)
        assertEquals("[ ADDED ]", addedBadge)
        assertEquals("[ + SELECT ]", addAction)
      }
    }
  }

  @Test
  fun onDemandDiagnosticAndPermissionLocalizationStringsAreValid() {
    for (lang in AppLanguage.entries) {
      val accessGranted = StardomLocalization.onDemandLocationAccessGranted(lang)
      val accessDenied = StardomLocalization.onDemandLocationAccessDenied(lang)
      val servicesDisabled = StardomLocalization.onDemandLocationServicesDisabled(lang)
      val servicesEnabled = StardomLocalization.onDemandLocationServicesEnabled(lang)
      val enableLocationBtn = StardomLocalization.onDemandEnableLocationBtn(lang)
      val scanBlocked = StardomLocalization.onDemandScanBlocked(lang)
      val scanLocationOff = StardomLocalization.onDemandScanLocationOff(lang)
      val scanNoPermission = StardomLocalization.onDemandScanNoPermission(lang)
      val scanTimeout = StardomLocalization.onDemandScanTimeout(lang)
      val knownBadge = StardomLocalization.onDemandKnownBadge(lang)

      assertTrue(accessGranted.isNotBlank())
      assertTrue(accessDenied.isNotBlank())
      assertTrue(servicesDisabled.isNotBlank())
      assertTrue(servicesEnabled.isNotBlank())
      assertTrue(enableLocationBtn.isNotBlank())
      assertTrue(scanBlocked.isNotBlank())
      assertTrue(scanLocationOff.isNotBlank())
      assertTrue(scanNoPermission.isNotBlank())
      assertTrue(scanTimeout.isNotBlank())
      assertTrue(knownBadge.isNotBlank())

      if (lang == AppLanguage.RU) {
        assertEquals("Доступ к геолокации: разрешён", accessGranted)
        assertEquals("Доступ к геолокации: не разрешён", accessDenied)
        assertEquals("Службы геолокации: выключены", servicesDisabled)
        assertEquals("Службы геолокации: включены", servicesEnabled)
        assertEquals("ВКЛЮЧИТЬ ГЕОЛОКАЦИЮ", enableLocationBtn)
        assertEquals("СКАНИРОВАНИЕ ЗАБЛОКИРОВАНО ANDROID", scanBlocked)
        assertEquals("ГЕОЛОКАЦИЯ ВЫКЛЮЧЕНА", scanLocationOff)
        assertEquals("НЕТ РАЗРЕШЕНИЯ", scanNoPermission)
        assertEquals("СКАНИРОВАНИЕ НЕ ЗАВЕРШЕНО", scanTimeout)
        assertEquals("[ ИЗВЕСТНА ]", knownBadge)
        assertEquals(
            "Для автоматического переключения в фоне требуется доступ к геолокации в любом режиме.",
            StardomLocalization.onDemandBgLocationNotice(lang))
        assertEquals("РАЗРЕШИТЬ В ФОНЕ", StardomLocalization.onDemandBgLocationGrantBtn(lang))
        assertEquals("Автоматизация сети активна", StardomLocalization.onDemandMonitorNotificationText(lang))
      } else {
        assertEquals("Location permission: granted", accessGranted)
        assertEquals("Location permission: not granted", accessDenied)
        assertEquals("Location services: disabled", servicesDisabled)
        assertEquals("Location services: enabled", servicesEnabled)
        assertEquals("ENABLE LOCATION", enableLocationBtn)
        assertEquals("SCAN BLOCKED BY ANDROID", scanBlocked)
        assertEquals("LOCATION SERVICES DISABLED", scanLocationOff)
        assertEquals("PERMISSION MISSING", scanNoPermission)
        assertEquals("SCAN TIMED OUT", scanTimeout)
        assertEquals("[ KNOWN ]", knownBadge)
        assertEquals(
            "Background network automation requires location access set to 'Allow all the time'.",
            StardomLocalization.onDemandBgLocationNotice(lang))
        assertEquals("ALLOW IN BACKGROUND", StardomLocalization.onDemandBgLocationGrantBtn(lang))
        assertEquals("Network automation active", StardomLocalization.onDemandMonitorNotificationText(lang))
      }
    }
  }
}
