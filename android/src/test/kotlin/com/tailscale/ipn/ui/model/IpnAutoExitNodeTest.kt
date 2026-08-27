// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpnAutoExitNodeTest {
  @Test
  fun decodesConfiguredAutoExitNodeSeparatelyFromEffectiveExitNode() {
    val prefs =
        Json.decodeFromString<Ipn.Prefs>(
            "{\"AutoExitNode\":\"any\",\"ExitNodeID\":\"effective-node\"}")

    assertEquals("any", prefs.AutoExitNode)
    assertEquals("effective-node", prefs.activeExitNodeID)
  }

  @Test
  fun encodesNativeAutoExitNodePreferenceWithoutStableNodeId() {
    val encoded =
        Json.encodeToString(Ipn.MaskedPrefs().apply { AutoExitNode = "any" })
            .let(Json::parseToJsonElement)
            .jsonObject

    assertEquals("any", encoded.getValue("AutoExitNode").jsonPrimitive.content)
    assertEquals("true", encoded.getValue("AutoExitNodeSet").jsonPrimitive.content)
    assertNull(encoded["ExitNodeID"])
    assertNull(encoded["ExitNodeIDSet"])
  }

  @Test
  fun deepCopyKeepsConfiguredAutoExitNodeWhenPreparingVpnStart() {
    val copied = Ipn.MaskedPrefs().apply { AutoExitNode = "any" }.deepCopy()

    assertEquals("any", copied.AutoExitNode)
    assertEquals(true, copied.AutoExitNodeSet)
  }

  @Test
  fun encodesMaskedPrefsForAuthKeyLoginWithoutEarlyWantRunning() {
    val prefs =
        Ipn.MaskedPrefs().apply {
          WantRunning = false
          AutoExitNode = "any"
          LoggedOut = false
          ControlURL = "https://headscale.example.com"
        }
    val encoded = Json.encodeToString(prefs).let(Json::parseToJsonElement).jsonObject

    assertEquals("false", encoded.getValue("WantRunning").jsonPrimitive.content)
    assertEquals("true", encoded.getValue("WantRunningSet").jsonPrimitive.content)
    assertEquals("false", encoded.getValue("LoggedOut").jsonPrimitive.content)
    assertEquals("true", encoded.getValue("LoggedOutSet").jsonPrimitive.content)
    assertEquals("any", encoded.getValue("AutoExitNode").jsonPrimitive.content)
    assertEquals(
        "https://headscale.example.com", encoded.getValue("ControlURL").jsonPrimitive.content)
  }
}
