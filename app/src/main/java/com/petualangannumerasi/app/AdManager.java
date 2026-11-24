package com.petualangannumerasi.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.nativead.MediaView;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;

import java.util.Date;

public class AdManager implements DefaultLifecycleObserver {

    // Ganti 3 Unit ID Test berikut dengan Unit ID Produksi AdMob Anda sebelum rilis
    private static final String AD_UNIT_APP_OPEN = "ca-app-pub-6983364109428063/4167285107";
    private static final String AD_UNIT_NATIVE = "ca-app-pub-6983364109428063/8364057126";
    private static final String AD_UNIT_INTERSTITIAL = "ca-app-pub-6983364109428063/5480366779";

    private static final long APP_OPEN_COOLDOWN_MS = 30_000L; // Jeda minimal 30 detik antar iklan layar penuh

    private final Activity activity;
    private final WebView webView;
    private final FrameLayout nativeAdContainer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AppOpenAd appOpenAd;
    private NativeAd currentNativeAd;
    private InterstitialAd interstitialAd;

    private boolean isInitialized = false;
    private boolean isContentReady = false;
    private boolean isGameplayActive = false;
    private boolean isNativeAllowedByWeb = true;
    private boolean isShowingFullScreenAd = false;
    private boolean isLoadingAppOpen = false;
    private boolean isLoadingNative = false;
    private boolean hasShownColdStartAppOpen = false;

    private long appOpenLoadTimeMs = 0L;
    private long lastFullScreenAdDismissedMs = 0L;

    public AdManager(@NonNull Activity activity,
                     @NonNull WebView webView,
                     @NonNull FrameLayout nativeAdContainer) {
        this.activity = activity;
        this.webView = webView;
        this.nativeAdContainer = nativeAdContainer;

        ProcessLifecycleOwner.get().getLifecycle().addObserver(this);
        applyFamiliesPolicy(true, RequestConfiguration.MAX_AD_CONTENT_RATING_G);
    }

    /**
     * Menerapkan kebijakan Google Play Families & COPPA sebelum memuat iklan.
     */
    public void applyFamiliesPolicy(boolean childDirected, @NonNull String maxRating) {
        int childTag = childDirected
                ? RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                : RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_UNSPECIFIED;

        int underAgeTag = childDirected
                ? RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_TRUE
                : RequestConfiguration.TAG_FOR_UNDER_AGE_OF_CONSENT_UNSPECIFIED;

        RequestConfiguration config = new RequestConfiguration.Builder()
                .setTagForChildDirectedTreatment(childTag)
                .setTagForUnderAgeOfConsent(underAgeTag)
                .setMaxAdContentRating(maxRating)
                .build();

        MobileAds.setRequestConfiguration(config);

        if (!isInitialized) {
            isInitialized = true;
            MobileAds.initialize(activity.getApplicationContext(), initializationStatus ->
                    mainHandler.post(() -> {
                        loadAppOpenAd();
                        loadInterstitialAd();
                        if (isContentReady) {
                            loadNativeAdvancedAd();
                        }
                    })
            );
        }
    }

    /**
     * Dipanggil saat halaman utama Blogspot selesai dimuat (true) atau saat offline (false).
     */
    public void setContentReady(boolean ready) {
        mainHandler.post(() -> {
            this.isContentReady = ready;
            if (!ready) {
                updateNativeContainerVisibility();
                return;
            }

            if (currentNativeAd == null) {
                loadNativeAdvancedAd();
            } else {
                updateNativeContainerVisibility();
            }

            if (interstitialAd == null) {
                loadInterstitialAd();
            }

            if (!isAppOpenAdAvailable()) {
                loadAppOpenAd();
            } else if (!hasShownColdStartAppOpen && !isGameplayActive) {
                tryShowAppOpenAd(true);
            }
        });
    }

    public void setGameplayActive(boolean active) {
        mainHandler.post(() -> {
            this.isGameplayActive = active;
            updateNativeContainerVisibility();
        });
    }

    public void setNativeAdVisible(boolean visible) {
        mainHandler.post(() -> {
            this.isNativeAllowedByWeb = visible;
            updateNativeContainerVisibility();
        });
    }

    private void updateNativeContainerVisibility() {
        boolean shouldShow = isContentReady
                && !isGameplayActive
                && isNativeAllowedByWeb
                && !isShowingFullScreenAd
                && currentNativeAd != null;
        nativeAdContainer.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
    }

