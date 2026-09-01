// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import com.tailscale.ipn.ui.model.VpnState
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
}
