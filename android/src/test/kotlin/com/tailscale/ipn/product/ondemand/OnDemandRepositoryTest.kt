// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.ondemand

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandRepositoryTest {

  @Test
  fun initialState_loadsKnownSsidsFromPreferences() {
    val initialPrefs = FakeSharedPreferences(
        mapOf("known_ssids" to setOf("Office-WiFi", "Home-Net"))
    )
    val repo = OnDemandRepository(initialPrefs)

    assertEquals(setOf("Office-WiFi", "Home-Net"), repo.knownSsids.value)
  }

  @Test
  fun rememberKnownSsid_addsTrimmedSsidAndPersists() {
    val prefs = FakeSharedPreferences()
    val repo = OnDemandRepository(prefs)

    repo.rememberKnownSsid("  CoffeeShop-5G  ")

    assertEquals(setOf("CoffeeShop-5G"), repo.knownSsids.value)
    assertEquals(setOf("CoffeeShop-5G"), prefs.getStringSet("known_ssids", emptySet()))
  }

  @Test
  fun rememberKnownSsid_ignoresEmptyOrBlank() {
    val prefs = FakeSharedPreferences()
    val repo = OnDemandRepository(prefs)

    repo.rememberKnownSsid("")
    repo.rememberKnownSsid("   ")

    assertTrue(repo.knownSsids.value.isEmpty())
    assertTrue(prefs.getStringSet("known_ssids", emptySet())!!.isEmpty())
  }
  @Test
  fun rememberKnownSsid_rejectsInvalidSsids() {
    val prefs = FakeSharedPreferences()
    val repo = OnDemandRepository(prefs)

    repo.rememberKnownSsid(null)
    repo.rememberKnownSsid("<unknown ssid>")
    repo.rememberKnownSsid("0x")
    repo.rememberKnownSsid("0x00")
    repo.rememberKnownSsid("")
    repo.rememberKnownSsid("   ")
    repo.rememberKnownSsid("\"<unknown ssid>\"")
    repo.rememberKnownSsid("\"0x\"")

    assertTrue(repo.knownSsids.value.isEmpty())
    assertTrue(prefs.getStringSet("known_ssids", emptySet())!!.isEmpty())
  }

  @Test
  fun initialState_filtersOutInvalidSsidsFromPreferences() {
    val initialPrefs = FakeSharedPreferences(
        mapOf("known_ssids" to setOf("Office-WiFi", "<unknown ssid>", "0x", "0x00", "", "   ", "\"Home-Net\""))
    )
    val repo = OnDemandRepository(initialPrefs)

    assertEquals(setOf("Office-WiFi", "Home-Net"), repo.knownSsids.value)
  }

  @Test
  fun rememberKnownSsid_preservesExistingSsids() {
    val prefs = FakeSharedPreferences()
    val repo = OnDemandRepository(prefs)

    repo.rememberKnownSsid("NetworkA")
    repo.rememberKnownSsid("NetworkB")

    assertEquals(setOf("NetworkA", "NetworkB"), repo.knownSsids.value)
    assertEquals(setOf("NetworkA", "NetworkB"), prefs.getStringSet("known_ssids", emptySet()))
  }

  @Test
  fun removeKnownSsid_removesAndPersists() {
    val initialPrefs = FakeSharedPreferences(
        mapOf("known_ssids" to setOf("NetworkA", "NetworkB", "NetworkC"))
    )
    val repo = OnDemandRepository(initialPrefs)

    repo.removeKnownSsid("NetworkB")

    assertEquals(setOf("NetworkA", "NetworkC"), repo.knownSsids.value)
    assertEquals(setOf("NetworkA", "NetworkC"), initialPrefs.getStringSet("known_ssids", emptySet()))
  }

  @Test
  fun removeKnownSsid_nonExistentSsidIsNoOp() {
    val initialPrefs = FakeSharedPreferences(
        mapOf("known_ssids" to setOf("NetworkA"))
    )
    val repo = OnDemandRepository(initialPrefs)

    repo.removeKnownSsid("NonExistent")

    assertEquals(setOf("NetworkA"), repo.knownSsids.value)
    assertEquals(setOf("NetworkA"), initialPrefs.getStringSet("known_ssids", emptySet()))
  }
}

private class FakeSharedPreferences(
    initialValues: Map<String, Any?> = emptyMap()
) : SharedPreferences {
  private val values = initialValues.toMutableMap()

  override fun getAll(): Map<String, *> = values.toMap()

  override fun getString(key: String, defValue: String?): String? =
      values[key] as? String ?: defValue

  @Suppress("UNCHECKED_CAST")
  override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
      (values[key] as? Set<String>)?.toSet() ?: defValues

  override fun getInt(key: String, defValue: Int): Int =
      values[key] as? Int ?: defValue

  override fun getLong(key: String, defValue: Long): Long =
      values[key] as? Long ?: defValue

  override fun getFloat(key: String, defValue: Float): Float =
      values[key] as? Float ?: defValue

  override fun getBoolean(key: String, defValue: Boolean): Boolean =
      values[key] as? Boolean ?: defValue

  override fun contains(key: String): Boolean = values.containsKey(key)

  override fun edit(): SharedPreferences.Editor = FakeEditor(this)

  override fun registerOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener?
  ) {}

  override fun unregisterOnSharedPreferenceChangeListener(
      listener: SharedPreferences.OnSharedPreferenceChangeListener?
  ) {}

  private class FakeEditor(private val parent: FakeSharedPreferences) : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private val removals = mutableSetOf<String>()
    private var clearRequested = false

    override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
      pending[key] = value
      removals.remove(key)
    }

    override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
      pending[key] = values?.toSet()
      removals.remove(key)
    }

    override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
      pending[key] = value
      removals.remove(key)
    }

    override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
      pending[key] = value
      removals.remove(key)
    }

    override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
      pending[key] = value
      removals.remove(key)
    }

    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
      pending[key] = value
      removals.remove(key)
    }

    override fun remove(key: String): SharedPreferences.Editor = apply {
      removals.add(key)
      pending.remove(key)
    }

    override fun clear(): SharedPreferences.Editor = apply {
      clearRequested = true
    }

    override fun commit(): Boolean {
      apply()
      return true
    }

    override fun apply() {
      if (clearRequested) {
        parent.values.clear()
      }
      for (key in removals) {
        parent.values.remove(key)
      }
      for ((key, value) in pending) {
        if (value == null) {
          parent.values.remove(key)
        } else {
          parent.values[key] = value
        }
      }
    }
  }
}
