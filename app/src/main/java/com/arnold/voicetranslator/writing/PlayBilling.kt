package com.arnold.voicetranslator.writing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams

/** What the billing client wants the UI to say after a purchase or restore. */
enum class BillingNotice {
    Unavailable,
    Pending,
    Error,
    Restored,
    AlreadyOwned,
    NothingToRestore,
}

/**
 * One-time in-app product. The product id has to exist in Play Console as a
 * managed product (one-time) with the same id, or the purchase sheet will not open.
 */
class PlayBilling(
    context: Context,
    private val productId: String,
    private val listener: Listener,
) : PurchasesUpdatedListener {

    interface Listener {
        /** [purchaseToken] is set only when Play reports PURCHASED for [productId]. Never log it. */
        fun onOwned(owned: Boolean, purchaseToken: String?)
        fun onPrice(formatted: String?)
        fun onNotice(notice: BillingNotice)
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .enableAutoServiceReconnection()
        .build()

    fun connect() {
        if (client.isReady) {
            queryOwned(report = false)
            queryPrice()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryOwned(report = false)
                    queryPrice()
                }
            }

            override fun onBillingServiceDisconnected() = Unit
        })
    }

    fun restore() {
        if (!client.isReady) {
            listener.onNotice(BillingNotice.Unavailable)
            connect()
            return
        }
        queryOwned(report = true)
    }

    fun purchase(activity: Activity) {
        if (!client.isReady) {
            listener.onNotice(BillingNotice.Unavailable)
            connect()
            return
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            val product = fetchedProduct(result, details)
            if (product == null) {
                listener.onNotice(BillingNotice.Unavailable)
                return@queryProductDetailsAsync
            }
            launch(activity, product)
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val list = purchases.orEmpty()
                acknowledge(list)
                listener.onOwned(owns(list), purchasedToken(list))
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                queryOwned(report = false)
                listener.onNotice(BillingNotice.AlreadyOwned)
            }
            else -> listener.onNotice(BillingNotice.Error)
        }
    }

    private fun launch(activity: Activity, product: ProductDetails) {
        val details = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(product)
        // PBL 8 identifies the one-time offer with a token. The backwards-compatible
        // offer is the same single product this flow already sold.
        oneTimeOffer(product)?.offerToken?.let(details::setOfferToken)
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(details.build()))
            .build()
        val result = client.launchBillingFlow(activity, flow)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            listener.onNotice(BillingNotice.Error)
        }
    }

    private fun queryPrice() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            listener.onPrice(fetchedProduct(result, details)?.let(::oneTimeOffer)?.formattedPrice)
        }
    }

    /** Successfully fetched product, or null when Play left it in [QueryProductDetailsResult.getUnfetchedProductList]. */
    private fun fetchedProduct(
        result: BillingResult,
        details: QueryProductDetailsResult,
    ): ProductDetails? {
        if (result.responseCode != BillingClient.BillingResponseCode.OK) return null
        return details.productDetailsList.firstOrNull()
    }

    private fun oneTimeOffer(product: ProductDetails): ProductDetails.OneTimePurchaseOfferDetails? =
        product.oneTimePurchaseOfferDetails
            ?: product.oneTimePurchaseOfferDetailsList?.firstOrNull()

    private fun queryOwned(report: Boolean) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                if (report) listener.onNotice(BillingNotice.Error)
                return@queryPurchasesAsync
            }
            acknowledge(purchases)
            val owned = owns(purchases)
            val pending = purchases.any { purchase ->
                productId in purchase.products && purchase.purchaseState == Purchase.PurchaseState.PENDING
            }
            listener.onOwned(owned, purchasedToken(purchases))
            if (report) {
                listener.onNotice(
                    when {
                        owned -> BillingNotice.Restored
                        pending -> BillingNotice.Pending
                        else -> BillingNotice.NothingToRestore
                    },
                )
            }
        }
    }

    private fun owns(purchases: List<Purchase>): Boolean = purchases.any { purchase ->
        productId in purchase.products && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
    }

    private fun purchasedToken(purchases: List<Purchase>): String? =
        purchases.firstOrNull { purchase ->
            productId in purchase.products && purchase.purchaseState == Purchase.PurchaseState.PURCHASED
        }?.purchaseToken

    private fun acknowledge(purchases: List<Purchase>) {
        for (purchase in purchases) {
            acknowledgeOne(purchase, attempt = 0)
        }
    }

    private fun acknowledgeOne(purchase: Purchase, attempt: Int) {
        if (productId !in purchase.products) return
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED || purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) return@acknowledgePurchase
            // Response code only. The purchase token stays out of logcat.
            Log.w(TAG, "acknowledgePurchase failed code=${result.responseCode} attempt=${attempt + 1}")
            if (attempt + 1 < MAX_ACK_ATTEMPTS) {
                acknowledgeOne(purchase, attempt + 1)
            }
        }
    }

    private companion object {
        const val TAG = "PlayBilling"
        const val MAX_ACK_ATTEMPTS = 3
    }
}
