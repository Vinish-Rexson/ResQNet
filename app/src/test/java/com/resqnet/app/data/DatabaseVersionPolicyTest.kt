package com.resqnet.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseVersionPolicyTest {
    @Test fun taskThreeSchemaIsVersionFourAndResetsOnlyEarlierSchemas() {
        assertEquals(4, RESQNET_DATABASE_VERSION)
        assertTrue(canDestructivelyResetFrom(1))
        assertTrue(canDestructivelyResetFrom(2))
        assertTrue(canDestructivelyResetFrom(3))
        assertFalse(canDestructivelyResetFrom(4))
    }
}
