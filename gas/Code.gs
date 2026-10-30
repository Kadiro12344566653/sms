/**
 * =====================================================================
 *  رسائل الأولياء (sms) — متوسطة الشهيد بدراني بوعمامة
 *  مشروع Google Apps Script مستقل عن «دفتر الغيابات» (daftar):
 *   - يقرأ فقط من ملف daftar على Drive (الغيابات، التلاميذ، الأقسام، الحصص). لا يكتب فيه شيئا.
 *   - يحفظ في ملف خاص به: هواتف الأولياء وسجل الرسائل.
 *   - تطبيق Android «مرسل رسائل الغيابات» على هاتف المؤسسة يأخذ الرسائل ويرسلها من الشريحة.
 *
 *  أول مرة: اختر الدالة setup ثم «تشغيل» ← نشر ← نشر جديد ← تطبيق ويب (التنفيذ باسم: أنا، الوصول: أي شخص).
 * =====================================================================
 */
var CFG = {
  APP_NAME: 'رسائل الأولياء',
  SCHOOL_NAME: 'متوسطة الشهيد بدراني بوعمامة',
  TIMEZONE: 'Africa/Algiers',
  DAFTAR_DB_NAME: 'دفتر الغيابات — قاعدة البيانات',   // اسم ملف daftar على Drive (يُبحث عنه تلقائيا)
  CORRECTION_MINUTES: 10,      // مهلة تصحيح الغياب في daftar بعد نهاية الحصة: الرسالة تُرسل بعدها
  GAP_SEC: 90,                 // ثوان بين رسالة وأخرى
  SCAN_EVERY_SEC: 60,          // البحث عن غيابات جديدة مرة كل دقيقة على الأكثر
  TOKEN_DAYS: 7,
  MAX_ATTEMPTS: 3
};

var DEFAULT_TEMPLATE = 'متوسطة الشهيد بدراني بوعمامة: نعلمكم بغياب {التلميذ} ({القسم}) اليوم {التاريخ} عن حصة {المادة} من {البداية} إلى {النهاية}.';

var SMS_COLS = ['id', 'sms_date', 'kind', 'unit_key', 'student_id', 'name', 'class_label', 'phone', 'text', 'status', 'attempts',
                'created_at', 'send_after', 'sent_at', 'error', 'absence_ids'];
var PHONE_COLS = ['student_id', 'national_id', 'name', 'class_label', 'phone', 'updated_at'];

/* ================================================================== */
/*  التهيئة                                                             */
/* ================================================================== */
function setup() {
  var props = PropertiesService.getScriptProperties();
  // 1) ملف daftar (قراءة فقط)
  if (!props.getProperty('DAFTAR_ID')) {
    var it = DriveApp.getFilesByName(CFG.DAFTAR_DB_NAME), found = [];
    while (it.hasNext()) { var f = it.next(); if (f.getMimeType() === MimeType.GOOGLE_SHEETS && !f.isTrashed()) found.push(f); }
    if (found.length === 1) props.setProperty('DAFTAR_ID', found[0].getId());
    else Logger.log(found.length ? 'وُجد أكثر من ملف باسم «' + CFG.DAFTAR_DB_NAME + '»: الصق رابط الملف الصحيح في صفحة الإعدادات.'
                                 : 'لم أجد ملف «' + CFG.DAFTAR_DB_NAME + '»: الصق رابطه في صفحة الإعدادات.');
  }
  // 2) ملف sms الخاص
  var id = props.getProperty('SMS_DB_ID'), ss = null;
  if (id) { try { ss = SpreadsheetApp.openById(id); } catch (e) { ss = null; } }
  if (!ss) { ss = SpreadsheetApp.create(CFG.APP_NAME + ' (sms) — البيانات'); props.setProperty('SMS_DB_ID', ss.getId()); }
  ensureSheet_(ss, 'sms', SMS_COLS);
  ensureSheet_(ss, 'phones', PHONE_COLS);
  var def = ss.getSheetByName('Sheet1') || ss.getSheetByName('ورقة1') || ss.getSheetByName('Feuille 1');
  if (def && ss.getSheets().length > 2) ss.deleteSheet(def);
  if (!props.getProperty('KEY')) props.setProperty('KEY', newKey_());
  if (!props.getProperty('SECRET')) props.setProperty('SECRET', Utilities.getUuid() + Utilities.getUuid());
  if (!props.getProperty('TEMPLATE')) props.setProperty('TEMPLATE', DEFAULT_TEMPLATE);
  var msg = 'تمت التهيئة. ملف daftar: ' + (props.getProperty('DAFTAR_ID') ? 'موجود ✔' : 'غير محدد ✘ (الصق رابطه في الإعدادات)') +
            ' — ملف sms: ' + ss.getUrl() + ' — الآن: نشر ← نشر جديد ← تطبيق ويب.';
  Logger.log(msg);
  return msg;
}

