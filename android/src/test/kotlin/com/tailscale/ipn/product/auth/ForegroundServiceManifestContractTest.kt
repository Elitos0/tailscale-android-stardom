// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceManifestContractTest {
  private val manifestFile = File("src/main/AndroidManifest.xml")

  @Test
  fun manifestDeclaresSpecialUseForegroundServiceAndSubtypeProperty() {
    assertTrue("Manifest file must exist", manifestFile.exists())
    val content = manifestFile.readText()

    assertTrue(
        "Must declare FOREGROUND_SERVICE permission",
        content.contains("android.permission.FOREGROUND_SERVICE\""))
    assertTrue(
        "Must declare FOREGROUND_SERVICE_SPECIAL_USE permission for targetSdk 34+ compliance",
        content.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE\""))
    assertFalse(
        "Must not declare FOREGROUND_SERVICE_SYSTEM_EXEMPTED to prevent targetSdk 36 SecurityException",
        content.contains("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED"))
    assertFalse(
        "Must avoid broad permission escalation (SCHEDULE_EXACT_ALARM)",
        content.contains("SCHEDULE_EXACT_ALARM"))
    assertFalse(
        "Must avoid broad permission escalation (USE_EXACT_ALARM)",
        content.contains("USE_EXACT_ALARM"))

    assertTrue(
        "IPNService must declare foregroundServiceType specialUse",
        content.contains("android:foregroundServiceType=\"specialUse\""))
    assertTrue(
        "IPNService must declare PROPERTY_SPECIAL_USE_FGS_SUBTYPE property",
        content.contains("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"))
  }
}
