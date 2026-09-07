// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import com.tailscale.ipn.ui.model.VpnState
import com.tailscale.ipn.ui.theme.StardomColors
import org.junit.Assert.assertEquals
import org.junit.Test

class StardomOrbitControlTest {
  @Test
  fun settlingAddsOneForwardQuarterTurnAfterTheExistingTargetAlignment() {
    assertEquals(135f, orbitTargetAlignment(90f), 0f)

    val plan = orbitSettlingPlan(90f)

    assertEquals(225f, plan.targetRotation, 0f)
    assertEquals(2700, plan.durationMillis)
    assertEquals(0f, plan.targetRotation % 45f, 0f)
  }

  @Test
  fun settlingFromAnExactTargetKeepsTheExistingForwardAlignmentRule() {
    assertEquals(45f, orbitTargetAlignment(0f), 0f)

    val plan = orbitSettlingPlan(0f)

    assertEquals(135f, plan.targetRotation, 0f)
    assertEquals(2700, plan.durationMillis)
    assertEquals(0f, plan.targetRotation % 45f, 0f)
  }

  @Test
  fun settlingWrapsForwardFromJustBeforeZeroDegrees() {
    assertEquals(360f, orbitTargetAlignment(359f), 0f)

    val plan = orbitSettlingPlan(359f)

    assertEquals(450f, plan.targetRotation, 0f)
    assertEquals(1820, plan.durationMillis)
    assertEquals(0f, plan.targetRotation % 45f, 0f)
  }

  @Test
  fun settlingWrapsForwardFromJustAfterZeroDegrees() {
    assertEquals(45f, orbitTargetAlignment(0.1f), 0.001f)

    val plan = orbitSettlingPlan(0.1f)

    assertEquals(135f, plan.targetRotation, 0.001f)
    assertEquals(2697, plan.durationMillis)
    assertEquals(0f, plan.targetRotation % 45f, 0.001f)
  }

  @Test
  fun settlingPreservesTheExactTargetRuleForNegativeRotation() {
    assertEquals(45f, orbitTargetAlignment(-1f), 0f)

    val plan = orbitSettlingPlan(-1f)

    assertEquals(135f, plan.targetRotation, 0f)
    assertEquals(2720, plan.durationMillis)
    assertEquals(0f, plan.targetRotation % 45f, 0f)
  }

  @Test
  fun rotationDurationUsesConnectingSpeedForEveryConnectingState() {
    for (state in
        listOf(
            VpnState.RESOLVING_STAR_ROUTE,
            VpnState.HANDSHAKING_CIPHER,
            VpnState.AUTHENTICATING_NODE)) {
      assertEquals(4000, orbitRotationDurationMillis(state))
    }
  }

  @Test
  fun rotationDurationUsesIdleSpeedForEveryOtherState() {
    for (state in VpnState.entries.filterNot { it.isConnecting }) {
      assertEquals(28000, orbitRotationDurationMillis(state))
    }
  }

  @Test
  fun illuminationOnlyAppliesWhenConnectedWhileDisconnectedRetainsStaticOutlines() {
    // Disconnected state: normal static outlines
    assertEquals(StardomColors.Border, orbitSecondaryCircleColor(VpnState.DISCONNECTED))
    assertEquals(StardomColors.Border, orbitDiamondColor(VpnState.DISCONNECTED))
    assertEquals(StardomColors.BorderStrong, orbitOrbitalElementColor(VpnState.DISCONNECTED))
    assertEquals(StardomColors.BorderStrong, orbitButtonBorderColor(VpnState.DISCONNECTED))
    assertEquals(StardomColors.TextSecondary, orbitButtonContentColor(VpnState.DISCONNECTED))

    // Connecting states: retain static outlines while rotating
    for (state in
        listOf(
            VpnState.RESOLVING_STAR_ROUTE,
            VpnState.HANDSHAKING_CIPHER,
            VpnState.AUTHENTICATING_NODE)) {
      assertEquals(StardomColors.Border, orbitSecondaryCircleColor(state))
      assertEquals(StardomColors.Border, orbitDiamondColor(state))
      assertEquals(StardomColors.BorderStrong, orbitOrbitalElementColor(state))
      assertEquals(StardomColors.BorderStrong, orbitButtonBorderColor(state))
      assertEquals(StardomColors.TextSecondary, orbitButtonContentColor(state))
    }

    // Connected (SECURED) state: visibly luminous elements
    assertEquals(StardomColors.BorderStrong, orbitSecondaryCircleColor(VpnState.SECURED))
    assertEquals(StardomColors.BorderStrong, orbitDiamondColor(VpnState.SECURED))
    assertEquals(StardomColors.TextPrimary, orbitOrbitalElementColor(VpnState.SECURED))
    assertEquals(StardomColors.TextPrimary, orbitButtonBorderColor(VpnState.SECURED))
    assertEquals(StardomColors.TextPrimary, orbitButtonContentColor(VpnState.SECURED))

    // Error state: error highlights
    assertEquals(StardomColors.ErrorBorder, orbitSecondaryCircleColor(VpnState.ERROR))
    assertEquals(StardomColors.Error, orbitDiamondColor(VpnState.ERROR))
    assertEquals(StardomColors.Error, orbitOrbitalElementColor(VpnState.ERROR))
    assertEquals(StardomColors.Error, orbitButtonBorderColor(VpnState.ERROR))
    assertEquals(StardomColors.Error, orbitButtonContentColor(VpnState.ERROR))
  }
}
