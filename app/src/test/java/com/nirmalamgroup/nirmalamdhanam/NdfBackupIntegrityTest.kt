package com.nirmalamgroup.nirmalamdhanam

import com.nirmalamgroup.nirmalamdhanam.data.local.NdfBackupIntegrity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdfBackupIntegrityTest {
    @Test fun sha256DetectsTamperedBackupPayload() {
        val original = "encrypted database payload".encodeToByteArray()
        val expected = NdfBackupIntegrity.sha256Hex(original)
        assertTrue(NdfBackupIntegrity.matchesExpected(original, expected))
        assertFalse(NdfBackupIntegrity.matchesExpected("encrypted database payloae".encodeToByteArray(), expected))
    }
}
