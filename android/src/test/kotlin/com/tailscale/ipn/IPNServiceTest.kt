// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IPNServiceTest {
  private val vpnPkg = "com.stardom.vpn"
  private val builtIn = listOf("com.google.android.apps.messaging", "com.google.android.projection.gearhead")

  @Test
  fun includeModeAddsVpnPackageName() {
    val result = packagesForVpnBuilder(
        packagesList = listOf("com.android.chrome"),
        allowPackages = true,
        vpnPackageName = vpnPkg,
        builtInDisallowedPackages = builtIn
    )

    assertEquals(listOf("com.android.chrome", vpnPkg), result)
    assertTrue(result.contains(vpnPkg))
    assertTrue(result.contains("com.android.chrome"))
  }

  @Test
  fun includeModeEmptyListStaysEmpty() {
    val result = packagesForVpnBuilder(
        packagesList = emptyList(),
        allowPackages = true,
        vpnPackageName = vpnPkg,
        builtInDisallowedPackages = builtIn
    )

    assertTrue(result.isEmpty())
    assertEquals(emptyList<String>(), result)
  }

  @Test
  fun excludeModeIncludesBuiltInDisallowedPackages() {
    val result = packagesForVpnBuilder(
        packagesList = listOf("com.example.browser"),
        allowPackages = false,
        vpnPackageName = vpnPkg,
        builtInDisallowedPackages = builtIn
    )

    assertEquals(listOf("com.example.browser", "com.google.android.apps.messaging", "com.google.android.projection.gearhead"), result)
    assertTrue(result.contains("com.example.browser"))
    assertTrue(result.containsAll(builtIn))
  }

  @Test
  fun excludeModeNeverContainsVpnPackageName() {
    val resultWithVpnInUserList = packagesForVpnBuilder(
        packagesList = listOf("com.example.app", vpnPkg),
        allowPackages = false,
        vpnPackageName = vpnPkg,
        builtInDisallowedPackages = builtIn
    )

    assertFalse("Exclude mode must never contain vpnPackageName", resultWithVpnInUserList.contains(vpnPkg))
    assertEquals(listOf("com.example.app", "com.google.android.apps.messaging", "com.google.android.projection.gearhead"), resultWithVpnInUserList)

    val resultWithVpnInBuiltIn = packagesForVpnBuilder(
        packagesList = listOf("com.example.app"),
        allowPackages = false,
        vpnPackageName = vpnPkg,
        builtInDisallowedPackages = builtIn + vpnPkg
    )

    assertFalse("Exclude mode must never contain vpnPackageName even if in builtIn list", resultWithVpnInBuiltIn.contains(vpnPkg))
    assertEquals(listOf("com.example.app", "com.google.android.apps.messaging", "com.google.android.projection.gearhead"), resultWithVpnInBuiltIn)
  }

  @Test
  fun deduplicationWorksInBothModes() {
    // Include mode deduplication
    val includeResult = packagesForVpnBuilder(
        packagesList = listOf("com.android.chrome", vpnPkg, "com.android.chrome"),
        allowPackages = true,
        vpnPackageName = vpnPkg,
        builtInDisallowedPackages = builtIn
    )
    assertEquals(listOf("com.android.chrome", vpnPkg), includeResult)

    // Exclude mode deduplication
    val excludeResult = packagesForVpnBuilder(
        packagesList = listOf("com.google.android.apps.messaging", "com.example.app", "com.example.app"),
        allowPackages = false,
        vpnPackageName = vpnPkg,
        builtInDisallowedPackages = builtIn
    )
    assertEquals(listOf("com.google.android.apps.messaging", "com.example.app", "com.google.android.projection.gearhead"), excludeResult)
  }
}
