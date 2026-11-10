package dev.contactguard.dialer

import org.junit.Assert.assertEquals
import org.junit.Test

class DiffTest {
    private fun c(id: Long, name: String, phone: String?, acct: String = "a@gmail.com") = RawSnap(
        id, "com.google", acct, name, false,
        listOfNotNull(phone?.let { mapOf("mimetype" to RawSnap.PHONE_MIME, "data1" to it) }),
    )

    @Test fun detectsLossIgnoresMovesAndMass() {
        val old = listOf(c(1, "Mom", "+91 98765 43210"), c(2, "Dad", "9876500000"), c(3, "Shop", null))
        // Dad re-synced under a new raw id with formatted number -> not lost. Mom and Shop gone -> lost.
        val live = listOf(c(9, "dad", "098765-00000"))
        assertEquals(listOf(1L, 3L), diff(old, live).lost.map { it.rawId })
        assertEquals(emptySet<String>(), diff(old, live).massAccounts) // 2 lost < 5

        // Our own deletes are ignored.
        assertEquals(listOf(3L), diff(old, live, ignore = setOf(1)).lost.map { it.rawId })

        // Whole account wiped -> mass.
        val many = (1L..10L).map { c(it, "P$it", "90000000$it") }
        assertEquals(setOf("com.google|a@gmail.com"), diff(many, emptyList()).massAccounts)
    }
}