function ensureSheet_(ss, name, cols) {
  var sh = ss.getSheetByName(name);
  if (!sh) sh = ss.insertSheet(name);
  sh.getRange(1, 1, 1, cols.length).setValues([cols]).setFontWeight('bold').setBackground('#e6eff8');
  sh.setFrozenRows(1);
  sh.getRange(1, 1, sh.getMaxRows(), cols.length).setNumberFormat('@');
  return sh;
}

/* ================================================================== */
/*  أدوات                                                               */
/* ================================================================== */
var MEM = {};
function P_() { return PropertiesService.getScriptProperties(); }
function now_() { return new Date(); }
function fmt_(d, f) { return Utilities.formatDate(d, CFG.TIMEZONE, f); }
function today_() { return fmt_(now_(), 'yyyy-MM-dd'); }
function nowSql_() { return fmt_(now_(), 'yyyy-MM-dd HH:mm:ss'); }
function epoch_(date, hm) { hm = String(hm || '00:00').substr(0, 5); return Utilities.parseDate(date + ' ' + hm + ':00', CFG.TIMEZONE, 'yyyy-MM-dd HH:mm:ss').getTime() / 1000; }
function epochSql_(s) { s = String(s || ''); return s.length >= 16 ? epoch_(s.substr(0, 10), s.substr(11, 5)) + Number(s.substr(17, 2) || 0) : 0; }
function hm_(t) { return String(t || '').substr(0, 5); }
function fail_(m) { throw new Error(m); }
var AR_DAYS = ['الأحد', 'الاثنين', 'الثلاثاء', 'الأربعاء', 'الخميس', 'الجمعة', 'السبت'];
function dow_(date) { var p = date.split('-').map(Number); return new Date(Date.UTC(p[0], p[1] - 1, p[2])).getUTCDay(); }

/** رقم نقال جزائري ← 0XXXXXXXXX، أو '' */
function normPhone_(v) {
  var d = String(v == null ? '' : v).replace(/[٠-٩]/g, function (c) { return String(c.charCodeAt(0) - 0x660); }).replace(/[^\d+]/g, '');
  d = d.replace(/^\+213/, '0').replace(/^00213/, '0').replace(/^213(?=[567]\d{8}$)/, '0');
  if (/^[567]\d{8}$/.test(d)) d = '0' + d;
  return /^0[567]\d{8}$/.test(d) ? d : '';
}

function newKey_() {
  var abc = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789', s = '';
  var b = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, Utilities.getUuid() + Date.now());
  for (var i = 0; i < 10; i++) s += abc.charAt((b[i] + 256) % abc.length);
  return s;
}

function withLock_(fn) {
  if (MEM.locked) return fn();
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) fail_('الخادم مشغول، أعد المحاولة.');
  MEM.locked = true;
  try { return fn(); } finally { MEM.locked = false; SpreadsheetApp.flush(); lock.releaseLock(); }
}

/* ---------------- قراءة ملف daftar (قراءة فقط) ---------------- */
function daftar_() {
  if (!MEM.daftar) {
    var id = P_().getProperty('DAFTAR_ID');
    if (!id) fail_('ملف «دفتر الغيابات — قاعدة البيانات» غير محدد: الصق رابطه في الإعدادات.');
    MEM.daftar = SpreadsheetApp.openById(id);
  }
  return MEM.daftar;
}

/** كل صفوف ورقة من daftar كأشياء حسب أسماء الأعمدة (السطر الأول) */
function dAll_(name) {
  var k = 'd_' + name;
  if (MEM[k]) return MEM[k];
  var sh = daftar_().getSheetByName(name);
  if (!sh || sh.getLastRow() < 2) return (MEM[k] = []);
  var v = sh.getRange(1, 1, sh.getLastRow(), sh.getLastColumn()).getDisplayValues();
  return (MEM[k] = rowsToObjs_(v[0], v.slice(1)));
}

