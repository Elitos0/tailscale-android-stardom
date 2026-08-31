// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHide
import com.tailscale.ipn.product.policy.AccessState
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.theme.disabledListItem
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.LoadingIndicator
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.ExitNodePickerNav
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModel
import com.tailscale.ipn.ui.viewModel.ExitNodePickerViewModelFactory
import kotlinx.coroutines.flow.StateFlow

@Composable
fun ExitNodePicker(
    nav: ExitNodePickerNav,
    accessState: StateFlow<AccessState>,
    model: ExitNodePickerViewModel =
        viewModel(factory = ExitNodePickerViewModelFactory(nav, accessState))
) {
  LoadingIndicator.Wrap {
    Scaffold(topBar = { Header(R.string.choose_exit_node, onBack = nav.onNavigateBackHome) }) {
        innerPadding ->
      val tailnetExitNodes by model.tailnetExitNodes.collectAsState()
      val anyActive by model.anyActive.collectAsState()
      val autoExitNode by model.autoExitNode.collectAsState()
      val allowLANAccess = Notifier.prefs.collectAsState().value?.ExitNodeAllowLANAccess == true
      val showRunAsExitNode by MDMSettings.runExitNode.flow.collectAsState()
      val allowLanAccessMDMDisposition by MDMSettings.exitNodeAllowLANAccess.flow.collectAsState()
      val managedByOrganization by model.managedByOrganization.collectAsState()
      val forcedExitNodeId = MDMSettings.exitNodeID.flow.collectAsState().value.value

      LazyColumn(modifier = Modifier.padding(innerPadding)) {
        item(key = "header") {
          if (forcedExitNodeId != null) {
            Text(
                text =
                    managedByOrganization.value?.let {
                      stringResource(R.string.exit_node_mdm_orgname, it)
                    } ?: stringResource(R.string.exit_node_mdm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
          } else {
            AutoExitNodeItem(model, autoExitNode)
          }
          if (showRunAsExitNode.value == ShowHide.Show) {
            Lists.ItemDivider()
            RunAsExitNodeItem(nav = nav, viewModel = model, anyActive)
          }
        }

        item(key = "divider1") { Lists.SectionDivider() }

        itemsWithDividers(tailnetExitNodes, key = { it.id!! }) { node -> ExitNodeItem(model, node) }

        if (!allowLanAccessMDMDisposition.value.hiddenFromUser) {
          item(key = "allowLANAccess") {
            Lists.SectionDivider()

            Setting.Switch(R.string.allow_lan_access, isOn = allowLANAccess) {
              LoadingIndicator.start()
              model.toggleAllowLANAccess { LoadingIndicator.stop() }
            }
          }
        }
      }
    }
  }
}

@Composable
fun AutoExitNodeItem(
    viewModel: ExitNodePickerViewModel,
    autoExitNode: ExitNodePickerViewModel.AutoExitNode,
) {
  val isRunningExitNode = viewModel.isRunningExitNode.collectAsState().value
  val forcedExitNodeId = MDMSettings.exitNodeID.flow.collectAsState().value.value

  Box {
    var modifier: Modifier = Modifier
    if (!isRunningExitNode && forcedExitNodeId == null) {
      modifier = modifier.clickable { viewModel.setAutoExitNode() }
    }
    ListItem(
        modifier = modifier,
        colors =
            if (!isRunningExitNode) MaterialTheme.colorScheme.listItem
            else MaterialTheme.colorScheme.disabledListItem,
        headlineContent = {
          Text(stringResource(R.string.auto_exit_node), style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          autoExitNode.effectiveNodeLabel?.let { label ->
            Text(
                stringResource(R.string.auto_exit_node_effective, label),
                style = MaterialTheme.typography.bodyMedium)
          }
        },
        trailingContent = {
          if (autoExitNode.selected) {
            Icon(Icons.Outlined.Check, null)
          }
        })
  }
}

@Composable
fun ExitNodeItem(
    viewModel: ExitNodePickerViewModel,
    node: ExitNodePickerViewModel.ExitNode,
) {
  val online by node.online.collectAsState()
  val isRunningExitNode = viewModel.isRunningExitNode.collectAsState().value
  val forcedExitNodeId = MDMSettings.exitNodeID.flow.collectAsState().value.value

  Box {
    var modifier: Modifier = Modifier
    if (online && !isRunningExitNode && forcedExitNodeId == null) {
      modifier = modifier.clickable { viewModel.setExitNode(node) }
    }
    ListItem(
        modifier = modifier,
        colors =
            if (online && !isRunningExitNode) MaterialTheme.colorScheme.listItem
            else MaterialTheme.colorScheme.disabledListItem,
        headlineContent = {
          Text(node.city.ifEmpty { node.label }, style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          if (!online)
              Text(stringResource(R.string.offline), style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = {
          Row {
            if (node.selected) {
              Icon(Icons.Outlined.Check, null)
            }
          }
        })
  }
}

@Composable
fun MullvadItem(nav: ExitNodePickerNav, count: Int, selected: Boolean) {
  Box {
    ListItem(
        modifier = Modifier.clickable { nav.onNavigateToMullvad() },
        headlineContent = {
          Text(
              stringResource(R.string.mullvad_exit_nodes),
              style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          Text(
              "$count ${stringResource(R.string.countries)}",
              style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = {
          if (selected) {
            Icon(Icons.Outlined.Check, null)
          }
        })
  }
}

@Composable
fun MullvadInfoItem(nav: ExitNodePickerNav) {
  Box {
    ListItem(
        modifier = Modifier.clickable { nav.onNavigateToMullvadInfo() },
        headlineContent = {
          Text(
              stringResource(R.string.mullvad_exit_nodes),
              style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          Text(
              stringResource(R.string.enable_in_the_admin_console),
              style = MaterialTheme.typography.bodyMedium)
        })
  }
}

@Composable
fun RunAsExitNodeItem(
    nav: ExitNodePickerNav,
    viewModel: ExitNodePickerViewModel,
    anyActive: Boolean
) {
  val isRunningExitNode = viewModel.isRunningExitNode.collectAsState().value

  Box {
    var modifier: Modifier = Modifier
    if (!anyActive) {
      modifier = modifier.clickable { nav.onNavigateToRunAsExitNode() }
    }
    ListItem(
        modifier = modifier,
        colors =
            if (!anyActive) MaterialTheme.colorScheme.listItem
            else MaterialTheme.colorScheme.disabledListItem,
        headlineContent = {
          Text(
              stringResource(id = R.string.run_as_exit_node),
              style = MaterialTheme.typography.bodyMedium)
        },
        supportingContent = {
          if (isRunningExitNode) {
            Text(stringResource(R.string.enabled))
          } else {
            Text(stringResource(R.string.disabled))
          }
        })
  }
}
