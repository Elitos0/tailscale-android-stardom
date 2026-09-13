// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.ui.model.AccountProfile
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable
fun StardomProfileView(
    profile: AccountProfile,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
  val isActive = !profile.isStub

  Column(
      modifier = modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Title
    Text(
        text = "ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ",
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 3.sp,
        color = StardomColors.TextPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(4.dp))

    // Subtitle
    Text(
        text = "АККАУНТ // ДОСТУП // УСТРОЙСТВА",
        fontFamily = IbmPlexMono,
        fontSize = 9.sp,
        letterSpacing = 2.sp,
        color = StardomColors.TextMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Card 01 - IDENTITY
    StardomTechnicalPanel(
        panelNumber = "01",
        title = "IDENTITY",
        tag = "// АККАУНТ",
        icon = {
          Icon(
              imageVector = Icons.Outlined.Person,
              contentDescription = "Identity",
              tint = StardomColors.TextPrimary,
              modifier = Modifier.size(24.dp)
          )
        }
    ) {
      Text(
          text = "SIGNED IN AS",
          fontFamily = IbmPlexMono,
          fontSize = 8.sp,
          letterSpacing = 1.5.sp,
          color = StardomColors.TextMuted
      )

      Spacer(modifier = Modifier.height(2.dp))

      Text(
          text = profile.accountId.ifEmpty { "UNKNOWN" },
          fontFamily = SpaceGrotesk,
          fontWeight = FontWeight.Medium,
          fontSize = 16.sp,
          color = StardomColors.TextPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(2.dp))

      Text(
          text = if (isActive) "ACCOUNT // ACTIVE" else "ACCOUNT // OFFLINE",
          fontFamily = IbmPlexMono,
          fontSize = 9.sp,
          letterSpacing = 1.sp,
          color = if (isActive) StardomColors.TextSecondary else StardomColors.TextMuted
      )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Card 02 - ACCESS PLAN
    StardomTechnicalPanel(
        panelNumber = "02",
        title = "ACCESS PLAN",
        tag = "// ТАРИФНЫЙ ПЛАН",
        icon = {
          SparkleStarIcon(
              modifier = Modifier.size(24.dp),
              tint = StardomColors.TextPrimary
          )
        }
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
            text = "ORBITAL APEX",
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 1.sp,
            color = StardomColors.TextPrimary
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
              modifier =
                  Modifier.size(6.dp)
                      .background(
                          color = if (isActive) StardomColors.Selected else StardomColors.TextMuted,
                          shape = CircleShape
                      )
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
              text = if (isActive) "ACTIVE" else "OFFLINE",
              fontFamily = IbmPlexMono,
              fontSize = 10.sp,
              letterSpacing = 1.5.sp,
              color = if (isActive) StardomColors.TextPrimary else StardomColors.TextMuted
          )
        }
      }

      Spacer(modifier = Modifier.height(2.dp))

      Text(
          text = "BETA PLAN",
          fontFamily = IbmPlexMono,
          fontSize = 9.sp,
          letterSpacing = 1.5.sp,
          color = StardomColors.TextSecondary
      )

      Spacer(modifier = Modifier.height(8.dp))
      Divider(thickness = 1.dp, color = StardomColors.BorderFaint)
      Spacer(modifier = Modifier.height(8.dp))

      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
            text = "VALID UNTIL",
            fontFamily = IbmPlexMono,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = StardomColors.TextMuted
        )

        Text(
            text = formatValidUntilDate(profile.validUntil),
            fontFamily = IbmPlexMono,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = StardomColors.TextPrimary
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Card 03 - DEVICES
    StardomTechnicalPanel(
        panelNumber = "03",
        title = "DEVICES",
        tag = "// УСТРОЙСТВА",
        icon = {
          MonitorDeviceIcon(
              modifier = Modifier.size(24.dp),
              tint = StardomColors.TextPrimary
          )
        }
    ) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
            text = "COMING SOON",
            fontFamily = IbmPlexMono,
            fontWeight = FontWeight.Medium,
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = StardomColors.TextSecondary
        )
      }

      Spacer(modifier = Modifier.height(2.dp))

      Text(
          text = "MANAGEMENT // IN DEVELOPMENT",
          fontFamily = IbmPlexMono,
          fontSize = 8.sp,
          letterSpacing = 1.sp,
          color = StardomColors.TextMuted
      )
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Bottom CLOSE Button
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(48.dp)
                .background(StardomColors.Background)
                .border(width = 1.dp, color = StardomColors.BorderStrong)
                .clickable { onClose() },
        contentAlignment = Alignment.Center
    ) {
      Text(
          text = "CLOSE  →",
          fontFamily = SpaceGrotesk,
          fontWeight = FontWeight.Medium,
          fontSize = 12.sp,
          letterSpacing = 2.sp,
          color = StardomColors.TextPrimary
      )
    }
  }
}

