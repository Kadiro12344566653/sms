package dz.school.attendance.sms;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** شاشة واحدة: رمز الربط، اختيار الشريحة، تشغيل/إيقاف، الحالة والسجل */
public class MainActivity extends Activity {
    private static final int BLUE = 0xFF0B4B8A, GREEN = 0xFF1B7F4B, RED = 0xFFB4232C, MUTED = 0xFF5D7791, BG = 0xFFF1F6FB;
    private final Handler h = new Handler();
    private EditText keyIn;
    private TextView stateTv, statusTv, sentTv, logTv, simTv;
    private Button toggle;
    private LinearLayout simBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 17) getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        build();
        handlePair(getIntent());
        askPermissions();
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handlePair(i); }

    /** زر «ربط هذا الهاتف» في صفحة المدير: daftarsms://pair?l=<رابط الربط> */
    private void handlePair(Intent i) {
        if (i == null || i.getData() == null) return;
        String l = i.getData().getQueryParameter("l");
        String[] p = Cfg.parsePair(l);
        if (p == null) { toast("رابط الربط غير صالح."); return; }
        Cfg.p(this).edit().putString("server", p[0]).putString("key", p[1]).apply();
        keyIn.setText(l);
        Cfg.log(this, "تم الربط");
        toast("تم ربط هذا الهاتف. اضغط «تشغيل».");
    }

    @Override protected void onResume() { super.onResume(); refresh(); h.postDelayed(tick, 2000); buildSims(); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacks(tick); }

    private final Runnable tick = new Runnable() { public void run() { refresh(); h.postDelayed(this, 2000); } };

    /* ---------------- الواجهة ---------------- */
    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = col();
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(root);

        TextView title = text("مرسل رسائل الغيابات", 22, BLUE, true);
        root.addView(title);
        root.addView(text("متوسطة الشهيد بدراني بوعمامة — يرسل رسائل SMS لأولياء التلاميذ الغائبين من شريحة هذا الهاتف.", 14, MUTED, false));

        LinearLayout st = card(root);
        stateTv = text("", 20, RED, true);
        st.addView(stateTv);
        statusTv = text("", 15, 0xFF12314F, false);
        st.addView(statusTv);
        sentTv = text("", 15, MUTED, false);
        st.addView(sentTv);

        LinearLayout k = card(root);
        k.addView(text("رابط الربط", 16, 0xFF12314F, true));
        k.addView(text("افتح صفحة «رسائل الأولياء» من متصفح هذا الهاتف ← الإعدادات ← «ربط هذا الهاتف». أو انسخ «رابط الربط» من هناك والصقه هنا.", 13, MUTED, false));
        keyIn = new EditText(this);
        keyIn.setTextSize(13);
        keyIn.setTypeface(Typeface.MONOSPACE);
        keyIn.setMinLines(3);
        keyIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keyIn.setHint("https://script.google.com/macros/s/…/exec#XXXXXXXXXX");
        String srv = Cfg.server(this);
        keyIn.setText(srv.isEmpty() ? "" : srv + "#" + Cfg.key(this));
        if (Build.VERSION.SDK_INT >= 17) keyIn.setTextDirection(View.TEXT_DIRECTION_LTR);
        k.addView(keyIn, new LinearLayout.LayoutParams(-1, -2));

        simBox = card(root);
        simTv = text("الشريحة", 16, 0xFF12314F, true);
        simBox.addView(simTv);

        toggle = new Button(this);
        toggle.setTextSize(19);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setTextColor(Color.WHITE);
        toggle.setAllCaps(false);
        toggle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { onToggle(); } });
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, dp(60));
        tl.topMargin = dp(14);
        root.addView(toggle, tl);

        Button bat = flatBtn("السماح بالعمل في الخلفية دون توقف");
        bat.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { askBattery(true); } });
        root.addView(bat);

        LinearLayout tips = card(root);
        tips.addView(text("نصائح", 16, 0xFF12314F, true));
        tips.addView(text("• اترك الهاتف موصولا بالشاحن ومتصلا بالإنترنت أثناء الدوام.\n• تأكد أن عرض الشريحة فيه رسائل SMS (أو رصيد كاف).\n• في هواتف Xiaomi/Oppo/Huawei: فعّل «التشغيل التلقائي» للتطبيق وألغِ «توفير البطارية».\n• يرسل رسالة واحدة كل دقيقة ونصف، بعد نهاية الحصة بـ 10 دقائق.", 14, MUTED, false));

        LinearLayout lg = card(root);
        lg.addView(text("آخر العمليات", 16, 0xFF12314F, true));
        logTv = text("", 13, 0xFF12314F, false);
        logTv.setTypeface(Typeface.MONOSPACE);
        if (Build.VERSION.SDK_INT >= 17) logTv.setTextDirection(View.TEXT_DIRECTION_RTL);
        lg.addView(logTv);

        setContentView(sv);
    }

    private void refresh() {
        SharedPreferences sp = Cfg.p(this);
        boolean run = Cfg.running(this);
        long seen = sp.getLong("seen_at", 0);
        boolean online = run && System.currentTimeMillis() - seen < 3 * 60 * 1000;
        stateTv.setText(!run ? "● متوقف" : online ? "● يعمل ومتصل بالخادم" : "● يعمل — في انتظار الاتصال");
        stateTv.setTextColor(!run ? RED : online ? GREEN : 0xFF9A5B00);
        statusTv.setText(run ? sp.getString("status", "") : "اضغط «تشغيل» لبدء إرسال الرسائل.");
        sentTv.setText("أُرسلت اليوم: " + Cfg.sentToday(this) + " رسالة");
        logTv.setText(sp.getString("log", "—"));
        toggle.setText(run ? "إيقاف" : "تشغيل");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(run ? RED : GREEN);
        if (Build.VERSION.SDK_INT >= 16) toggle.setBackground(g); else toggle.setBackgroundDrawable(g);
    }

    private void onToggle() {
        if (Cfg.running(this)) {
            Cfg.p(this).edit().putBoolean("running", false).apply();
            SmsService.stop(this);
            Cfg.log(this, "أُوقف");
            refresh();
            return;
        }
        String[] pair = Cfg.parsePair(keyIn.getText().toString());
        if (pair == null) { toast("الصق «رابط الربط» كاملا كما يظهر في صفحة رسائل الأولياء (يبدأ بـ https://script.google.com وينتهي بـ #والرمز)."); return; }
        String key = pair[1];
        Cfg.p(this).edit().putString("server", pair[0]).apply();
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            toast("اسمح للتطبيق بإرسال الرسائل أولا.");
            askPermissions();
            return;
        }
        Cfg.p(this).edit().putString("key", key).putBoolean("running", true).apply();
        Cfg.log(this, "تشغيل");
        Cfg.status(this, "جارٍ الاتصال بالخادم…");
        SmsService.start(this);
        askBattery(false);
        refresh();
    }

    /* ---------------- الشريحة (هاتف بشريحتين) ---------------- */
    private void buildSims() {
        while (simBox.getChildCount() > 1) simBox.removeViewAt(1);
        if (Build.VERSION.SDK_INT < 22 || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            simBox.addView(text("تُستعمل الشريحة الافتراضية للرسائل.", 14, MUTED, false));
            return;
        }
        List<SubscriptionInfo> subs = null;
        try { subs = SubscriptionManager.from(this).getActiveSubscriptionInfoList(); } catch (Exception ignored) { }
        if (subs == null || subs.isEmpty()) { simBox.addView(text("لا توجد شريحة في الهاتف!", 14, RED, true)); return; }
        if (subs.size() == 1) {
            SubscriptionInfo s = subs.get(0);
            Cfg.p(this).edit().putInt("sub", s.getSubscriptionId()).putString("sim_name", simName(s)).apply();
            simBox.addView(text("✓ " + simName(s), 15, GREEN, true));
            return;
        }
        simBox.addView(text("في الهاتف شريحتان: اختر شريحة المؤسسة", 14, MUTED, false));
        int cur = Cfg.subId(this);
        for (final SubscriptionInfo s : subs) {
            Button b = flatBtn((s.getSubscriptionId() == cur ? "✓ " : "") + "الشريحة " + (s.getSimSlotIndex() + 1) + ": " + simName(s));
            if (s.getSubscriptionId() == cur) b.setTextColor(GREEN);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Cfg.p(MainActivity.this).edit().putInt("sub", s.getSubscriptionId()).putString("sim_name", simName(s)).apply();
                    buildSims();
                }
            });
            simBox.addView(b);
        }
    }

    private String simName(SubscriptionInfo s) {
        CharSequence c = s.getCarrierName(), d = s.getDisplayName();
        String n = c != null && c.length() > 0 ? c.toString() : d != null ? d.toString() : "شريحة";
        return n;
    }

    /* ---------------- الأذونات ---------------- */
    private void askPermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> need = new ArrayList<>();
        need.add(Manifest.permission.SEND_SMS);
        need.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> ask = new ArrayList<>();
        for (String p : need) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) ask.add(p);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        buildSims();
    }

    private void askBattery(boolean always) {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            if (always) toast("✓ التطبيق مسموح له بالعمل في الخلفية.");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    /* ---------------- أدوات ---------------- */
    private static String fmtKey(String k) { return k.length() == 10 ? k.substring(0, 5) + "-" + k.substring(5) : k; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = col();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 16) c.setBackground(g); else c.setBackgroundDrawable(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        parent.addView(c, lp);
        return c;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(0, dp(3), 0, dp(3));
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.START);
        return t;
    }

    private Button flatBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(BLUE);
        b.setTextSize(15);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(Color.WHITE);
        g.setStroke(dp(2), BLUE);
        if (Build.VERSION.SDK_INT >= 16) b.setBackground(g); else b.setBackgroundDrawable(g);
        b.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }
}
