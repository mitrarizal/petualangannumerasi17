package com.petualangannumerasi.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.nativead.MediaView;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdOptions;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final String BLOG_URL = "https://petualangannumerasi17.blogspot.com/?m=1";
    private static final String BLOG_HOST = "petualangannumerasi17.blogspot.com";

    // GANTI DENGAN UNIT ID NATIVE ADVANCED ADMOB PRODUKSI SAAT RILIS
    private static final String NATIVE_AD_UNIT_ID = "ca-app-pub-3940256099942544/2247696110";

    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout offlineContainer;
    private FrameLayout nativeAdContainer;
    private View adDivider;

    private NativeAd currentNativeAd;
    private boolean isAdLoading = false;
    private final AtomicBoolean isMobileAdsInitialized = new AtomicBoolean(false);
    private ConsentInformation consentInformation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        loadOfflineAssetImage();
        setupWebView();
        setupBackNavigation();
        initConsentAndAds();

        loadBlogContent();
    }

    private void initViews() {
        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        offlineContainer = findViewById(R.id.offlineContainer);
        nativeAdContainer = findViewById(R.id.nativeAdContainer);
        adDivider = findViewById(R.id.adDivider);

        Button btnRetry = findViewById(R.id.btnRetry);
        btnRetry.setOnClickListener(v -> loadBlogContent());
    }

    private void loadOfflineAssetImage() {
        ImageView ivNoInternet = findViewById(R.id.ivNoInternet);
        try (InputStream is = getAssets().open("no_internet.png")) {
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            ivNoInternet.setImageBitmap(bitmap);
        } catch (IOException e) {
            ivNoInternet.setImageResource(android.R.drawable.ic_dialog_alert);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // Keamanan Google Play Store: blokir akses file lokal dari WebView
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host != null && (host.equals(BLOG_HOST) || host.endsWith(".blogspot.com") || host.endsWith(".google.com"))) {
                    return false;
                }
                // Buka tautan eksternal di browser sistem agar tidak terjebak di WebView
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                    startActivity(intent);
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                // Muat iklan hanya sekali jika belum tersedia agar tidak terjadi penumpukan
                if (currentNativeAd == null && !isAdLoading && isMobileAdsInitialized.get()) {
                    loadNativeAd();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showOfflineState();
                }
            }
        });
    }

    private void loadBlogContent() {
        if (isNetworkAvailable()) {
            offlineContainer.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            if (webView.getUrl() == null) {
                webView.loadUrl(BLOG_URL);
            } else {
                webView.reload();
            }
        } else {
            showOfflineState();
        }
    }

    private void showOfflineState() {
        progressBar.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        offlineContainer.setVisibility(View.VISIBLE);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return capabilities != null && (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        );
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void initConsentAndAds() {
        ConsentRequestParameters params = new ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false)
                .build();

        consentInformation = UserMessagingPlatform.getConsentInformation(this);
        consentInformation.requestConsentInfoUpdate(
                this,
                params,
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(
                        this,
                        loadAndShowError -> {
                            if (consentInformation.canRequestAds()) {
                                initializeMobileAdsSdk();
                            }
                        }
                ),
                requestConsentError -> {
                    if (consentInformation.canRequestAds()) {
                        initializeMobileAdsSdk();
                    }
                }
        );

        if (consentInformation.canRequestAds()) {
            initializeMobileAdsSdk();
        }
    }

    private void initializeMobileAdsSdk() {
        if (isMobileAdsInitialized.getAndSet(true)) {
            return;
        }
        MobileAds.initialize(this, initializationStatus -> loadNativeAd());
    }

    private void loadNativeAd() {
        if (isAdLoading || isDestroyed() || isFinishing() || !isNetworkAvailable()) {
            return;
        }

        isAdLoading = true;

        NativeAdOptions adOptions = new NativeAdOptions.Builder()
                .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                .setRequestMultipleImages(false)
                .build();

        AdLoader adLoader = new AdLoader.Builder(this, NATIVE_AD_UNIT_ID)
                .forNativeAd(nativeAd -> {
                    isAdLoading = false;
                    if (isDestroyed() || isFinishing()) {
                        nativeAd.destroy();
                        return;
                    }

                    // Hancurkan referensi iklan sebelumnya untuk mencegah kebocoran memori & tumpukan
                    if (currentNativeAd != null) {
                        currentNativeAd.destroy();
                    }
                    currentNativeAd = nativeAd;

                    NativeAdView adView = (NativeAdView) getLayoutInflater()
                            .inflate(R.layout.native_ad_layout, nativeAdContainer, false);
                    populateNativeAdView(nativeAd, adView);

                    // Bersihkan wadah sebelum menambahkan tampilan baru (Anti-Ad Stacking)
                    nativeAdContainer.removeAllViews();
                    nativeAdContainer.addView(adView);
                    adDivider.setVisibility(View.VISIBLE);
                    nativeAdContainer.setVisibility(View.VISIBLE);
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        isAdLoading = false;
                        if (currentNativeAd == null) {
                            adDivider.setVisibility(View.GONE);
                            nativeAdContainer.setVisibility(View.GONE);
                        }
                    }
                })
                .withNativeAdOptions(adOptions)
                .build();

        adLoader.loadAd(new AdRequest.Builder().build());
    }

    private void populateNativeAdView(NativeAd nativeAd, NativeAdView adView) {
        TextView headlineView = adView.findViewById(R.id.ad_headline);
        TextView bodyView = adView.findViewById(R.id.ad_body);
        TextView advertiserView = adView.findViewById(R.id.ad_advertiser);
        Button callToActionView = adView.findViewById(R.id.ad_call_to_action);
        ImageView iconView = adView.findViewById(R.id.ad_app_icon);
        MediaView mediaView = adView.findViewById(R.id.ad_media);

        adView.setHeadlineView(headlineView);
        adView.setBodyView(bodyView);
        adView.setAdvertiserView(advertiserView);
        adView.setCallToActionView(callToActionView);
        adView.setIconView(iconView);
        adView.setMediaView(mediaView);

        headlineView.setText(nativeAd.getHeadline());

        if (nativeAd.getBody() != null) {
            bodyView.setVisibility(View.VISIBLE);
            bodyView.setText(nativeAd.getBody());
        } else {
            bodyView.setVisibility(View.GONE);
        }

        if (nativeAd.getCallToAction() != null) {
            callToActionView.setVisibility(View.VISIBLE);
            callToActionView.setText(nativeAd.getCallToAction());
        } else {
            callToActionView.setVisibility(View.GONE);
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

        // Tampilkan MediaView hanya jika iklan memiliki aset media video/gambar utama
        if (nativeAd.getMediaContent() != null && nativeAd.getMediaContent().hasVideoContent()) {
            mediaView.setVisibility(View.VISIBLE);
            mediaView.setMediaContent(nativeAd.getMediaContent());
        } else {
            mediaView.setVisibility(View.GONE);
        }

        adView.setNativeAd(nativeAd);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (currentNativeAd != null) {
            currentNativeAd.destroy();
            currentNativeAd = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
