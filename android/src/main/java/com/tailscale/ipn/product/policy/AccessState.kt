// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

sealed interface AccessState {
  data class Active(val allowedExitNodeIds: Set<String>) : AccessState

  data object Disabled : AccessState

  data object Unavailable : AccessState
}
