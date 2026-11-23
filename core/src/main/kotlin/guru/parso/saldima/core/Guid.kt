// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

data class Guid private constructor(val raw: String) {
    companion object {
        private val pattern = Regex("^[0-9a-f]{32}$")
        fun isValid(value: String): Boolean = pattern.matches(value)
        fun parse(value: String): Guid = Guid(value.also {
            require(isValid(it)) { "invalid GnuCash GUID" }
        })
    }
}

fun interface IdGenerator {
    fun next(): Guid
}
