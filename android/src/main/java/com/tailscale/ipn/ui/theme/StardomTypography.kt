// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.theme

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
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

internal const val RUSSO_ONE_FONT_RESOURCE_NAME = "russo_one_regular"

internal fun shouldUseRussoOne(
    language: com.tailscale.ipn.ui.model.AppLanguage,
    resourceAvailable: Boolean,
): Boolean = language == com.tailscale.ipn.ui.model.AppLanguage.RU && resourceAvailable

private fun russoOneResourceId(context: Context): Int =
    context.resources.getIdentifier(
        RUSSO_ONE_FONT_RESOURCE_NAME, "font", context.packageName)

@Composable
fun StardomTechnicalFont(language: com.tailscale.ipn.ui.model.AppLanguage): FontFamily {
  val context = LocalContext.current
  return remember(language, context) {
    val russoOneId = russoOneResourceId(context)
    if (shouldUseRussoOne(language, russoOneId != 0)) {
      FontFamily(
          Font(resId = russoOneId, weight = FontWeight.Normal),
          Font(resId = russoOneId, weight = FontWeight.Medium),
          Font(resId = russoOneId, weight = FontWeight.SemiBold),
          Font(resId = russoOneId, weight = FontWeight.Bold),
      )
    } else {
      IbmPlexMono
    }
  }
}
