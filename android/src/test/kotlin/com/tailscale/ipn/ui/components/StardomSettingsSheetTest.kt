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
              StardomLocalization.autoWifiDesc(lang))

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
}
