package com.komic.reader

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class DavCredentialsTest {
    @Test fun savedAuthorizationSurvivesReopenAndIsBoundToLibraryAddress() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val server = Server(UUID.randomUUID().toString(), "Test", "https://example.test/dav/")
        val store = DavCredentials(context)
        try {
            assertNull(store.load(server))
            store.save(server, "Basic test-value")
            assertEquals("Basic test-value", DavCredentials(context).load(server))
            assertNull(store.load(server.copy(address = "https://other.test/dav/")))
            store.save(server, "Basic replacement")
            store.forget(server)
            assertNull(store.load(server))
        } finally {
            store.forget(server)
        }
    }
}
