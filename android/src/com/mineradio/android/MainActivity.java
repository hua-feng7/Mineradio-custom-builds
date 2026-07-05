package com.mineradio.android;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final String TAG = "MineradioAndroid";
    private static final int FILE_CHOOSER_REQUEST = 4201;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 4202;
    private static final String PREFS_NAME = "mineradio_android_state";
    private static final String PREF_NOTIFICATION_PERMISSION_ASKED = "post_notifications_permission_asked";
    private static final String APP_ASSET_VERSION = "20260706-lyric-depth-exit-hold";
    private static final String DESKTOP_WEBVIEW_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MineradioAndroid/1.0";
    private static final String QQ_MUSIC_LOGIN_URL = "https://y.qq.com/n/ryqq/profile";
    private static final String QQ_MUSIC_WARMUP_URL = "https://y.qq.com/n/ryqq/player";
    private static final String QQ_MUSIC_QQ_QR_URL =
            "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609"
                    + "&pt_3rd_aid=100497308&daid=383&pt_skey_valid=0&style=35"
                    + "&s_url=https%3A%2F%2Fconnect.qq.com&refer_cgi=authorize&which="
                    + "&sdkp=pcweb&sdkv=v1.0&loginty=3&response_type=code&client_id=100497308"
                    + "&redirect_uri=https%3A%2F%2Fy.qq.com%2Fportal%2Fwx_redirect.html%3Flogin_type%3D1%26surl%3Dhttps%253A%252F%252Fy.qq.com%252Fn%252Fryqq_v2%252Fprofile"
                    + "&state=state&display=pc&scope=get_user_info%2Cget_app_friends";
    private WebView webView;
    private LocalHttpServer localServer;
    private ValueCallback<Uri[]> filePathCallback;
    private Dialog qqLoginDialog;
    private WebView qqLoginWebView;
    private boolean qqLoginWarmupStarted;
    private Dialog neteaseLoginDialog;
    private WebView neteaseLoginWebView;
    private BroadcastReceiver mediaControlReceiver;
    private boolean playbackActive;
    private boolean notificationPermissionAsked;
    private float preferredRefreshRate;

    private static class RefreshProfile {
        float refreshRate;
        int modeId;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        configureWindow();

        try {
            localServer = new LocalHttpServer(this);
            localServer.start();
        } catch (IOException e) {
            showFatalError("Failed to start local server: " + e.getMessage());
            return;
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        applyHighRefreshToView(webView);
        setContentView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        hideSystemBars();

        clearMainWebViewResourceCache(webView);
        configureWebView(webView);
        registerMediaControlReceiver();
        webView.loadUrl(localServer.baseUrl() + "?v=" + APP_ASSET_VERSION);
    }

    private void clearMainWebViewResourceCache(WebView view) {
        if (view == null) return;
        try {
            view.clearCache(true);
            view.clearHistory();
            view.clearFormData();
        } catch (Throwable e) {
            Log.w(TAG, "clear webview cache failed", e);
        }
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        applyHighRefreshToWindow(window);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = window.getAttributes();
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attrs);
        }
        hideSystemBars();
    }

    private void applyHighRefreshToWindow(Window window) {
        RefreshProfile profile = findBestRefreshProfile();
        if (profile.refreshRate <= 0f) return;
        preferredRefreshRate = profile.refreshRate;
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.preferredRefreshRate = profile.refreshRate;
        if (Build.VERSION.SDK_INT >= 23 && profile.modeId > 0) {
            attrs.preferredDisplayModeId = profile.modeId;
        }
        window.setAttributes(attrs);
    }

    private RefreshProfile findBestRefreshProfile() {
        RefreshProfile profile = new RefreshProfile();
        Display display = null;
        if (Build.VERSION.SDK_INT >= 30) {
            display = getDisplay();
        }
        if (display == null) {
            display = getWindowManager().getDefaultDisplay();
        }
        if (display == null) return profile;
        profile.refreshRate = Math.max(0f, display.getRefreshRate());
        if (Build.VERSION.SDK_INT >= 23) {
            Display.Mode current = display.getMode();
            Display.Mode[] modes = display.getSupportedModes();
            if (current != null && modes != null) {
                for (Display.Mode mode : modes) {
                    if (mode == null) continue;
                    if (mode.getPhysicalWidth() != current.getPhysicalWidth()
                            || mode.getPhysicalHeight() != current.getPhysicalHeight()) {
                        continue;
                    }
                    if (mode.getRefreshRate() > profile.refreshRate) {
                        profile.refreshRate = mode.getRefreshRate();
                        profile.modeId = mode.getModeId();
                    }
                }
            }
        }
        return profile;
    }

    private void applyHighRefreshToView(View view) {
        if (view == null) return;
        view.setKeepScreenOn(true);
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        if (Build.VERSION.SDK_INT >= 16) {
            view.postInvalidateOnAnimation();
        }
        if (Build.VERSION.SDK_INT >= 30 && preferredRefreshRate > 0f) {
            try {
                View.class.getMethod("setFrameRate", float.class, int.class)
                        .invoke(view, preferredRefreshRate, 0);
            } catch (Throwable ignored) {
            }
        }
    }

    private void hideSystemBars() {
        View decorView = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decorView == null ? null : decorView.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        if (decorView != null) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void configureWebView(WebView view) {
        WebView.setWebContentsDebuggingEnabled(true);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setTextZoom(100);
        settings.setLoadsImagesAutomatically(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setRenderPriority(WebSettings.RenderPriority.HIGH);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            settings.setOffscreenPreRaster(true);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            settings.setSafeBrowsingEnabled(false);
        }
        String ua = settings.getUserAgentString();
        settings.setUserAgentString(ua + " MineradioAndroid/1.0");

        view.addJavascriptInterface(new AndroidBridge(), "MineradioAndroidBridge");
        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return false;
                if ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost())) return false;
                openExternal(uri.toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectAndroidRuntime(view);
                view.postDelayed(new Runnable() {
                    @Override public void run() {
                        requestNotificationPermissionIfNeeded();
                    }
                }, 2500);
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, consoleMessage.message() + " @ " + consoleMessage.sourceId() + ":" + consoleMessage.lineNumber());
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (Build.VERSION.SDK_INT >= 21) {
                    request.grant(request.getResources());
                }
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    private void injectAndroidRuntime(WebView view) {
        String refreshRate = String.format(Locale.US, "%.2f", preferredRefreshRate > 0f ? preferredRefreshRate : 0f);
        String js = "(function(){"
                + "document.documentElement.classList.add('android-webview-root');"
                + "if(document.body)document.body.classList.add('android-webview');"
                + "window.__mineradioAndroidHighRefresh=true;"
                + "window.MineradioAndroid={isAndroid:true,version:'1.0',highRefreshRequested:true,preferredRefreshRate:" + refreshRate + ",preferNeteaseQrLogin:true,"
                + "toast:function(m){try{MineradioAndroidBridge.toast(String(m||''));}catch(e){}}," 
                + "openExternal:function(u){try{MineradioAndroidBridge.openExternal(String(u||''));}catch(e){}},"
                + "openNeteaseMusicLogin:function(){return window.desktopWindow&&window.desktopWindow.openNeteaseMusicLogin?window.desktopWindow.openNeteaseMusicLogin():Promise.reject(new Error('Android Netease login bridge unavailable'));},"
                + "openQQMusicLogin:function(){return window.desktopWindow&&window.desktopWindow.openQQMusicLogin?window.desktopWindow.openQQMusicLogin():Promise.reject(new Error('Android QQ login bridge unavailable'));}};"
                + "window.__MineradioAndroidLoginCallbacks=window.__MineradioAndroidLoginCallbacks||{};"
                + "window.__MineradioAndroidResolveLogin=function(id,payload){var cb=window.__MineradioAndroidLoginCallbacks&&window.__MineradioAndroidLoginCallbacks[id];if(cb){delete window.__MineradioAndroidLoginCallbacks[id];cb(payload||{});}};"
                + "window.desktopWindow=window.desktopWindow||{};"
                + "window.desktopWindow.isDesktop=true;"
                + "window.desktopWindow.preferNeteaseQrLogin=true;"
                + "window.desktopWindow.openNeteaseMusicLogin=function(){return new Promise(function(resolve){var id='ne_'+Date.now()+'_'+Math.random().toString(36).slice(2);window.__MineradioAndroidLoginCallbacks[id]=resolve;try{MineradioAndroidBridge.openNeteaseMusicLogin(id);}catch(e){delete window.__MineradioAndroidLoginCallbacks[id];resolve({ok:false,error:String(e&&e.message||e)});}});};"
                + "window.desktopWindow.openQQMusicLogin=function(){return new Promise(function(resolve){var id='qq_'+Date.now()+'_'+Math.random().toString(36).slice(2);window.__MineradioAndroidLoginCallbacks[id]=resolve;try{MineradioAndroidBridge.openQQMusicLogin(id);}catch(e){delete window.__MineradioAndroidLoginCallbacks[id];resolve({ok:false,error:String(e&&e.message||e)});}});};"
                + "try{navigator.mediaSession&&navigator.mediaSession.setActionHandler&&navigator.mediaSession.setActionHandler('seekbackward',function(){});}catch(e){}"
                + "if(!document.getElementById('android-touch-adapter-script')){"
                + "var s=document.createElement('script');"
                + "s.id='android-touch-adapter-script';"
                + "s.src='/android-touch-adapter.js?v=5';"
                + "s.defer=true;"
                + "(document.head||document.documentElement).appendChild(s);"
                + "}"
                + "if(!document.getElementById('android-media-session-script')){"
                + "var ms=document.createElement('script');"
                + "ms.id='android-media-session-script';"
                + "ms.src='/android-media-session.js?v=1';"
                + "ms.defer=true;"
                + "(document.head||document.documentElement).appendChild(ms);"
                + "}"
                + "})();";
        if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(js, null);
        else view.loadUrl("javascript:" + js);
    }

    private void showFatalError(String message) {
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextColor(Color.WHITE);
        text.setBackgroundColor(Color.BLACK);
        text.setPadding(32, 32, 32, 32);
        setContentView(text);
    }

    private void openExternal(String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressLint({"SetJavaScriptEnabled"})
    private void openNeteaseMusicLoginDialog(final String requestId) {
        if (neteaseLoginDialog != null && neteaseLoginDialog.isShowing()) {
            neteaseLoginDialog.dismiss();
        }
        final Dialog dialog = new Dialog(this);
        neteaseLoginDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 10, 12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(18, 12, 18, 12);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("网易云音乐登录");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, titleParams);
        Button done = new Button(this);
        done.setText("完成");
        Button close = new Button(this);
        close.setText("关闭");
        bar.addView(done);
        bar.addView(close);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6));

        final WebView loginView = new WebView(this);
        neteaseLoginWebView = loginView;
        configureNeteaseLoginWebView(loginView, requestId, progress);
        root.addView(loginView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finishNeteaseLogin(requestId, true, "user-finished");
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                resolveNeteaseLogin(requestId, false, "", "cancelled");
                dialog.dismiss();
            }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialogInterface) {
                if (neteaseLoginWebView != null) {
                    neteaseLoginWebView.stopLoading();
                    neteaseLoginWebView.destroy();
                    neteaseLoginWebView = null;
                }
                neteaseLoginDialog = null;
            }
        });
        dialog.setContentView(root);
        dialog.show();
        Window shown = dialog.getWindow();
        if (shown != null) {
            shown.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            shown.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) manager.setAcceptThirdPartyCookies(loginView, true);
        loginView.loadUrl("https://music.163.com/#/login");
    }

    private void configureNeteaseLoginWebView(final WebView loginView, final String requestId, final ProgressBar progress) {
        WebSettings settings = loginView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 MineradioAndroid/1.0");
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        loginView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri == null) return false;
                String host = uri.getHost();
                if (host == null) return false;
                return false;
            }

            @Override public void onPageFinished(WebView view, String url) {
                captureNeteaseCookieIfReady(requestId, false, "page-finished");
            }
        });
        loginView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                if (newProgress >= 70) captureNeteaseCookieIfReady(requestId, false, "progress");
            }
        });
    }

    private void finishNeteaseLogin(String requestId, boolean allowPartial, String reason) {
        if (!captureNeteaseCookieIfReady(requestId, allowPartial, reason)) {
            resolveNeteaseLogin(requestId, false, "", "NetEase cookie not ready, please finish login first.");
        }
    }

    private boolean captureNeteaseCookieIfReady(String requestId, boolean allowPartial, String reason) {
        String cookie = collectNeteaseCookies();
        if (cookie.isEmpty()) return false;
        try {
            if (localServer == null) return false;
            org.json.JSONObject result = localServer.saveNeteaseCookieFromNative(cookie);
            if (result.optBoolean("ok", false) || (allowPartial && result.optBoolean("hasCookie", false))) {
                resolveNeteaseLogin(requestId, result.optBoolean("ok", false), cookie, reason, result);
                if (neteaseLoginDialog != null && neteaseLoginDialog.isShowing()) neteaseLoginDialog.dismiss();
                Toast.makeText(this, result.optBoolean("ok", false) ? "网易云音乐登录已保存" : "已读取网易云 Cookie，但授权不完整", Toast.LENGTH_SHORT).show();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Netease native login cookie save failed", e);
        }
        return false;
    }

    private String collectNeteaseCookies() {
        CookieManager manager = CookieManager.getInstance();
        StringBuilder out = new StringBuilder();
        appendCookie(out, manager.getCookie("https://music.163.com"));
        appendCookie(out, manager.getCookie("https://interface.music.163.com"));
        appendCookie(out, manager.getCookie("https://api.music.163.com"));
        return out.toString();
    }

    private void resolveNeteaseLogin(String requestId, boolean ok, String cookie, String reason) {
        resolveNeteaseLogin(requestId, ok, cookie, reason, null);
    }

    private void resolveNeteaseLogin(String requestId, boolean ok, String cookie, String reason, org.json.JSONObject status) {
        if (webView == null || requestId == null || requestId.isEmpty()) return;
        StringBuilder payload = new StringBuilder();
        payload.append("{ok:").append(ok ? "true" : "false");
        payload.append(",provider:'netease'");
        payload.append(",cookie:'").append(escapeJs(cookie == null ? "" : cookie)).append("'");
        payload.append(",message:'").append(escapeJs(reason == null ? "" : reason)).append("'");
        if (status != null) {
            payload.append(",loggedIn:").append(status.optBoolean("loggedIn", false) ? "true" : "false");
            payload.append(",nickname:'").append(escapeJs(status.optString("nickname", ""))).append("'");
            payload.append(",userId:'").append(escapeJs(status.optString("userId", ""))).append("'");
        }
        payload.append("}");
        String js = "window.__MineradioAndroidResolveLogin&&window.__MineradioAndroidResolveLogin('"
                + escapeJs(requestId) + "'," + payload + ");";
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null);
        else webView.loadUrl("javascript:" + js);
    }

    @SuppressLint({"SetJavaScriptEnabled"})
    private void openQQMusicLoginDialog(final String requestId) {
        if (qqLoginDialog != null && qqLoginDialog.isShowing()) {
            qqLoginDialog.dismiss();
        }
        qqLoginWarmupStarted = false;
        final Dialog dialog = new Dialog(this);
        qqLoginDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(8, 10, 12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(18, 12, 18, 12);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("QQ 音乐登录");
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, titleParams);
        final Button loginMode = new Button(this);
        loginMode.setText("网页登录");
        Button done = new Button(this);
        done.setText("完成");
        Button close = new Button(this);
        close.setText("关闭");
        bar.addView(loginMode);
        bar.addView(done);
        bar.addView(close);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6));

        final WebView loginView = new WebView(this);
        qqLoginWebView = loginView;
        configureLoginWebView(loginView, requestId, progress);
        root.addView(loginView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        done.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finishQQLogin(requestId, true, "user-finished");
            }
        });
        loginMode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (qqLoginWebView == null) return;
                String current = qqLoginWebView.getUrl();
                if (current != null && current.contains("xui.ptlogin2.qq.com/cgi-bin/xlogin")) {
                    qqLoginWebView.loadUrl(QQ_MUSIC_LOGIN_URL);
                    loginMode.setText("扫码登录");
                } else {
                    qqLoginWebView.loadUrl(QQ_MUSIC_QQ_QR_URL);
                    loginMode.setText("网页登录");
                }
            }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                resolveQQLogin(requestId, false, "", "cancelled");
                dialog.dismiss();
            }
        });
        dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialogInterface) {
                if (qqLoginWebView != null) {
                    qqLoginWebView.stopLoading();
                    qqLoginWebView.destroy();
                    qqLoginWebView = null;
                }
                qqLoginDialog = null;
            }
        });
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
        dialog.show();
        Window shown = dialog.getWindow();
        if (shown != null) {
            shown.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            shown.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        CookieManager manager = CookieManager.getInstance();
        manager.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= 21) manager.setAcceptThirdPartyCookies(loginView, true);
        loginView.loadUrl(QQ_MUSIC_QQ_QR_URL);
    }

    private void configureLoginWebView(final WebView loginView, final String requestId, final ProgressBar progress) {
        WebSettings settings = loginView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUserAgentString(DESKTOP_WEBVIEW_UA);
        if (Build.VERSION.SDK_INT >= 21) settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        loginView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                if (uri == null) return false;
                String scheme = uri.getScheme();
                if (scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    Log.i(TAG, "Blocked external QQ login scheme because it cannot return WebView cookies: " + uri);
                    Toast.makeText(MainActivity.this, "QQ App 登录不会回传到 Mineradio，已切回扫码登录", Toast.LENGTH_SHORT).show();
                    view.loadUrl(QQ_MUSIC_QQ_QR_URL);
                    return true;
                }
                return false;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (url != null && url.contains("ptlogin2.qq.com")) forceQQQrLogin(view);
                captureQQCookieIfReady(requestId, false, "page-finished");
            }
        });
        loginView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                if (newProgress >= 70) captureQQCookieIfReady(requestId, false, "progress");
            }
        });
    }

    private void forceQQQrLogin(WebView view) {
        if (view == null) return;
        String css = "html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;overflow:hidden!important;background:#fff!important;}"
                + ".page_header_upice,#pwdlogin,#q_login,#switch,#zc_feedback,.bottom_link,#download-link-pad,#download-link,.qrimg_mask{display:none!important;}"
                + "#content,.page_content,#web_login{display:block!important;width:100%!important;height:100%!important;margin:0!important;padding:0!important;}"
                + "#qrlogin{display:flex!important;flex-direction:column!important;align-items:center!important;justify-content:center!important;position:fixed!important;inset:0!important;width:100%!important;height:100%!important;background:#fff!important;color:#333!important;}"
                + "#qrimg{display:block!important;width:min(42vh,260px)!important;height:min(42vh,260px)!important;opacity:1!important;margin:0 auto 14px!important;}"
                + "#qr_invalid{display:none!important;}"
                + "#qrlogin .title,#qrlogin .title_2{display:block!important;color:#333!important;text-align:center!important;font-size:16px!important;line-height:1.5!important;}"
                + "#qrlogin_switch{display:block!important;margin-top:10px!important;color:#666!important;font-size:13px!important;}";
        String js = "(function(){"
                + "var css='" + escapeJs(css) + "';"
                + "function run(){try{"
                + "if(window.pt&&pt.hideOneKey)pt.hideOneKey();"
                + "if(window.pt&&pt.switchqr)pt.switchqr();"
                + "var head=document.head||document.documentElement;"
                + "var style=document.getElementById('mineradio-qq-qr-style');"
                + "if(!style){style=document.createElement('style');style.id='mineradio-qq-qr-style';head.appendChild(style);}"
                + "if(style.textContent!==css)style.textContent=css;"
                + "var img=document.getElementById('qrimg');"
                + "if(img&&!img.src&&window.pt&&pt.qrcode&&pt.qrcode.get)pt.qrcode.get();"
                + "if(img&&img.src)img.style.opacity='1';"
                + "var qr=document.getElementById('qrlogin');if(qr)qr.style.display='flex';"
                + "}catch(e){console.warn('Mineradio QQ QR force failed',e);}}"
                + "run();setTimeout(run,400);setTimeout(run,1200);setTimeout(run,2600);"
                + "})();";
        if (Build.VERSION.SDK_INT >= 19) view.evaluateJavascript(js, null);
        else view.loadUrl("javascript:" + js);
    }

    private void finishQQLogin(String requestId, boolean allowPartial, String reason) {
        if (!captureQQCookieIfReady(requestId, allowPartial, reason)) {
            resolveQQLogin(requestId, false, "", "QQ cookie not ready, please finish login first.");
        }
    }

    private boolean captureQQCookieIfReady(String requestId, boolean allowPartial, String reason) {
        String cookie = collectQQCookies();
        if (cookie.isEmpty()) return false;
        try {
            if (localServer == null) return false;
            org.json.JSONObject result = localServer.saveQQCookieFromNative(cookie);
            if (result.optBoolean("ok", false)
                    && !result.optBoolean("playbackKeyReady", false)
                    && !allowPartial
                    && !qqLoginWarmupStarted) {
                qqLoginWarmupStarted = true;
                if (qqLoginWebView != null) {
                    qqLoginWebView.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (qqLoginWebView != null) qqLoginWebView.loadUrl(QQ_MUSIC_WARMUP_URL);
                        }
                    }, 900);
                }
                return false;
            }
            if (result.optBoolean("ok", false) || (allowPartial && result.optBoolean("hasCookie", false))) {
                resolveQQLogin(requestId, result.optBoolean("ok", false), cookie, reason, result);
                if (qqLoginDialog != null && qqLoginDialog.isShowing()) qqLoginDialog.dismiss();
                Toast.makeText(this, result.optBoolean("ok", false) ? "QQ 音乐登录已保存" : "已读取 QQ Cookie，但授权不完整", Toast.LENGTH_SHORT).show();
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "QQ native login cookie save failed", e);
        }
        return false;
    }

    private String collectQQCookies() {
        CookieManager manager = CookieManager.getInstance();
        StringBuilder out = new StringBuilder();
        appendCookie(out, manager.getCookie("https://y.qq.com"));
        appendCookie(out, manager.getCookie("https://u.y.qq.com"));
        appendCookie(out, manager.getCookie("https://c.y.qq.com"));
        appendCookie(out, manager.getCookie("https://i.y.qq.com"));
        appendCookie(out, manager.getCookie("https://y.qq.com/n/ryqq/profile"));
        appendCookie(out, manager.getCookie("https://y.qq.com/n/ryqq/player"));
        appendCookie(out, manager.getCookie("https://qqmusic.qq.com"));
        appendCookie(out, manager.getCookie("https://music.qq.com"));
        appendCookie(out, manager.getCookie("https://graph.qq.com"));
        appendCookie(out, manager.getCookie("https://ptlogin2.qq.com"));
        appendCookie(out, manager.getCookie("https://xui.ptlogin2.qq.com"));
        appendCookie(out, manager.getCookie("https://ssl.ptlogin2.qq.com"));
        appendCookie(out, manager.getCookie("https://ui.ptlogin2.qq.com"));
        appendCookie(out, manager.getCookie("https://qzone.qq.com"));
        appendCookie(out, manager.getCookie("https://qq.com"));
        return out.toString();
    }

    private void appendCookie(StringBuilder out, String cookie) {
        if (cookie == null || cookie.trim().isEmpty()) return;
        String[] parts = cookie.split(";");
        for (String part : parts) {
            String clean = part == null ? "" : part.trim();
            if (clean.isEmpty() || clean.indexOf('=') <= 0) continue;
            String key = clean.substring(0, clean.indexOf('=')).trim();
            if (containsCookieKey(out, key)) continue;
            if (out.length() > 0) out.append("; ");
            out.append(clean);
        }
    }

    private boolean containsCookieKey(StringBuilder cookies, String key) {
        if (cookies == null || key == null || key.isEmpty()) return false;
        String text = cookies.toString();
        return text.startsWith(key + "=") || text.contains("; " + key + "=");
    }

    private void resolveQQLogin(String requestId, boolean ok, String cookie, String reason) {
        resolveQQLogin(requestId, ok, cookie, reason, null);
    }

    private void resolveQQLogin(String requestId, boolean ok, String cookie, String reason, org.json.JSONObject status) {
        if (webView == null || requestId == null || requestId.isEmpty()) return;
        StringBuilder payload = new StringBuilder();
        payload.append("{ok:").append(ok ? "true" : "false");
        payload.append(",provider:'qq'");
        payload.append(",cookie:'").append(escapeJs(cookie == null ? "" : cookie)).append("'");
        payload.append(",message:'").append(escapeJs(reason == null ? "" : reason)).append("'");
        if (status != null) {
            payload.append(",loggedIn:").append(status.optBoolean("loggedIn", false) ? "true" : "false");
            payload.append(",playbackKeyReady:").append(status.optBoolean("playbackKeyReady", false) ? "true" : "false");
            payload.append(",partial:").append(status.optBoolean("playbackKeyReady", false) ? "false" : "true");
            payload.append(",nickname:'").append(escapeJs(status.optString("nickname", ""))).append("'");
            payload.append(",userId:'").append(escapeJs(status.optString("userId", status.optString("uin", "")))).append("'");
        }
        payload.append("}");
        String js = "window.__MineradioAndroidResolveLogin&&window.__MineradioAndroidResolveLogin('"
                + escapeJs(requestId) + "'," + payload + ");";
        if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null);
        else webView.loadUrl("javascript:" + js);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyHighRefreshToWindow(getWindow());
        applyHighRefreshToView(webView);
        hideSystemBars();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null && !playbackActive) webView.onPause();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webView == null) {
            return;
        }
        String js = "(function(){"
                + "var ids=['login-modal','user-modal','local-beat-modal','cover-crop-modal'];"
                + "for(var i=0;i<ids.length;i++){"
                + "var el=document.getElementById(ids[i]);"
                + "if(el&&el.classList&&el.classList.contains('show')){"
                + "if(ids[i]==='login-modal'&&typeof closeLoginModal==='function')closeLoginModal();"
                + "else if(ids[i]==='user-modal'&&typeof closeUserModal==='function')closeUserModal();"
                + "else el.classList.remove('show');"
                + "document.body&&document.body.classList&&document.body.classList.remove('login-guide-active');"
                + "return true;}}"
                + "var r=document.getElementById('search-results');"
                + "if(r&&r.classList&&r.classList.contains('show')){r.classList.remove('show');return true;}"
                + "if(document.activeElement&&document.activeElement.blur&&document.activeElement.tagName==='INPUT'){document.activeElement.blur();return true;}"
                + "if(window.MineradioAndroidTouch&&typeof window.MineradioAndroidTouch.handleBack==='function'&&window.MineradioAndroidTouch.handleBack())return true;"
                + "return false;"
                + "})();";
        if (Build.VERSION.SDK_INT >= 19) {
            webView.evaluateJavascript(js, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if ("true".equals(value)) return;
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        unregisterMediaControlReceiver();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        if (localServer != null) {
            localServer.stop();
            localServer = null;
        }
        stopService(new Intent(this, MineradioPlaybackService.class));
        super.onDestroy();
    }

    private void registerMediaControlReceiver() {
        if (mediaControlReceiver != null) return;
        mediaControlReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !MineradioPlaybackService.ACTION_CONTROL.equals(intent.getAction())) return;
                handleMediaControl(intent.getStringExtra(MineradioPlaybackService.EXTRA_CONTROL));
            }
        };
        IntentFilter filter = new IntentFilter(MineradioPlaybackService.ACTION_CONTROL);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mediaControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mediaControlReceiver, filter);
        }
    }

    private void unregisterMediaControlReceiver() {
        if (mediaControlReceiver == null) return;
        try {
            unregisterReceiver(mediaControlReceiver);
        } catch (Exception ignored) {
        }
        mediaControlReceiver = null;
    }

    private void handleMediaControl(final String action) {
        if (action == null || action.trim().isEmpty() || webView == null) return;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (webView == null) return;
                String js = "(function(){try{"
                        + "var a='" + escapeJs(action) + "';"
                        + "if(window.MineradioAndroid&&typeof window.MineradioAndroid.handleMediaAction==='function'){"
                        + "return window.MineradioAndroid.handleMediaAction(a);}"
                        + "if(a==='next'&&typeof nextTrack==='function')return nextTrack();"
                        + "if(a==='previous'&&typeof prevTrack==='function')return prevTrack();"
                        + "if(typeof togglePlay==='function')return togglePlay();"
                        + "}catch(e){console.warn('[AndroidMediaControl]',e);}return false;})();";
                if (Build.VERSION.SDK_INT >= 19) webView.evaluateJavascript(js, null);
                else webView.loadUrl("javascript:" + js);
            }
        });
    }

    private void updatePlaybackService(String stateJson) {
        String safe = stateJson == null ? "{}" : stateJson;
        boolean nextPlaybackActive = false;
        try {
            JSONObject root = new JSONObject(safe);
            nextPlaybackActive = root.optBoolean("playing", false);
        } catch (Exception e) {
            nextPlaybackActive = false;
        }
        playbackActive = nextPlaybackActive;
        Intent intent = new Intent(this, MineradioPlaybackService.class);
        intent.setAction(MineradioPlaybackService.ACTION_UPDATE);
        intent.putExtra(MineradioPlaybackService.EXTRA_STATE, safe);
        try {
            if (Build.VERSION.SDK_INT >= 26 && playbackActive) startForegroundService(intent);
            else startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "playback service update failed: " + e.getMessage());
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33 || notificationPermissionAsked) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_NOTIFICATION_PERMISSION_ASKED, false)) return;
        notificationPermissionAsked = true;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_NOTIFICATION_PERMISSION_ASKED, true)
                .apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
    }

    public class AndroidBridge {
        @JavascriptInterface
        public String getInfo() {
            return String.format(Locale.US,
                    "{\"platform\":\"android\",\"sdk\":%d,\"model\":\"%s\",\"manufacturer\":\"%s\"}",
                    Build.VERSION.SDK_INT,
                    escapeJson(Build.MODEL),
                    escapeJson(Build.MANUFACTURER));
        }

        @JavascriptInterface
        public void toast(final String message) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean enabled) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public void openExternal(String url) {
            MainActivity.this.openExternal(url);
        }

        @JavascriptInterface
        public void openNeteaseMusicLogin(final String requestId) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    openNeteaseMusicLoginDialog(requestId);
                }
            });
        }

        @JavascriptInterface
        public void openQQMusicLogin(final String requestId) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    openQQMusicLoginDialog(requestId);
                }
            });
        }

        @JavascriptInterface
        public void updatePlaybackState(final String stateJson) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    updatePlaybackService(stateJson);
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeJs(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("</", "<\\/");
    }
}
