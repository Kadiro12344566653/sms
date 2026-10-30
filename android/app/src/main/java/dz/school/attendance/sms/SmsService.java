package dz.school.attendance.sms;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.telephony.SmsManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * خدمة دائمة: تسأل الخادم عن الرسالة التالية، ترسلها من الشريحة، ثم تخبر الخادم بالنتيجة.
 * الخادم هو الذي يفرض المدة بين رسالة وأخرى (دقيقة ونصف) ويعيدها في wait.
 */
public class SmsService extends Service {
    static final String CHANNEL = "sms_gateway";
    static final int NOTIF_ID = 42;
    private volatile boolean stop = false;
    private Thread worker;
    private PowerManager.WakeLock wake;

    static void start(Context c) {
        Intent i = new Intent(c, SmsService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) { c.stopService(new Intent(c, SmsService.class)); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showNotif("جارٍ التشغيل…");
        if (worker == null || !worker.isAlive()) {
            stop = false;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && wake == null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daftar:sms");
                wake.setReferenceCounted(false);
                wake.acquire();
            }
            worker = new Thread(new Runnable() { public void run() { loop(); } }, "sms-loop");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stop = true;
        if (worker != null) worker.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        Cfg.status(this, "متوقف");
        super.onDestroy();
    }

    /* ---------------- الحلقة ---------------- */
    private void loop() {
        JSONObject done = null;
        int errors = 0;
        while (!stop) {
            long waitSec = 30;
            try {
                JSONObject payload = new JSONObject();
                payload.put("device", deviceName());
                if (done != null) payload.put("done", done);
                JSONObject req = new JSONObject();
                req.put("action", "gnext");                       // بحساب المدير المسجل في التطبيق
                req.put("token", Cfg.token(this));
                req.put("payload", payload);
                JSONObject res = post(Cfg.SERVER, req.toString());
                done = null;
                if (res.optBoolean("auth", false)) {
                    String err = "انتهت جلسة المدير: افتح التطبيق وادخل من جديد";
                    Cfg.status(this, "⚠ " + err);
                    Cfg.log(this, "⚠ " + err);
                    Cfg.p(this).edit().putBoolean("running", false).apply();
                    showNotif(err);
                    stopSelf();
                    break;
                } else if (!res.optBoolean("ok", false)) {
                    String err = res.optString("error", "خطأ من الخادم");
                    Cfg.status(this, "⚠ " + err);
                    Cfg.log(this, "⚠ " + err);
                    showNotif(err);
                    waitSec = 120;
                } else {
                    errors = 0;
                    JSONObject d = res.optJSONObject("data");
                    if (d == null) d = new JSONObject();
                    waitSec = d.optLong("wait", 30);
                    JSONObject msg = d.optJSONObject("msg");
                    if (!d.optBoolean("enabled", true)) {
                        Cfg.status(this, "متصل — إرسال الرسائل موقوف (فعّله من تبويب «اليوم»)");
                        showNotif("إرسال الرسائل موقوف");
                    } else if (msg != null) {
                        String phone = msg.optString("phone"), text = msg.optString("text");
                        Cfg.status(this, "يرسل إلى " + phone + "…");
                        String err = sendSms(phone, text);
                        done = new JSONObject();
                        done.put("id", msg.optString("id"));
                        done.put("date", msg.optString("date"));
                        done.put("ok", err == null);
                        if (err != null) done.put("error", err);
                        if (err == null) { Cfg.addSent(this); Cfg.log(this, "✓ " + phone); }
                        else Cfg.log(this, "✗ " + phone + " — " + err);
                        waitSec = 0;   // نخبر الخادم بالنتيجة فورا، وهو يعيد مدة الانتظار
                    } else {
                        int left = d.optInt("pending", 0);
                        Cfg.status(this, d.optBoolean("capped", false) ? "بلغ الحد اليومي للرسائل" : "متصل — " + (left > 0 ? left + " رسالة في الانتظار" : "لا توجد رسائل جاهزة"));
                        showNotif("متصل · أُرسلت " + Cfg.sentToday(this) + " رسالة اليوم");
                    }
                    Cfg.p(this).edit().putLong("seen_at", System.currentTimeMillis()).apply();
                }
            } catch (Exception e) {
                errors++;
                String m = "لا يوجد اتصال بالإنترنت";
                Cfg.status(this, "⚠ " + m + " (محاولة " + errors + ")");
                if (errors == 1 || errors % 10 == 0) Cfg.log(this, "⚠ " + m);
                showNotif(m);
                waitSec = Math.min(300, 15L * errors);
            }
            if (done == null) waitSec = Math.max(5, Math.min(waitSec, 900));
            try { Thread.sleep(waitSec * 1000L); } catch (InterruptedException ie) { if (stop) break; }
        }
    }

    /* ---------------- إرسال SMS ---------------- */
    private String sendSms(String phone, String text) {
        final String action = getPackageName() + ".SENT." + System.currentTimeMillis();
        SmsManager sm = smsManager();
        ArrayList<String> parts = sm.divideMessage(text);
        final CountDownLatch latch = new CountDownLatch(parts.size());
        final int[] worst = { -1 };   // -1 = نجاح
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                int code = getResultCode();
                if (code != android.app.Activity.RESULT_OK) worst[0] = code;
                latch.countDown();
            }
        };
        IntentFilter f = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(rcv, f);
        try {
            ArrayList<PendingIntent> sent = new ArrayList<>();
            int pf = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            for (int i = 0; i < parts.size(); i++) {
                Intent it = new Intent(action).setPackage(getPackageName());
                sent.add(PendingIntent.getBroadcast(this, (int) (System.currentTimeMillis() % 100000) + i, it, pf));
            }
            if (parts.size() == 1) sm.sendTextMessage(phone, null, text, sent.get(0), null);
            else sm.sendMultipartTextMessage(phone, null, parts, sent, null);
            if (!latch.await(90, TimeUnit.SECONDS)) return "لم يرد الهاتف بنتيجة الإرسال";
            if (worst[0] == -1) return null;
            switch (worst[0]) {
                case SmsManager.RESULT_ERROR_NO_SERVICE: return "لا توجد تغطية شبكة";
                case SmsManager.RESULT_ERROR_RADIO_OFF: return "الشبكة مغلقة (وضع الطيران؟)";
                case SmsManager.RESULT_ERROR_NULL_PDU: return "رقم أو نص غير صالح";
                case SmsManager.RESULT_ERROR_GENERIC_FAILURE: return "فشل الإرسال (تحقق من رصيد الشريحة)";
                default: return "فشل الإرسال (رمز " + worst[0] + ")";
            }
        } catch (SecurityException se) {
            return "لا يوجد إذن إرسال الرسائل";
        } catch (Exception e) {
            return "فشل الإرسال: " + e.getClass().getSimpleName();
        } finally {
            try { unregisterReceiver(rcv); } catch (Exception ignored) { }
        }
    }

    @SuppressWarnings("deprecation")
    private SmsManager smsManager() {
        int sub = Cfg.subId(this);
        if (sub >= 0 && Build.VERSION.SDK_INT >= 22) {
            try { return SmsManager.getSmsManagerForSubscriptionId(sub); } catch (Exception ignored) { }
        }
        return SmsManager.getDefault();
    }

    private String deviceName() {
        String sim = Cfg.simLabel(this);
        String m = Build.MODEL == null ? "" : Build.MODEL;
        String b = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        if (!m.toLowerCase().startsWith(b.toLowerCase())) m = b + " " + m;
        return m.trim() + (sim.isEmpty() ? "" : " — " + sim);
    }

    /* ---------------- HTTP (Apps Script يعيد 302 إلى googleusercontent) ---------------- */
    static JSONObject post(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(20000);
        c.setReadTimeout(40000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "text/plain;charset=utf-8");
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        int hops = 0;
        while ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308) && hops++ < 5) {
            String loc = c.getHeaderField("Location");
            drain(c, code);                                // نقرأ الرد ونغلقه دون قطع الاتصال: يُعاد استعماله (أسرع)
            c = (HttpURLConnection) new URL(new URL(url), loc).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(40000);
            code = c.getResponseCode();
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) bo.write(buf, 0, n);
        if (in != null) in.close();                         // لا disconnect(): الاتصال المشفّر يبقى مفتوحا للطلب التالي
        String s = bo.toString("UTF-8").trim();
        if (!s.startsWith("{")) throw new Exception("HTTP " + code);
        return new JSONObject(s);
    }

    private static void drain(HttpURLConnection c, int code) {
        try {
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return;
            byte[] b = new byte[4096];
            while (in.read(b) > 0) { }
            in.close();
        } catch (Exception ignored) { }
    }

    /* ---------------- الإشعار الدائم ---------------- */
    private void showNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "إرسال رسائل الأولياء", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("رسائل الأولياء: هذا الهاتف يرسل")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF0B4B8A);
        Notification n = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            if (nm != null) nm.notify(NOTIF_ID, n);
        }
    }
}
