// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

/**
 * Capitalizes only the first character of a user-facing node name.
 *
 * This is intentionally presentation-only: callers must keep using the original node name for
 * identifiers, comparisons, persistence, and API values. Character.uppercaseChar() avoids the
 * default-locale transformations that can corrupt technical names.
 */
fun String.capitalizeNodeNameForDisplay(): String {
  if (any { it.isUpperCase() }) return this
  return replaceFirstChar { character ->
    if (character.isLowerCase()) character.uppercaseChar() else character
  }
}
