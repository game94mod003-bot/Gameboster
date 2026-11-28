# Game Center — Notes

The Booster page is a practical launcher and device-health advisor, not a privileged game-tuner.

## What it does
- Lists apps with a launcher activity, excluding Abolix itself.
- Stores one selected app, keeps its profile separately, and opens it through its normal launch intent.
- Samples Android public telemetry while the Booster tab is visible.
- Recommends a conservative profile from thermal state, low-memory state, available RAM, power-save mode, and battery percentage.
- Offers shortcuts to the phone's display, battery-saver, and selected-app settings.

## What it cannot do on a normal Android install
- Read reliable in-game FPS for another process.
- Change a third-party game's render resolution, quality presets, CPU/GPU clocks, or OEM Game Mode.
- Kill other applications, clear their private caches, or force-close their processes.
- Force a system-wide refresh rate. Android treats app refresh-rate requests as preferences and may ignore them; the Booster only reports the display modes and opens system settings.

The five-second telemetry refresh runs only while the Activity is resumed and the Booster tab is selected. Unsupported vendor telemetry is reported as unavailable rather than guessed.
