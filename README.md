# Psiphone abulix

اپ اندروید (Kotlin + Compose) با هسته Xray: CDN Fronting روی Cloudflare (WS+TLS)، Beast Mode (Fragment + TLS fingerprint رندوم)،
اسکنر IP تمیز، پینگ، کشور خروجی، نمودار مصرف، پایداری، و اشتراک کانفیگ رمزنگاری‌شده (فقط داخل همین اپ).

## ساخت APK
1. همه فایل‌ها را در یک ریپوی GitHub آپلود کن.
2. تب Actions → Build Debug APK (خودکار اجرا میشه).
3. خروجی: Artifacts → PsiphoneAbulix-debug.

## قبل از استفاده
به یک سرور خودت نیاز داری (VLESS/Trojan + WebSocket + TLS پشت Cloudflare با پروکسی نارنجی).
لینک vless://... رو کپی کن و در تب «کانفیگ‌ها» از کلیپ‌بورد اضافه کن، بعد اسکنر رو بزن و بهترین IP رو انتخاب کن.
