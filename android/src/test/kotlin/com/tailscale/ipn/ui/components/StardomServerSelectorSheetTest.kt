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
  fun rowLabelsKeepCountryConstellationAndCoordinatesInEllipsizedDetailsLine() {
    val node =
        StarServerNode(
            id = "exit-local-1",
            starName = "STARDOM-EXIT-LOCAL-1",
            constellation = "ORION-IV",
            city = "Frankfurt",
            countryCode = "DE",
            coordinates = "50.1109° N, 8.6821° E",
            basePingMs = 24,
            loadPercent = 31,
            ipAddress = "100.64.0.1",
        )

    val labels = serverSelectorRowLabels(node)

    assertEquals("STARDOM-EXIT-LOCAL-1 // FRANKFURT", labels.title)
    assertEquals("[DE] • ORION-IV • 50.1109° N, 8.6821° E", labels.details)
  }

  @Test
  fun titleColumnNeverBecomesNegativeForNarrowConstraints() {
    assertEquals(0, serverSelectorTitleColumnWidthDp(100))
  }
}
