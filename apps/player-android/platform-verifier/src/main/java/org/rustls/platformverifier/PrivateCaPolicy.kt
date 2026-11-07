package org.rustls.platformverifier

import java.security.MessageDigest

/** Classification only: Android must already have validated the complete chain. */
internal fun isOperatorPrivateRoot(encoded: ByteArray, configuredSha256: String): Boolean {
    if (!configuredSha256.matches(Regex("[0-9a-fA-F]{64}"))) return false
    val actual = MessageDigest.getInstance("SHA-256").digest(encoded)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return actual.equals(configuredSha256, ignoreCase = true)
}
