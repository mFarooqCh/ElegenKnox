package com.elegen.elegencashbook.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure diff-string builder used by the edit-history hooks — no Room/Android involved. */
class BuildChangesTest {

    @Test
    fun `no fields changed returns null`() {
        assertNull(buildChanges(Triple("name", "Book", "Book"), Triple("currency", "PKR", "PKR")))
    }

    @Test
    fun `single changed field formats as field=old to new`() {
        assertEquals("name=Book→Renamed", buildChanges(Triple("name", "Book", "Renamed")))
    }

    @Test
    fun `only changed fields are included, unchanged ones are skipped`() {
        assertEquals(
            "amountPaisa=100→200",
            buildChanges(Triple("amountPaisa", 100L, 200L), Triple("description", "same", "same")),
        )
    }

    @Test
    fun `multiple changed fields are joined with semicolons`() {
        assertEquals(
            "amountPaisa=100→200;description=old→new",
            buildChanges(Triple("amountPaisa", 100L, 200L), Triple("description", "old", "new")),
        )
    }

    @Test
    fun `null old value (first sight) still reports as a change`() {
        assertEquals("bookId=null→bk2", buildChanges(Triple("bookId", null, "bk2")))
    }
}
