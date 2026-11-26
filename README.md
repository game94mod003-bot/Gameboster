# Aether

Android VPN app (WARP over MASQUE / CONNECT-IP on HTTP/2, IPv4 + IPv6).

## Build with GitHub
1. Upload all files of this project to the root of a GitHub repository (keep the `.github` folder).
2. Open the **Actions** tab, wait for **Build Aether APK**, then download the **Aether-APK** artifact (app-debug.apk).

## Usage
1. **Scan**: gets a WARP key automatically if you have none, then tests Cloudflare endpoints with the real TLS handshake and the real CONNECT-IP request. Only endpoints that really answer are kept (level 3 = verified).
2. **Connect**: tries the verified endpoints first (with and without ClientHello fragmentation), then the endpoint Cloudflare gave at registration.
3. Long-press the log box to copy the log.
