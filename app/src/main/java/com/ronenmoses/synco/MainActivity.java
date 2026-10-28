package com.ronenmoses.synco;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Synco for Android – a secure, full-screen shell around the user's own Synco (Google Apps Script web app).
 * Only the configured Synco address (and Google's app frames) open inside; anything else goes to the browser / other apps.
 */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 11, REQ_PERM_CAMERA = 21, REQ_PERM_LOCATION = 22, REQ_PERM_CHOOSER = 23;
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
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false);
        applyBarColors();

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
        setupBack();

        String url = BuildConfig.SYNCO_URL;
        if (url == null || url.isEmpty()) url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        if (url.isEmpty()) askForAddress();
        else start(url, saved);
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
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setUserAgentString(s.getUserAgentString() + " SyncoAndroid/" + BuildConfig.VERSION_NAME);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) androidx.webkit.WebSettingsCompat.setSafeBrowsingEnabled(s, true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);   // Google serves the app inside its own frame
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sch = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
                if (sch.equals("https") && (allowedHost(u.getHost()) || !r.isForMainFrame())) return false;
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
                        if (sch.equals("https") && allowedHost(u.getHost())) web.loadUrl(u.toString()); else openOutside(u);
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "SyncoNative", rules, (view, message, origin, mainFrame, reply) -> {
                if (!allowedHost(origin.getHost())) return;
                String data = message.getData();
                if (data != null) handleMessage(data);
            });
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                java.io.InputStream in = getAssets().open("synco-bridge.js");
                byte[] b = new byte[in.available()];
                int n = 0, r; while (n < b.length && (r = in.read(b, n, b.length - n)) > 0) n += r;
                in.close();
                WebViewCompat.addDocumentStartJavaScript(web, new String(b, 0, n, "UTF-8"), rules);
            } catch (IOException ignored) { }
        }
    }

    private boolean allowedHost(String h) {
        if (h == null) return false;
        h = h.toLowerCase(Locale.ROOT);
        return h.equals(homeHost) || h.equals("script.google.com") || h.endsWith(".googleusercontent.com");
    }

    // ---------------------------------------------------------------- messages from the page
    private void handleMessage(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String t = o.optString("t");
            switch (t) {
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
