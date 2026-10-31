package com.lumahome.launcher;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BillingManager implements PurchasesUpdatedListener {
    public static final class Tier {
        public final String id;
        public final String title;
        public final String badge;
        public final String fallbackPrice;
        public final String description;
        public Tier(String id, String title, String badge, String fallbackPrice, String description) {
            this.id = id; this.title = title; this.badge = badge; this.fallbackPrice = fallbackPrice; this.description = description;
        }
    }

    public static final Tier[] TIERS = new Tier[]{
        new Tier("tip_1", "Quick Coffee Tip", "Tip", "$1.00", "A quick coffee to say thanks"),
        new Tier("tip_3", "Warm Snack Tip", "Snack", "$3.00", "Fuel for the next feature"),
        new Tier("tip_5", "Supporter Tier", "Star", "$5.00", "Helps keep Luma independent & ad-free"),
        new Tier("tip_10", "Super Supporter", "Super", "$10.00", "Generous backing for TV innovations"),
        new Tier("tip_25", "Gold Patron", "Patron", "$25.00", "Ultimate appreciation from a power user")
    };

    public interface BillingCallback {
        void onSuccess(String productId);
        void onError(String message);
        void onCanceled();
    }

    private static BillingManager instance;
    private final Context appContext;
    private final LauncherStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BillingClient billingClient;
    private boolean isConnecting;
    private boolean isReady;
    private final Map<String, ProductDetails> productDetailsMap = new HashMap<>();
    private BillingCallback activeCallback;

    public static synchronized BillingManager get(Context context, LauncherStore store) {
        if (instance == null) {
            instance = new BillingManager(context.getApplicationContext(), store);
        }
        return instance;
    }

    public static synchronized BillingManager get() {
        return instance;
    }

    private BillingManager(Context context, LauncherStore store) {
        this.appContext = context;
        this.store = store;
        initBillingClient();
    }

    private void initBillingClient() {
        try {
            billingClient = BillingClient.newBuilder(appContext)
                    .setListener(this)
                    .enablePendingPurchases()
                    .build();
            connect(null);
        } catch (Exception ignored) {
            isReady = false;
        }
    }

    public void connect(final Runnable onReady) {
        if (billingClient == null || isReady) {
            if (isReady && onReady != null) onReady.run();
            return;
        }
        if (isConnecting) return;
        isConnecting = true;
        try {
            billingClient.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult billingResult) {
                    isConnecting = false;
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        isReady = true;
                        queryProducts();
                        if (onReady != null) handler.post(onReady);
                    }
                }

                @Override
                public void onBillingServiceDisconnected() {
                    isConnecting = false;
                    isReady = false;
                }
            });
        } catch (Exception e) {
            isConnecting = false;
            isReady = false;
        }
    }

    private void queryProducts() {
        if (!isReady || billingClient == null) return;
        List<QueryProductDetailsParams.Product> list = new ArrayList<>();
        for (Tier tier : TIERS) {
            list.add(QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(tier.id)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build());
        }
        QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                .setProductList(list)
                .build();
        billingClient.queryProductDetailsAsync(params, (billingResult, productDetailsList) -> {
            if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && productDetailsList != null) {
                synchronized (productDetailsMap) {
                    productDetailsMap.clear();
                    for (ProductDetails details : productDetailsList) {
                        productDetailsMap.put(details.getProductId(), details);
                    }
                }
            }
        });
    }

    public String getFormattedPrice(String productId) {
        synchronized (productDetailsMap) {
            ProductDetails details = productDetailsMap.get(productId);
            if (details != null && details.getOneTimePurchaseOfferDetails() != null) {
                return details.getOneTimePurchaseOfferDetails().getFormattedPrice();
            }
        }
        for (Tier tier : TIERS) {
            if (tier.id.equals(productId)) return tier.fallbackPrice;
        }
        return "$1.00";
    }

    public boolean isReady() {
        return isReady;
    }

    public boolean isSupporter() {
        return store != null && store.flag("supporter", false);
    }

    public int getTipsCount() {
        return store != null ? store.number("supporter_tips_count", 0) : 0;
    }

    public void launchPurchase(Activity activity, String productId, BillingCallback callback) {
        this.activeCallback = callback;
        if (!isReady || billingClient == null) {
            if (callback != null) {
                callback.onError("Google Play Billing is connecting or unavailable on this device. You can also tip via the phone QR code.");
            }
            connect(null);
            return;
        }
        ProductDetails details;
        synchronized (productDetailsMap) {
            details = productDetailsMap.get(productId);
        }
        if (details == null) {
            // Products not yet loaded from Play Console (e.g. offline or pending Console setup)
            if (callback != null) {
                callback.onError("Product not available from Google Play Store yet. (Ensure in-app product '" + productId + "' is active in Play Console).");
            }
            return;
        }
        List<BillingFlowParams.ProductDetailsParams> productParamsList = new ArrayList<>();
        productParamsList.add(BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build());
        BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productParamsList)
                .build();
        BillingResult result = billingClient.launchBillingFlow(activity, flowParams);
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            if (callback != null) {
                callback.onError(result.getDebugMessage().isEmpty() ? "Unable to start purchase flow." : result.getDebugMessage());
            }
        }
    }

    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        int code = billingResult.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            if (activeCallback != null) {
                handler.post(() -> activeCallback.onCanceled());
            }
        } else {
            if (activeCallback != null) {
                final String msg = billingResult.getDebugMessage().isEmpty() ? "Purchase failed (" + code + ")" : billingResult.getDebugMessage();
                handler.post(() -> activeCallback.onError(msg));
            }
        }
    }

    private void handlePurchase(Purchase purchase) {
        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            ConsumeParams consumeParams = ConsumeParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken())
                    .build();
            billingClient.consumeAsync(consumeParams, (result, token) -> {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    if (store != null) {
                        store.setFlag("supporter", true);
                        int count = store.number("supporter_tips_count", 0);
                        store.setNumber("supporter_tips_count", count + 1);
                        store.set("supporter_since", String.valueOf(System.currentTimeMillis()));
                    }
                    if (activeCallback != null) {
                        final String pid = purchase.getProducts().isEmpty() ? "" : purchase.getProducts().get(0);
                        handler.post(() -> activeCallback.onSuccess(pid));
                    }
                }
            });
        }
    }
}
