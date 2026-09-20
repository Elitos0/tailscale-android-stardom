// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class NetworkChangeCallbackTest {

  @Before
  fun setUp() {
    NetworkChangeCallback.resetForTesting()
  }

  @After
  fun tearDown() {
    NetworkChangeCallback.resetForTesting()
  }

  // --- Tier Ranking Tests ---

  @Test
  fun prefersValidatedNetworkWithDns() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("unvalidated-dns", validated = false, hasDns = true),
                candidate("validated-no-dns", validated = true, hasDns = false),
                candidate("validated-dns", validated = true, hasDns = true),
            ))

    assertEquals("validated-dns", result)
  }

  @Test
  fun prefersValidatedNetworkWithoutDnsOverUnvalidatedNetworkWithDns() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("unvalidated-dns", validated = false, hasDns = true),
                candidate("validated-no-dns", validated = true, hasDns = false),
            ))

    assertEquals("validated-no-dns", result)
  }

  @Test
  fun fallsBackToUnvalidatedNetworkWithDns() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("unvalidated-no-dns", validated = false, hasDns = false),
                candidate("unvalidated-dns", validated = false, hasDns = true),
            ))

    assertEquals("unvalidated-dns", result)
  }

  @Test
  fun fallsBackToUnvalidatedNetworkWithoutDns() {
    val result =
        pickPreferredNetwork(listOf(candidate("unvalidated", validated = false, hasDns = false)))

    assertEquals("unvalidated", result)
  }

  @Test
  fun prefersNonMeteredNetworkWithinSameTier() {
    // Tier 1: Validated + DNS
    assertEquals(
        "tier1-non-metered",
        pickPreferredNetwork(
            listOf(
                candidate("tier1-metered", validated = true, hasDns = true, nonMetered = false),
                candidate("tier1-non-metered", validated = true, hasDns = true, nonMetered = true),
            )))

    // Tier 2: Validated + No DNS
    assertEquals(
        "tier2-non-metered",
        pickPreferredNetwork(
            listOf(
                candidate("tier2-metered", validated = true, hasDns = false, nonMetered = false),
                candidate("tier2-non-metered", validated = true, hasDns = false, nonMetered = true),
            )))

    // Tier 3: Unvalidated + DNS
    assertEquals(
        "tier3-non-metered",
        pickPreferredNetwork(
            listOf(
                candidate("tier3-metered", validated = false, hasDns = true, nonMetered = false),
                candidate("tier3-non-metered", validated = false, hasDns = true, nonMetered = true),
            )))

    // Tier 4: Unvalidated + No DNS
    assertEquals(
        "tier4-non-metered",
        pickPreferredNetwork(
            listOf(
                candidate("tier4-metered", validated = false, hasDns = false, nonMetered = false),
                candidate("tier4-non-metered", validated = false, hasDns = false, nonMetered = true),
            )))
  }

  @Test
  fun meteredInHigherTierBeatsNonMeteredInLowerTier() {
    // Tier 1 metered vs Tier 2 non-metered
    assertEquals(
        "tier1-metered",
        pickPreferredNetwork(
            listOf(
                candidate("tier2-non-metered", validated = true, hasDns = false, nonMetered = true),
                candidate("tier1-metered", validated = true, hasDns = true, nonMetered = false),
            )))

    // Tier 2 metered vs Tier 3 non-metered
    assertEquals(
        "tier2-metered",
        pickPreferredNetwork(
            listOf(
                candidate("tier3-non-metered", validated = false, hasDns = true, nonMetered = true),
                candidate("tier2-metered", validated = true, hasDns = false, nonMetered = false),
            )))

    // Tier 3 metered vs Tier 4 non-metered
    assertEquals(
        "tier3-metered",
        pickPreferredNetwork(
            listOf(
                candidate("tier4-non-metered", validated = false, hasDns = false, nonMetered = true),
                candidate("tier3-metered", validated = false, hasDns = true, nonMetered = false),
            )))
  }

  @Test
  fun networkCanBecomePreferredWhenItBecomesValidated() {
    val cellular = candidate("cellular", validated = true, nonMetered = false)
    val wifi = candidate("wifi", validated = false, nonMetered = true)

    assertEquals("cellular", pickPreferredNetwork(listOf(cellular, wifi)))

    val validatedWifi = wifi.copy(validated = true)

    assertEquals("wifi", pickPreferredNetwork(listOf(cellular, validatedWifi)))
  }

  // --- No VPN-Loop / Candidate Exclusion Tests ---

  @Test
  fun ignoresVpnNetworks() {
    val result =
        pickPreferredNetwork(
            listOf(
                // Even with ideal attributes (validated, DNS, non-metered), VPN must never be chosen
                candidate("vpn", notVpn = false, validated = true, hasDns = true, nonMetered = true),
                candidate("non-vpn", notVpn = true, validated = false, hasDns = false, nonMetered = false),
            ))

    assertEquals("non-vpn", result)
  }

  @Test
  fun allVpnCandidatesReturnNullToPreventRoutingLoops() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("vpn-1", notVpn = false, validated = true, hasDns = true, nonMetered = true),
                candidate("vpn-2", notVpn = false, validated = false, hasDns = true, nonMetered = false),
            ))

    assertNull(result)
  }

  @Test
  fun ignoresNetworksWithoutInternet() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("no-internet", internet = false, validated = true, hasDns = true, nonMetered = true),
                candidate("internet", internet = true, validated = false, hasDns = false, nonMetered = false),
            ))

    assertEquals("internet", result)
  }

  @Test
  fun returnsNullWhenNoUsableNetworkExists() {
    val result =
        pickPreferredNetwork(
            listOf(
                candidate("no-internet", internet = false),
                candidate("vpn", notVpn = false),
            ))

    assertNull(result)
  }

  // --- Change Callback and Underlying Network Listener Tests ---

  @Test
  fun underlyingNetworkListenerNotifiedOnPreferredNetworkChange() {
    val network1 = mock(Network::class.java)
    val network2 = mock(Network::class.java)

    val caps1 = mock(NetworkCapabilities::class.java)
    `when`(caps1.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
    `when`(caps1.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(true)
    `when`(caps1.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(false)
    `when`(caps1.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).thenReturn(false)

    val caps2 = mock(NetworkCapabilities::class.java)
    `when`(caps2.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
    `when`(caps2.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(true)
    `when`(caps2.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
    `when`(caps2.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).thenReturn(true)

    val dispatched = mutableListOf<Network?>()
    NetworkChangeCallback.setUnderlyingNetworkListener { dispatched.add(it) }

    // Initial addition of network1 (unvalidated)
    NetworkChangeCallback.updateNetworkForTesting(network1, caps = caps1)
    assertEquals(listOf(network1), dispatched)
    assertEquals(network1, NetworkChangeCallback.cachedDefaultNetwork)

    // Updating network1 with same caps should not trigger another callback
    NetworkChangeCallback.updateNetworkForTesting(network1, caps = caps1)
    assertEquals(listOf(network1), dispatched)

    // Addition of network2 (validated) causes network change
    NetworkChangeCallback.updateNetworkForTesting(network2, caps = caps2)
    assertEquals(listOf(network1, network2), dispatched)
    assertEquals(network2, NetworkChangeCallback.cachedDefaultNetwork)

    // Removing network2 falls back to network1
    NetworkChangeCallback.removeNetworkForTesting(network2)
    assertEquals(listOf(network1, network2, network1), dispatched)
    assertEquals(network1, NetworkChangeCallback.cachedDefaultNetwork)

    // Removing network1 clears default network
    NetworkChangeCallback.removeNetworkForTesting(network1)
    assertEquals(listOf(network1, network2, network1, null), dispatched)
    assertNull(NetworkChangeCallback.cachedDefaultNetwork)
  }

  @Test
  fun capabilitiesChangeMakingNetworkValidatedElevatesDefaultNetwork() {
    val cellular = mock(Network::class.java)
    val wifi = mock(Network::class.java)

    val cellularCaps = mock(NetworkCapabilities::class.java)
    `when`(cellularCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
    `when`(cellularCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(true)
    `when`(cellularCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
    `when`(cellularCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).thenReturn(false)

    val wifiCapsUnvalidated = mock(NetworkCapabilities::class.java)
    `when`(wifiCapsUnvalidated.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
    `when`(wifiCapsUnvalidated.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(true)
    `when`(wifiCapsUnvalidated.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(false)
    `when`(wifiCapsUnvalidated.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).thenReturn(true)

    val wifiCapsValidated = mock(NetworkCapabilities::class.java)
    `when`(wifiCapsValidated.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
    `when`(wifiCapsValidated.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)).thenReturn(true)
    `when`(wifiCapsValidated.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
    `when`(wifiCapsValidated.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)).thenReturn(true)

    val dispatched = mutableListOf<Network?>()
    NetworkChangeCallback.setUnderlyingNetworkListener { dispatched.add(it) }

    // Add cellular (validated) and wifi (unvalidated)
    NetworkChangeCallback.updateNetworkForTesting(cellular, caps = cellularCaps)
    NetworkChangeCallback.updateNetworkForTesting(wifi, caps = wifiCapsUnvalidated)

    // Cellular is preferred because it is validated
    assertEquals(cellular, NetworkChangeCallback.cachedDefaultNetwork)
    assertEquals(listOf(cellular), dispatched)

    // Wifi gets validated via capabilities change -> wifi becomes preferred (validated + non-metered)
    NetworkChangeCallback.updateNetworkForTesting(wifi, caps = wifiCapsValidated)
    assertEquals(wifi, NetworkChangeCallback.cachedDefaultNetwork)
    assertEquals(listOf(cellular, wifi), dispatched)
  }

  @Test
  fun startupWithNullCapsDoesNotThrowAndDoesNotSelectUninitializedNetwork() {
    val uninitializedNetwork = mock(Network::class.java)
    val dispatched = mutableListOf<Network?>()
    NetworkChangeCallback.setUnderlyingNetworkListener { dispatched.add(it) }

    // Simulates onAvailable before caps/linkProps arrive (caps = null)
    NetworkChangeCallback.updateNetworkForTesting(uninitializedNetwork, caps = null, linkProps = null)

    assertNull(NetworkChangeCallback.cachedDefaultNetwork)
    assertTrue(dispatched.isEmpty())
  }

  private fun candidate(
      name: String,
      internet: Boolean = true,
      notVpn: Boolean = true,
      validated: Boolean = true,
      hasDns: Boolean = true,
      nonMetered: Boolean = false,
  ) =
      NetworkCandidate(
          value = name,
          internet = internet,
          notVpn = notVpn,
          validated = validated,
          hasDns = hasDns,
          nonMetered = nonMetered,
      )
}
