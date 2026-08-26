// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DesiredExitModeStoreTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val prefs =
      context.getSharedPreferences("stardom_desired_exit_mode", Context.MODE_PRIVATE)

  @Before
  fun setUp() {
    clearPrefs()
  }

  @After
  fun tearDown() {
    clearPrefs()
  }

  private fun clearPrefs() {
    prefs.edit().clear().commit()
  }

  @Test
  fun missingKindDefaultsModeToNull() {
    val store = SharedPreferencesDesiredExitModeStore(context)
    assertNull(store.mode.value)
  }

  @Test
  fun unknownKindDefaultsModeToNull() {
    prefs.edit().putString("kind", "unexpected_kind").commit()
    val store = SharedPreferencesDesiredExitModeStore(context)
    assertNull(store.mode.value)
  }

  @Test
  fun explicitAutoAndManualPersistAndRead() {
    val store = SharedPreferencesDesiredExitModeStore(context)
    store.set(DesiredExitMode.Auto)
    assertEquals(DesiredExitMode.Auto, store.mode.value)
    assertEquals("auto", prefs.getString("kind", null))

    store.set(DesiredExitMode.Manual("node-xyz"))
    assertEquals(DesiredExitMode.Manual("node-xyz"), store.mode.value)
    assertEquals("manual", prefs.getString("kind", null))
    assertEquals("node-xyz", prefs.getString("node_id", null))

    store.clear()
    assertNull(store.mode.value)
    assertEquals("none", prefs.getString("kind", null))
  }
}
