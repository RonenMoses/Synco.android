package com.ronenmoses.synco;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.FileProvider;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Synco for Android – a secure, full-screen shell around the user's own Synco (Google Apps Script web app).
 * Only the configured Synco address (and Google's app frames) open inside; anything else goes to the browser / other apps.
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 11, REQ_PERM_CAMERA = 21, REQ_PERM_LOCATION = 22, REQ_PERM_CHOOSER = 23, REQ_PERM_NOTIF = 24;
    private JavaScriptReplyProxy notifReply; private String notifRid; private String pendingGo;
    private static final String PREFS = "synco", KEY_URL = "url";

    private WebView web;
    private ProgressBar bar;
    private LinearLayout offline;
    private FrameLayout root;
    private String home = "", homeHost = "";
    private boolean mainError = false;

    private ValueCallback<Uri[]> fileCb;
    private WebChromeClient.FileChooserParams pendingParams;
    private Uri cameraUri;
    private PermissionRequest pendingMedia;
    private String pendingGeoOrigin;
    private GeolocationPermissions.Callback pendingGeoCb;

    // ---------------------------------------------------------------- life cycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        installCrashLog();
        try {
            startUi(saved);
        } catch (Throwable t) {
            showFatal(t);   // never just close: show what went wrong so it can be fixed
        }
        showLastCrash();
        takeGo(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); takeGo(i); }

    /** a tap on a Synco notification opens the right screen (the page asks for it with op "pending", or we push it) */
    private void takeGo(Intent i) {
        try { String g = i == null ? null : i.getStringExtra("go"); if (g != null && g.startsWith("#/")) { pendingGo = g; i.removeExtra("go"); pushGo(); } } catch (Throwable ignored) { }
    }
    private void pushGo() {
        if (pendingGo == null || notifReply == null) return;
        try { final String g = pendingGo; pendingGo = null; final JavaScriptReplyProxy r = notifReply; final String s = new JSONObject().put("t", "go").put("go", g).toString(); runOnUiThread(() -> { try { r.postMessage(s); } catch (Throwable ignored) { } }); } catch (Throwable ignored) { }
    }

    private void startUi(Bundle saved) {
        Window w = getWindow();
        try { if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false); } catch (Throwable ignored) { }
        try { applyBarColors(); } catch (Throwable ignored) { }

        root = new FrameLayout(this);
        root.setBackgroundColor(isNight() ? Color.rgb(24, 26, 36) : Color.WHITE);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(false);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP));
        offline = buildOffline();
        offline.setVisibility(View.GONE);
        root.addView(offline, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        setupWebView();
        try { setupBack(); } catch (Throwable ignored) { }

        String url = BuildConfig.SYNCO_URL;
        if (url == null || url.isEmpty()) url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        if (url.isEmpty()) askForAddress();
        else start(url, saved);
    }

    // ---------------------------------------------------------------- fingerprint / face sign-in
    private static final String BIO_KEY = "synco_bio_key", BIO_PREFS = "synco_bio";

    private void bioReply(JavaScriptReplyProxy r, String rid, JSONObject o) {
        if (r == null) return;
        try { o.put("rid", rid); final String s = o.toString(); runOnUiThread(() -> { try { r.postMessage(s); } catch (Throwable ignored) { } }); } catch (Throwable ignored) { }
    }

    private boolean bioAvailable() {
        try {
            if (Build.VERSION.SDK_INT < 28) return false;
            if (Build.VERSION.SDK_INT >= 30) {
                BiometricManager bm = getSystemService(BiometricManager.class);
                return bm != null && bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
            }
            if (Build.VERSION.SDK_INT == 29) {
                BiometricManager bm = getSystemService(BiometricManager.class);
                return bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS;
            }
            KeyguardManager km = getSystemService(KeyguardManager.class);
            return km != null && km.isKeyguardSecure() && getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        } catch (Throwable t) { return false; }
    }

    /** the phone has a fingerprint reader / face unlock, even if nothing is set up in it yet */
    private boolean bioHardware() {
        try {
            if (Build.VERSION.SDK_INT >= 30) { BiometricManager bm = getSystemService(BiometricManager.class); int s = bm == null ? -1 : bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK); return s == BiometricManager.BIOMETRIC_SUCCESS || s == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED; }
            if (Build.VERSION.SDK_INT == 29) { BiometricManager bm = getSystemService(BiometricManager.class); int s = bm == null ? -1 : bm.canAuthenticate(); return s == BiometricManager.BIOMETRIC_SUCCESS || s == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED; }
            PackageManager pm = getPackageManager();
            return pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) || (Build.VERSION.SDK_INT >= 29 && pm.hasSystemFeature(PackageManager.FEATURE_FACE));
        } catch (Throwable t) { return false; }
    }

    /** open the phone's own screen for adding a fingerprint / face (Synco then uses what the phone already knows) */
    private void bioEnroll() {
        Intent i;
        if (Build.VERSION.SDK_INT >= 30) { i = new Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL); i.putExtra(android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, BiometricManager.Authenticators.BIOMETRIC_WEAK); }
        else if (Build.VERSION.SDK_INT >= 28) i = new Intent(android.provider.Settings.ACTION_FINGERPRINT_ENROLL);
        else i = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
        try { startActivity(i); } catch (Throwable t) { try { startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)); } catch (Throwable ignored) { } }
    }

    // ---------------------------------------------------------------- phone notifications (reminders the web app chose)
    private void notifStatus(JavaScriptReplyProxy reply, String rid) {
        try {
            JSONObject r = new JSONObject();
            r.put("ok", true); r.put("perm", NotifReceiver.allowed(this)); r.put("exact", NotifReceiver.exactOk(this)); r.put("count", NotifReceiver.load(this).length());
            r.put("ask", Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED);
            bioReply(reply, rid, r);
        } catch (Throwable ignored) { }
    }

    private void handleNotif(JSONObject o, JavaScriptReplyProxy reply) {
        final String rid = o.optString("rid"), op = o.optString("op");
        notifReply = reply;
        try {
            NotifReceiver.channel(this);
            switch (op) {
                case "status": notifStatus(reply, rid); break;
                case "perm":
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                        notifRid = rid; requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_PERM_NOTIF);
                    } else notifStatus(reply, rid);
                    break;
                case "sync": {
                    JSONArray a = o.optJSONArray("items"); if (a == null) a = new JSONArray();
                    int n = NotifReceiver.replace(this, a);
                    bioReply(reply, rid, new JSONObject().put("ok", true).put("count", n));
                    break;
                }
                case "test":
                    NotifReceiver.show(this, "test-" + System.currentTimeMillis(), o.optString("title", "Synco"), o.optString("body", "ההתראות עובדות 🎉"), "");
                    notifStatus(reply, rid);
                    break;
                case "settings": {
                    Intent i;
                    if (Build.VERSION.SDK_INT >= 26) { i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS); i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()); }
                    else { i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS); i.setData(Uri.parse("package:" + getPackageName())); }
                    try { startActivity(i); } catch (Throwable ignored) { }
                    bioReply(reply, rid, new JSONObject().put("ok", true));
                    break;
                }
                case "pending": {
                    JSONObject r = new JSONObject().put("ok", true);
                    if (pendingGo != null) { r.put("go", pendingGo); pendingGo = null; }
                    bioReply(reply, rid, r);
                    break;
                }
                default: break;
            }
        } catch (Throwable t) { logError(t); }
    }

    /** AES-256-GCM key that lives inside the phone's secure hardware and can never be read out. */
    private SecretKey bioKey(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(BIO_KEY)) return (SecretKey) ks.getKey(BIO_KEY, null);
        if (!create) return null;
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(BIO_KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return kg.generateKey();
    }

    private interface BioDone { void ok(); void fail(String msg); }

    private void bioPrompt(String title, BioDone done) {
        if (Build.VERSION.SDK_INT < 28) { done.fail("המכשיר לא תומך"); return; }
        try {
            BiometricPrompt.Builder b = new BiometricPrompt.Builder(this).setTitle(title).setSubtitle("Synco");
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK);
            b.setNegativeButton("ביטול", getMainExecutor(), (d, w) -> done.fail(""));
            b.build().authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { done.ok(); }
                @Override public void onAuthenticationError(int code, CharSequence msg) { done.fail(code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ? "" : String.valueOf(msg)); }
            });
        } catch (Throwable t) { logError(t); done.fail("לא הצלחנו לפתוח את הזיהוי"); }
    }

    private void handleBio(JSONObject o, JavaScriptReplyProxy reply) {
        final String rid = o.optString("rid"), op = o.optString("op");
        final SharedPreferences sp = getSharedPreferences(BIO_PREFS, MODE_PRIVATE);
        try {
            if (op.equals("status")) {
                JSONObject r = new JSONObject();
                r.put("available", bioAvailable()); r.put("hw", bioHardware()); r.put("enrolled", sp.contains("blob")); r.put("user", sp.getString("user", ""));
                bioReply(reply, rid, r);
            } else if (op.equals("enroll")) {
                bioEnroll();
                bioReply(reply, rid, new JSONObject().put("ok", true));
            } else if (op.equals("clear")) {
                sp.edit().clear().apply();
                try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(BIO_KEY); } catch (Throwable ignored) { }
                bioReply(reply, rid, new JSONObject().put("ok", true));
            } else if (op.equals("save")) {
                final String u = o.optString("u"), p = o.optString("p");
                if (u.isEmpty() || p.isEmpty() || u.length() > 200 || p.length() > 500 || !bioAvailable()) { bioReply(reply, rid, new JSONObject().put("ok", false).put("msg", "לא זמין במכשיר הזה")); return; }
                Runnable store = () -> {
                    try {
                        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                        c.init(Cipher.ENCRYPT_MODE, bioKey(true));
                        byte[] ct = c.doFinal(new JSONObject().put("u", u).put("p", p).toString().getBytes("UTF-8"));
                        sp.edit().putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP)).putString("blob", Base64.encodeToString(ct, Base64.NO_WRAP)).putString("user", u).apply();
                        bioReply(reply, rid, new JSONObject().put("ok", true));
                    } catch (Throwable t) { logError(t); try { bioReply(reply, rid, new JSONObject().put("ok", false).put("msg", "לא הצלחנו לשמור")); } catch (Throwable ignored) { } }
                };
                if (o.optBoolean("silent") && u.equals(sp.getString("user", ""))) store.run();   // same user, new password – already unlocked with the password itself
                else bioPrompt("אישור עם טביעת אצבע / פנים", new BioDone() {
                    public void ok() { store.run(); }
                    public void fail(String m) { try { bioReply(reply, rid, new JSONObject().put("ok", false).put("msg", m)); } catch (Throwable ignored) { } }
                });
            } else if (op.equals("get")) {
                if (!sp.contains("blob")) { bioReply(reply, rid, new JSONObject().put("ok", false)); return; }
                bioPrompt("כניסה ל-Synco", new BioDone() {
                    public void ok() {
                        try {
                            SecretKey k = bioKey(false);
                            if (k == null) throw new IllegalStateException("no key");
                            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, Base64.decode(sp.getString("iv", ""), Base64.NO_WRAP)));
                            JSONObject v = new JSONObject(new String(c.doFinal(Base64.decode(sp.getString("blob", ""), Base64.NO_WRAP)), "UTF-8"));
                            bioReply(reply, rid, new JSONObject().put("ok", true).put("u", v.optString("u")).put("p", v.optString("p")));
                        } catch (Throwable t) {
                            logError(t); sp.edit().clear().apply();
                            try { bioReply(reply, rid, new JSONObject().put("ok", false).put("msg", "צריך להיכנס שוב עם סיסמה ולהפעיל מחדש")); } catch (Throwable ignored) { }
                        }
                    }
                    public void fail(String m) { try { bioReply(reply, rid, new JSONObject().put("ok", false).put("msg", m)); } catch (Throwable ignored) { } }
                });
            }
        } catch (Throwable t) { logError(t); }
    }

    // ---------------------------------------------------------------- diagnostics: keep the last error, show it on the next start
    private File crashFile() { return new File(getFilesDir(), "last-error.txt"); }

    private static String trace(Throwable t) { StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw)); return sw.toString(); }

    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final File f = crashFile();
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> {
            try (FileOutputStream os = new FileOutputStream(f)) { os.write(("Synco " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.SDK_INT + " · " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + trace(ex)).getBytes("UTF-8")); } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(th, ex);
        });
    }

    private void logError(Throwable t) {
        try (FileOutputStream os = new FileOutputStream(new File(getFilesDir(), "warnings.txt"), true)) { os.write(trace(t).getBytes("UTF-8")); } catch (Throwable ignored) { }
    }

    private void showLastCrash() {
        File f = crashFile();
        if (!f.exists()) return;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 20000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int n = 0, r; while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r; }
            f.delete();
            final String txt = new String(b, "UTF-8");
            new AlertDialog.Builder(this).setTitle("האפליקציה נסגרה בפעם הקודמת")
                    .setMessage("לחצו \"העתקה\" ושלחו את הפרטים כדי שנתקן.\n\n" + (txt.length() > 1500 ? txt.substring(0, 1500) + "…" : txt))
                    .setPositiveButton("העתקה", (d, i) -> copy(txt)).setNegativeButton("סגירה", null).show();
        } catch (Throwable ignored) { }
    }

    private void copy(String txt) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Synco error", txt));
            Toast.makeText(this, "הועתק – הדביקו ושלחו", Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) { }
    }

    private void showFatal(Throwable t) {
        final String txt = "Synco " + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.SDK_INT + " · " + Build.MANUFACTURER + " " + Build.MODEL + "\n\n" + trace(t);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(48), dp(20), dp(20));
        TextView h = new TextView(this);
        h.setText("משהו השתבש בפתיחה");
        h.setTextSize(20);
        Button b = new Button(this);
        b.setText("העתקת הפרטים");
        b.setOnClickListener(v -> copy(txt));
        TextView d = new TextView(this);
        d.setText(txt);
        d.setTextSize(12);
        d.setTextIsSelectable(true);
        d.setTextDirection(View.TEXT_DIRECTION_LTR);
        ScrollView sv = new ScrollView(this);
        sv.addView(d);
        l.addView(h); l.addView(b); l.addView(sv);
        setContentView(l);
    }

    private void start(String url, Bundle saved) {
        home = url;
        Uri u = Uri.parse(url);
        homeHost = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        installBridge();
        if (saved == null || web.restoreState(saved) == null) web.loadUrl(home);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);   // fold / unfold, rotate, resize, split screen – the page re-lays out by itself, nothing is reloaded
        root.requestApplyInsets();
    }

    @Override
    protected void onPause() { super.onPause(); if (web != null) web.onPause(); CookieManager.getInstance().flush(); }

    @Override
    protected void onResume() { super.onResume(); if (web != null) web.onResume(); }

    @Override
    protected void onDestroy() {
        if (web != null) { root.removeView(web); web.destroy(); web = null; }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- WebView
    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setLoadWithOverviewMode(false);   // always the phone layout – never the zoomed-out "desktop" page
        s.setUseWideViewPort(false);
        s.setTextZoom(100);                 // the page has its own text-size control; the phone's big-font setting made the sign-in screen huge
        s.setBuiltInZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " SyncoAndroid/" + BuildConfig.VERSION_NAME);
        try { if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(s, true); } catch (Throwable ignored) { }
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);   // Google serves the app inside its own frame
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setBackgroundColor(isNight() ? Color.rgb(24, 26, 36) : Color.WHITE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                if (sch.equals("https") && (navAllowed(u.getHost()) || !r.isForMainFrame())) return false;
                openOutside(u);
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) { mainError = false; }

            @Override
            public void onPageFinished(WebView v, String url) {
                bar.setVisibility(View.GONE);
                if (!mainError) offline.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }

            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                // the page's process died (low memory / WebView update): keep the app alive and offer a reload
                logError(new RuntimeException("WebView renderer gone, crash=" + (d != null && d.didCrash())));
                try { root.removeView(v); v.destroy(); } catch (Throwable ignored) { }
                web = null;
                showFatal(new RuntimeException("התצוגה נסגרה. סגרו ופתחו את האפליקציה שוב."));
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) { mainError = true; offline.setVisibility(View.VISIBLE); }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) { bar.setVisibility(p < 100 ? View.VISIBLE : View.GONE); bar.setProgress(p); }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                if (wantsImage(p) && hasCamera() && !granted(Manifest.permission.CAMERA)) {
                    pendingParams = p;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CHOOSER);
                } else openChooser(p);
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest r) {
                runOnUiThread(() -> {
                    if (!allowedHost(r.getOrigin().getHost())) { r.deny(); return; }
                    boolean video = Arrays.asList(r.getResources()).contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE);
                    if (!video) { r.deny(); return; }   // Synco only ever needs the camera (barcode scanning)
                    if (granted(Manifest.permission.CAMERA)) r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    else { pendingMedia = r; requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA); }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (!allowedHost(Uri.parse(origin).getHost())) { cb.invoke(origin, false, false); return; }
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) { cb.invoke(origin, true, false); return; }
                pendingGeoOrigin = origin; pendingGeoCb = cb;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM_LOCATION);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean gesture, Message msg) {
                // window.open / target=_blank: catch the address, then decide where it goes
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    private boolean done = false;
                    private void route(Uri u) {
                        if (done) return; done = true;
                        String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                        if (sch.equals("https") && navAllowed(u.getHost()) && web != null) web.loadUrl(u.toString()); else openOutside(u);
                        tmp.post(tmp::destroy);
                    }
                    @Override public boolean shouldOverrideUrlLoading(WebView w2, WebResourceRequest r) { route(r.getUrl()); return true; }
                    @Override public void onPageStarted(WebView w2, String url, android.graphics.Bitmap f) { if (url != null && !url.equals("about:blank")) route(Uri.parse(url)); }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(tmp);
                msg.sendToTarget();
                return true;
            }
        });

        web.setDownloadListener((url, ua, cd, mime, len) -> { if (url != null && url.startsWith("https://")) openOutside(Uri.parse(url)); });
    }

    /** JS bridge – injected only into Synco's own frames (origin rules), used for downloads, sharing and keep-screen-on. */
    private void installBridge() {
        Set<String> rules = new HashSet<>();
        rules.add("https://" + homeHost);
        rules.add("https://script.google.com");
        rules.add("https://*.googleusercontent.com");
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(web, "SyncoNative", rules, (view, message, origin, mainFrame, reply) -> {
                    if (origin == null || !allowedHost(origin.getHost())) return;
                    String data = message.getData();
                    if (data != null) handleMessage(data, reply);
                });
            }
        } catch (Throwable t) { logError(t); }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                java.io.InputStream in = getAssets().open("synco-bridge.js");
                byte[] b = new byte[in.available()];
                int n = 0, r; while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                in.close();
                WebViewCompat.addDocumentStartJavaScript(web, new String(b, 0, n, "UTF-8"), rules);
            } catch (Throwable t) { logError(t); }
        }
    }

    /** Pages that may open inside the app: Synco itself plus Google's own sign-in / redirect pages (the bridge stays limited to allowedHost). */
    private boolean navAllowed(String h) {
        if (h == null) return false;
        if (allowedHost(h)) return true;
        h = h.toLowerCase(Locale.ROOT);
        return h.equals("accounts.google.com") || h.equals("www.google.com") || h.equals("google.com") || h.endsWith(".google.com");
    }

    private boolean allowedHost(String h) {
        if (h == null) return false;
        h = h.toLowerCase(Locale.ROOT);
        return h.equals(homeHost) || h.equals("script.google.com") || h.endsWith(".googleusercontent.com");
    }

    // ---------------------------------------------------------------- messages from the page
    private void handleMessage(String json, JavaScriptReplyProxy reply) {
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("t");
            switch (t) {
                case "bio": final JSONObject bo = o; runOnUiThread(() -> handleBio(bo, reply)); break;
                case "notif": final JSONObject no = o; runOnUiThread(() -> handleNotif(no, reply)); break;
                case "save": saveFile(o.optString("name"), o.optString("mime"), o.optString("b64")); break;
                case "share": shareFrom(o); break;
                case "wake": final boolean on = o.optBoolean("on");
                    runOnUiThread(() -> { if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); });
                    break;
                case "toast": final String m = o.optString("m");
                    runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
                    break;
                default: break;
            }
        } catch (Exception ignored) { }
    }

    private static String safeName(String n) {
        n = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "").trim();
        if (n.isEmpty() || n.startsWith(".")) n = "Synco" + n;
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    private void saveFile(String name, String mime, String b64) {
        final String fn = safeName(name);
        final String mt = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
        new Thread(() -> {
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                Uri uri;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, fn);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mt);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Synco");
                    cv.put(MediaStore.Downloads.IS_PENDING, 1);
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new IOException("no uri");
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) { if (os == null) throw new IOException("no stream"); os.write(bytes); }
                    cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                } else {
                    File dir = new File(getExternalFilesDir(null), "Download");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("no dir");
                    File f = new File(dir, fn);
                    try (FileOutputStream os = new FileOutputStream(f)) { os.write(bytes); }
                    uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
                }
                final Uri done = uri;
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("הקובץ נשמר")
                        .setMessage(fn + "\nבתיקיית ההורדות" + (Build.VERSION.SDK_INT >= 29 ? " (Download/Synco)" : ""))
                        .setPositiveButton("פתיחה", (d, i) -> viewFile(done, mt))
                        .setNeutralButton("שיתוף", (d, i) -> shareUris(new ArrayList<>(Arrays.asList(done)), mt, "", ""))
                        .setNegativeButton("סגירה", null).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "לא הצלחנו לשמור את הקובץ", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void viewFile(Uri u, String mime) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (ActivityNotFoundException e) { Toast.makeText(this, "אין במכשיר אפליקציה שפותחת קובץ כזה", Toast.LENGTH_LONG).show(); }
    }

    private void shareFrom(JSONObject o) {
        final String title = o.optString("title"), text = o.optString("text");
        final JSONArray files = o.optJSONArray("files");
        new Thread(() -> {
            ArrayList<Uri> uris = new ArrayList<>();
            String mime = "text/plain";
            try {
                if (files != null && files.length() > 0) {
                    File dir = new File(getCacheDir(), "shared");
                    if (!dir.exists()) dir.mkdirs();
                    File[] old = dir.listFiles(); if (old != null) for (File f : old) f.delete();
                    for (int k = 0; k < files.length() && k < 10; k++) {
                        JSONObject f = files.getJSONObject(k);
                        File out = new File(dir, safeName(f.optString("name")));
                        try (FileOutputStream os = new FileOutputStream(out)) { os.write(Base64.decode(f.optString("b64"), Base64.DEFAULT)); }
                        uris.add(FileProvider.getUriForFile(this, getPackageName() + ".files", out));
                        mime = k == 0 ? f.optString("mime", "*/*") : "*/*";
                    }
                }
            } catch (Exception ignored) { }
            final String mt = mime;
            runOnUiThread(() -> shareUris(uris, mt, title, text));
        }).start();
    }

    private void shareUris(ArrayList<Uri> uris, String mime, String title, String text) {
        Intent i;
        if (uris.size() > 1) { i = new Intent(Intent.ACTION_SEND_MULTIPLE); i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); }
        else { i = new Intent(Intent.ACTION_SEND); if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0)); }
        i.setType(uris.isEmpty() ? "text/plain" : mime);
        if (text != null && !text.isEmpty()) i.putExtra(Intent.EXTRA_TEXT, text);
        if (title != null && !title.isEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "שיתוף")); } catch (ActivityNotFoundException ignored) { }
    }

    // ---------------------------------------------------------------- links that leave Synco
    private void openOutside(Uri u) {
        try {
            String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            Intent i;
            if (sch.equals("intent")) {
                i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                i.setSelector(null);
                try { startActivity(i); return; } catch (ActivityNotFoundException e) {
                    String fb = i.getStringExtra("browser_fallback_url");
                    if (fb != null && fb.startsWith("https://")) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fb))); }
                    return;
                }
            }
            if (sch.equals("javascript") || sch.equals("file") || sch.equals("content") || sch.equals("data") || sch.equals("blob")) return;
            i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "אין במכשיר אפליקציה שפותחת את הקישור הזה", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- file chooser (photos, Excel, backups) + camera
    private static boolean wantsImage(WebChromeClient.FileChooserParams p) {
        for (String a : p.getAcceptTypes()) if (a != null && (a.startsWith("image") || a.isEmpty())) return true;
        return p.getAcceptTypes().length == 0;
    }

    private boolean hasCamera() { return getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY); }

    private void openChooser(WebChromeClient.FileChooserParams p) {
        Intent pick;
        try { pick = p.createIntent(); } catch (Exception e) { pick = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*"); }
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        Intent chooser = Intent.createChooser(pick, "בחירת קובץ");
        cameraUri = null;
        if (wantsImage(p) && hasCamera() && granted(Manifest.permission.CAMERA)) {
            try {
                File dir = new File(getCacheDir(), "camera");
                if (!dir.exists()) dir.mkdirs();
                File photo = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
                cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".files", photo);
                Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
            } catch (Exception e) { cameraUri = null; }
        }
        try { startActivityForResult(chooser, REQ_FILE); }
        catch (ActivityNotFoundException e) { if (fileCb != null) fileCb.onReceiveValue(null); fileCb = null; }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || fileCb == null) return;
        Uri[] out = null;
        if (res == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data != null && data.getData() != null) out = new Uri[]{data.getData()};
            else if (cameraUri != null) out = new Uri[]{cameraUri};
        }
        fileCb.onReceiveValue(out);
        fileCb = null;
    }

    // ---------------------------------------------------------------- runtime permissions
    private boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        boolean ok = false;
        for (int r : res) if (r == PackageManager.PERMISSION_GRANTED) ok = true;
        if (req == REQ_PERM_CAMERA && pendingMedia != null) {
            if (ok) pendingMedia.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}); else pendingMedia.deny();
            pendingMedia = null;
        } else if (req == REQ_PERM_LOCATION && pendingGeoCb != null) {
            pendingGeoCb.invoke(pendingGeoOrigin, ok, false);
            pendingGeoCb = null; pendingGeoOrigin = null;
        } else if (req == REQ_PERM_NOTIF) {
            if (notifReply != null) notifStatus(notifReply, notifRid == null ? "" : notifRid);
            notifRid = null;
        } else if (req == REQ_PERM_CHOOSER && pendingParams != null) {
            openChooser(pendingParams);   // with or without the camera option
            pendingParams = null;
        }
    }

    // ---------------------------------------------------------------- back button
    private void setupBack() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
    }

    private void goBack() {
        if (web != null && web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() { goBack(); }

    // ---------------------------------------------------------------- first run: the Synco address (only when not built in)
    private void askForAddress() {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setHint("https://script.google.com/macros/s/…/exec");
        in.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(in, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("הכתובת של Synco")
                .setMessage("מדביקים את הכתובת שמופיעה ב-Synco ← \"אפליקציה לאנדרואיד\".")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("המשך", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String u = in.getText().toString().trim();
            Uri p = Uri.parse(u);
            if (!"https".equalsIgnoreCase(p.getScheme()) || p.getHost() == null || !p.getHost().contains(".") || u.contains(" ")) { in.setError("כתובת שמתחילה ב-https://"); return; }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, u).apply();
            d.dismiss();
            start(u, null);
        }));
        d.show();
    }

    // ---------------------------------------------------------------- small UI helpers
    private LinearLayout buildOffline() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(dp(32), dp(32), dp(32), dp(32));
        l.setBackgroundColor(isNight() ? Color.rgb(24, 26, 36) : Color.WHITE);
        l.setClickable(true);
        TextView t = new TextView(this);
        t.setText("אין חיבור לאינטרנט");
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(isNight() ? Color.WHITE : Color.rgb(31, 32, 51));
        TextView s = new TextView(this);
        s.setText("בודקים את החיבור ומנסים שוב. מה ששמרתם לא נמחק.");
        s.setTextSize(16);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(8), 0, dp(20));
        s.setTextColor(isNight() ? Color.rgb(190, 194, 210) : Color.rgb(91, 100, 120));
        Button b = new Button(this);
        b.setText("נסו שוב");
        b.setMinHeight(dp(48));
        b.setOnClickListener(v -> { offline.setVisibility(View.GONE); if (web.getUrl() == null || web.getUrl().equals("about:blank")) web.loadUrl(home); else web.reload(); });
        l.addView(t); l.addView(s); l.addView(b);
        return l;
    }

    @SuppressWarnings("deprecation")
    private void applyBarColors() {
        Window w = getWindow();
        boolean night = isNight();
        if (Build.VERSION.SDK_INT < 35) {
            int c = night ? Color.rgb(24, 26, 36) : Color.WHITE;
            w.setStatusBarColor(Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : c);
            w.setNavigationBarColor(Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : c);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController ic = w.getInsetsController();
            if (ic != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                ic.setSystemBarsAppearance(night ? 0 : light, light);
            }
        } else if (!night) {
            int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(f);
        }
    }

    private boolean isNight() { return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
