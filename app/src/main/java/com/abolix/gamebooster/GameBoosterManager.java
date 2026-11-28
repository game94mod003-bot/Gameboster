package com.abolix.gamebooster;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads public Android device telemetry and lists apps that expose a launcher activity. */
public final class GameBoosterManager {
    private GameBoosterManager() { }

    public static final class LaunchableApp {
        public final String packageName;
        public final String label;

        LaunchableApp(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    public static final class Snapshot {
        public final long totalRamBytes;
        public final long availableRamBytes;
        public final boolean lowMemory;
        public final int thermalStatus;
        public final int batteryPercent;
        public final int batteryTemperatureTenthsC;
        public final int batteryHealth;
        public final boolean powerSaveMode;
        public final boolean charging;
        public final float currentRefreshRateHz;
        public final float maxRefreshRateHz;
        public final int displayWidth;
        public final int displayHeight;

        Snapshot(long totalRamBytes, long availableRamBytes, boolean lowMemory,
                 int thermalStatus, int batteryPercent, int batteryTemperatureTenthsC,
                 int batteryHealth, boolean powerSaveMode, boolean charging, float currentRefreshRateHz,
                 float maxRefreshRateHz, int displayWidth, int displayHeight) {
            this.totalRamBytes = totalRamBytes;
            this.availableRamBytes = availableRamBytes;
            this.lowMemory = lowMemory;
            this.thermalStatus = thermalStatus;
            this.batteryPercent = batteryPercent;
            this.batteryTemperatureTenthsC = batteryTemperatureTenthsC;
            this.batteryHealth = batteryHealth;
            this.powerSaveMode = powerSaveMode;
            this.charging = charging;
            this.currentRefreshRateHz = currentRefreshRateHz;
            this.maxRefreshRateHz = maxRefreshRateHz;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
        }
    }

    /**
     * Uses package-visibility-safe launcher discovery. The manifest declares the matching
     * MAIN/LAUNCHER query; QUERY_ALL_PACKAGES is intentionally not requested.
     */
    public static List<LaunchableApp> findLaunchableApps(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> matches;
        if (Build.VERSION.SDK_INT >= 33) {
            matches = packageManager.queryIntentActivities(
                    query, PackageManager.ResolveInfoFlags.of(0L));
        } else {
            matches = packageManager.queryIntentActivities(query, 0);
        }

        Map<String, LaunchableApp> unique = new LinkedHashMap<>();
        String ownPackage = context.getPackageName();
        for (ResolveInfo resolveInfo : matches) {
            if (resolveInfo == null || resolveInfo.activityInfo == null) continue;
            String packageName = resolveInfo.activityInfo.packageName;
            if (packageName == null || packageName.trim().isEmpty() || ownPackage.equals(packageName)) continue;
            if (unique.containsKey(packageName)) continue;

            String label;
            try {
                CharSequence rawLabel = resolveInfo.loadLabel(packageManager);
                label = rawLabel == null ? packageName : rawLabel.toString().trim();
            } catch (RuntimeException error) {
                label = packageName;
            }
            if (label.isEmpty()) label = packageName;
            unique.put(packageName, new LaunchableApp(packageName, label));
        }

        List<LaunchableApp> result = new ArrayList<>(unique.values());
        Collections.sort(result, new Comparator<LaunchableApp>() {
            @Override public int compare(LaunchableApp left, LaunchableApp right) {
                return left.label.compareToIgnoreCase(right.label);
            }
        });
        return result;
    }

    /** Collects public system telemetry. Unsupported values are represented by -1 or 0. */
    public static Snapshot inspect(Context context) {
        long totalRam = 0L;
        long availableRam = 0L;
        boolean lowMemory = false;
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memoryInfo);
                totalRam = Math.max(0L, memoryInfo.totalMem);
                availableRam = Math.max(0L, memoryInfo.availMem);
                lowMemory = memoryInfo.lowMemory;
            }
        } catch (RuntimeException ignored) { }

        int thermalStatus = -1;
        boolean powerSaveMode = false;
        try {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                powerSaveMode = powerManager.isPowerSaveMode();
                if (Build.VERSION.SDK_INT >= 29) thermalStatus = powerManager.getCurrentThermalStatus();
            }
        } catch (RuntimeException ignored) { }

        int batteryPercent = -1;
        int batteryTemperature = -1;
        int batteryHealth = -1;
        boolean charging = false;
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) batteryPercent = Math.max(0, Math.min(100, Math.round(level * 100f / scale)));
                batteryTemperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                batteryHealth = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (RuntimeException ignored) { }

        float currentRefresh = 0f;
        float maxRefresh = 0f;
        int width = 0;
        int height = 0;
        try {
            DisplayManager displayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = displayManager == null ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                currentRefresh = Math.max(0f, display.getRefreshRate());
                Display.Mode currentMode = display.getMode();
                if (currentMode != null) {
                    width = currentMode.getPhysicalWidth();
                    height = currentMode.getPhysicalHeight();
                }
                Display.Mode[] supportedModes = display.getSupportedModes();
                if (supportedModes != null) {
                    for (Display.Mode mode : supportedModes) {
                        if (mode != null) maxRefresh = Math.max(maxRefresh, mode.getRefreshRate());
                    }
                }
            }
        } catch (RuntimeException ignored) { }

        return new Snapshot(totalRam, availableRam, lowMemory, thermalStatus,
                batteryPercent, batteryTemperature, batteryHealth, powerSaveMode, charging,
                currentRefresh, maxRefresh, width, height);
    }
}
