#!/usr/bin/env bash
# بناء «مرسل رسائل الغيابات» بأدوات Android SDK مباشرة (دون Gradle).
#   SDK_BT  = مجلد build-tools 34 أو أحدث،  SDK_JAR = android.jar (platforms/android-34)
#   KEYSTORE = ملف المفتاح (احتفظ به: بدونه لا يمكن تثبيت تحديث فوق النسخة القديمة)
set -euo pipefail
cd "$(dirname "$0")"
BT="${SDK_BT:?اضبط SDK_BT}"; JAR="${SDK_JAR:?اضبط SDK_JAR}"
SRC=app/src/main; OUT=build
PKG=dz.school.attendance.sms; VCODE=3; VNAME=1.2
rm -rf "$OUT"; mkdir -p "$OUT"/{gen,classes,dex}
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
sed "s|<manifest |<manifest package=\"$PKG\" |" "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$JAR" --manifest "$OUT/AndroidManifest.xml" \
    --java "$OUT/gen" --min-sdk-version 21 --target-sdk-version 34 \
    --version-code $VCODE --version-name $VNAME --auto-add-overlay "$OUT/res.zip"
javac -nowarn -source 8 -target 8 -g -bootclasspath "$JAR" -encoding UTF-8 -d "$OUT/classes" $(find "$SRC/java" "$OUT/gen" -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j ../unsigned.apk classes.dex)
"$BT/zipalign" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KS="${KEYSTORE:-sms-release.keystore}"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass daftarsms -keypass daftarsms -alias sms \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Daftar SMS, O=School, C=DZ" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:daftarsms --key-pass pass:daftarsms --ks-key-alias sms \
    --out "$OUT/daftar-sms.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/daftar-sms.apk" | head -2
echo "APK: $OUT/daftar-sms.apk"