/** صفوف تاريخ معين فقط (ورقة كبيرة مثل absences): نبحث عن أول وآخر سطر للتاريخ */
function dByDate_(name, col, date) {
  var sh = daftar_().getSheetByName(name);
  if (!sh || sh.getLastRow() < 2) return [];
  var head = sh.getRange(1, 1, 1, sh.getLastColumn()).getDisplayValues()[0];
  var ci = head.indexOf(col) + 1;
  if (!ci) return [];
  var found = sh.getRange(2, ci, sh.getLastRow() - 1, 1).createTextFinder(date).matchEntireCell(true).findAll();
  if (!found.length) return [];
  var min = Infinity, max = 0;
  found.forEach(function (c) { var r = c.getRow(); if (r < min) min = r; if (r > max) max = r; });
  var v = sh.getRange(min, 1, max - min + 1, head.length).getDisplayValues();
  return rowsToObjs_(head, v).filter(function (o) { return o[col] === date; });
}

function rowsToObjs_(head, rows) {
  var out = [];
  rows.forEach(function (r) {
    var o = {}, empty = true;
    for (var i = 0; i < head.length; i++) { if (!head[i]) continue; o[head[i]] = String(r[i] == null ? '' : r[i]).trim(); if (o[head[i]] !== '') empty = false; }
    if (!empty) out.push(o);
  });
  return out;
}
function indexBy_(rows, key) { var m = {}; rows.forEach(function (r) { m[r[key]] = r; }); return m; }

/* ---------------- ملف sms (الخاص بهذا المشروع) ---------------- */
function own_() {
  if (!MEM.own) {
    var id = P_().getProperty('SMS_DB_ID');
    if (!id) fail_('شغّل الدالة setup مرة واحدة من المحرر.');
    MEM.own = SpreadsheetApp.openById(id);
  }
  return MEM.own;
}
function ownSheet_(name) { return own_().getSheetByName(name) || ensureSheet_(own_(), name, name === 'sms' ? SMS_COLS : PHONE_COLS); }

function phones_() {
  if (MEM.phones) return MEM.phones;
  var sh = ownSheet_('phones'), out = [];
  if (sh.getLastRow() > 1) {
    sh.getRange(2, 1, sh.getLastRow() - 1, PHONE_COLS.length).getDisplayValues().forEach(function (r, i) {
      var o = { _row: i + 2 }; PHONE_COLS.forEach(function (c, j) { o[c] = String(r[j] || ''); });
      if (o.student_id || o.national_id) out.push(o);
    });
  }
  return (MEM.phones = out);
}

/** هاتف ولي تلميذ من daftar: بمعرّفه، ثم برقم التعريف */
function phoneOf_(st) {
  var list = phones_();
  for (var i = 0; i < list.length; i++) if (list[i].student_id === st.id && list[i].phone) return list[i].phone;
  if (st.national_id) for (var j = 0; j < list.length; j++) if (list[j].national_id === st.national_id && list[j].phone) return list[j].phone;
  return '';
}

/** حفظ هواتف: [{student_id, national_id, name, class_label, phone}] (phone '' = حذف) */
function savePhones_(items) {
  var sh = ownSheet_('phones'), list = phones_(), at = nowSql_(), add = [];
  var byId = {}; list.forEach(function (p) { if (p.student_id) byId[p.student_id] = p; });
  var changed = [];
  items.forEach(function (it) {
    var p = byId[it.student_id];
    if (!p && it.national_id) p = list.filter(function (x) { return x.national_id && x.national_id === it.national_id; })[0];
    var row = { student_id: it.student_id, national_id: it.national_id || '', name: it.name || '', class_label: it.class_label || '', phone: it.phone, updated_at: at };
    if (p) { for (var k in row) p[k] = row[k]; changed.push(p); }
    else if (it.phone) { add.push(row); byId[it.student_id] = row; }
  });
  changed.forEach(function (p) { sh.getRange(p._row, 1, 1, PHONE_COLS.length).setValues([PHONE_COLS.map(function (c) { return p[c]; })]); });
  if (add.length) {
    var start = sh.getLastRow() + 1;
    if (sh.getMaxRows() < start + add.length) sh.insertRowsAfter(sh.getMaxRows(), start + add.length - sh.getMaxRows() + 50);
    sh.getRange(start, 1, add.length, PHONE_COLS.length).setNumberFormat('@').setValues(add.map(function (r) { return PHONE_COLS.map(function (c) { return r[c]; }); }));
  }
  delete MEM.phones;
  return { updated: changed.length, added: add.length };
}

