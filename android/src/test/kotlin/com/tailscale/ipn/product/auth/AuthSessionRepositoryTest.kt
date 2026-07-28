// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.auth

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSessionRepositoryTest {
  @Test
  fun clearSessionRemovesPersistedCredentialsAndSignsOut() {
    val storage = InMemoryAuthStateStorage("serialized session")
    val repository = AuthSessionRepository(storage)

    repository.clearSession()

    assertNull(storage.read())
    assertTrue(repository.isSignedOut)
  }
}

private class InMemoryAuthStateStorage(private var value: String?) : AuthStateStorage {
  override fun read(): String? = value

  override fun write(value: String) {
    this.value = value
  }

  override fun clear() {
    value = null
  }
}