@Composable
fun StardomTechnicalPanel(
    panelNumber: String,
    title: String,
    tag: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
  Box(
      modifier =
          modifier
              .fillMaxWidth()
              .background(StardomColors.Panel)
              .border(width = 1.dp, color = StardomColors.Border)
              .drawCornerTickBrackets(
                  tickLength = 6.dp,
                  tickThickness = 1.5.dp,
                  tickColor = StardomColors.BorderStrong
              )
              .padding(16.dp)
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      // Header Row
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
            text = panelNumber,
            fontFamily = IbmPlexMono,
            fontSize = 10.sp,
            color = StardomColors.TextMuted
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = title,
            fontFamily = SpaceGrotesk,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            color = StardomColors.TextPrimary
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = tag,
            fontFamily = IbmPlexMono,
            fontSize = 9.sp,
            letterSpacing = 1.sp,
            color = StardomColors.TextMuted
        )
      }

      Spacer(modifier = Modifier.height(10.dp))
      Divider(thickness = 1.dp, color = StardomColors.BorderFaint)
      Spacer(modifier = Modifier.height(14.dp))

      // Body Row
      Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically
      ) {
        // Left Icon Box
        Box(
            modifier =
                Modifier.size(52.dp)
                    .border(width = 1.dp, color = StardomColors.BorderFaint)
                    .drawCornerTickBrackets(
                        tickLength = 6.dp,
                        tickThickness = 1.5.dp,
                        tickColor = StardomColors.BorderStrong
                    ),
            contentAlignment = Alignment.Center
        ) {
          icon()
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Right Content Column
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
          content()
        }
      }
    }
  }
}

/**
 * Draws corner tick brackets on all 4 corners of a box.
 */
fun Modifier.drawCornerTickBrackets(
    tickLength: Dp = 6.dp,
    tickThickness: Dp = 1.5.dp,
    tickColor: Color = StardomColors.BorderStrong
): Modifier = drawBehind {
  val len = tickLength.toPx()
  val stroke = tickThickness.toPx()
  val halfStroke = stroke / 2f
  val w = size.width
  val h = size.height

  // Top-left
  drawLine(
      color = tickColor,
      start = Offset(0f, halfStroke),
      end = Offset(len, halfStroke),
      strokeWidth = stroke
  )
  drawLine(
      color = tickColor,
      start = Offset(halfStroke, 0f),
      end = Offset(halfStroke, len),
      strokeWidth = stroke
  )

  // Top-right
  drawLine(
      color = tickColor,
      start = Offset(w - len, halfStroke),
      end = Offset(w, halfStroke),
      strokeWidth = stroke
  )
  drawLine(
      color = tickColor,
      start = Offset(w - halfStroke, 0f),
      end = Offset(w - halfStroke, len),
      strokeWidth = stroke
  )

  // Bottom-left
  drawLine(
      color = tickColor,
      start = Offset(0f, h - halfStroke),
      end = Offset(len, h - halfStroke),
      strokeWidth = stroke
  )
  drawLine(
      color = tickColor,
      start = Offset(halfStroke, h - len),
      end = Offset(halfStroke, h),
      strokeWidth = stroke
  )

  // Bottom-right
  drawLine(
      color = tickColor,
      start = Offset(w - len, h - halfStroke),
      end = Offset(w, h - halfStroke),
      strokeWidth = stroke
  )
  drawLine(
      color = tickColor,
      start = Offset(w - halfStroke, h - len),
      end = Offset(w - halfStroke, h),
      strokeWidth = stroke
  )
}