/* سجل الرسائل */
function smsRows_(date) {
  var k = 'sms_' + date;
  if (MEM[k]) return MEM[k];
  var sh = ownSheet_('sms'), out = [];
  if (sh.getLastRow() > 1) {
    var found = sh.getRange(2, 2, sh.getLastRow() - 1, 1).createTextFinder(date).matchEntireCell(true).findAll();
    if (found.length) {
      var min = Infinity, max = 0;
      found.forEach(function (c) { var r = c.getRow(); if (r < min) min = r; if (r > max) max = r; });
      sh.getRange(min, 1, max - min + 1, SMS_COLS.length).getDisplayValues().forEach(function (r, i) {
        var o = { _row: min + i }; SMS_COLS.forEach(function (c, j) { o[c] = String(r[j] || ''); });
        if (o.sms_date === date) out.push(o);
      });
    }
  }
  return (MEM[k] = out);
}
function smsUpdate_(rows) {
  var sh = ownSheet_('sms');
  rows.forEach(function (o) { sh.getRange(o._row, 1, 1, SMS_COLS.length).setValues([SMS_COLS.map(function (c) { return o[c]; })]); });
}
function smsInsert_(rows) {
  if (!rows.length) return;
  var sh = ownSheet_('sms'), last = sh.getLastRow(), next = Number(P_().getProperty('NEXT_ID') || 1);
  rows.forEach(function (r) { r.id = String(next++); });
  P_().setProperty('NEXT_ID', String(next));
  if (sh.getMaxRows() < last + rows.length + 1) sh.insertRowsAfter(sh.getMaxRows(), rows.length + 100);
  sh.getRange(last + 1, 1, rows.length, SMS_COLS.length).setNumberFormat('@')
    .setValues(rows.map(function (r) { return SMS_COLS.map(function (c) { return r[c] == null ? '' : String(r[c]); }); }));
  for (var k in MEM) if (k.indexOf('sms_') === 0) delete MEM[k];
}

/* ================================================================== */
/*  البحث عن غيابات جديدة وتجهيز الرسائل                                */
/* ================================================================== */
/**
 * غيابات اليوم في daftar ← رسالة لكل تلميذ عن كل حصة (الحصص المتتالية مع نفس الأستاذ والقسم = رسالة واحدة).
 * الرسالة تُجهَّز بعد انتهاء مهلة التصحيح (نهاية الحصة + CORRECTION_MINUTES).
 */
function scan_(force) {
  var props = P_();
  if (props.getProperty('ON') !== '1' || !props.getProperty('DAFTAR_ID')) return { queued: 0 };
  var nowS = Date.now() / 1000, last = Number(props.getProperty('LAST_SCAN') || 0);
  if (!force && nowS - last < CFG.SCAN_EVERY_SEC) return { queued: 0, skipped: true };
  props.setProperty('LAST_SCAN', String(Math.floor(nowS)));

  var date = today_(), since = Number(props.getProperty('ON_SINCE') || 0);
  var abs = dByDate_('absences', 'absence_date', date);
  if (!abs.length) return { queued: 0 };
  var sched = indexBy_(dAll_('schedule'), 'id'), times = indexBy_(dAll_('session_times'), 'id');
  var classes = indexBy_(dAll_('classes'), 'id'), subjects = indexBy_(dAll_('subjects'), 'id'), students = indexBy_(dAll_('students'), 'id');

  // تجميع: تلميذ + أستاذ + قسم + فترة، ثم كتل الحصص المتتالية
  var groups = {};
  abs.forEach(function (a) {
    var s = sched[a.schedule_id], t = s ? times[s.session_time_id] : null;
    if (!s || !t || !students[a.student_id]) return;
    var k = a.student_id + '|' + s.teacher_id + '|' + s.class_id + '|' + t.period;
    (groups[k] = groups[k] || []).push({ a: a, s: s, t: t, no: Number(t.session_no) });
  });
  var units = [];
  Object.keys(groups).forEach(function (k) {
    var g = groups[k].sort(function (x, y) { return x.no - y.no; }), cur = [];
    g.forEach(function (x) {
      var prev = cur[cur.length - 1];
      if (prev && (x.no === prev.no + 1 || x.no === prev.no)) cur.push(x);
      else { if (cur.length) units.push(cur); cur = [x]; }
    });
    if (cur.length) units.push(cur);
  });

  var existing = {};
  smsRows_(date).forEach(function (r) { if (r.unit_key) existing[r.unit_key] = r; });
  var tpl = props.getProperty('TEMPLATE') || DEFAULT_TEMPLATE, at = nowSql_(), add = [];
  units.forEach(function (u) {
    var first = u[0], lastU = u[u.length - 1], st = students[first.a.student_id];
    var key = date + '|' + st.id + '|' + first.s.id;
    if (existing[key]) return;
    var ready = epoch_(date, hm_(lastU.t.end_time)) + CFG.CORRECTION_MINUTES * 60;
    if (ready > nowS) return;                               // مهلة التصحيح لم تنته بعد
    if (since && ready < since) return;                     // قبل تفعيل الرسائل: لا نرسل غيابات قديمة
    var cls = classes[st.class_id] || classes[first.s.class_id] || {};
    var subj = []; u.forEach(function (x) { var n = (subjects[x.s.subject_id] || {}).name; if (n && subj.indexOf(n) < 0) subj.push(n); });
    var phone = normPhone_(phoneOf_(st));
    add.push({
      sms_date: date, kind: 'abs', unit_key: key, student_id: st.id, name: st.full_name, class_label: cls.label || '', phone: phone,
      text: render_(tpl, { 'التلميذ': st.full_name, 'القسم': cls.label || '', 'التاريخ': date.split('-').reverse().slice(0, 2).join('/'),
                           'اليوم': AR_DAYS[dow_(date)], 'المادة': subj.join(' / '), 'البداية': hm_(first.t.start_time), 'النهاية': hm_(lastU.t.end_time) }),
      status: phone ? 'pending' : 'no_phone', attempts: '0', created_at: at, send_after: '', sent_at: '', error: phone ? '' : 'لا يوجد هاتف الولي',
      absence_ids: u.map(function (x) { return x.a.id; }).join(',')
    });
  });
  smsInsert_(add);
  return { queued: add.filter(function (r) { return r.status === 'pending'; }).length, no_phone: add.filter(function (r) { return r.status === 'no_phone'; }).length };
}

