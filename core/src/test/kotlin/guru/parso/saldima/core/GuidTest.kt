// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GuidTest {
    @Test
    fun `validates 32 lowercase hex`() {
        assertTrue(Guid.isValid("0123456789abcdef0123456789abcdef"))
        assertFailsWith<IllegalArgumentException> { Guid.parse("nope") }
        assertEquals("0123456789abcdef0123456789abcdef", Guid.parse("0123456789abcdef0123456789abcdef").raw)
    }
}
