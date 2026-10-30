/* رسائل الأولياء — تفتح فورا حتى مع إنترنت ضعيف. نسخة: 5b85e31bf4 */
var CACHE = 'sms-5b85e31bf4';
var SHELL = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './fonts/tajawal-arabic-400-normal.woff2', './fonts/tajawal-arabic-700-normal.woff2', './fonts/tajawal-arabic-800-normal.woff2'];
self.addEventListener('install', function (e) { self.skipWaiting(); e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL); })); });
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (ks) { return Promise.all(ks.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); })); }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  var req = e.request, url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return;     // طلبات البيانات (Google) لا تُحفظ أبدا
  if (/\.(woff2|png|js)$/.test(url.pathname) && !/sw\.js$/.test(url.pathname)) {
    // خطوط وأيقونات وقارئ Excel: من الذاكرة
    e.respondWith(caches.match(req).then(function (r) { return r || fetch(req).then(function (res) { if (res.ok) { var cp = res.clone(); caches.open(CACHE).then(function (c) { c.put(req, cp); }); } return res; }); }));
    return;
  }
  // الصفحة: من الشبكة (أحدث نسخة)، ومن الذاكرة إن تأخرت أكثر من 2 ث أو انقطع الإنترنت
  e.respondWith(new Promise(function (resolve) {
    var done = false, cached = caches.match(req, { ignoreSearch: true });
    var t = setTimeout(function () { cached.then(function (r) { if (r && !done) { done = true; resolve(r); } }); }, 2000);
    fetch(req).then(function (res) {
      if (res && res.ok) { var cp = res.clone(); caches.open(CACHE).then(function (c) { c.put(req, cp); }); }
      if (!done) { done = true; clearTimeout(t); resolve(res); }
    }).catch(function () { cached.then(function (r) { if (!done) { done = true; clearTimeout(t); resolve(r || Response.error()); } }); });
  }));
});
