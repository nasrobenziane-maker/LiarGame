# إعداد تحديثات Android

يبني GitHub Actions نسخة debug للتجربة إذا لم تُضبط مفاتيح التوقيع. لتثبيت النسخ اللاحقة كتحديث فوق التطبيق الموجود، يجب توقيع كل نسخة بمفتاح الإصدار نفسه.

1. أنشئ مفتاحاً مرة واحدة على جهازك:

   ```powershell
   keytool -genkeypair -v -keystore liargame-release.jks -alias liargame -keyalg RSA -keysize 2048 -validity 10000
   ```

   احتفظ بملف `liargame-release.jks` وكلمات المرور في مكان آمن. لا تغيّر المفتاح ولا تفقده.

2. في إعدادات مستودع GitHub، أضف Repository secrets التالية:
   - `ANDROID_KEYSTORE_BASE64`: محتوى ملف keystore بترميز Base64. في PowerShell:
     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes('.\liargame-release.jks'))
     ```
   - `ANDROID_KEYSTORE_PASSWORD`: كلمة مرور keystore.
   - `ANDROID_KEY_ALIAS`: `liargame` (أو الاسم الذي اخترته عند إنشائه).
   - `ANDROID_KEY_PASSWORD`: كلمة مرور المفتاح.

بعد ضبطها، ينتج كل تشغيل للبناء APK إصداراً موقعاً قابلاً للتحديث، مع زيادة رقم الإصدار تلقائياً. النسخة المثبتة حالياً إذا كانت debug قد تحتاج إلى إزالتها مرة واحدة قبل تثبيت أول APK موقع؛ بعدها تُثبت التحديثات فوقها.
