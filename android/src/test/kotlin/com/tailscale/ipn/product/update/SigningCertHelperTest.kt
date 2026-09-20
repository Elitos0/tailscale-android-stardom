// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.product.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningCertHelperTest {

  @Test
  fun sha256HexProducesCorrectLowercaseHash() {
    val input = "stardom-vpn-test-bytes".toByteArray(Charsets.UTF_8)
    val hash = SigningCertHelper.sha256Hex(input)
    assertEquals(64, hash.length)
    assertTrue("Hash must be lowercase hex", hash.all { it in '0'..'9' || it in 'a'..'f' })

    // Known SHA-256 for "hello world"
    val helloBytes = "hello world".toByteArray(Charsets.UTF_8)
    assertEquals(
        "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
        SigningCertHelper.sha256Hex(helloBytes))
  }

  @Test
  fun verifyCertificatesMatchCorrectlyIdentifiesMatches() {
    val certA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    val certB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    val certC = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

    // Exact match single cert
    assertTrue(SigningCertHelper.verifyCertificatesMatch(listOf(certA), listOf(certA)))

    // Match in list of multiple signers / cert history
    assertTrue(SigningCertHelper.verifyCertificatesMatch(listOf(certA, certB), listOf(certB, certC)))

    // Case insensitive match
    assertTrue(SigningCertHelper.verifyCertificatesMatch(listOf(certA.uppercase()), listOf(certA.lowercase())))

    // No match
    assertFalse(SigningCertHelper.verifyCertificatesMatch(listOf(certA), listOf(certB)))

    // Empty list on either side
    assertFalse(SigningCertHelper.verifyCertificatesMatch(emptyList(), listOf(certA)))
    assertFalse(SigningCertHelper.verifyCertificatesMatch(listOf(certA), emptyList()))
    assertFalse(SigningCertHelper.verifyCertificatesMatch(emptyList(), emptyList()))
  }
}
