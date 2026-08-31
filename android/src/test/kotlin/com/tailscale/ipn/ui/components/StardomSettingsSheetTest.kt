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
        // Assert no legacy/system settings types are taken
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
  }

  @Test
  fun dnsProvidersExposeStardomZeroKnowledgeAndStandardResolvers() {
    val providers = DnsProvider.entries
    assertEquals(4, providers.size)
    assertTrue(providers.contains(DnsProvider.STARDOM_ZERO_KNOWLEDGE))
    assertTrue(providers.contains(DnsProvider.CLOUDFLARE_DOH))
    assertTrue(providers.contains(DnsProvider.QUAD9_SECURE))
    assertTrue(providers.contains(DnsProvider.CUSTOM_ENCRYPTED))

    assertEquals("10.64.0.1", DnsProvider.STARDOM_ZERO_KNOWLEDGE.address)
    assertEquals("1.1.1.1", DnsProvider.CLOUDFLARE_DOH.address)
    assertEquals("9.9.9.9", DnsProvider.QUAD9_SECURE.address)
  }

  @Test
  fun supportedLanguagesOnlyIncludeRussianAndEnglish() {
    assertEquals(2, AppLanguage.entries.size)
    assertEquals(AppLanguage.RU, AppLanguage.valueOf("RU"))
    assertEquals(AppLanguage.EN, AppLanguage.valueOf("EN"))
  }
}
