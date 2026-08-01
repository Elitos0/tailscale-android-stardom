// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.StringArrayListMDMSetting
import com.tailscale.ipn.product.policy.ALLOWED_SUGGESTED_EXIT_NODES_KEY
import com.tailscale.ipn.product.policy.SyspolicyStringArrayJSONBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AllowedSuggestedExitNodePolicyInstrumentationTest {
  @Test
  fun stardomSyspolicyKeyFailsClosedWhileOtherMissingKeysRemainMissing() {
    assertEquals(
        "[]",
        SyspolicyStringArrayJSONBridge.get(
            ALLOWED_SUGGESTED_EXIT_NODES_KEY,
            productCandidatesJSON = { error("unavailable") },
            fallbackValue = { error("fallback must not be used") },
        ),
    )
    assertThrows(MDMSettings.NoSuchKeyException::class.java) {
      SyspolicyStringArrayJSONBridge.get(
          "MissingKey",
          productCandidatesJSON = { "[]" },
          fallbackValue = { throw MDMSettings.NoSuchKeyException() },
      )
    }
  }

  @Test
  fun presentMalformedManagedCandidateListRemainsConfiguredAndFailClosed() {
    val setting = StringArrayListMDMSetting("Candidates", "Candidates")
    val restrictions = Bundle().apply { putInt("Candidates", 7) }

    setting.setFrom(restrictions, lazy { error("preferences must not be read") })

    assertTrue(setting.flow.value.isSet)
    assertNull(setting.flow.value.value)
    assertEquals(
        "[]",
        SyspolicyStringArrayJSONBridge.get(
            ALLOWED_SUGGESTED_EXIT_NODES_KEY,
            productCandidatesJSON = { "[]" },
            fallbackValue = { error("fallback must not be used") },
        ),
    )
  }

  @Test
  fun presentWrongTypePreferenceRemainsConfiguredAndFailClosed() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val preferences = context.getSharedPreferences("candidate-policy-test", 0)
    preferences.edit().clear().putInt("Candidates", 7).commit()
    val setting = StringArrayListMDMSetting("Candidates", "Candidates")

    try {
      setting.setFrom(null, lazy { preferences })

      assertTrue(setting.flow.value.isSet)
      assertNull(setting.flow.value.value)
    } finally {
      preferences.edit().clear().commit()
    }
  }
}
