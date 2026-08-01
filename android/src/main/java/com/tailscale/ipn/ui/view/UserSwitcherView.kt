// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.viewModel.UserSwitcherViewModel

data class UserSwitcherNav(
    val backToSettings: BackNavigation,
    val onNavigateHome: () -> Unit,
    val onReauthenticate: () -> Unit,
    val onClearStardomSession: () -> Unit,
)

/**
 * Stardom has one authentication authority and one active Headscale identity. The upstream
 * multi-profile implementation remains in the vendor history, but is intentionally not rendered or
 * reachable from the Stardom production graph.
 */
@Composable
fun UserSwitcherView(nav: UserSwitcherNav, viewModel: UserSwitcherViewModel = viewModel()) {
  val currentUser by viewModel.loggedInUser.collectAsState()
  val showErrorDialog by viewModel.errorDialog.collectAsState()

  Scaffold(topBar = { Header(R.string.accounts, onBack = nav.backToSettings) }) { innerPadding ->
    Column(modifier = Modifier.padding(innerPadding).fillMaxWidth()) {
      showErrorDialog?.let { ErrorDialog(type = it, action = { viewModel.errorDialog.set(null) }) }

      currentUser?.let {
        UserView(profile = it, actionState = UserActionState.CURRENT)
        Lists.SectionDivider()
      }

      Setting.Text(R.string.reauthenticate, onClick = nav.onReauthenticate)

      if (currentUser != null) {
        Lists.ItemDivider()
        Setting.Text(
            R.string.log_out,
            destructive = true,
            onClick = {
              nav.onClearStardomSession()
              viewModel.logout {
                it.onSuccess { nav.onNavigateHome() }
                    .onFailure { viewModel.errorDialog.set(ErrorDialogType.LOGOUT_FAILED) }
              }
            })
      }
    }
  }
}

@Composable
@Preview
fun UserSwitcherViewPreview() {
  UserSwitcherView(
      UserSwitcherNav(
          backToSettings = {},
          onNavigateHome = {},
          onReauthenticate = {},
          onClearStardomSession = {},
      ),
      UserSwitcherViewModel(),
  )
}
