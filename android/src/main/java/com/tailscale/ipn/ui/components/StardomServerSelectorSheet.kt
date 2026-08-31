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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.StarServerNode
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StardomServerSelectorSheet(
    servers: List<StarServerNode>,
    selectedServer: StarServerNode,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSelectServer: (StarServerNode) -> Unit,
    language: AppLanguage = AppLanguage.RU
) {
  var searchQuery by remember { mutableStateOf("") }

  val filteredServers =
      remember(searchQuery, servers) {
        if (searchQuery.isBlank()) {
          servers
        } else {
          servers.filter {
            it.starName.contains(searchQuery, ignoreCase = true) ||
                it.city.contains(searchQuery, ignoreCase = true) ||
                it.constellation.contains(searchQuery, ignoreCase = true) ||
                it.countryCode.contains(searchQuery, ignoreCase = true)
          }
        }
      }

  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = StardomColors.Background,
      scrimColor = StardomColors.Background.copy(alpha = 0.88f),
      dragHandle = {
        Box(
            modifier =
                Modifier.padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(1.dp)
                    .background(StardomColors.BorderStrong))
      }) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(horizontal = 20.dp, vertical = 6.dp)) {
              // Header
              Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                  modifier = Modifier.fillMaxWidth()) {
                    Column {
                      Text(
                          text = StardomLocalization.serverDirectoryTitle(language),
                          color = StardomColors.TextPrimary,
                          fontSize = 14.sp,
                          fontWeight = FontWeight.Medium,
                          fontFamily = SpaceGrotesk,
                          letterSpacing = 1.5.sp)
                      Spacer(Modifier.height(3.dp))
                      Text(
                          text =
                              StardomLocalization.serverDirectorySubtitle(servers.size, language),
                          color = StardomColors.TextSecondary,
                          fontSize = 9.sp,
                          fontFamily = IbmPlexMono,
                          letterSpacing = 1.sp)
                    }

                    Box(
                        modifier =
                            Modifier.background(StardomColors.Panel)
                                .border(1.dp, StardomColors.Border)
                                .clickable(onClickLabel = "Close") { onDismiss() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                          Text(
                              text = StardomLocalization.closeBtn(language),
                              color = StardomColors.TextSecondary,
                              fontSize = 9.sp,
                              fontFamily = IbmPlexMono,
                              letterSpacing = 1.sp)
                        }
                  }

              Spacer(modifier = Modifier.height(16.dp))

              // Search Box
              Box(
                  modifier =
                      Modifier.fillMaxWidth()
                          .background(StardomColors.Panel)
                          .border(1.dp, StardomColors.Border)
                          .padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Text(
                          text = ">",
                          color = StardomColors.TextPrimary,
                          fontSize = 12.sp,
                          fontWeight = FontWeight.Bold,
                          fontFamily = IbmPlexMono)
                      Spacer(modifier = Modifier.width(10.dp))
                      BasicTextField(
                          value = searchQuery,
                          onValueChange = { searchQuery = it },
                          textStyle =
                              TextStyle(
                                  color = StardomColors.TextPrimary,
                                  fontSize = 12.sp,
                                  fontFamily = IbmPlexMono),
                          cursorBrush = SolidColor(StardomColors.TextPrimary),
                          singleLine = true,
                          modifier = Modifier.fillMaxWidth().testTag("server_search_input"),
                          decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                              Text(
                                  text = StardomLocalization.searchPlaceholder(language),
                                  color = StardomColors.TextMuted,
                                  fontSize = 11.sp,
                                  fontFamily = IbmPlexMono,
                                  letterSpacing = 1.sp)
                            }
                            innerTextField()
                          })
                    }
                  }

              Spacer(modifier = Modifier.height(14.dp))

              // Server list
              LazyColumn(
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                  modifier = Modifier.fillMaxWidth()) {
                    items(filteredServers, key = { it.id }) { server ->
                      val isSelected = server.id == selectedServer.id

                      Box(
                          modifier =
                              Modifier.fillMaxWidth()
                                  .testTag("server_item_${server.id}")
                                  .background(
                                      if (isSelected) StardomColors.PanelSelected
                                      else StardomColors.Panel)
                                  .border(
                                      1.dp,
                                      if (isSelected) StardomColors.BorderStrong
                                      else StardomColors.BorderFaint)
                                  .clickable(onClickLabel = "Select server ${server.starName}") {
                                    onSelectServer(server)
                                    onDismiss()
                                  }
                                  .padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()) {
                                  Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                      // Square Indicator
                                      Box(
                                          modifier =
                                              Modifier.size(10.dp)
                                                  .border(
                                                      1.dp,
                                                      if (isSelected) StardomColors.Selected
                                                      else StardomColors.TextMuted),
                                          contentAlignment = Alignment.Center) {
                                            if (isSelected) {
                                              Box(
                                                  modifier =
                                                      Modifier.size(4.dp)
                                                          .background(StardomColors.Selected))
                                            }
                                          }

                                      Spacer(modifier = Modifier.width(10.dp))

                                      Text(
                                          text = "${server.starName} // ${server.city.uppercase()}",
                                          color =
                                              if (isSelected) StardomColors.TextPrimary
                                              else StardomColors.TextSecondary,
                                          fontSize = 13.sp,
                                          fontWeight = FontWeight.Medium,
                                          fontFamily = SpaceGrotesk,
                                          letterSpacing = 1.sp)

                                      Spacer(modifier = Modifier.width(6.dp))

                                      Text(
                                          text = "[${server.countryCode}]",
                                          color = StardomColors.TextMuted,
                                          fontSize = 10.sp,
                                          fontFamily = IbmPlexMono)
                                    }

                                    Spacer(modifier = Modifier.height(5.dp))

                                    Text(
                                        text = "${server.constellation} • ${server.coordinates}",
                                        color = StardomColors.TextMuted,
                                        fontSize = 9.sp,
                                        fontFamily = IbmPlexMono,
                                        modifier = Modifier.padding(start = 20.dp))
                                  }

                                  // Right telemetry: Ping & Load
                                  Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "${server.basePingMs} MS",
                                        color = StardomColors.TextPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Normal,
                                        fontFamily = IbmPlexMono)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text =
                                            "${StardomLocalization.loadLabel(language)} ${server.loadPercent}%",
                                        color = StardomColors.TextMuted,
                                        fontSize = 8.sp,
                                        fontFamily = IbmPlexMono)
                                  }
                                }
                          }
                    }
                  }
            }
      }
}