function render_(tpl, v) {
  return String(tpl).replace(/\{(التلميذ|القسم|التاريخ|اليوم|المادة|البداية|النهاية)\}/g, function (m, k) { return v[k] == null ? '' : v[k]; }).replace(/\s+/g, ' ').trim();
}

/** قبل الإرسال: هل ما زال الغياب مسجلا في daftar؟ (المدير قد يحذف حصة أو يصحح) */
function stillAbsent_(r) {
  if (r.kind !== 'abs' || !r.absence_ids) return true;
  var ids = r.absence_ids.split(','), cur = indexBy_(dByDate_('absences', 'absence_date', r.sms_date), 'id');
  return ids.some(function (id) { return cur[id] && cur[id].student_id === r.student_id; });
}

/* ================================================================== */
/*  تطبيق «مرسل رسائل الغيابات» على هاتف المؤسسة                       */
/* ================================================================== */
/** p = {key, device, done:{id, date, ok, error}} ← {msg:{id,date,phone,text}|null, wait} */
function gateway_next(p) {
  var props = P_();
  var key = props.getProperty('KEY');
  if (!key || String(p.key || '').toUpperCase().replace(/[^A-Z0-9]/g, '') !== key) fail_('رابط الربط غير صحيح: انسخه من جديد من صفحة رسائل الأولياء.');
  props.setProperty('SEEN', nowSql_());
  if (p.device) props.setProperty('DEVICE', String(p.device).substr(0, 60));
  var nowS = Date.now() / 1000, date = today_();
  if (p.done && p.done.id) {
    var d = /^\d{4}-\d{2}-\d{2}$/.test(p.done.date || '') ? p.done.date : date;
    var r = smsRows_(d).filter(function (x) { return x.id === String(p.done.id); })[0];
    if (r && r.status === 'sending') {
      if (p.done.ok) { r.status = 'sent'; r.sent_at = nowSql_(); r.error = ''; }
      else { r.error = String(p.done.error || 'فشل الإرسال').substr(0, 120); r.status = Number(r.attempts) >= CFG.MAX_ATTEMPTS ? 'failed' : 'pending'; }
      smsUpdate_([r]);
    }
  }
  if (props.getProperty('ON') !== '1') return { msg: null, wait: 120, enabled: false };
  try { scan_(false); } catch (e) { props.setProperty('SCAN_ERR', String(e.message || e).substr(0, 200)); }

  var rows = smsRows_(date);
  var stale = rows.filter(function (x) { return x.status === 'sending' && nowS - epochSql_(x.sent_at) > 300; });
  if (stale.length) { stale.forEach(function (x) { x.status = Number(x.attempts) >= CFG.MAX_ATTEMPTS ? 'failed' : 'pending'; x.error = 'انقطع الهاتف أثناء الإرسال'; }); smsUpdate_(stale); }
  var pending = rows.filter(function (x) { return x.status === 'pending'; }).sort(function (a, b) { return Number(a.id) - Number(b.id); });
  if (!pending.length) return { msg: null, wait: 30, enabled: true };
  var left = Math.ceil(CFG.GAP_SEC - (nowS - Number(props.getProperty('LAST_SENT_AT') || 0)));
  if (left > 0) return { msg: null, wait: Math.min(left, CFG.GAP_SEC), enabled: true, pending: pending.length };

  for (var i = 0; i < pending.length; i++) {
    var m = pending[i];
    if (!stillAbsent_(m)) { m.status = 'cancelled'; m.error = 'لم يعد الغياب مسجلا في daftar'; smsUpdate_([m]); continue; }
    m.status = 'sending'; m.attempts = String(Number(m.attempts || 0) + 1); m.sent_at = nowSql_();
    smsUpdate_([m]);
    props.setProperty('LAST_SENT_AT', String(Math.floor(nowS)));
    return { msg: { id: m.id, date: m.sms_date, phone: m.phone, text: m.text }, wait: CFG.GAP_SEC, enabled: true, pending: pending.length - i - 1 };
  }
  return { msg: null, wait: 30, enabled: true };
}

