// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.theme

import com.tailscale.ipn.ui.model.AppLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StardomTypographyTest {
  @Test
  fun russianWithBundledResourceUsesRussoOne() {
    assertTrue(shouldUseRussoOne(AppLanguage.RU, resourceAvailable = true))
  }

  @Test
  fun englishKeepsIbmPlexMono() {
    assertFalse(shouldUseRussoOne(AppLanguage.EN, resourceAvailable = true))
  }

  @Test
  fun missingResourceFallsBackToIbmPlexMono() {
    assertFalse(shouldUseRussoOne(AppLanguage.RU, resourceAvailable = false))
    assertFalse(shouldUseRussoOne(AppLanguage.EN, resourceAvailable = false))
  }
}
