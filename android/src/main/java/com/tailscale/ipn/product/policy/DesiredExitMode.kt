// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DesiredExitMode {
  data object Auto : DesiredExitMode

  data class Manual(val nodeId: String) : DesiredExitMode
}

interface DesiredExitModeStore {
  val mode: StateFlow<DesiredExitMode?>

  fun set(mode: DesiredExitMode)

  fun clear()
}

class SharedPreferencesDesiredExitModeStore(context: Context) : DesiredExitModeStore {
  private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private val _mode = MutableStateFlow(read())
  override val mode: StateFlow<DesiredExitMode?> = _mode.asStateFlow()

  override fun set(mode: DesiredExitMode) {
    when (mode) {
      DesiredExitMode.Auto ->
          prefs.edit().putString(KEY_KIND, "auto").remove(KEY_NODE).apply()
      is DesiredExitMode.Manual -> {
        val id = mode.nodeId.trim()
        if (id.isEmpty()) return
        prefs.edit().putString(KEY_KIND, "manual").putString(KEY_NODE, id).apply()
      }
    }
    _mode.value = mode
  }

  override fun clear() {
    prefs.edit().remove(KEY_KIND).remove(KEY_NODE).apply()
    _mode.value = null
  }

  private fun read(): DesiredExitMode? =
      when (prefs.getString(KEY_KIND, null)) {
        "auto" -> DesiredExitMode.Auto
        "manual" ->
            prefs.getString(KEY_NODE, null)?.trim()?.takeIf { it.isNotEmpty() }?.let {
              DesiredExitMode.Manual(it)
            }
        else -> null
      }

  private companion object {
    const val PREFS = "stardom_desired_exit_mode"
    const val KEY_KIND = "kind"
    const val KEY_NODE = "node_id"
  }
}
