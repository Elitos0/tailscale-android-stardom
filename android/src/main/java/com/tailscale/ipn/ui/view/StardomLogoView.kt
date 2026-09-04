// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R

@Composable
fun StardomLogoView(modifier: Modifier = Modifier) {
  Image(
      painter = painterResource(R.drawable.ic_launcher_foreground),
      contentDescription = stringResource(R.string.app_name),
      modifier = modifier)
}
