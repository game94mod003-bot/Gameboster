# Build-fix notes

This revision addresses the two Java compiler errors reported by the GitHub Actions build:

1. `ProxyNode.getOutbound()` now declares `org.json.JSONException`, the checked exception thrown by `new JSONObject(String)`. Its only call site is inside `SingBoxConfig.build(...)`, which already declares `throws Exception`.
2. `AbolixVpnService.addExcludedRoutes(...)` now converts the literal route address to `java.net.InetAddress` before constructing `android.net.IpPrefix`. Android API 33 exposes `IpPrefix(InetAddress, int)`; the `(String, int)` overload is not part of the public API.

The full Android build still needs to run on a runner with the Android SDK installed (for example, GitHub Actions). This environment does not have Gradle or an Android SDK, so no successful APK build is claimed here.
