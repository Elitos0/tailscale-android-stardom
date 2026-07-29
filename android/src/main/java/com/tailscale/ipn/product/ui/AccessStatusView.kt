// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ui

import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tailscale.ipn.R
import com.tailscale.ipn.product.auth.AuthSessionRepository
import com.tailscale.ipn.product.policy.AccessRepository
import com.tailscale.ipn.product.policy.AccessState

@Composable
fun AccessStatusView(
    accessRepository: AccessRepository,
    authSessionRepository: AuthSessionRepository,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(accessRepository, authSessionRepository, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      accessRepository.refresh(context, authSessionRepository)
    }
  }
  val accessState by accessRepository.state.collectAsState()
  AccessStatusView(accessState)
}

@Composable
fun AccessStatusView(accessState: AccessState) {
  val message =
      when (accessState) {
        is AccessState.Active -> R.string.vpn_access_active
        AccessState.Disabled -> R.string.vpn_access_disabled
        AccessState.Unavailable -> R.string.vpn_access_unavailable
      }
  ListItem(
      headlineContent = {
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium)
      })
}
