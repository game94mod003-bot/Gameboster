package com.abolix.gamebooster;

import android.content.Context;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local, privacy-conscious connection diagnostics. No profile or subscription content is collected. */
public final class AbolixDiagnostics {
    private AbolixDiagnostics() { }

    public static final class Issue {
        public final String code;
        public final String title;
        public final String suggestion;

        private Issue(String code, String title, String suggestion) {
            this.code = code;
            this.title = title;
            this.suggestion = suggestion;
        }
    }

    private static final class Rule {
        final String[] needles;
        final Issue issue;
        Rule(String code, String title, String suggestion, String... needles) {
            this.needles = needles;
            this.issue = new Issue(code, title, suggestion);
        }
        boolean matches(String value) {
            for (String needle : needles) if (value.contains(needle)) return true;
            return false;
        }
    }

    // More than 50 common failure signatures are classified locally. Unknown errors retain
    // their original diagnostic text and receive the generic ABX-050 code.
    private static final Rule[] RULES = {
        new Rule("ABX-DNS-001", "مسیر DNS ناسازگار است", "خطای DNS مربوط به detour است. در DNS server جدید sing-box نباید detour: direct تنظیم شود.", "detour to an empty direct outbound makes no sense", "empty direct outbound"),
        new Rule("ABX-DNS-002", "نام سرور DNS پیدا نشد", "اتصال اینترنت یا DNS اپراتور را بررسی کن؛ یک بار با Wi‑Fi و یک بار با دیتای موبایل امتحان کن.", "no such host", "unknownhostexception", "name or service not known", "temporary failure in name resolution"),
        new Rule("ABX-DNS-003", "پاسخ DNS دریافت نشد", "سرور DNS پاسخ نداده است. اتصال را عوض کن یا بعداً دوباره امتحان کن.", "dns timeout", "i/o timeout", "lookup timeout", "context deadline exceeded"),
        new Rule("ABX-DNS-004", "ساختار DNS پشتیبانی نمی‌شود", "نوع و فیلدهای DNS کانفیگ با نسخه هسته سازگار نیست؛ کانفیگ را به‌روز کن.", "unknown dns server type", "dns server type", "missing dns server"),
        new Rule("ABX-DNS-005", "گواهی DNS امن معتبر نیست", "تاریخ و ساعت گوشی را خودکار کن و گواهی سرور DNS را بررسی کن.", "x509:", "certificate is not valid", "certificate has expired", "certificate signed by unknown authority"),
        new Rule("ABX-DNS-006", "اتصال HTTPS-DNS برقرار نشد", "پورت 443 و دسترسی به DNS-over-HTTPS را بررسی کن.", "dns/https", "https dns", "dns over https"),
        new Rule("ABX-DNS-007", "مسیر DNS به outbound ناموجود اشاره می‌کند", "tag مربوط به DNS یا outbound را اصلاح کن؛ نام tagها باید دقیقاً یکسان باشند.", "dns detour", "dns outbound", "detour not found"),
        new Rule("ABX-DNS-008", "پاسخ DNS خالی است", "سرور DNS دیگری انتخاب کن و دوباره اتصال را امتحان کن.", "empty dns response", "no answer", "no such record"),
        new Rule("ABX-NET-001", "سرور در دسترس نیست", "آدرس و پورت کانفیگ را بررسی کن؛ ممکن است سرور خاموش یا فیلتر شده باشد.", "connection refused", "actively refused"),
        new Rule("ABX-NET-002", "زمان اتصال به سرور تمام شد", "پینگ و پورت سرور را بررسی کن یا یک سرور دیگر انتخاب کن.", "connection timed out", "connect timed out", "operation timed out", "i/o timeout"),
        new Rule("ABX-NET-003", "شبکه در دسترس نیست", "حالت پرواز را خاموش کن و اتصال Wi‑Fi یا دیتای موبایل را بررسی کن.", "network is unreachable", "enetunreach"),
        new Rule("ABX-NET-004", "مسیر شبکه پیدا نشد", "شبکه فعلی اینترنت ندارد یا مسیر دسترسی به سرور وجود ندارد.", "no route to host", "network unreachable"),
        new Rule("ABX-NET-005", "اتصال توسط مقصد بسته شد", "ممکن است سرور اتصال را رد کرده باشد؛ پورت یا وضعیت اشتراک را بررسی کن.", "connection reset by peer", "forcibly closed", "econnreset"),
        new Rule("ABX-NET-006", "اتصال ناگهانی قطع شد", "شبکه ناپایدار است؛ بین Wi‑Fi و دیتای موبایل جابه‌جا شو.", "unexpected eof", "broken pipe", "connection was closed"),
        new Rule("ABX-NET-007", "اینترنت دستگاه موجود نیست", "ابتدا بدون VPN بازشدن یک سایت معمولی را بررسی کن.", "no internet", "network has no internet", "net::err_internet_disconnected"),
        new Rule("ABX-NET-008", "پورت سرور نامعتبر است", "شماره پورت باید بین 1 تا 65535 باشد.", "invalid port", "port out of range", "port is required"),
        new Rule("ABX-NET-009", "آدرس سرور نامعتبر است", "دامنه یا IP سرور را دوباره از ارائه‌دهنده دریافت کن.", "invalid address", "invalid ip", "address is invalid"),
        new Rule("ABX-NET-010", "اتصال به شبکه تغییر کرده است", "اتصال را قطع و دوباره وصل کن؛ هنگام جابه‌جایی Wi‑Fi و موبایل این خطا ممکن است رخ دهد.", "network changed", "network is lost", "onlost"),
        new Rule("ABX-TLS-001", "مذاکره TLS ناموفق بود", "SNI، گواهی، ساعت گوشی و تنظیمات TLS کانفیگ را بررسی کن.", "tls handshake", "handshake failure", "remote error: tls"),
        new Rule("ABX-TLS-002", "گواهی TLS معتبر نیست", "ساعت گوشی باید صحیح باشد؛ گواهی سرور را از ارائه‌دهنده بررسی کن.", "certificate verify failed", "unknown certificate", "certificate required"),
        new Rule("ABX-TLS-003", "نام دامنه گواهی با SNI یکی نیست", "مقدار SNI یا server_name باید با دامنه گواهی سرور سازگار باشد.", "hostname mismatch", "doesn't match", "not valid for"),
        new Rule("ABX-TLS-004", "گواهی TLS منقضی شده است", "تاریخ و ساعت دستگاه را بررسی کن؛ در صورت صحت، ارائه‌دهنده باید گواهی را تمدید کند.", "certificate has expired", "expired certificate"),
        new Rule("ABX-REALITY-001", "کلید Reality نامعتبر است", "publicKey را دقیقاً از لینک اصلی کپی کن؛ کاراکتر اضافه یا فاصله نباید داشته باشد.", "invalid public key", "reality public_key", "reality: invalid"),
        new Rule("ABX-REALITY-002", "تنظیمات Reality ناقص است", "کلید publicKey و در صورت نیاز shortId را از ارائه‌دهنده بگیر.", "realitysettings", "short_id", "shortid"),
        new Rule("ABX-VPN-001", "مجوز VPN صادر نشده است", "در پنجره اندروید اجازه اتصال VPN را تأیید کن و مطمئن شو برنامه VPN دیگری فعال نیست.", "vpn permission", "permission not granted", "user rejected"),
        new Rule("ABX-VPN-002", "رابط TUN ساخته نشد", "برنامه VPN دیگری را قطع کن و مجوز VPN را دوباره تأیید کن.", "tun interface", "tun device", "establish() returned null", "tun was not created"),
        new Rule("ABX-VPN-003", "سوکت هسته محافظت نشد", "Android نتوانسته سوکت خروجی هسته را از تونل خارج کند؛ شبکه را تغییر بده و دوباره تلاش کن.", "protect socket", "protect the socket", "socket protection"),
        new Rule("ABX-VPN-004", "اندروید اجرای سرویس VPN را متوقف کرد", "اعلان‌های Abolix را فعال کن و محدودیت اجرای پس‌زمینه/باتری را برای برنامه بردار.", "foreground service", "startforeground", "foreground_service"),
        new Rule("ABX-VPN-005", "سرویس VPN قبلی هنوز بسته نشده است", "چند ثانیه صبر کن، قطع اتصال را بزن و دوباره تلاش کن.", "already active", "service is already running", "service already started"),
        new Rule("ABX-VPN-006", "ساخت آدرس رابط VPN ناموفق بود", "آدرس‌های IPv4 و IPv6 و MTU کانفیگ TUN را بررسی کن.", "invalid tun address", "addaddress", "route address"),
        new Rule("ABX-VPN-007", "مسیرهای VPN ناسازگارند", "قوانین route و route-exclude کانفیگ را بررسی کن.", "invalid route", "route overlap", "exclude route"),
        new Rule("ABX-VPN-008", "مجوز اعلان‌ها غیرفعال است", "از تنظیمات اندروید اعلان‌های Abolix را فعال کن تا خطاهای اتصال را ببینی.", "post_notifications", "notification permission", "notifications are disabled"),
        new Rule("ABX-VPN-009", "برنامه دیگری VPN را در اختیار دارد", "VPN فعال دیگر، فایروال یا AdBlock مبتنی بر VPN را موقتاً خاموش کن.", "another vpn", "vpn already", "only one vpn"),
        new Rule("ABX-CORE-001", "کتابخانه sing-box بارگذاری نشد", "نسخه APK یا معماری پردازنده مناسب گوشی را نصب کن؛ برنامه را از منبع معتبر دوباره بساز.", "classnotfoundexception", "libbox", "no implementation found"),
        new Rule("ABX-CORE-002", "راه‌اندازی اولیه هسته ناموفق بود", "فایل‌های برنامه یا دسترسی پوشه‌های کاری هسته مشکل دارند؛ داده برنامه را پاک و دوباره نصب کن.", "setup failed", "libbox setup", "workingpath", "basepath"),
        new Rule("ABX-CORE-003", "نسخه API هسته با برنامه سازگار نیست", "نسخه libbox و bridge اندروید باید از یک نسخه باشند.", "nosuchmethodexception", "method not found", "unimplemented method"),
        new Rule("ABX-CORE-004", "ساختار کانفیگ JSON معتبر نیست", "فایل JSON را دوباره خروجی بگیر؛ ویرگول اضافه، کوتیشن یا براکت ناقص را اصلاح کن.", "jsonexception", "unexpected character", "unexpected token", "invalid character"),
        new Rule("ABX-CORE-005", "sing-box یک گزینه ناشناخته دیده است", "کانفیگ را برای نسخه sing-box 1.14.4 تبدیل کن و گزینه‌های قدیمی را حذف کن.", "unknown field", "unknown option", "unknown key"),
        new Rule("ABX-CORE-006", "یک بخش اجباری کانفیگ وجود ندارد", "اطلاعات کامل سرور و فیلدهای اجباری پروتکل را وارد کن.", "missing required", "is required", "field is missing"),
        new Rule("ABX-CORE-007", "برچسب outbound تکراری است", "برای هر outbound یک tag یکتا تعریف کن.", "duplicate tag", "already exists"),
        new Rule("ABX-CORE-008", "ارجاع به outbound پیدا نشد", "مقدار tag در route یا DNS باید دقیقاً با tag یک outbound برابر باشد.", "outbound not found", "outbound not exists", "not found: outbound"),
        new Rule("ABX-CORE-009", "نوع inbound پشتیبانی نمی‌شود", "نوع inbound این کانفیگ را به TUN یا یک ورودی پشتیبانی‌شده تبدیل کن.", "unknown inbound type", "unsupported inbound"),
        new Rule("ABX-CORE-010", "نوع outbound پشتیبانی نمی‌شود", "این پروتکل در نسخه فعلی هسته نیست؛ از VLESS، VMess، Trojan، Shadowsocks یا پروتکل‌های اعلام‌شده استفاده کن.", "unknown outbound type", "unsupported protocol", "unsupported outbound"),
        new Rule("ABX-CORE-011", "هسته حافظه کافی ندارد", "برنامه‌های سنگین را ببند، گوشی را راه‌اندازی مجدد کن و دوباره تلاش کن.", "outofmemoryerror", "out of memory", "cannot allocate memory"),
        new Rule("ABX-CORE-012", "زمان راه‌اندازی هسته تمام شد", "اگر بیش از 45 ثانیه طول کشید، گزارش را ارسال و یک سرور دیگر امتحان کن.", "45 seconds", "startup timeout", "watchdog"),
        new Rule("ABX-CORE-013", "هسته هنگام شروع متوقف شد", "گزارش خطا را بفرست؛ لاگ کامل برای تشخیص علت لازم است.", "panic:", "fatal error", "native crash", "segmentation fault"),
        new Rule("ABX-PROFILE-001", "UUID یا شناسه کاربر معتبر نیست", "UUID را از لینک اصلی دوباره کپی کن و مطمئن شو ناقص نشده است.", "invalid uuid", "invalid user id", "uuid"),
        new Rule("ABX-PROFILE-002", "رمز یا روش رمزنگاری نامعتبر است", "رمز و cipher را دقیقاً با اطلاعات ارائه‌دهنده تطبیق بده.", "invalid password", "unsupported cipher", "unknown method"),
        new Rule("ABX-PROFILE-003", "تنظیمات انتقال WebSocket ناقص است", "path و Host هدر باید با لینک اصلی هماهنگ باشند.", "websocket", "ws path", "websocket: bad"),
        new Rule("ABX-PROFILE-004", "تنظیمات gRPC ناقص است", "serviceName را از ارائه‌دهنده کانفیگ دریافت کن.", "grpc", "service_name"),
        new Rule("ABX-PROFILE-005", "تنظیمات VMess ناقص است", "UUID، alterId و security را بررسی کن؛ لینک VMess را کامل کپی کن.", "vmess", "alter_id", "alterid"),
        new Rule("ABX-PROFILE-006", "تنظیمات VLESS ناقص است", "UUID، flow و نوع امنیت را از لینک VLESS بررسی کن.", "vless", "flow"),
        new Rule("ABX-PROFILE-007", "پروفایل Trojan ناقص است", "رمز، پورت و SNI را بررسی کن.", "trojan", "password"),
        new Rule("ABX-SUB-001", "اشتراک خالی است", "لینک اشتراک را دوباره بررسی کن؛ ممکن است اشتراک منقضی یا خالی باشد.", "subscription is empty", "no proxies", "no nodes", "empty subscription"),
        new Rule("ABX-SUB-002", "رمزگشایی Base64 ناموفق بود", "لینک کامل اشتراک را کپی کن؛ ابتدای base64:// یا انتهای متن نباید حذف شود.", "base64", "illegal base64", "bad base64"),
        new Rule("ABX-SUB-003", "فایل YAML قابل خواندن نیست", "فایل Clash YAML را دوباره دانلود کن و فاصله‌گذاری و ساختار آن را بررسی کن.", "yaml", "snakeyaml", "while parsing", "mapping values are not allowed"),
        new Rule("ABX-SUB-004", "سرور اشتراک دسترسی را رد کرد", "وضعیت اشتراک، محدودیت IP و اعتبار لینک را در پنل ارائه‌دهنده بررسی کن.", "http 401", "http 403", "unauthorized", "forbidden"),
        new Rule("ABX-SUB-005", "سرور اشتراک پیدا نشد", "دامنه لینک اشتراک یا اتصال اینترنت را بررسی کن.", "http 404", "not found"),
        new Rule("ABX-SUB-006", "درخواست اشتراک بیش از حد تکرار شده", "چند دقیقه صبر کن؛ سرور اشتراک محدودیت تعداد درخواست دارد.", "http 429", "too many requests"),
        new Rule("ABX-SUB-007", "سرور اشتراک موقتاً خطا دارد", "مشکل از سرور اشتراک است؛ کمی بعد دوباره به‌روزرسانی کن.", "http 500", "http 502", "http 503", "bad gateway", "service unavailable"),
        new Rule("ABX-SUB-008", "اندازه اشتراک بیش از حد مجاز است", "فهرست اشتراک را در پنل ارائه‌دهنده کوچک‌تر یا فیلتر کن.", "response too large", "more than 3 megabytes"),
        new Rule("ABX-SUB-009", "زمان اتصال اشتراک تمام شد", "آدرس اشتراک را بعد از اتصال اینترنت بازبینی کن.", "subscription timeout", "read timed out", "connect timed out"),
        new Rule("ABX-ANDROID-001", "دسترسی لازم اندروید رد شده است", "مجوزهای موردنیاز Abolix را از تنظیمات برنامه بررسی کن.", "securityexception", "permission denied", "not allowed"),
        new Rule("ABX-ANDROID-002", "سازگاری نسخه اندروید مشکل دارد", "نسخه Android گوشی و حداقل نسخه موردنیاز برنامه را بررسی کن.", "unsupportedoperationexception", "requires api", "not supported on this device"),
        new Rule("ABX-ANDROID-003", "برنامه توسط سیستم محدود شده است", "محدودیت باتری را برای Abolix بردار و اجازه اجرای پس‌زمینه بده.", "background start not allowed", "appop", "restricted"),
        new Rule("ABX-ANDROID-004", "فایل یا پوشه برنامه قابل دسترسی نیست", "فضای خالی گوشی را بررسی کن؛ سپس برنامه را دوباره نصب کن.", "permission denied", "failed to create", "no space left"),
        new Rule("ABX-UNKNOWN-001", "پاسخ سرور قابل تشخیص نیست", "گزارش تشخیصی را برای سازنده Abolix ارسال کن تا خطا در نسخه بعد بررسی شود.", "eof", "malformed response", "unexpected response")
    };

