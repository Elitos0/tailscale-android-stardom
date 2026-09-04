// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import com.tailscale.ipn.ui.model.StarServerNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StardomServerSelectorSheetTest {
  @Test
  fun longNodeLabelsKeepTelemetryColumnReservedAtPhoneWidth() {
    val itemWidthDp = 360 - (20 * 2)

    assertEquals(2, SERVER_SELECTOR_TITLE_MAX_LINES)
    assertEquals(1, SERVER_SELECTOR_DETAILS_MAX_LINES)
    assertEquals(76, SERVER_SELECTOR_TELEMETRY_COLUMN_WIDTH_DP)
    assertEquals(204, serverSelectorTitleColumnWidthDp(itemWidthDp))
    assertTrue(serverSelectorTitleColumnWidthDp(itemWidthDp) > 0)
  }

  @Test
  fun rowLabelsUseOnlyLiveLocationMetadata() {
    val node =
        StarServerNode(
            id = "exit-local-1",
            label = "Germany 1",
            city = "Frankfurt",
            countryCode = "DE",
            country = "Germany")

    val labels = serverSelectorRowLabels(node)

    assertEquals("Germany 1 // FRANKFURT", labels.title)
    assertEquals("DE • Germany", labels.details)
  }

  @Test
  fun rowLabelsShowNoInventedLocationWhenMetadataIsMissing() {
    val node =
        StarServerNode(
            id = "exit-local-1", label = "Germany 1", city = "", countryCode = "", country = "")

    val labels = serverSelectorRowLabels(node)

    assertEquals("Germany 1", labels.title)
    assertEquals("—", labels.details)
  }

  @Test
  fun titleColumnNeverBecomesNegativeForNarrowConstraints() {
    assertEquals(0, serverSelectorTitleColumnWidthDp(100))
  }
}
