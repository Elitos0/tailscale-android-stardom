// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.ConnectionMode
import com.tailscale.ipn.ui.model.StarServerNode
import org.junit.Assert.assertEquals
import org.junit.Test

class StardomRoutingPanelTest {
  @Test
  fun routingNodeTitleCapitalizesLowercaseNodeNameInAutoMode() {
    val server =
        StarServerNode(
            id = "finland",
            label = "finland",
            city = "helsinki",
            countryCode = "FI",
            country = "Finland")

    val titleEn = routingNodeTitle(ConnectionMode.AUTO, server, AppLanguage.EN)
    val titleRu = routingNodeTitle(ConnectionMode.AUTO, server, AppLanguage.RU)

    assertEquals("AUTO / Finland", titleEn)
    assertEquals("AUTO / Finland", titleRu)
  }

  @Test
  fun routingNodeTitleCapitalizesLowercaseNodeNameWithCityInManualMode() {
    val server =
        StarServerNode(
            id = "finland",
            label = "finland",
            city = "helsinki",
            countryCode = "FI",
            country = "Finland")

    val title = routingNodeTitle(ConnectionMode.MANUAL, server, AppLanguage.RU)

    assertEquals("Finland: HELSINKI", title)
  }

  @Test
  fun routingNodeTitleCapitalizesLowercaseNodeNameWithoutCityInManualMode() {
    val server =
        StarServerNode(
            id = "finland",
            label = "finland",
            city = "",
            countryCode = "FI",
            country = "Finland")

    val title = routingNodeTitle(ConnectionMode.MANUAL, server, AppLanguage.RU)

    assertEquals("Finland", title)
  }

  @Test
  fun routingNodeTitlePreservesAlreadyCapitalizedNodeName() {
    val server =
        StarServerNode(
            id = "node-1",
            label = "Germany 1",
            city = "Frankfurt",
            countryCode = "DE",
            country = "Germany")

    val title = routingNodeTitle(ConnectionMode.MANUAL, server, AppLanguage.EN)

    assertEquals("Germany 1: FRANKFURT", title)
  }

  @Test
  fun routingNodeTitleHandlesNullActiveServer() {
    assertEquals(
        "AUTO / ОЖИДАНИЕ УЗЛА",
        routingNodeTitle(ConnectionMode.AUTO, null, AppLanguage.RU))
    assertEquals(
        "AUTO / NODE PENDING",
        routingNodeTitle(ConnectionMode.AUTO, null, AppLanguage.EN))
    assertEquals(
        "УЗЕЛ НЕ ВЫБРАН",
        routingNodeTitle(ConnectionMode.MANUAL, null, AppLanguage.RU))
    assertEquals(
        "NO NODE SELECTED",
        routingNodeTitle(ConnectionMode.MANUAL, null, AppLanguage.EN))
  }
}
