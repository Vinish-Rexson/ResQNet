package com.resqnet.app.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DirectConversationIdTest {
    @Test fun deterministicIdIsSymmetricAndLengthDelimited() {
        assertEquals(
            directConversationId("node-a", "node-b"),
            directConversationId("node-b", "node-a"),
        )
        assertNotEquals(
            directConversationId("a", "bc"),
            directConversationId("ab", "c"),
        )
    }
}
