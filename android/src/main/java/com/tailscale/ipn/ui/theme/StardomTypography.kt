// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.tailscale.ipn.R

val SpaceGrotesk =
    FontFamily(
        Font(resId = R.font.space_grotesk_regular, weight = FontWeight.Normal),
        Font(resId = R.font.space_grotesk_medium, weight = FontWeight.Medium),
        Font(resId = R.font.space_grotesk_medium, weight = FontWeight.SemiBold),
        Font(resId = R.font.space_grotesk_medium, weight = FontWeight.Bold),
    )

val IbmPlexMono =
    FontFamily(
        Font(resId = R.font.ibm_plex_mono_regular, weight = FontWeight.Normal),
        Font(resId = R.font.ibm_plex_mono_medium, weight = FontWeight.Medium),
        Font(resId = R.font.ibm_plex_mono_medium, weight = FontWeight.SemiBold),
        Font(resId = R.font.ibm_plex_mono_medium, weight = FontWeight.Bold),
    )
