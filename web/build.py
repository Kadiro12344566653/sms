"""يبني صفحة «رسائل الأولياء» لـ GitHub Pages من نفس ملف الواجهة gas/Index.html.
الاستعمال:  python3 web/build.py <مجلد الإخراج>   (ثم يُرفع المجلد إلى فرع gh-pages)
"""
import hashlib, json, os, re, shutil, sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, 'web', 'out')
CODE = open(os.path.join(ROOT, 'gas', 'Code.gs'), encoding='utf-8').read()
# رابط الخادم = نفس الرابط المدمج في تطبيق الهاتف
API = re.search(r'static final String SERVER = "([^"]+)"', open(os.path.join(ROOT, 'android/app/src/main/java/dz/school/attendance/sms/Cfg.java'), encoding='utf-8').read()).group(1)
ASSETS = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'www')

os.makedirs(os.path.join(OUT, 'fonts'), exist_ok=True)
html = open(os.path.join(ROOT, 'gas', 'Index.html'), encoding='utf-8').read()
head = ('<meta name="theme-color" content="#0b4b8a">\n<meta name="robots" content="noindex, nofollow">\n'
        '<link rel="manifest" href="manifest.webmanifest">\n<link rel="icon" href="icon-192.png">\n<link rel="apple-touch-icon" href="icon-192.png">\n'
        '<link rel="preconnect" href="https://script.google.com">\n<link rel="preconnect" href="https://script.googleusercontent.com">\n'
        '<link rel="preload" href="fonts/tajawal-arabic-400-normal.woff2" as="font" type="font/woff2" crossorigin>\n'
        '<link rel="preload" href="fonts/tajawal-arabic-700-normal.woff2" as="font" type="font/woff2" crossorigin>')
html = html.replace('<meta charset="UTF-8">', '<meta charset="UTF-8">\n' + head, 1)
# الخطوط من الموقع نفسه (لا حاجة لـ Google Fonts)
html = re.sub(r'<link href="https://fonts.googleapis.com/[^>]*>\n?', '', html)
html = html.replace('<script>\n/* ====', '<script>window.SMS_API = %s;</script>\n<script>\n/* ====' % json.dumps(API), 1)
html = html.replace('</body>', "<script>if ('serviceWorker' in navigator) addEventListener('load', function () { navigator.serviceWorker.register('sw.js').catch(function () {}); });</script>\n</body>")
assert 'window.SMS_API' in html
open(os.path.join(OUT, 'index.html'), 'w', encoding='utf-8').write(html)

for f in os.listdir(os.path.join(ASSETS, 'fonts')):
    shutil.copy(os.path.join(ASSETS, 'fonts', f), os.path.join(OUT, 'fonts', f))
shutil.copy(os.path.join(ASSETS, 'xlsx.full.min.js'), os.path.join(OUT, 'xlsx.full.min.js'))
icon = Image.open(os.path.join(ROOT, 'android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png')).convert('RGBA')
icon.resize((192, 192), Image.LANCZOS).save(os.path.join(OUT, 'icon-192.png'))
icon.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, 'icon-512.png'))
json.dump({"name": "رسائل الأولياء — متوسطة الشهيد بدراني بوعمامة", "short_name": "رسائل الأولياء", "lang": "ar", "dir": "rtl",
           "start_url": "./", "scope": "./", "display": "standalone", "background_color": "#0b4b8a", "theme_color": "#0b4b8a",
           "icons": [{"src": "icon-192.png", "sizes": "192x192", "type": "image/png"},
                     {"src": "icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any maskable"}]},
          open(os.path.join(OUT, 'manifest.webmanifest'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
ver = hashlib.sha1(html.encode()).hexdigest()[:10]
open(os.path.join(OUT, 'sw.js'), 'w', encoding='utf-8').write("""/* رسائل الأولياء — تفتح فورا حتى مع إنترنت ضعيف. نسخة: %s */
var CACHE = 'sms-%s';
var SHELL = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './fonts/tajawal-arabic-400-normal.woff2', './fonts/tajawal-arabic-700-normal.woff2', './fonts/tajawal-arabic-800-normal.woff2'];
self.addEventListener('install', function (e) { self.skipWaiting(); e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL); })); });
self.addEventListener('activate', function (e) {
  e.waitUntil(caches.keys().then(function (ks) { return Promise.all(ks.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); })); }).then(function () { return self.clients.claim(); }));
});
self.addEventListener('fetch', function (e) {
  var req = e.request, url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== location.origin) return;     // طلبات البيانات (Google) لا تُحفظ أبدا
  if (/\\.(woff2|png|js)$/.test(url.pathname) && !/sw\\.js$/.test(url.pathname)) {
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
""" % (ver, ver))
open(os.path.join(OUT, '.nojekyll'), 'w').close()
print('built', OUT, 'version', ver, 'api', API[:60] + '…')
