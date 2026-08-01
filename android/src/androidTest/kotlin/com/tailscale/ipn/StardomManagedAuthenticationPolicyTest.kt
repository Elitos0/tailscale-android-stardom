// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.SettingState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class StardomManagedAuthenticationPolicyTest {
  @Test
  fun installedApplicationRestrictionsDoNotAdvertiseAlternateAuthentication() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val applicationInfo =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
          context.packageManager.getApplicationInfo(
              context.packageName,
              PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
          )
        } else {
          @Suppress("DEPRECATION")
          context.packageManager.getApplicationInfo(
              context.packageName, PackageManager.GET_META_DATA)
        }
    val restrictionsResource = applicationInfo.metaData.getInt("android.content.APP_RESTRICTIONS")
    val keys = mutableSetOf<String>()
    context.resources.getXml(restrictionsResource).use { parser ->
      while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "restriction") {
          parser
              .getAttributeValue("http://schemas.android.com/apk/res/android", "key")
              ?.let(keys::add)
        }
        parser.next()
      }
    }

    assertFalse("AuthKey" in keys)
    assertFalse("LoginURL" in keys)
  }

  @Test
  fun installedNativeSyspolicyRejectsPresentManagedAuthKeyAndMalformedLoginUrl() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App
    MDMSettings.authKey.flow.value = SettingState("tskey-auth-adversarial", true)
    MDMSettings.loginURL.flow.value = SettingState("not a control URL", true)
    try {
      assertThrows(MDMSettings.NoSuchKeyException::class.java) {
        app.getSyspolicyStringValue("AuthKey")
      }
      assertThrows(MDMSettings.NoSuchKeyException::class.java) {
        app.getSyspolicyStringValue("LoginURL")
      }
    } finally {
      MDMSettings.authKey.flow.value = SettingState(null, false)
      MDMSettings.loginURL.flow.value = SettingState(null, false)
    }
  }
}
