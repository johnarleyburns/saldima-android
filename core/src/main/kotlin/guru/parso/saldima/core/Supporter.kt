// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

object SupporterProduct {
    const val ID = "saldima_supporter"
    const val DISPLAY_NAME = "Supporter"
}

sealed interface SupporterPurchaseResult {
    data object Purchased : SupporterPurchaseResult
    data object Cancelled : SupporterPurchaseResult
    data object Pending : SupporterPurchaseResult
}

fun interface SupporterPurchasing {
    suspend fun purchase(): SupporterPurchaseResult
}

interface SupporterFlags {
    fun isSupporter(): Boolean
    fun markSupporter()
}

class SupporterController(
    private val purchasing: SupporterPurchasing,
    private val flags: SupporterFlags,
) {
    suspend fun purchase(): SupporterPurchaseResult {
        val result = purchasing.purchase()
        if (result == SupporterPurchaseResult.Purchased) flags.markSupporter()
        return result
    }

    fun isSupporter(): Boolean = flags.isSupporter()
}
