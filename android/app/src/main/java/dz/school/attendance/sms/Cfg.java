package dz.school.attendance.sms;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** الإعدادات والحالة (تُقرأ من الواجهة ومن الخدمة) */
final class Cfg {
    /** رابط مشروع «رسائل الأولياء» (من رابط الربط) — لا يوجد رابط مدمج */
    /** رابط مشروع «رسائل الأولياء» المنشور (مدمج)؛ رابط الربط إن وُجد يأخذ مكانه */
    static final String DEFAULT_SERVER = "https://script.google.com/macros/s/AKfycbxe0mtu9Ix6v_oVhWT0skpWbkLlX_Ji3DS9XM_LxQFSAsjsLLHXAjM7VUhOY_hY-jBX-g/exec";
    static String server(Context c) { String s = p(c).getString("server", ""); return s.isEmpty() ? DEFAULT_SERVER : s; }

    /** رابط الربط: https://script.google.com/macros/s/.../exec#KEY  ← {server, key} أو null */
    static String[] parsePair(String s) {
        if (s == null) return null;
        s = s.trim();
        int h = s.lastIndexOf('#');
        if (h < 0) {                                            // رمز الربط وحده (10 أحرف) ← الرابط المدمج
            String k = s.toUpperCase().replaceAll("[^A-Z0-9]", "");
            return k.length() == 10 ? new String[]{DEFAULT_SERVER, k} : null;
        }
        String url = s.substring(0, h).trim(), key = s.substring(h + 1).toUpperCase().replaceAll("[^A-Z0-9]", "");
        if (!url.startsWith("https://script.google.com/") || key.length() != 10) return null;
        return new String[]{url, key};
    }

    static SharedPreferences p(Context c) { return c.getSharedPreferences("sms", Context.MODE_PRIVATE); }

    static String key(Context c) { return p(c).getString("key", ""); }
    static boolean running(Context c) { return p(c).getBoolean("running", false); }
    static int subId(Context c) { return p(c).getInt("sub", -1); }

    static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }
    static String now() { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()); }

    /** عدد المرسلة اليوم */
    static int sentToday(Context c) {
        SharedPreferences sp = p(c);
        return today().equals(sp.getString("sent_day", "")) ? sp.getInt("sent_n", 0) : 0;
    }
    static synchronized void addSent(Context c) {
        SharedPreferences sp = p(c);
        int n = sentToday(c) + 1;
        sp.edit().putString("sent_day", today()).putInt("sent_n", n).apply();
    }

    /** سجل قصير يظهر في الواجهة (آخر 40 سطرا) */
    static synchronized void log(Context c, String line) {
        SharedPreferences sp = p(c);
        String old = sp.getString("log", "");
        String all = now().substring(0, 5) + "  " + line + (old.isEmpty() ? "" : "\n" + old);
        String[] lines = all.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(40, lines.length); i++) { if (i > 0) sb.append('\n'); sb.append(lines[i]); }
        sp.edit().putString("log", sb.toString()).apply();
    }

    static void status(Context c, String s) { p(c).edit().putString("status", s).putLong("status_at", System.currentTimeMillis()).apply(); }

    private Cfg() { }
}
