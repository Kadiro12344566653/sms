package dz.school.attendance.sms;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** إعدادات وحالة التطبيق (مشتركة بين الواجهة وخدمة الإرسال) */
final class Cfg {
    /** رابط مشروع «رسائل الأولياء» المنشور على Google Apps Script */
    static final String SERVER = "https://script.google.com/macros/s/AKfycbxe0mtu9Ix6v_oVhWT0skpWbkLlX_Ji3DS9XM_LxQFSAsjsLLHXAjM7VUhOY_hY-jBX-g/exec";
    static final String VERSION = "2.0";

    static SharedPreferences p(Context c) { return c.getSharedPreferences("sms", Context.MODE_PRIVATE); }

    /* ---------- جلسة المدير ---------- */
    static String token(Context c) { return p(c).getString("token", ""); }
    static String session(Context c) {
        JSONObject o = new JSONObject();
        try { o.put("token", token(c)); o.put("name", p(c).getString("name", "")); o.put("email", p(c).getString("email", "")); } catch (Exception ignored) { }
        return o.toString();
    }
    static void setSession(Context c, String json) {
        SharedPreferences.Editor e = p(c).edit();
        try {
            if (json == null || json.isEmpty()) { e.remove("token").remove("name").remove("email").putBoolean("running", false); }
            else { JSONObject o = new JSONObject(json); e.putString("token", o.optString("token")).putString("name", o.optString("name")).putString("email", o.optString("email")); }
        } catch (Exception ignored) { }
        e.apply();
    }

    /* ---------- حالة الإرسال ---------- */
    static boolean running(Context c) { return p(c).getBoolean("running", false); }
    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    static String now() { return new SimpleDateFormat("HH:mm", Locale.US).format(new Date()); }
    static int sentToday(Context c) { SharedPreferences s = p(c); return today().equals(s.getString("sent_day", "")) ? s.getInt("sent_n", 0) : 0; }
    static synchronized void addSent(Context c) { p(c).edit().putString("sent_day", today()).putInt("sent_n", sentToday(c) + 1).apply(); }
    static void status(Context c, String s) { p(c).edit().putString("status", s).apply(); }
    static synchronized void log(Context c, String line) {
        String old = p(c).getString("log", "");
        String all = now() + "  " + line + (old.isEmpty() ? "" : "\n" + old);
        String[] l = all.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(30, l.length); i++) { if (i > 0) sb.append('\n'); sb.append(l[i]); }
        p(c).edit().putString("log", sb.toString()).apply();
    }

    /* ---------- الشرائح ---------- */
    static boolean granted(Context c, String perm) { return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED; }

    static List<SubscriptionInfo> subs(Context c) {
        if (Build.VERSION.SDK_INT < 22 || !granted(c, Manifest.permission.READ_PHONE_STATE)) return null;
        try { return SubscriptionManager.from(c).getActiveSubscriptionInfoList(); } catch (Throwable e) { return null; }
    }

    @SuppressWarnings("deprecation")
    static String numberOf(Context c, SubscriptionInfo s) {
        String n = null;
        try { if (Build.VERSION.SDK_INT >= 33) n = SubscriptionManager.from(c).getPhoneNumber(s.getSubscriptionId()); } catch (Throwable ignored) { }
        if (n == null || n.isEmpty()) { try { n = s.getNumber(); } catch (Throwable ignored) { } }
        return n == null ? "" : n;
    }

    /** الشريحة المختارة، أو الوحيدة، أو -1 (شريحة الرسائل الافتراضية) */
    static int subId(Context c) {
        int chosen = p(c).getInt("sub", -1);
        List<SubscriptionInfo> l = subs(c);
        if (l == null || l.isEmpty()) return chosen;
        for (SubscriptionInfo s : l) if (s.getSubscriptionId() == chosen) return chosen;
        return l.size() == 1 ? l.get(0).getSubscriptionId() : -1;
    }

    static String simLabel(Context c) {
        int id = subId(c);
        List<SubscriptionInfo> l = subs(c);
        if (l != null) for (SubscriptionInfo s : l) if (s.getSubscriptionId() == id) {
            CharSequence n = s.getCarrierName();
            String num = numberOf(c, s);
            return (n == null ? "" : n.toString()) + " — الشريحة " + (s.getSimSlotIndex() + 1) + (num.isEmpty() ? "" : " (" + num + ")");
        }
        return "شريحة الرسائل الافتراضية";
    }

    static boolean batteryOk(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    /** للواجهة */
    static String info(Context c) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences s = p(c);
            o.put("running", running(c));
            o.put("status", s.getString("status", ""));
            o.put("sent_today", sentToday(c));
            o.put("log", s.getString("log", ""));
            o.put("sub", subId(c));
            o.put("sim_label", simLabel(c));
            o.put("battery_ok", batteryOk(c));
            o.put("sms_permission", granted(c, Manifest.permission.SEND_SMS));
            JSONArray a = new JSONArray();
            List<SubscriptionInfo> l = subs(c);
            if (l != null) for (SubscriptionInfo x : l) {
                JSONObject j = new JSONObject();
                j.put("id", x.getSubscriptionId());
                j.put("slot", x.getSimSlotIndex() + 1);
                CharSequence n = x.getCarrierName();
                j.put("carrier", n == null ? "" : n.toString());
                j.put("number", numberOf(c, x));
                a.put(j);
            }
            o.put("sims", a);
        } catch (Exception ignored) { }
        return o.toString();
    }

    private Cfg() { }
}
