// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.ConnectionMode
import com.tailscale.ipn.ui.model.StarServerNode
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors
import com.tailscale.ipn.ui.theme.StardomDimensions

@Composable
fun StardomRoutingPanel(
    connectionMode: ConnectionMode,
    activeServer: StarServerNode?,
    onRoutingModeChange: (ConnectionMode) -> Unit,
    onNodeClick: () -> Unit,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.RU
) {
  Column(
      modifier =
          modifier
              .fillMaxWidth()
              .background(StardomColors.Panel)
              .border(width = 1.dp, color = StardomColors.Border)
              .padding(
                  horizontal = StardomDimensions.PanelPaddingHorizontal,
                  vertical = StardomDimensions.PanelPaddingVertical)) {
        /*
         * ROUTING SECTION HEADER
         */
        Text(
            text = if (language == AppLanguage.RU) "МАРШРУТИЗАЦИЯ" else "ROUTING",
            color = StardomColors.TextSecondary,
            fontFamily = IbmPlexMono,
            fontSize = 9.sp,
            letterSpacing = 2.sp)

        Spacer(Modifier.height(8.dp))

        /*
         * AUTO / MANUAL SELECTORS (50 / 50 unified container)
         */
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(60.dp)
                    .border(width = 1.dp, color = StardomColors.BorderStrong)) {
              RoutingModeCell(
                  title = "AUTO",
                  subtitle = if (language == AppLanguage.RU) "НИЗКИЙ ПИНГ" else "LOWEST LATENCY",
                  selected = connectionMode == ConnectionMode.AUTO,
                  testTag = "mode_tab_auto",
                  modifier = Modifier.weight(1f),
                  onClick = { onRoutingModeChange(ConnectionMode.AUTO) })

              /*
               * CENTRAL DIVIDER
               */
              Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(StardomColors.Border))

              RoutingModeCell(
                  title = "MANUAL",
                  subtitle = if (language == AppLanguage.RU) "ВЫБОР УЗЛА" else "SELECT NODE",
                  selected = connectionMode == ConnectionMode.MANUAL,
                  testTag = "mode_tab_manual",
                  modifier = Modifier.weight(1f),
                  onClick = { onRoutingModeChange(ConnectionMode.MANUAL) })
            }

        Spacer(Modifier.height(10.dp))

        /*
         * HAIRLINE DIVIDER (1px)
         */
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(StardomColors.Border))

        Spacer(Modifier.height(13.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
              Text(
                  text = if (language == AppLanguage.RU) "АКТИВНЫЙ УЗЕЛ" else "ACTIVE NODE",
                  color = StardomColors.TextSecondary,
                  fontFamily = IbmPlexMono,
                  fontSize = 9.sp,
                  letterSpacing = 1.8.sp)

              Text(
                  text = "—",
                  color = StardomColors.TextSecondary,
                  fontFamily = IbmPlexMono,
                  fontSize = 9.sp,
                  letterSpacing = 1.5.sp)
            }

        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(StardomColors.Border))

        Spacer(Modifier.height(9.dp))

        Row(
            modifier =
                Modifier.fillMaxWidth().testTag("active_server_card").clickable(
                    onClickLabel = "Select node") {
                      onNodeClick()
                    },
            verticalAlignment = Alignment.CenterVertically) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = routingNodeTitle(connectionMode, activeServer, language),
                    color = StardomColors.TextPrimary,
                    fontFamily = SpaceGrotesk,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 18.sp,
                    letterSpacing = 1.2.sp)

                Spacer(Modifier.height(3.dp))

                Text(
                    text = routingNodeDetails(activeServer, language),
                    color = StardomColors.TextSecondary,
                    fontFamily = IbmPlexMono,
                    fontSize = 9.sp,
                    lineHeight = 10.sp,
                    letterSpacing = 1.2.sp)
              }

              Spacer(Modifier.width(8.dp))

              Box(
                  modifier =
                      Modifier.size(36.dp).border(width = 1.dp, color = StardomColors.Border),
                  contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Select node",
                        tint = StardomColors.TextSecondary,
                        modifier = Modifier.size(18.dp))
                  }
            }
      }
}

internal fun routingNodeTitle(
    connectionMode: ConnectionMode,
    activeServer: StarServerNode?,
    language: AppLanguage,
): String =
    when {
      activeServer == null && connectionMode == ConnectionMode.AUTO ->
          if (language == AppLanguage.RU) "AUTO / ОЖИДАНИЕ УЗЛА" else "AUTO / NODE PENDING"
      activeServer == null ->
          if (language == AppLanguage.RU) "УЗЕЛ НЕ ВЫБРАН" else "NO NODE SELECTED"
      connectionMode == ConnectionMode.AUTO -> "AUTO / ${activeServer.label}"
      activeServer.city.isNotBlank() -> "${activeServer.label}: ${activeServer.city.uppercase()}"
      else -> activeServer.label
    }

internal fun routingNodeDetails(activeServer: StarServerNode?, language: AppLanguage): String {
  if (activeServer == null) {
    return if (language == AppLanguage.RU) "ОЖИДАЕМ ДАННЫЕ TAILNET" else "WAITING FOR TAILNET DATA"
  }
  return listOf(activeServer.city, activeServer.countryCode, activeServer.country)
      .filter { it.isNotBlank() }
      .joinToString(" / ")
      .ifBlank {
        if (language == AppLanguage.RU) "МЕТАДАННЫЕ НЕДОСТУПНЫ" else "METADATA UNAVAILABLE"
      }
}

@Composable
private fun RoutingModeCell(
    title: String,
    subtitle: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
  Row(
      modifier =
          modifier
              .fillMaxHeight()
              .background(if (selected) StardomColors.PanelSelected else StardomColors.Panel)
              .testTag(testTag)
              .clickable(onClickLabel = "$title mode") { onClick() }
              .padding(start = 20.dp, end = 10.dp),
      verticalAlignment = Alignment.CenterVertically) {
        /*
         * OUTER SQUARE INDICATOR
         */
        Box(
            modifier =
                Modifier.size(24.dp)
                    .border(
                        width = 1.dp,
                        color = if (selected) StardomColors.Selected else StardomColors.TextMuted),
            contentAlignment = Alignment.Center) {
              if (selected) {
                Box(modifier = Modifier.size(16.dp).background(StardomColors.Selected))
              }
            }

        Spacer(Modifier.width(15.dp))

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
              text = title,
              color = if (selected) StardomColors.TextPrimary else StardomColors.TextSecondary,
              fontFamily = SpaceGrotesk,
              fontWeight = FontWeight.Medium,
              fontSize = 13.sp,
              lineHeight = 14.sp,
              letterSpacing = 1.2.sp)

          Text(
              text = subtitle,
              color = StardomColors.TextSecondary,
              fontFamily = IbmPlexMono,
              fontSize = 8.sp,
              lineHeight = 9.sp,
              letterSpacing = 1.sp)
        }
      }
}
