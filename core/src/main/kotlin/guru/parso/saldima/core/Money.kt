// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

data class Money(val num: Long, val denom: Long) {
    init {
        require(denom > 0) { "denominator must be positive" }
    }

    fun normalized(): Money {
        if (num == 0L) return ZERO
        if (num == Long.MIN_VALUE) return this
        val divisor = gcd(kotlin.math.abs(num), denom)
        return Money(num / divisor, denom / divisor)
    }

    fun negated() = Money(Math.negateExact(num), denom)

    fun roundedHalfUp(fraction: Long): Money {
        require(fraction > 0) { "fraction must be positive" }
        val scaled = Math.multiplyExact(num, fraction)
        val quotient = scaled / denom
        val remainder = scaled % denom
        val twice = Math.multiplyExact(kotlin.math.abs(remainder), 2)
        val sign = if (remainder < 0) -1L else 1L
        val rounded = if (twice >= denom) quotient + sign else quotient
        return if (rounded == 0L) ZERO else Money(rounded, fraction)
    }

    fun roundedHalfEven(fraction: Long): Money {
        require(fraction > 0) { "fraction must be positive" }
        val scaled = Math.multiplyExact(num, fraction)
        val quotient = scaled / denom
        val remainder = scaled % denom
        val twice = Math.multiplyExact(kotlin.math.abs(remainder), 2)
        val sign = if (remainder < 0) -1L else 1L
        val rounded = when {
            twice > denom -> quotient + sign
            twice == denom && quotient % 2L != 0L -> quotient + sign
            else -> quotient
        }
        return if (rounded == 0L) ZERO else Money(rounded, fraction)
    }

    fun formatted(fraction: Long): String {
        val places = decimalPlaces(fraction)
        val rounded = roundedHalfEven(fraction)
        val negative = rounded.num < 0
        val digits = kotlin.math.abs(rounded.num).toString().padStart(places + 1, '0')
        val whole = digits.dropLast(places)
        val decimals = if (places == 0) "" else "." + digits.takeLast(places)
        return (if (negative) "-" else "") + whole + decimals
    }

    companion object {
        val ZERO = Money(0, 1)

        fun add(left: Money, right: Money): Money = Money(
            Math.addExact(Math.multiplyExact(left.num, right.denom), Math.multiplyExact(right.num, left.denom)),
            Math.multiplyExact(left.denom, right.denom),
        ).normalized()

        fun subtract(left: Money, right: Money): Money = add(left, right.negated())

        fun multiply(left: Money, right: Money): Money = Money(
            Math.multiplyExact(left.num, right.num),
            Math.multiplyExact(left.denom, right.denom),
        ).normalized()

        fun decimalPlaces(fraction: Long): Int {
            require(fraction > 0) { "fraction must be positive" }
            var remaining = fraction
            var places = 0
            while (remaining % 10L == 0L && remaining > 1L) {
                remaining /= 10
                places++
            }
            require(remaining == 1L) { "fraction must be a power of ten to format" }
            return places
        }

        fun parseExact(text: String): Money {
            val match = Regex("^([+-]?)(\\d+)(?:\\.(\\d+))?$").matchEntire(text)
                ?: throw IllegalArgumentException("not a decimal amount")
            val sign = if (match.groupValues[1] == "-") -1L else 1L
            val whole = match.groupValues[2].toLong()
            val decimals = match.groupValues[3]
            var scale = 1L
            repeat(decimals.length) { scale = Math.multiplyExact(scale, 10L) }
            val decimalValue = if (decimals.isEmpty()) 0L else decimals.toLong()
            return Money(Math.addExact(Math.multiplyExact(whole, scale), decimalValue) * sign, scale)
        }

        fun parseHalfUp(text: String, fraction: Long): Money = parseExact(text).roundedHalfUp(fraction)

        private fun gcd(a: Long, b: Long): Long {
            var left = a
            var right = b
            while (right != 0L) {
                val next = left % right
                left = right
                right = next
            }
            return if (left == 0L) 1L else left
        }
    }
}