/**
 * 4-pointed diamond sparkle star icon drawn on Canvas with curved edges.
 */
@Composable
fun SparkleStarIcon(
    modifier: Modifier = Modifier,
    tint: Color = StardomColors.TextPrimary
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f

    val path = Path().apply {
      moveTo(cx, 0f)
      quadraticBezierTo(cx, cy, w, cy)
      quadraticBezierTo(cx, cy, cx, h)
      quadraticBezierTo(cx, cy, 0f, cy)
      quadraticBezierTo(cx, cy, cx, 0f)
      close()
    }

    drawPath(
        path = path,
        color = tint,
        style = Stroke(width = 1.5.dp.toPx())
    )
  }
}

/**
 * Line-art monitor and device icon drawn on Canvas.
 */
@Composable
fun MonitorDeviceIcon(
    modifier: Modifier = Modifier,
    tint: Color = StardomColors.TextPrimary
) {
  Canvas(modifier = modifier) {
    val w = size.width
    val h = size.height
    val strokeWidth = 1.5.dp.toPx()

    // Screen frame
    val screenLeft = w * 0.12f
    val screenTop = h * 0.15f
    val screenRight = w * 0.88f
    val screenBottom = h * 0.65f

    drawRect(
        color = tint,
        topLeft = Offset(screenLeft, screenTop),
        size = Size(screenRight - screenLeft, screenBottom - screenTop),
        style = Stroke(width = strokeWidth)
    )

    // Screen stand pole
    val standX = w * 0.5f
    drawLine(
        color = tint,
        start = Offset(standX, screenBottom),
        end = Offset(standX, h * 0.82f),
        strokeWidth = strokeWidth
    )

    // Screen stand base
    drawLine(
        color = tint,
        start = Offset(w * 0.32f, h * 0.82f),
        end = Offset(w * 0.68f, h * 0.82f),
        strokeWidth = strokeWidth
    )

    // Inner subtle display line
    drawLine(
        color = tint.copy(alpha = 0.5f),
        start = Offset(screenLeft + 4.dp.toPx(), screenTop + (screenBottom - screenTop) * 0.5f),
        end = Offset(screenRight - 4.dp.toPx(), screenTop + (screenBottom - screenTop) * 0.5f),
        strokeWidth = 1.dp.toPx()
    )
  }
}

/**
 * Formats validUntil string to DD.MM.YYYY.
 * Accepts:
 * - "YYYY.MM.DD" -> "DD.MM.YYYY"
 * - "YYYY-MM-DD" -> "DD.MM.YYYY"
 * - ISO-8601 timestamps (e.g. "2028-12-31T23:59:59Z")
 * - Fallback: "31.12.2028"
 */
internal fun formatValidUntilDate(validUntil: String?): String {
  if (validUntil.isNullOrBlank()) {
    return "31.12.2028"
  }

  val trimmed = validUntil.trim()

  // Match YYYY.MM.DD
  val dotRegex = Regex("""^(\d{4})\.(\d{2})\.(\d{2})$""")
  dotRegex.matchEntire(trimmed)?.let { match ->
    val (year, month, day) = match.destructured
    return "$day.$month.$year"
  }

  // Match YYYY-MM-DD
  val dashRegex = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
  dashRegex.matchEntire(trimmed)?.let { match ->
    val (year, month, day) = match.destructured
    return "$day.$month.$year"
  }

  // Try parsing ISO timestamp
  try {
    val instant =
        try {
          OffsetDateTime.parse(trimmed).toInstant()
        } catch (_: Exception) {
          Instant.parse(trimmed)
        }
    val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(java.time.ZoneId.of("UTC"))
    return formatter.format(instant)
  } catch (_: Exception) {
    // If it already looks like DD.MM.YYYY
    val ddmmyyyyRegex = Regex("""^\d{2}\.\d{2}\.\d{4}$""")
    if (ddmmyyyyRegex.matches(trimmed)) {
      return trimmed
    }
  }

  return "31.12.2028"
}
