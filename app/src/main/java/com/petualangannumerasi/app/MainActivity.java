package com.petualangannumerasi.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public class MainActivity extends AppCompatActivity {

    private static final String BLOGGER_GAME_URL = "https://petualangannumerasi17.blogspot.com/?m=1";

    private WebView webView;
    private FrameLayout nativeAdContainer;
    private AdManager adManager;
    private boolean isInGameSession = false;
    private boolean isOfflinePageShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        enableImmersiveFullscreen();

        webView = findViewById(R.id.game_webview);
        nativeAdContainer = findViewById(R.id.native_ad_container);

        adManager = new AdManager(this, webView, nativeAdContainer);

        setupWebView();
        setupBackNavigation();

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(BLOGGER_GAME_URL);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(Color.parseColor("#F0F9FF"));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        // Daftarkan jembatan JavaScript dengan nama AndroidAdMob & AndroidApp
        WebAppAdMobInterface jsBridge = new WebAppAdMobInterface();
        webView.addJavascriptInterface(jsBridge, "AndroidAdMob");
        webView.addJavascriptInterface(jsBridge, "AndroidApp");

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (url != null && url.startsWith("https://")) {
                    isOfflinePageShown = false;
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host != null && (host.contains("blogspot.com")
                        || host.contains("blogger.com")
                        || host.contains("script.google.com")
                        || host.contains("googleusercontent.com"))) {
                    return false;
                }

                // Buka tautan eksternal (misal Kebijakan Privasi) di browser eksternal jika di luar sesi soal
                if (!isInGameSession && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (Exception ignored) {
                    }
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!isOfflinePageShown && url != null && url.startsWith("https://")) {
                    view.evaluateJavascript("if(window.setBannerHeight){window.setBannerHeight(0);}", null);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    isOfflinePageShown = true;
                    isInGameSession = false;
                    adManager.setContentReady(false);
                    showOfflineScreen(view);
                }
            }
        });
    }

    private void showOfflineScreen(@NonNull WebView view) {
        String offlineHtml = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'>"
                + "<style>body{margin:0;padding:24px;font-family:sans-serif;background:#F0F9FF;color:#0F172A;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:90vh;text-align:center;}"
                + "img{max-width:200px;height:auto;margin-bottom:16px;}"
                + "h2{margin:0 0 8px;font-size:20px;}"
                + "p{margin:0 0 20px;color:#475569;font-size:14px;}"
                + "button{background:#0284C7;color:#fff;border:none;border-radius:10px;padding:12px 24px;"
                + "font-size:15px;font-weight:bold;cursor:pointer;}</style></head><body>"
                + "<img src='file:///android_asset/no_internet.png' alt='Offline' />"
                + "<h2>Koneksi Internet Terputus</h2>"
                + "<p>Periksa jaringan Wi-Fi atau data seluler Anda, lalu muat ulang petualangan.</p>"
                + "<button onclick=\"window.location.href='" + BLOGGER_GAME_URL + "'\">Coba Lagi</button>"
                + "</body></html>";
        view.loadDataWithBaseURL("file:///android_asset/", offlineHtml, "text/html", "UTF-8", null);
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (isInGameSession && webView != null) {
                    webView.evaluateJavascript("if(typeof confirmQuitLevel==='function'){confirmQuitLevel();}", null);
                } else if (webView != null && webView.canGoBack() && !isOfflinePageShown) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });
    }

    private void enableImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveFullscreen();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (adManager != null) adManager.onDestroy();
        if (webView != null) {
            webView.removeAllViews();
            webView.destroy();
        }
        super.onDestroy();
    }

    /**
     * Jembatan metode JavaScript yang dipanggil oleh skrip Blogspot.
     * Fokus iklan aktif: App Open Ad, Native Advanced, dan Interstitial Ad.
     */
    private class WebAppAdMobInterface {

        @JavascriptInterface
        public void configureFamiliesPolicy(boolean childDirected, String maxAdContentRating) {
            String rating = (maxAdContentRating != null && !maxAdContentRating.isEmpty())
                    ? maxAdContentRating : "G";
            adManager.applyFamiliesPolicy(childDirected, rating);
        }

        @JavascriptInterface
        public void onGameContentReady() {
            adManager.setContentReady(true);
        }

        @JavascriptInterface
        public void onContentLoaded() {
            adManager.setContentReady(true);
        }

        @JavascriptInterface
        public void setGameplayState(boolean active) {
            isInGameSession = active;
            adManager.setGameplayActive(active);
        }

        @JavascriptInterface
        public void setNativeAdVisible(boolean visible) {
            adManager.setNativeAdVisible(visible);
        }

        @JavascriptInterface
        public void showInterstitial(String placement) {
            adManager.showInterstitialAd(placement != null ? placement : "level_transition");
        }

        @JavascriptInterface
        public void panggilIklanInterstisial() {
            adManager.showInterstitialAd("level_transition");
        }

        // Safe fallback jika skrip lama di Blogspot masih memanggil fungsi banner/rewarded
        @JavascriptInterface
        public int getBannerHeightPx() {
            return 0;
        }

        @JavascriptInterface
        public void showRewardedAd(String rewardType) {
            String safeType = rewardType != null ? rewardType.replace("\\", "\\\\").replace("'", "\\'") : "extra_life";
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript("if(window.onRewardEarned){window.onRewardEarned('" + safeType + "');}", null);
                }
            });
        }

        @JavascriptInterface
        public void panggilIklanRewarded() {
            showRewardedAd("extra_life");
        }

        @JavascriptInterface
        public void onNativeAdClicked() {
            // Pelacakan klik ditangani otomatis oleh NativeAdView SDK
        }

        @JavascriptInterface
        public void onScoreUpdated(String jsonPayload) {
            // Hook opsional
        }
    }
}
