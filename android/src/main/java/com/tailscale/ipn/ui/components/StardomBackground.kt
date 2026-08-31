// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.model.VpnState
import com.tailscale.ipn.ui.theme.StardomColors

private data class StarNode(
    val relX: Float,
    val relY: Float,
    val size: Float,
    val alpha: Float,
    val isSquare: Boolean = false
)

@Composable
fun StardomBackground(vpnState: VpnState = VpnState.DISCONNECTED, modifier: Modifier = Modifier) {
  val infiniteTransition = rememberInfiniteTransition(label = "stardom_background_anim")

  val celestialRotation by
      infiniteTransition.animateFloat(
          initialValue = 0f,
          targetValue = 360f,
          animationSpec =
              infiniteRepeatable(
                  animation =
                      tween(
                          durationMillis = if (vpnState.isConnecting) 30000 else 120000,
                          easing = LinearEasing),
                  repeatMode = RepeatMode.Restart),
          label = "celestial_orbit")

  // Curated deterministic constellations
  val stars = remember {
    listOf(
        // Primary Constellation (Upper Right between Header and Orbit)
        StarNode(0.70f, 0.17f, 1.8f, 0.70f),
        StarNode(0.79f, 0.20f, 1.5f, 0.65f),
        StarNode(0.68f, 0.25f, 1.6f, 0.75f),
        StarNode(0.88f, 0.27f, 1.4f, 0.60f),
        StarNode(0.93f, 0.22f, 3.2f, 0.80f, isSquare = true),

        // Secondary Constellation (Mid-Left of Orbit)
        StarNode(0.12f, 0.36f, 1.6f, 0.65f),
        StarNode(0.20f, 0.32f, 1.4f, 0.55f),
        StarNode(0.15f, 0.44f, 1.8f, 0.70f),

        // Lower Constellation (Lower-Left below Orbit)
        StarNode(0.10f, 0.68f, 1.6f, 0.60f),
        StarNode(0.22f, 0.72f, 1.8f, 0.70f),
        StarNode(0.16f, 0.80f, 1.4f, 0.55f),

        // Faint accent node (Lower-Right)
        StarNode(0.86f, 0.74f, 1.5f, 0.55f),
        StarNode(0.92f, 0.82f, 1.4f, 0.50f))
  }

  val constellationLines = remember {
    listOf(
        // Upper Right
        Pair(0, 1),
        Pair(1, 2),
        Pair(1, 3),
        Pair(3, 4),
        // Mid Left
        Pair(5, 6),
        Pair(6, 7),
        // Lower Left
        Pair(8, 9),
        Pair(9, 10),
        // Lower Right
        Pair(11, 12))
  }

  Canvas(modifier = modifier.fillMaxSize().background(StardomColors.Background)) {
    val columns = 6
    val rows = 11

    val columnWidth = size.width / columns
    val rowHeight = size.height / rows

    /*
     * TECHNICAL GRID (1px hairline, alpha ~0.65)
     */
    for (column in 0..columns) {
      val x = column * columnWidth
      drawLine(
          color = StardomColors.Grid.copy(alpha = 0.65f),
          start = Offset(x, 0f),
          end = Offset(x, size.height),
          strokeWidth = 1f)
    }

    for (row in 0..rows) {
      val y = row * rowHeight
      drawLine(
          color = StardomColors.Grid.copy(alpha = 0.65f),
          start = Offset(0f, y),
          end = Offset(size.width, y),
          strokeWidth = 1f)
    }

    /*
     * SUBTLE INTERSECTION CROSS MARKERS (+)
     */
    val crossArm = 3.dp.toPx()
    val crossColor = StardomColors.BorderFaint.copy(alpha = 0.40f)

    for (c in 1 until columns) {
      for (r in 1 until rows) {
        val cx = c * columnWidth
        val cy = r * rowHeight

        // Draw selective subtle cross markers
        if ((c + r) % 2 == 0) {
          drawLine(
              color = crossColor,
              start = Offset(cx - crossArm, cy),
              end = Offset(cx + crossArm, cy),
              strokeWidth = 1f)
          drawLine(
              color = crossColor,
              start = Offset(cx, cy - crossArm),
              end = Offset(cx, cy + crossArm),
              strokeWidth = 1f)
        }
      }
    }

    /*
     * CONSTELLATION DRIFT (subtle 1px lines and points)
     */
    val center = Offset(size.width / 2f, size.height / 2f)

    withTransform({ rotate(degrees = celestialRotation, pivot = center) }) {
      // Lines
      for (pair in constellationLines) {
        if (pair.first < stars.size && pair.second < stars.size) {
          val s1 = stars[pair.first]
          val s2 = stars[pair.second]
          drawLine(
              color = StardomColors.BorderStrong.copy(alpha = 0.32f),
              start = Offset(s1.relX * size.width, s1.relY * size.height),
              end = Offset(s2.relX * size.width, s2.relY * size.height),
              strokeWidth = 1f)
        }
      }

      // Star nodes
      for (star in stars) {
        val pos = Offset(star.relX * size.width, star.relY * size.height)
        if (star.isSquare) {
          val sq = star.size.dp.toPx()
          drawRect(
              color = StardomColors.TextPrimary.copy(alpha = star.alpha),
              topLeft = Offset(pos.x - sq / 2, pos.y - sq / 2),
              size = Size(sq, sq))
        } else {
          drawCircle(
              color = StardomColors.TextSecondary.copy(alpha = star.alpha),
              radius = star.size.dp.toPx() / 2,
              center = pos)
        }
      }
    }
  }
}