/* ================================================================== */
/*  دخول المدير (بنفس حساب المدير في daftar)                            */
/* ================================================================== */
function sha256Hex_(s) {
  return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, s, Utilities.Charset.UTF_8)
    .map(function (b) { var v = (b < 0 ? b + 256 : b).toString(16); return v.length === 1 ? '0' + v : v; }).join('');
}
function hashPassword_(pw, salt) { var h = salt + '|' + pw; for (var i = 0; i < 300; i++) h = sha256Hex_(h + '|' + salt); return h; }
function sign_(s) { return Utilities.base64EncodeWebSafe(Utilities.computeHmacSha256Signature(s, P_().getProperty('SECRET'))).replace(/=+$/, ''); }

function login_(p) {
  var email = String(p.email || '').trim().toLowerCase(), pw = String(p.password || '');
  var cache = CacheService.getScriptCache(), fk = 'f_' + sha256Hex_(email).substr(0, 16), fails = Number(cache.get(fk) || 0);
  if (fails >= 5) fail_('محاولات كثيرة. حاول بعد 5 دقائق.');
  var u = dAll_('users').filter(function (x) { return String(x.email).toLowerCase() === email && x.role === 'admin' && x.status === 'active'; })[0];
  if (!u || hashPassword_(pw, u.salt) !== u.password_hash) { cache.put(fk, String(fails + 1), 300); fail_('البريد أو كلمة المرور غير صحيحة (حساب المدير في دفتر الغيابات).'); }
  cache.remove(fk);
  var payload = Utilities.base64EncodeWebSafe(JSON.stringify({ u: u.id, h: u.password_hash.substr(0, 12), e: Math.floor(Date.now() / 1000) + CFG.TOKEN_DAYS * 86400 })).replace(/=+$/, '');
  return { token: payload + '.' + sign_(payload), name: u.full_name };
}

function me_(token) {
  if (!token || String(token).indexOf('.') < 0) return null;
  var parts = String(token).split('.');
  if (sign_(parts[0]) !== parts[1]) return null;
  var p; try { var s = parts[0]; while (s.length % 4) s += '='; p = JSON.parse(Utilities.newBlob(Utilities.base64DecodeWebSafe(s)).getDataAsString()); } catch (e) { return null; }
  if (!p || p.e < Date.now() / 1000) return null;
  var u = dAll_('users').filter(function (x) { return x.id === String(p.u) && x.role === 'admin' && x.status === 'active'; })[0];
  return u && u.password_hash.substr(0, 12) === p.h ? u : null;   // تغيير كلمة المرور في daftar يُخرج الجلسة
}

/* ================================================================== */
/*  صفحة المدير                                                         */
/* ================================================================== */
function admin_status(p) {
  var props = P_().getProperties(), date = /^\d{4}-\d{2}-\d{2}$/.test(p.date || '') ? p.date : today_();
  var scan = null;
  if (date === today_()) { try { scan = scan_(true); } catch (e) { scan = { error: e.message }; } }
  var rows = smsRows_(date), st = {};
  rows.forEach(function (r) { st[r.status] = (st[r.status] || 0) + 1; });
  var seen = props.SEEN ? Math.round(Date.now() / 1000 - epochSql_(props.SEEN)) : null;
  var students = props.DAFTAR_ID ? dAll_('students').filter(function (s) { return s.is_active === '1'; }) : [];
  var withPhone = students.filter(function (s) { return normPhone_(phoneOf_(s)); }).length;
  return {
    date: date, enabled: props.ON === '1', seen_ago: seen, seen: props.SEEN || '', device: props.DEVICE || '',
    daftar_ok: !!props.DAFTAR_ID, scan: scan, scan_err: props.SCAN_ERR || '',
    stats: st, total: students.length, with_phone: withPhone,
    list: rows.slice().sort(function (a, b) { return Number(b.id) - Number(a.id); }).slice(0, 300).map(function (r) {
      return { id: r.id, kind: r.kind, name: r.name, label: r.class_label, phone: r.phone, status: r.status, text: r.text, error: r.error,
               time: (r.status === 'sent' ? r.sent_at : r.created_at).substr(11, 5) };
    })
  };
}

