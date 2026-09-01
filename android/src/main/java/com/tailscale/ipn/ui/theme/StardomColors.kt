// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.theme

import androidx.compose.ui.graphics.Color
import com.tailscale.ipn.ui.model.VpnState

object StardomColors {
  // ABSOLUTE BASE
  val Background = Color(0xFF050505)

  // Very slight elevation from background, strictly no gray mist
  val Panel = Color(0xFF070707)
  val PanelSelected = Color(0xFF090909)

  // Lines
  val Grid = Color(0xFF151719)

  val BorderFaint = Color(0xFF202225)
  val Border = Color(0xFF303235)
  val BorderStrong = Color(0xFF606367)

  // Text
  val TextPrimary = Color(0xFFEEEEEC)
  val TextSecondary = Color(0xFF85888A)
  val TextMuted = Color(0xFF55575A)

  // Selected indicator (solid off-white instead of neon)
  val Selected = Color(0xFFE7E7E4)

  // Error / Warning
  val Error = Color(0xFFEF5350)
  val ErrorBorder = Color(0xFF940822)

  // Secured state accent: technical blue, deliberately flat and low-saturation.
  val Secured = Color(0xFF63B8D8)
  val SecuredBorder = Color(0xFF327A94)

  fun stateAccent(vpnState: VpnState, isError: Boolean? = null, border: Boolean = false): Color =
      when {
        isError ?: vpnState.isError -> if (border) ErrorBorder else Error
        vpnState.isConnected -> if (border) SecuredBorder else Secured
        else -> if (border) BorderStrong else TextPrimary
      }
}
