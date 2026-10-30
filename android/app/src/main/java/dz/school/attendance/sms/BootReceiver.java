package dz.school.attendance.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** بعد إعادة تشغيل الهاتف أو تحديث التطبيق: يعود الإرسال تلقائيا إن كان مشغلا */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Cfg.running(c) && !Cfg.key(c).isEmpty()) {
            try { SmsService.start(c); } catch (Exception ignored) { }
        }
    }
}