function admin_students() {
  var classes = indexBy_(dAll_('classes'), 'id');
  var list = dAll_('students').filter(function (s) { return s.is_active === '1'; }).map(function (s) {
    var c = classes[s.class_id] || {};
    return { id: s.id, nid: s.national_id || '', name: s.full_name, class_id: s.class_id, label: c.label || '?', level: Number(c.level || 0), group: Number(c.group_no || 0), phone: normPhone_(phoneOf_(s)) };
  });
  list.sort(function (a, b) { return (a.level - b.level) || (a.group - b.group) || a.name.localeCompare(b.name, 'ar'); });
  return { students: list };
}

/** items: [{id, phone}] — من الإدخال اليدوي أو من ملف Excel (بعد المطابقة في الصفحة) */
function admin_savePhones(p) {
  var students = indexBy_(dAll_('students'), 'id'), classes = indexBy_(dAll_('classes'), 'id'), bad = 0, items = [];
  (p.items || []).forEach(function (it) {
    var s = students[String(it.id)];
    if (!s) return;
    var raw = String(it.phone || '').trim(), ph = normPhone_(raw);
    if (raw && !ph) { bad++; return; }
    items.push({ student_id: s.id, national_id: s.national_id || '', name: s.full_name, class_label: (classes[s.class_id] || {}).label || '', phone: ph });
  });
  if (bad && items.length <= 1) fail_('رقم الهاتف غير صحيح. أدخل رقما نقالا مثل 0661234567');
  var r = savePhones_(items);
  // رسائل اليوم التي كانت بدون هاتف: تصبح جاهزة
  var fix = smsRows_(today_()).filter(function (x) { return x.status === 'no_phone'; }), ch = [];
  fix.forEach(function (x) { var s = students[x.student_id]; var ph = s ? normPhone_(phoneOf_(s)) : ''; if (ph) { x.phone = ph; x.status = 'pending'; x.error = ''; ch.push(x); } });
  if (ch.length) smsUpdate_(ch);
  return { message: 'تم حفظ ' + (r.updated + r.added) + ' رقم' + (bad ? ' (' + bad + ' رقم غير صحيح لم يُحفظ)' : '') + '.' };
}

function admin_settings() {
  var p = P_().getProperties();
  var url = ''; try { url = ScriptApp.getService().getUrl() || ''; } catch (e) {}
  url = url.replace(/\/dev$/, '/exec');
  var dName = '', dUrl = '';
  if (p.DAFTAR_ID) { try { var f = SpreadsheetApp.openById(p.DAFTAR_ID); dName = f.getName(); dUrl = f.getUrl(); } catch (e) { dName = 'تعذر فتح الملف'; } }
  return { enabled: p.ON === '1', template: p.TEMPLATE || DEFAULT_TEMPLATE, default_template: DEFAULT_TEMPLATE, key: p.KEY || '',
           pair: url && p.KEY ? url + '#' + p.KEY : '', daftar_name: dName, daftar_url: dUrl, gap: CFG.GAP_SEC, late: CFG.CORRECTION_MINUTES };
}

function admin_saveSettings(p) {
  var props = P_(), set = {};
  if (p.daftar_link !== undefined && String(p.daftar_link).trim()) {
    var m = String(p.daftar_link).match(/[-\w]{25,}/);
    if (!m) fail_('الرابط غير صحيح: انسخ رابط ملف «دفتر الغيابات — قاعدة البيانات» من Drive.');
    try {
      var ss = SpreadsheetApp.openById(m[0]);
      if (!ss.getSheetByName('absences') || !ss.getSheetByName('students')) fail_('هذا ليس ملف قاعدة بيانات دفتر الغيابات.');
    } catch (e) { fail_(e.message.indexOf('دفتر') >= 0 ? e.message : 'تعذر فتح الملف: تأكد أنه على نفس حساب Google.'); }
    set.DAFTAR_ID = m[0];
  }
  if (p.template !== undefined) {
    var t = String(p.template || '').trim();
    if (t && t.indexOf('{التلميذ}') < 0) fail_('يجب أن يحتوي النص على {التلميذ}.');
    if (t.length > 300) fail_('النص طويل جدا.');
    set.TEMPLATE = t || DEFAULT_TEMPLATE;
  }
  if (p.enabled !== undefined) {
    set.ON = p.enabled ? '1' : '0';
    if (p.enabled && props.getProperty('ON') !== '1') set.ON_SINCE = String(Math.floor(Date.now() / 1000));   // لا نرسل غيابات ما قبل التفعيل
  }
  if (p.new_key) set.KEY = newKey_();
  props.setProperties(set);
  return { message: p.new_key ? 'تم إنشاء رابط ربط جديد: اربط الهاتف من جديد.' : p.enabled === true ? 'تم التفعيل: تُرسل الرسائل بعد انتهاء مهلة التصحيح.' : p.enabled === false ? 'تم إيقاف الرسائل.' : 'تم الحفظ.' };
}