    private AdRequest buildCompliantAdRequest() {
        return new AdRequest.Builder().build();
    }

    // =========================================================================
    // 1. APP OPEN AD (PEMBUKAAN APLIKASI - COLD START & WARM START)
    // =========================================================================
    public void loadAppOpenAd() {
        if (activity.isFinishing() || activity.isDestroyed() || isLoadingAppOpen || isAppOpenAdAvailable()) {
            return;
        }
        isLoadingAppOpen = true;

        AppOpenAd.load(activity, AD_UNIT_APP_OPEN, buildCompliantAdRequest(),
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd ad) {
                        appOpenAd = ad;
                        isLoadingAppOpen = false;
                        appOpenLoadTimeMs = new Date().getTime();

                        // Tampilkan saat pembukaan awal jika beranda sudah siap dan pemain belum mulai soal
                        if (isContentReady && !hasShownColdStartAppOpen && !isGameplayActive && !isShowingFullScreenAd) {
                            tryShowAppOpenAd(true);
                        }
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        isLoadingAppOpen = false;
                    }
                });
    }

    private boolean isAppOpenAdAvailable() {
        if (appOpenAd == null) return false;
        long fourHoursMs = 4L * 3600L * 1000L;
        return (new Date().getTime() - appOpenLoadTimeMs) < fourHoursMs;
    }

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        // Dipanggil saat aplikasi kembali dari latar belakang (warm start)
        if (!isContentReady || isGameplayActive || isShowingFullScreenAd) {
            return;
        }
        tryShowAppOpenAd(false);
    }

    private void tryShowAppOpenAd(boolean isColdStart) {
        if (activity.isFinishing() || activity.isDestroyed() || isShowingFullScreenAd) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!isColdStart && (now - lastFullScreenAdDismissedMs) < APP_OPEN_COOLDOWN_MS) {
            return;
        }

        if (!isAppOpenAdAvailable()) {
            loadAppOpenAd();
            return;
        }

        if (isColdStart) {
            hasShownColdStartAppOpen = true;
        }

        appOpenAd.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdShowedFullScreenContent() {
                isShowingFullScreenAd = true;
                updateNativeContainerVisibility();
                evaluateJs("if(window.onAppOpenAdOpened){window.onAppOpenAdOpened();}");
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                isShowingFullScreenAd = false;
                appOpenAd = null;
                lastFullScreenAdDismissedMs = System.currentTimeMillis();
                updateNativeContainerVisibility();
                evaluateJs("if(window.onAppOpenAdClosed){window.onAppOpenAdClosed();}");
                loadAppOpenAd();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                isShowingFullScreenAd = false;
                appOpenAd = null;
                updateNativeContainerVisibility();
                evaluateJs("if(window.onAppOpenAdClosed){window.onAppOpenAdClosed();}");
                loadAppOpenAd();
            }
        });

        appOpenAd.show(activity);
    }

    // =========================================================================
    // 2. NATIVE ADVANCED AD (KHUSUS LAYAR BERANDA / NON-GAMEPLAY)
    // =========================================================================
    public void loadNativeAdvancedAd() {
        if (activity.isFinishing() || activity.isDestroyed() || isLoadingNative) {
            return;
        }
        isLoadingNative = true;

        AdLoader adLoader = new AdLoader.Builder(activity, AD_UNIT_NATIVE)
                .forNativeAd(nativeAd -> {
                    isLoadingNative = false;
                    if (activity.isDestroyed() || activity.isFinishing()) {
                        nativeAd.destroy();
                        return;
                    }
                    if (currentNativeAd != null) {
                        currentNativeAd.destroy();
                    }
                    currentNativeAd = nativeAd;

                    NativeAdView adView = (NativeAdView) LayoutInflater.from(activity)
                            .inflate(R.layout.ad_native_layout, nativeAdContainer, false);
                    populateNativeAdView(nativeAd, adView);

                    nativeAdContainer.removeAllViews();
                    nativeAdContainer.addView(adView);
                    updateNativeContainerVisibility();
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        isLoadingNative = false;
                        nativeAdContainer.setVisibility(View.GONE);
                    }
                })
                .withNativeAdOptions(new NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .build())
                .build();

        adLoader.loadAd(buildCompliantAdRequest());
    }

    private void populateNativeAdView(@NonNull NativeAd nativeAd, @NonNull NativeAdView adView) {
        TextView headlineView = adView.findViewById(R.id.ad_headline);
        TextView bodyView = adView.findViewById(R.id.ad_body);
        TextView advertiserView = adView.findViewById(R.id.ad_advertiser);
        Button ctaView = adView.findViewById(R.id.ad_call_to_action);
        ImageView iconView = adView.findViewById(R.id.ad_app_icon);
        MediaView mediaView = adView.findViewById(R.id.ad_media);

        adView.setHeadlineView(headlineView);
        adView.setBodyView(bodyView);
        adView.setAdvertiserView(advertiserView);
        adView.setCallToActionView(ctaView);
        adView.setIconView(iconView);
        adView.setMediaView(mediaView);

        headlineView.setText(nativeAd.getHeadline());

        if (nativeAd.getMediaContent() != null) {
            mediaView.setVisibility(View.VISIBLE);
            mediaView.setMediaContent(nativeAd.getMediaContent());
        } else {
            mediaView.setVisibility(View.GONE);
        }

        if (nativeAd.getBody() != null) {
            bodyView.setVisibility(View.VISIBLE);
            bodyView.setText(nativeAd.getBody());
        } else {
            bodyView.setVisibility(View.GONE);
        }

        if (nativeAd.getCallToAction() != null) {
            ctaView.setVisibility(View.VISIBLE);
            ctaView.setText(nativeAd.getCallToAction());
        } else {
            ctaView.setVisibility(View.INVISIBLE);
        }

        if (nativeAd.getIcon() != null) {
            iconView.setVisibility(View.VISIBLE);
            iconView.setImageDrawable(nativeAd.getIcon().getDrawable());
        } else {
            iconView.setVisibility(View.GONE);
        }

        if (nativeAd.getAdvertiser() != null) {
            advertiserView.setVisibility(View.VISIBLE);
            advertiserView.setText(nativeAd.getAdvertiser());
        } else {
            advertiserView.setVisibility(View.GONE);
        }

        adView.setNativeAd(nativeAd);
    }

    // =========================================================================
    // 3. INTERSTITIAL AD (TRANSISI ANTAR-LEVEL / SELESAI SESI)
    // =========================================================================
    public void loadInterstitialAd() {
        if (activity.isFinishing() || activity.isDestroyed() || interstitialAd != null) {
            return;
        }

        InterstitialAd.load(activity, AD_UNIT_INTERSTITIAL, buildCompliantAdRequest(),
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd ad) {
                        interstitialAd = ad;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        interstitialAd = null;
                    }
                });
    }

    public void showInterstitialAd(@NonNull String placement) {
        mainHandler.post(() -> {
            if (isShowingFullScreenAd || interstitialAd == null) {
                evaluateJs("if(window.onAdClosed){window.onAdClosed();}");
                if (interstitialAd == null) loadInterstitialAd();
                return;
            }

            interstitialAd.setFullScreenContentCallback(new FullScreenContentCallback() {
                @Override
                public void onAdShowedFullScreenContent() {
                    isShowingFullScreenAd = true;
                    updateNativeContainerVisibility();
                    evaluateJs("if(window.onAdOpened){window.onAdOpened();}");
                }

                @Override
                public void onAdDismissedFullScreenContent() {
                    isShowingFullScreenAd = false;
                    interstitialAd = null;
                    lastFullScreenAdDismissedMs = System.currentTimeMillis();
                    updateNativeContainerVisibility();
                    evaluateJs("if(window.onAdClosed){window.onAdClosed();}");
                    loadInterstitialAd();
                    // Segarkan Native Ad setiap kali pengguna kembali dari Interstitial
                    loadNativeAdvancedAd();
                }

                @Override
                public void onAdFailedToShowFullScreenContent(@NonNull AdError adError) {
                    isShowingFullScreenAd = false;
                    interstitialAd = null;
                    updateNativeContainerVisibility();
                    evaluateJs("if(window.onAdClosed){window.onAdClosed();}");
                    loadInterstitialAd();
                }
            });

            interstitialAd.show(activity);
        });
    }

    // =========================================================================
    // LIFECYCLE & UTILITAS WEBVIEW
    // =========================================================================
    public void onDestroy() {
        ProcessLifecycleOwner.get().getLifecycle().removeObserver(this);
        if (currentNativeAd != null) {
            currentNativeAd.destroy();
            currentNativeAd = null;
        }
    }

    private void evaluateJs(@NonNull String script) {
        mainHandler.post(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed() && webView != null) {
                webView.evaluateJavascript(script, null);
            }
        });
    }
}
