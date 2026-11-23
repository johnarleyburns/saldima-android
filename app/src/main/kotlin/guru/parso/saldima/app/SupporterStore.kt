// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import guru.parso.saldima.core.SupporterProduct

/**
 * One-time Play Billing consumable. It never unlocks a feature; success only
 * sets the local Supporter badge. Product/price are configured in Play Console.
 */
class SupporterStore(context: Context) : PurchasesUpdatedListener {
    private val prefs = context.getSharedPreferences("saldima", Context.MODE_PRIVATE)
    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    var isSupporter: Boolean
        get() = prefs.getBoolean("isSupporter", false)
        private set(value) = prefs.edit().putBoolean("isSupporter", value).apply()

    fun connect() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) = Unit
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun purchase(activity: Activity) {
        connect()
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(SupporterProduct.ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val query = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        client.queryProductDetailsAsync(query) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val productDetails = details.firstOrNull() ?: return@queryProductDetailsAsync
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(
                    listOf(
                        BillingFlowParams.ProductDetailsParams.newBuilder()
                            .setProductDetails(productDetails)
                            .build()
                    )
                )
                .build()
            client.launchBillingFlow(activity, params)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        for (purchase in purchases) {
            if (purchase.products.contains(SupporterProduct.ID)) {
                client.consumeAsync(
                    ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                ) { _, _ -> isSupporter = true }
            }
        }
    }
}

fun ProductDetails.oneTimePrice(): String =
    oneTimePurchaseOfferDetails?.formattedPrice ?: ""
