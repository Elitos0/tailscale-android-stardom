// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StardomProfileViewTest {

  @Test
  fun formatValidUntilDate_handlesDottedFormat() {
    assertEquals("31.12.2028", formatValidUntilDate("2028.12.31"))
    assertEquals("15.09.2026", formatValidUntilDate("2026.09.15"))
  }

  @Test
  fun formatValidUntilDate_handlesDashedFormat() {
    assertEquals("31.12.2028", formatValidUntilDate("2028-12-31"))
    assertEquals("01.01.2027", formatValidUntilDate("2027-01-01"))
  }

  @Test
  fun formatValidUntilDate_handlesIsoTimestamp() {
    assertEquals("31.12.2028", formatValidUntilDate("2028-12-31T23:59:59Z"))
    assertEquals("15.09.2026", formatValidUntilDate("2026-09-15T12:00:00+00:00"))
  }

  @Test
  fun formatValidUntilDate_handlesExistingFormattedDate() {
    assertEquals("31.12.2028", formatValidUntilDate("31.12.2028"))
  }

  @Test
  fun formatValidUntilDate_handlesNullOrBlank() {
    assertEquals("31.12.2028", formatValidUntilDate(null))
    assertEquals("31.12.2028", formatValidUntilDate(""))
    assertEquals("31.12.2028", formatValidUntilDate("   "))
    assertEquals("31.12.2028", formatValidUntilDate("invalid-date"))
  }
}
