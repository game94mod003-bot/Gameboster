# Abolix connection-fix package

## Fix from the supplied device screenshot

The observed fatal error was:

`start dns/https[cloudflare-doh]: detour to an empty direct outbound makes no sense`

The app-generated `SingBoxConfig` attached `"detour": "direct"` to a new-format HTTPS DNS server. That field is removed. sing-box 1.14 DNS server dialers already use the default dialer; an explicit detour to an empty direct outbound is invalid.

Reference: https://github.com/SagerNet/sing-box/issues/3585

## Error reporting

- 67 local signatures classify common DNS, network, TLS, Reality, VPN, libbox, profile, subscription, and Android failures.
- Connection failures create a persistent diagnostic report and a high-priority Android notification.
- When the app is open, it displays a dialog with error code, explanation, next step, and actions to share/copy the report.
- Report fields: timestamp, app version, device manufacturer/model, Android version, startup stage, classified issue and sanitized error.
- Share is always user-initiated. Profile links, UUIDs, credentials and complete configuration are redacted/omitted.

## Smaller APK output

- Gradle produces `arm64-v8a` and `armeabi-v7a` APKs separately; no universal APK is produced.
- Release builds run R8 and resource shrinking, with keep rules for the reflectively accessed libbox API.
- Use the arm64 APK on most current Android phones; use armeabi-v7a only on 32-bit ARM devices.

## Regression checks added

- Generated HTTPS DNS has no `detour` property.
- The diagnostic signature catalog contains at least 50 cases.
- The supplied DNS failure maps to `ABX-DNS-001`.

A complete Android Gradle build and a real VPN connection test must still run in GitHub Actions and on the target device; this local environment has no Android SDK/Gradle installation.


## Build fix 1.0.2
- Fixed Java compile error in `MainActivity.showPendingConnectionError`: report selection now produces an immutable `diagnosticReport` value captured by both AlertDialog lambdas.
- Bumped app version name to 1.0.2.
