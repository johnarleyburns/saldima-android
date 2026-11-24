// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class SequenceIds : IdGenerator {
    private var value = 0L

    override fun next(): Guid {
        value += 1
        return Guid.parse(value.toString(16).padStart(32, '0'))
    }
}

class XmlBookWriterTest {
    private fun guid(character: Char) = Guid.parse(character.toString().repeat(32))

    private fun snapshot(): BookSnapshot {
        val root = guid('1')
        val templateRoot = guid('2')
        val assets = guid('3')
        val bank = guid('4')
        val investments = guid('5')
        val hidden = guid('6')
        val income = guid('7')
        val sales = guid('8')
        return BookSnapshot(
            bookGuid = guid('a'),
            currencyCode = "USD",
            fraction = 100,
            accounts = listOf(
                SnapshotAccount(root, "Root Account", "ROOT"),
                SnapshotAccount(templateRoot, "Template Root", "ROOT"),
                SnapshotAccount(assets, "Assets", "ASSET", parentGuid = root, commodityCode = "USD"),
                SnapshotAccount(bank, "Bank & Cash", "BANK", parentGuid = assets, commodityCode = "USD"),
                SnapshotAccount(
                    investments,
                    "Investments",
                    "ASSET",
                    parentGuid = assets,
                    commodityCode = "USD",
                    placeholder = true,
                ),
                SnapshotAccount(
                    hidden,
                    "Old account",
                    "BANK",
                    parentGuid = assets,
                    commodityCode = "USD",
                    hidden = true,
                ),
                SnapshotAccount(income, "Income", "INCOME", parentGuid = root, commodityCode = "USD"),
                SnapshotAccount(sales, "Sales", "INCOME", parentGuid = income, commodityCode = "USD"),
            ),
            transactions = listOf(
                SnapshotTransaction(
                    guid = guid('b'),
                    date = "2026-11-24",
                    description = "Opening & deposit",
                    currencyCode = "USD",
                    splits = listOf(
                        SnapshotSplit(bank, Money(1_000, 100)),
                        SnapshotSplit(sales, Money(-1_000, 100)),
                    ),
                ),
            ),
            templateRootGuid = templateRoot,
        )
    }

    @Test
    fun `exports gzip XML that reopens with the same tree and postings`() {
        val bytes = XmlBookWriter.gzip(snapshot(), SequenceIds())
        assertTrue(bytes.size > 2)
        assertEquals(0x1f.toByte(), bytes[0])
        assertEquals(0x8b.toByte(), bytes[1])

        val file = File.createTempFile("saldima-export", ".gnucash")
        try {
            file.writeBytes(bytes)
            val read = XmlBookReader.read(file)
            assertEquals(Guid.parse("a".repeat(32)), read.bookGuid)
            assertEquals("USD", read.currencyCode)
            assertEquals(100L, read.fraction)
            assertEquals("ROOT", read.accounts.first().type)
            val names = read.accounts.map { it.name }
            assertEquals(
                listOf(
                    "Root Account",
                    "Assets",
                    "Bank & Cash",
                    "Investments",
                    "Old account",
                    "Income",
                    "Sales",
                ),
                names,
            )
            assertTrue("Template Root" !in names)
            val assets = read.accounts.first { it.name == "Assets" }
            assertEquals(Guid.parse("1".repeat(32)), assets.parentGuid)
            assertEquals("USD", read.accounts.first { it.name == "Bank & Cash" }.commodityCode)
            assertTrue(read.accounts.first { it.name == "Investments" }.placeholder)
            assertTrue(read.accounts.first { it.name == "Old account" }.hidden)

            val transaction = assertNotNull(read.transactions.firstOrNull())
            assertEquals("Opening & deposit", transaction.description)
            assertEquals("2026-11-24", transaction.date)
            assertEquals(2, transaction.splits.size)
            assertEquals(Money(1_000, 100), transaction.splits[0].value)
            assertEquals(Money(-1_000, 100), transaction.splits[1].value)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `writes an oracle copy when asked`() {
        val path = System.getenv("SALDIMA_XML_EXPORT_PATH") ?: return
        if (path.isBlank()) return
        File(path).writeBytes(XmlBookWriter.gzip(snapshot(), SequenceIds()))
    }
}
