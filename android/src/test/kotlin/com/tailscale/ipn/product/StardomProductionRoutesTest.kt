// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StardomProductionRoutesTest {
  @Test
  fun productionGraphHasOnlyTheStardomAuthenticationAuthority() {
    assertTrue(StardomProductionRoutes.paths.contains(StardomRoute.LOGIN_WITH_STARDOM.path))
    assertTrue(StardomProductionRoutes.paths.contains(StardomRoute.ACCOUNT.path))

    listOf(
            "userSwitcher",
            "loginWithAuthKey",
            "loginWithCustomControlURL",
            "addProfile",
            "reauthenticate",
            "mullvad",
            "mullvadInfo",
            "mullvadCountry",
        )
        .forEach { forbidden ->
          assertFalse(
              "production route remains reachable: $forbidden",
              forbidden in StardomProductionRoutes.paths)
        }
  }

  @Test
  fun routePathsAreUniqueAndFixed() {
    assertEquals(StardomRoute.entries.size, StardomProductionRoutes.paths.size)
    assertEquals("loginWithStardom", StardomRoute.LOGIN_WITH_STARDOM.path)
  }
}
