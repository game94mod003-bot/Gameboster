package com.abolix.gamebooster;

/** Conservative, deterministic recommendations; it never claims to control another app's CPU/GPU. */
public final class GameBoosterAdvisor {
    public enum Profile { PERFORMANCE, BALANCED, COOL_DOWN, POWER_SAVER }

    private static final long ONE_GIB = 1024L * 1024L * 1024L;

    private GameBoosterAdvisor() { }

    public static Profile recommend(int thermalStatus, long availableRamBytes,
                                    boolean lowMemory, boolean powerSaveMode,
                                    int batteryPercent) {
        // Android thermal severity is an OS signal: 3=severe, 4=critical, 5=emergency, 6=shutdown.
        if (thermalStatus >= 3) return Profile.COOL_DOWN;
        if (powerSaveMode || (batteryPercent >= 0 && batteryPercent <= 15)) {
            return Profile.POWER_SAVER;
        }
        if (thermalStatus == 2 || lowMemory
                || (availableRamBytes > 0L && availableRamBytes < ONE_GIB)
                || (thermalStatus < 0 && availableRamBytes <= 0L)) {
            return Profile.BALANCED;
        }
        return Profile.PERFORMANCE;
    }

    public static String title(Profile profile) {
        switch (profile) {
            case PERFORMANCE: return "عملکرد پیشنهادی";
            case BALANCED: return "متعادل";
            case COOL_DOWN: return "اول خنک‌سازی";
            case POWER_SAVER: return "صرفه‌جویی باتری";
            default: return "خودکار";
        }
    }

    public static String description(Profile profile) {
        switch (profile) {
            case PERFORMANCE:
                return "وضعیت فعلی دستگاه برای شروع بازی مناسب به نظر می‌رسد. این پیشنهاد تنظیمات داخلی بازی یا کلاک CPU/GPU را تغییر نمی‌دهد.";
            case BALANCED:
                return "فشار حافظه یا دما بالاتر است؛ برنامه‌های سنگین غیرضروری را خودت ببند و تنظیمات گرافیکی بازی را یک پله پایین بیاور.";
            case COOL_DOWN:
                return "سیستم دمای بالا گزارش کرده است. بازی را متوقف کن، اجازه بده گوشی خنک شود و هنگام داغی گوشی را شارژ نکن.";
            case POWER_SAVER:
                return "حالت ذخیره‌ی نیرو فعال است یا شارژ کم است؛ محدودیت‌های انرژی سیستم ممکن است عملکرد را کاهش دهند.";
            default:
                return "وضعیت دستگاه را بررسی کن.";
        }
    }
}
