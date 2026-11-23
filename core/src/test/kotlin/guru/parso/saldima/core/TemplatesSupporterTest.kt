// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private class FakePurchasing(var result: SupporterPurchaseResult = SupporterPurchaseResult.Purchased) : SupporterPurchasing {
    override suspend fun purchase() = result
}

private class FakeFlags(var value: Boolean = false) : SupporterFlags {
    override fun isSupporter() = value
    override fun markSupporter() { value = true }
}

class TemplatesSupporterTest {
    @Test
    fun `agency template has trust and commission accounts`() {
        val paths = BookTemplates.flatten(BookTemplates.insuranceAgency).map { it.path }
        assertTrue("Assets/Trust/Premium Trust" in paths)
        assertTrue("Expenses/E&O Insurance" in paths)
        assertEquals(paths.size, paths.toSet().size)
        assertEquals("personal", BookTemplates.byId("nope").id)
    }

    @Test
    fun `supporter purchase is one-way and non-gating`() = runBlocking {
        val purchasing = FakePurchasing()
        val flags = FakeFlags()
        val controller = SupporterController(purchasing, flags)
        assertFalse(controller.isSupporter())
        assertEquals(SupporterPurchaseResult.Purchased, controller.purchase())
        assertTrue(controller.isSupporter())

        val cancel = FakePurchasing(SupporterPurchaseResult.Cancelled)
        val cancelFlags = FakeFlags()
        val cancelController = SupporterController(cancel, cancelFlags)
        assertEquals(SupporterPurchaseResult.Cancelled, cancelController.purchase())
        assertFalse(cancelFlags.value)
        assertEquals("saldima_supporter", SupporterProduct.ID)
    }
}
