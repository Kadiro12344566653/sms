package dz.school.attendance.sms;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * تطبيق «رسائل الأولياء»: الواجهة (assets/www/index.html) + جسر Native:
 *  - كل طلبات الخادم تمر من هنا (HTTP من الهاتف، دون قيود المتصفح)
 *  - هذا الهاتف يرسل الرسائل من شريحته (SmsService) بحساب المدير
 */
public class MainActivity extends Activity {
    private static final int PERM_REQUEST = 11, FILE_REQUEST = 12;
    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private boolean startAfterPerms = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(0xFF0B4B8A);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(false);
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return openOutside(r.getUrl()); }
            @SuppressWarnings("deprecation") @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return openOutside(Uri.parse(url)); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "text/csv", "text/comma-separated-values"});
                try { startActivityForResult(Intent.createChooser(i, "اختر ملف Excel"), FILE_REQUEST); }
                catch (Exception e) { fileCb = null; return false; }
                return true;
            }
        });
        setContentView(web);
        if (b != null) web.restoreState(b); else web.loadUrl("file:///android_asset/www/index.html");
    }

    /** روابط الإنترنت (مثلا ملف Drive) تفتح في المتصفح */
    private boolean openOutside(Uri u) {
        if (u == null || "file".equals(u.getScheme())) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle o) { super.onSaveInstanceState(o); web.saveState(o); }
    @Override protected void onResume() { super.onResume(); notifyPage(); }

    private void notifyPage() { if (web != null) web.post(new Runnable() { public void run() { web.evaluateJavascript("window.onNativeChange && window.onNativeChange()", null); } }); }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == FILE_REQUEST && fileCb != null) {
            Uri u = res == RESULT_OK && data != null ? data.getData() : null;
            fileCb.onReceiveValue(u == null ? null : new Uri[]{u});
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    /* ---------------- الأذونات ---------------- */
    private boolean requestNeeded() {
        if (Build.VERSION.SDK_INT < 23) return false;
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.SEND_SMS);
        need.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 26) need.add("android.permission.READ_PHONE_NUMBERS");
        if (Build.VERSION.SDK_INT >= 33) need.add("android.permission.POST_NOTIFICATIONS");
        List<String> ask = new ArrayList<>();
        for (String p : need) if (!Cfg.granted(this, p)) ask.add(p);
        if (ask.isEmpty()) return false;
        requestPermissions(ask.toArray(new String[0]), PERM_REQUEST);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == PERM_REQUEST && startAfterPerms) { startAfterPerms = false; startGateway(); }
        notifyPage();
    }

    private void startGateway() {
        if (!Cfg.granted(this, Manifest.permission.SEND_SMS)) {
            Cfg.p(this).edit().putBoolean("running", false).apply();
            Cfg.status(this, "لا يوجد إذن إرسال الرسائل");
            notifyPage();
            return;
        }
        Cfg.p(this).edit().putBoolean("running", true).apply();
        Cfg.status(this, "جارٍ الاتصال بالخادم…");
        Cfg.log(this, "تشغيل الإرسال");
        SmsService.start(this);
        if (!Cfg.batteryOk(this)) openBattery();
        notifyPage();
    }

    private void openBattery() {
        if (Build.VERSION.SDK_INT < 23) return;
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { } }
    }

    /* ---------------- الجسر مع الواجهة ---------------- */
    private class Bridge {
        @JavascriptInterface public String version() { return Cfg.VERSION; }
        @JavascriptInterface public String session() { return Cfg.session(MainActivity.this); }
        @JavascriptInterface public String gwInfo() { return Cfg.info(MainActivity.this); }

        @JavascriptInterface
        public void setSession(String json) {
            Cfg.setSession(MainActivity.this, json);
            if (json == null || json.isEmpty()) SmsService.stop(MainActivity.this);
        }

        /** طلب للخادم في الخلفية؛ النتيجة إلى window.__native(id, json) */
        @JavascriptInterface
        public void call(final String id, final String action, final String token, final String payload) {
            new Thread(new Runnable() {
                public void run() {
                    String out;
                    try {
                        JSONObject req = new JSONObject();
                        req.put("action", action);
                        req.put("token", token == null ? "" : token);
                        req.put("payload", new JSONObject(payload == null || payload.isEmpty() ? "{}" : payload));
                        out = SmsService.post(Cfg.SERVER, req.toString()).toString();
                    } catch (Exception e) {
                        out = "{\"ok\":false,\"error\":\"تعذر الاتصال بالخادم. تحقق من الإنترنت.\"}";
                    }
                    final String js = "window.__native(" + JSONObject.quote(id) + "," + JSONObject.quote(out) + ")";
                    web.post(new Runnable() { public void run() { web.evaluateJavascript(js, null); } });
                }
            }).start();
        }

        @JavascriptInterface
        public void gwStart(final int sub) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Cfg.p(MainActivity.this).edit().putInt("sub", sub).apply();
                    if (requestNeeded()) { startAfterPerms = true; return; }
                    startGateway();
                }
            });
        }

        @JavascriptInterface
        public void gwStop() {
            runOnUiThread(new Runnable() {
                public void run() {
                    Cfg.p(MainActivity.this).edit().putBoolean("running", false).apply();
                    Cfg.log(MainActivity.this, "إيقاف الإرسال");
                    SmsService.stop(MainActivity.this);
                    notifyPage();
                }
            });
        }

        @JavascriptInterface public void openBattery() { runOnUiThread(new Runnable() { public void run() { MainActivity.this.openBattery(); } }); }
        @JavascriptInterface public void askPermissions() { runOnUiThread(new Runnable() { public void run() { requestNeeded(); } }); }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}