    private static final Issue UNKNOWN = new Issue("ABX-050", "خطای ناشناخته در اتصال", "این خطا در فهرست محلی شناخته نشد. گزارش تشخیصی را برای سازنده Abolix ارسال کن.");

    public static Issue classify(String rawMessage) {
        String value = rawMessage == null ? "" : rawMessage.toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) if (rule.matches(value)) return rule.issue;
        return UNKNOWN;
    }

    public static int knownIssueCount() { return RULES.length; }

    public static String formatReport(Context context, String stage, String rawMessage) {
        Issue issue = classify(rawMessage);
        String version = BuildConfig.VERSION_NAME;
        try {
            version = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
        return "ABOLIX CONNECTION REPORT\n"
                + "Issue: " + issue.code + " - " + issue.title + "\n"
                + "Time: " + time + "\n"
                + "App: " + version + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "Device: " + safe(Build.MANUFACTURER) + " " + safe(Build.MODEL) + "\n"
                + "Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Stage: " + safe(stage) + "\n"
                + "Error: " + sanitize(rawMessage) + "\n"
                + "Suggested action: " + issue.suggestion + "\n"
                + "Note: Subscription links, UUIDs, passwords and full proxy configuration are not included.\n";
    }

    private static String sanitize(String value) {
        if (value == null || value.trim().isEmpty()) return "(empty)";
        String result = value.replaceAll("(?i)\\b(?:vless|vmess|trojan|ss|ssr|hysteria2|tuic|socks|https?)://\\S+", "[profile-link-redacted]");
        result = result.replaceAll("(?i)(password|passwd|uuid|token|secret|private_key|public_key)\\s*[:=]\\s*[^,\\s]+", "$1=[redacted]");
        result = result.replaceAll("[\\r\\n]+", " ").trim();
        return result.length() > 900 ? result.substring(0, 900) + "…" : result;
    }

    private static String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "unknown";
        return value.replaceAll("[\\r\\n]", " ");
    }
}
