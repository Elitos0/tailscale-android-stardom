// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.ui.model.AppLanguage
import com.tailscale.ipn.ui.model.StardomLocalization
import com.tailscale.ipn.ui.model.VpnState
import com.tailscale.ipn.ui.theme.IbmPlexMono
import com.tailscale.ipn.ui.theme.SpaceGrotesk
import com.tailscale.ipn.ui.theme.StardomColors

@Composable
fun StardomStatus(
    vpnState: VpnState,
    modifier: Modifier = Modifier,
    language: AppLanguage = AppLanguage.RU
) {
  Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
        text = StardomLocalization.statusHeader(language),
        color = StardomColors.TextSecondary,
        fontFamily = IbmPlexMono,
        fontSize = 9.sp,
        letterSpacing = 2.sp)

    Spacer(Modifier.height(6.dp))

    val statusText = StardomLocalization.powerButtonStatus(vpnState, language)

    Text(
        text = statusText,
        color = if (vpnState.isError) StardomColors.Error else StardomColors.TextPrimary,
        fontFamily = SpaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 23.sp,
        letterSpacing = 3.5.sp)
  }
}
