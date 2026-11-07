package org.rustls.platformverifier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateCaPolicyTest {
    private val encoded = "abc".toByteArray()
    private val hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun onlyTheExactConfiguredAnchorIsPrivate() {
        assertTrue(isOperatorPrivateRoot(encoded, hash))
        assertTrue(isOperatorPrivateRoot(encoded, hash.uppercase()))
        assertFalse(isOperatorPrivateRoot("other".toByteArray(), hash))
        assertFalse(isOperatorPrivateRoot(encoded, "c".repeat(64)))
    }

    @Test fun absentOrMalformedConfigurationCannotClassifyARootAsPrivate() {
        assertFalse(isOperatorPrivateRoot(encoded, ""))
        assertFalse(isOperatorPrivateRoot(encoded, "invalid"))
        assertFalse(isOperatorPrivateRoot(encoded, "0".repeat(63)))
    }
}
