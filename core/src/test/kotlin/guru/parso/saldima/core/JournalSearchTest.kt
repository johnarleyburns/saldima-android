// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JournalSearchTest {
    @Test
    fun `matches by description, split memo or account name, case-insensitively, and a blank query matches everything`() {
        val bank = Guid.parse("1".repeat(32))
        val rent = Guid.parse("2".repeat(32))
        val names = mapOf(bank to "Checking", rent to "Rent")

        val transaction = SnapshotTransaction(
            guid = Guid.parse("a".repeat(32)),
            date = "2026-02-01",
            description = "Grocery run",
            splits = listOf(
                SnapshotSplit(bank, Money(-4_000, 100), memo = "weekly shop"),
                SnapshotSplit(rent, Money(4_000, 100)),
            ),
        )

        assertTrue(matchesJournalSearch(transaction, "", names))
        assertTrue(matchesJournalSearch(transaction, "GROCERY", names))
        assertTrue(matchesJournalSearch(transaction, "weekly", names))
        assertTrue(matchesJournalSearch(transaction, "checking", names))
        assertTrue(matchesJournalSearch(transaction, "rent", names))
        assertFalse(matchesJournalSearch(transaction, "no such transaction", names))
    }
}