/** وضع التجربة: رسالة إلى أي رقم، جاهزة فورا */
function admin_test(p) {
  var ph = normPhone_(p.phone);
  if (!ph) fail_('رقم الهاتف غير صحيح. مثال: 0664172468');
  var text = String(p.text || '').replace(/\s+/g, ' ').trim();
  if (!text) fail_('اكتب نص الرسالة.');
  if (text.length > 300) fail_('النص طويل جدا.');
  smsInsert_([{ sms_date: today_(), kind: 'test', unit_key: '', student_id: '', name: String(p.name || 'رسالة تجريبية').substr(0, 60), class_label: '', phone: ph, text: text,
                status: 'pending', attempts: '0', created_at: nowSql_(), send_after: '', sent_at: '', error: '', absence_ids: '' }]);
  return { message: 'أُضيفت رسالة تجريبية إلى ' + ph + '. تُرسل خلال دقيقتين إن كان هاتف المؤسسة يعمل.' };
}

function admin_action(p) {
  var rows = smsRows_(today_()), ch = [];
  if (p.op === 'retry') rows.forEach(function (r) { if (r.status === 'failed') { r.status = 'pending'; r.attempts = '0'; r.error = ''; ch.push(r); } });
  if (p.op === 'cancel') rows.forEach(function (r) { if (r.status === 'pending') { r.status = 'cancelled'; r.error = 'ألغاها المدير'; ch.push(r); } });
  if (p.op === 'cancel_one') rows.forEach(function (r) { if (r.id === String(p.id) && r.status === 'pending') { r.status = 'cancelled'; r.error = 'ألغاها المدير'; ch.push(r); } });
  if (ch.length) smsUpdate_(ch);
  return { message: (p.op === 'retry' ? 'أُعيدت ' : 'أُلغيت ') + ch.length + ' رسالة.' };
}

/* ================================================================== */
/*  الواجهة                                                             */
/* ================================================================== */
var ROUTES = {
  'login':         { fn: login_ },
  'status':        { fn: admin_status, admin: true },
  'students':      { fn: admin_students, admin: true },
  'savePhones':    { fn: admin_savePhones, admin: true, lock: true },
  'settings':      { fn: admin_settings, admin: true },
  'saveSettings':  { fn: admin_saveSettings, admin: true, lock: true },
  'test':          { fn: admin_test, admin: true, lock: true },
  'action':        { fn: admin_action, admin: true, lock: true },
  'next':          { fn: gateway_next, lock: true }
};

/** كل الطلبات: من الصفحة (google.script.run.api) ومن تطبيق الهاتف (doPost) */
function api(action, token, payload) {
  try {
    var r = ROUTES[action];
    if (!r) fail_('إجراء غير معروف.');
    if (r.admin && !me_(token)) return { ok: false, auth: true, error: 'انتهت الجلسة، ادخل من جديد.' };
    var run = function () { return r.fn(payload || {}); };
    return { ok: true, data: r.lock ? withLock_(run) : run() };
  } catch (e) {
    return { ok: false, error: String(e && e.message ? e.message : e) };
  }
}

function doGet() {
  if (!P_().getProperty('SMS_DB_ID')) setup();
  return HtmlService.createHtmlOutputFromFile('Index').setTitle(CFG.APP_NAME)
    .addMetaTag('viewport', 'width=device-width, initial-scale=1, viewport-fit=cover');
}

/** تطبيق الهاتف: POST نص JSON {action:'next', payload:{key, device, done}} */
function doPost(e) {
  var out;
  try {
    var req = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    out = (req.action === 'next' || req.action === 'sms.next') ? api('next', null, req.payload || {}) : { ok: false, error: 'طلب غير صالح.' };
  } catch (err) { out = { ok: false, error: 'طلب غير صالح.' }; }
  return ContentService.createTextOutput(JSON.stringify(out)).setMimeType(ContentService.MimeType.JSON);
}
