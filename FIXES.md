# Abolix reliability update

## Core startup
- Replaced the preflight `Libbox.checkConfig()` call with `Libbox.formatConfig()`. `FormatConfig` parses/normalizes the JSON without constructing a second temporary engine with a stub platform TUN. The real `CommandServer.startOrReloadService()` remains the authoritative engine/TUN startup and returns the actual sing-box error.
- Separated libbox base, working, and temporary directories and create them before `Libbox.setup()`.
- Added the libbox OOM/power draft initialization hooks used by the official Android sing-box client when those hooks exist in the pinned libbox build.
- Added startup-stage Logcat/notification/app status updates so the user sees which exact phase is running instead of an opaque spinner.
- Switched the VPN foreground service to Android's `systemExempted` type and matching permission, following the official sing-box Android VPN service declaration. The VPN service is non-exported.
- Fixed restoration of VPN status on Activity resume to read the same private preferences written by the service.

## Importer
- Added URI-style VMess links (`vmess://uuid@host:port?...`) in addition to the classic Base64 JSON VMess format.
- Added the `shadowsocks://` alias alongside `ss://`.
- Added regression tests for both new forms.
- Fixed a JSONC parser defect that duplicated every character inside quoted JSON strings; this broke sing-box/Xray JSON imports even when the supplied JSON was valid. Added coverage for escaped quotes and `//` inside a JSON string.

## Supported format boundary
- Supported: VLESS (TLS/REALITY, WS, gRPC, HTTP/2, HTTPUpgrade, Vision flow), VMess (Base64 JSON and URI form), Trojan, Shadowsocks SIP002/legacy links, Hysteria/Hysteria2, TUIC, AnyTLS, SOCKS and HTTP(S), Clash YAML, sing-box JSON and supported Xray JSON outbound forms.
- Not supported by upstream sing-box 1.14.4: ShadowsocksR (`ssr://`) and XHTTP/mKCP transports. These are explicitly rejected rather than silently mis-imported. WireGuard needs the sing-box `endpoints[]` model and is not represented by this app's single-outbound node model yet.

## Validation performed in this workspace
- ZIP integrity and file inventory checks.
- XML parsing of AndroidManifest.xml and resource XML.
- YAML parsing of GitHub Actions workflow.
- Source-level review against sing-box/libbox 1.14.x API and the official Android client implementation.

## Not claimed
This workspace has no Android SDK, Gradle executable, emulator, or Android device. Therefore no APK compilation, libbox JNI runtime test, real VPN handshake, traffic-forwarding test, or on-device protocol test is claimed. GitHub Actions runs the importer unit tests and Android assemble task; final connection validation still requires the user's device and a live working subscription.

## BuildConfig generation (AGP 8.x)

Enabled `android.buildFeatures.buildConfig` in `app/build.gradle`. Android Gradle Plugin 8.0+ disables `BuildConfig` generation by default, while `AbolixVpnService` reads `BuildConfig.DEBUG`, `BuildConfig.VERSION_CODE`, and `BuildConfig.VERSION_NAME` when initializing libbox. This fixes the reported `cannot find symbol: BuildConfig` compile failure for both debug and release variants.


## Game Center / Booster addition
- Added package-visibility-scoped launcher discovery and game launching without `QUERY_ALL_PACKAGES`.
- Added public Android telemetry for total/available RAM, thermal status (API 29+), battery level/temperature/charging, power-save mode, current/supported display refresh rates, and current display mode resolution.
- Added automatic advisory and manual performance/balanced/cool-down/power-saver profiles. These are recommendations only; the app does not claim to overclock or tune another app's CPU/GPU, force its frame rate, read its real FPS, or terminate other processes.
- Added a 5-second foreground-only dashboard refresh loop, stop-on-pause lifecycle handling, system settings shortcuts, and unit tests for profile selection.
- Updated app label and README documentation.

## Reported Java compiler failures

- `ProxyNode.getOutbound()` declares `throws JSONException`, matching `new JSONObject(...)`; its callers propagate the checked exception through the existing config-building methods and unit tests.
- `AbolixVpnService.addExcludedRoutes()` resolves the route address with `InetAddress.getByName(...)` before constructing `IpPrefix`. Android 13+ exposes the `IpPrefix(InetAddress, int)` constructor, not a `String` constructor.
- The matching source snapshot already contained these two corrections; they were rechecked while preparing this ZIP. `buildFeatures.buildConfig true` addresses the separate AGP 8 `BuildConfig` generation issue.
