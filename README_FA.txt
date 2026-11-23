AghaKocholo Game Booster v2.0
===============================

ساخت APK فقط با گوشی از طریق GitHub Actions

۱) وارد github.com شو و یک حساب رایگان بساز/وارد شو.
۲) یک Repository جدید بساز؛ نام پیشنهادی: AghaKocholo-Game-Booster
۳) ZIP را از حالت فشرده خارج کن.
۴) تمام فایل‌ها و پوشه‌های داخل پوشه AghaKocholo-Game-Booster-v2 را در ریشه Repository آپلود کن.
   نکته: خود پوشه بیرونی را آپلود نکن؛ فایل‌هایی مثل settings.gradle و پوشه app باید در ریشه باشند.
۵) Commit changes را بزن.
۶) در Repository وارد تب Actions شو.
۷) workflow با نام Build AghaKocholo Game Booster APK اجرا می‌شود.
۸) پس از سبز شدن اجرا، پایین صفحه اجرا بخش Artifacts را باز کن و
   AghaKocholo-Game-Booster-v2.0 را دانلود کن.
۹) ZIP خروجی را باز کن؛ app-debug.apk داخل آن است. آن را نصب کن.

اگر workflow خودکار اجرا نشد:
Actions > Build AghaKocholo Game Booster APK > Run workflow

مشخصات:
- Package: com.aghakoochooloo.gamebooster
- Version: 2.0
- Minimum Android: 6.0 (API 23)
- Java: 17
- Gradle: 8.13
- Android Gradle Plugin: 8.13.2
- Compile SDK: 36

محدودیت‌های شفاف:
- عدد نمایشگر، نرخ نوسازی صفحه (Hz) است، نه FPS واقعی بازی.
- اندروید معمولاً دمای CPU را به اپ‌های عادی نمی‌دهد؛ دمای باتری نمایش داده می‌شود.
- تست شبکه، زمان پاسخ HTTPS به سرور تست است و پینگ سرور بازی نیست.
- دکمه Boost انیمیشن دارد و بازی انتخاب‌شده را اجرا می‌کند؛ CPU/GPU را اورکلاک یا برنامه‌های دیگر را اجباری نمی‌بندد.
- تنظیمات گرافیکی، تنظیمات نمایشگر اندروید را باز می‌کند؛ کیفیت گرافیک داخل هر بازی باید از خود بازی تنظیم شود.
- اولین بار برای DND باید دسترسی ویژه را از تنظیمات گوشی فعال کنی.
