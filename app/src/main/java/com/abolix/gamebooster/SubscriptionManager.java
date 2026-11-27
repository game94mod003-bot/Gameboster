package com.abolix.gamebooster;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Subscription loading is transactional: a failed refresh never replaces valid saved nodes. */
public final class SubscriptionManager {
    private static final String PREFS = "abolix_secure_store";
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final String KEY_URL = "subscription_url";
    private static final String KEY_RAW = "subscription_raw";
    private static final String KEY_NODES = "subscription_nodes";
    private static final String KEY_UPDATED = "subscription_updated";

    private SubscriptionManager() { }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getUrl(Context context) {
        try { return SecureStore.get(context, KEY_URL, ""); }
        catch (RuntimeException e) { return ""; }
    }
    public static void saveUrl(Context context, String url) { SecureStore.put(context, KEY_URL, url == null ? "" : url.trim()); }
    public static long getLastUpdated(Context context) { return prefs(context).getLong(KEY_UPDATED, 0L); }
    public static String getRaw(Context context) {
        try { return SecureStore.get(context, KEY_RAW, ""); }
        catch (RuntimeException e) { return ""; }
    }

    public static List<ProxyNode> getNodes(Context context) {
        ArrayList<ProxyNode> nodes = new ArrayList<>();
        String stored;
        try { stored = SecureStore.get(context, KEY_NODES, "[]"); }
        catch (RuntimeException e) { return nodes; }
        try {
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                try { nodes.add(ProxyNode.fromStorageJson(array.getJSONObject(i))); }
                catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return nodes;
    }

    public static RefreshResult importText(Context context, String input) throws Exception {
        String clean = input == null ? "" : input.trim();
        if (clean.isEmpty()) throw new IllegalArgumentException("لینک یا متن اشتراک خالی است");
        List<ProxyNode> nodes = parseSubscription(clean);
        commit(context, "", clean, nodes);
        return new RefreshResult(nodes, "اشتراک با موفقیت وارد شد", false);
    }

    public static RefreshResult refresh(Context context, String subscriptionUrl) throws Exception {
        String urlText = subscriptionUrl == null ? "" : subscriptionUrl.trim();
        if (!urlText.startsWith("https://")) {
            throw new IllegalArgumentException("برای امنیت، آدرس اشتراک باید با https:// شروع شود");
        }
        byte[] response;
        try {
            response = download(urlText);
        } catch (Exception primary) {
            try {
                response = DnsFallback.downloadViaDns(urlText);
            } catch (Exception fallback) {
                throw new Exception("دریافت اشتراک با DNS عادی و DNS-over-HTTPS ناموفق بود.\nDNS fallback: "
                        + safeMessage(fallback) + "\nاتصال اصلی: " + safeMessage(primary), primary);
            }
        }
        String body = new String(response, StandardCharsets.UTF_8).trim();
        List<ProxyNode> nodes = parseSubscription(body);
        commit(context, urlText, body, nodes);
        return new RefreshResult(nodes, "اشتراک به‌روزرسانی شد", true);
    }

    private static byte[] download(String address) throws Exception {
        URL url = new URL(address);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(10000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "text/plain, application/json;q=0.9, */*;q=0.8");
        connection.setRequestProperty("User-Agent", "Abolix-Android/1.0");
        try {
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) throw new Exception("redirect اشتراک پذیرفته نشد؛ لینک HTTPS نهایی را وارد کن");
            if (code != HttpURLConnection.HTTP_OK) throw new Exception("سرور اشتراک HTTP " + code + " برگرداند");
            int length = connection.getContentLength();
            if (length > MAX_BYTES) throw new Exception("حجم اشتراک بیشتر از 3 مگابایت است");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > MAX_BYTES) throw new Exception("حجم اشتراک بیشتر از 3 مگابایت است");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    public static List<ProxyNode> parseSubscription(String body) throws Exception {
        if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("محتوای اشتراک خالی است");
        String candidate = body.trim().replace("\uFEFF", "");
        if (!candidate.contains("://") && !candidate.startsWith("{")) {
            String encoded = candidate.replaceAll("\\s", "");
            try {
                byte[] decoded = Base64.decode(encoded, Base64.URL_SAFE | Base64.NO_WRAP);
                String asText = new String(decoded, StandardCharsets.UTF_8).trim();
                if (asText.contains("://")) candidate = asText;
            } catch (Exception urlSafeError) {
                try {
                    byte[] decoded = Base64.decode(encoded, Base64.DEFAULT);
                    String asText = new String(decoded, StandardCharsets.UTF_8).trim();
                    if (asText.contains("://")) candidate = asText;
                } catch (Exception ignored) { }
            }
        }
        String[] lines = candidate.split("\\r?\\n");
        ArrayList<ProxyNode> nodes = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        for (String line : lines) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            String normalized = value.toLowerCase(java.util.Locale.ROOT);
            if (!(normalized.startsWith("vless://") || normalized.startsWith("vmess://") || normalized.startsWith("trojan://")
                    || normalized.startsWith("ss://") || normalized.startsWith("hysteria2://") || normalized.startsWith("hy2://")
                    || normalized.startsWith("tuic://"))) continue;
            try { nodes.add(ProxyNode.parse(value)); }
            catch (Exception e) { if (errors.size() < 3) errors.add(safeMessage(e)); }
        }
        if (nodes.isEmpty()) {
            String suffix = errors.isEmpty() ? "" : "\n" + android.text.TextUtils.join("\n", errors);
            throw new IllegalArgumentException("هیچ لینک پشتیبانی‌شده‌ای در اشتراک پیدا نشد. فرمت‌های قابل‌خواندن: VLESS، VMess، Trojan، Shadowsocks، Hysteria2 و TUIC." + suffix);
        }
        return nodes;
    }

    private static void commit(Context context, String url, String raw, List<ProxyNode> nodes) throws Exception {
        JSONArray array = new JSONArray();
        for (ProxyNode node : nodes) array.put(node.toStorageJson());
        String previousUrl = getUrl(context);
        String nextUrl = url != null && !url.isEmpty() ? url : previousUrl;
        String encryptedRaw = SecureStore.seal(raw);
        String encryptedNodes = SecureStore.seal(array.toString());
        String encryptedUrl = SecureStore.seal(nextUrl);
        prefs(context).edit()
                .putString(KEY_RAW, encryptedRaw)
                .putString(KEY_NODES, encryptedNodes)
                .putString(KEY_URL, encryptedUrl)
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply();
    }

    public static String formatUpdated(Context context) {
        long value = getLastUpdated(context);
        if (value <= 0) return "هنوز به‌روزرسانی نشده";
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy/MM/dd  HH:mm", java.util.Locale.getDefault());
        return format.format(new java.util.Date(value));
    }

    private static String safeMessage(Throwable error) {
        String text = error == null ? "خطای نامشخص" : error.getMessage();
        if (text == null || text.trim().isEmpty()) return "خطای ارتباط";
        return text.length() > 150 ? text.substring(0, 150) : text;
    }

    public static final class RefreshResult {
        public final List<ProxyNode> nodes;
        public final String message;
        public final boolean downloaded;
        RefreshResult(List<ProxyNode> nodes, String message, boolean downloaded) {
            this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
            this.message = message;
            this.downloaded = downloaded;
        }
    }

    /** Independent DNS-over-HTTPS diagnostic: does not disable TLS verification. */
    private static final class DnsFallback {
        static byte[] downloadViaDns(String address) throws Exception {
            URL url = new URL(address);
            if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IllegalArgumentException("DNS fallback فقط برای HTTPS فعال است");
            String host = url.getHost();
            List<String> addresses = DnsOverHttps.resolveA(host);
            if (addresses.isEmpty()) throw new Exception("DNS-over-HTTPS برای دامنه IP برنگرداند");
            Exception lastError = null;
            for (String ip : addresses) {
                java.net.Socket plain = new java.net.Socket();
                javax.net.ssl.SSLSocket ssl = null;
                try {
                    if (!AbolixVpnService.protectSocket(plain)) {
                        throw new java.io.IOException("امکان اجرای DNS fallback خارج از تونل VPN فراهم نشد");
                    }
                    int port = url.getPort() > 0 ? url.getPort() : 443;
                    plain.connect(new java.net.InetSocketAddress(ip, port), 4500);
                    ssl = (javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                            .createSocket(plain, host, port, true);
                    javax.net.ssl.SSLParameters parameters = ssl.getSSLParameters();
                    parameters.setServerNames(Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(parameters);
                    ssl.setSoTimeout(9000);
                    ssl.startHandshake();

                    String path = url.getFile();
                    if (path == null || path.isEmpty()) path = "/";
                    String hostHeader = host + (port == 443 ? "" : ":" + port);
                    java.io.OutputStream output = ssl.getOutputStream();
                    String request = "GET " + path + " HTTP/1.1\r\nHost: " + hostHeader
                            + "\r\nAccept: text/plain, application/json;q=0.9, */*;q=0.8"
                            + "\r\nUser-Agent: Abolix-Android/1.0\r\nConnection: close\r\n\r\n";
                    output.write(request.getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    java.io.InputStream input = ssl.getInputStream();
                    String status = readAsciiLine(input);
                    if (status == null || !status.matches("HTTP/1\\.[01] 200 .*")) {
                        throw new Exception("سرور اشتراک از مسیر DNS پاسخ موفق نداد: " + status);
                    }
                    int contentLength = -1;
                    boolean chunked = false;
                    String line;
                    while ((line = readAsciiLine(input)) != null && !line.isEmpty()) {
                        String lower = line.toLowerCase(java.util.Locale.ROOT);
                        if (lower.startsWith("content-length:")) contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
                    }
                    if (contentLength > MAX_BYTES) throw new Exception("حجم اشتراک بیشتر از 3 مگابایت است");
                    if (chunked) return readChunked(input);
                    if (contentLength >= 0) return readExactly(input, contentLength);
                    return readToEnd(input);
                } catch (Exception e) {
                    lastError = e;
                } finally {
                    try { if (ssl != null) ssl.close(); else plain.close(); } catch (Exception ignored) { }
                }
            }
            throw lastError == null ? new Exception("DNS fallback ناموفق بود") : lastError;
        }

        private static String readAsciiLine(java.io.InputStream input) throws Exception {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;
            while (line.size() < 8192) {
                int next = input.read();
                if (next < 0) break;
                if (previous == '\r' && next == '\n') {
                    byte[] bytes = line.toByteArray();
                    return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
                }
                line.write(next); previous = next;
            }
            if (line.size() == 0) return null;
            throw new Exception("HTTP header line نامعتبر یا بیش از حد طولانی است");
        }

        private static byte[] readExactly(java.io.InputStream input, int length) throws Exception {
            if (length < 0 || length > MAX_BYTES) throw new Exception("حجم اشتراک نامعتبر است");
            byte[] data = new byte[length]; int offset = 0;
            while (offset < length) {
                int count = input.read(data, offset, Math.min(8192, length - offset));
                if (count < 0) throw new Exception("پاسخ اشتراک ناقص است");
                offset += count;
            }
            return data;
        }

        private static byte[] readToEnd(java.io.InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new Exception("حجم اشتراک بیشتر از 3 مگابایت است");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }

        private static byte[] readChunked(java.io.InputStream input) throws Exception {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readAsciiLine(input);
                if (sizeLine == null) throw new Exception("پاسخ chunked ناقص است");
                int separator = sizeLine.indexOf(';');
                String sizeText = separator >= 0 ? sizeLine.substring(0, separator) : sizeLine;
                int size = Integer.parseInt(sizeText.trim(), 16);
                if (size == 0) { while (true) { String trailer = readAsciiLine(input); if (trailer == null || trailer.isEmpty()) break; } break; }
                if (output.size() + size > MAX_BYTES) throw new Exception("حجم اشتراک بیشتر از 3 مگابایت است");
                byte[] chunk = readExactly(input, size);
                output.write(chunk);
                String ending = readAsciiLine(input);
                if (ending == null || !ending.isEmpty()) throw new Exception("ساختار chunked نامعتبر است");
            }
            return output.toByteArray();
        }
    }

    private static final class DnsOverHttps {
        static List<String> resolveA(String host) throws Exception {
            java.net.Socket plain = new java.net.Socket();
            javax.net.ssl.SSLSocket ssl = null;
            try {
                // Connect to the resolver by numeric IP and bypass the TUN to avoid a fallback loop.
                if (!AbolixVpnService.protectSocket(plain)) {
                    throw new java.io.IOException("امکان خروج سوکت DNS از تونل VPN فراهم نشد");
                }
                plain.connect(new java.net.InetSocketAddress(java.net.InetAddress.getByName("1.1.1.1"), 443), 3500);
                ssl = (javax.net.ssl.SSLSocket) ((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault())
                        .createSocket(plain, "cloudflare-dns.com", 443, true);
                javax.net.ssl.SSLParameters parameters = ssl.getSSLParameters();
                parameters.setServerNames(Collections.singletonList(new javax.net.ssl.SNIHostName("cloudflare-dns.com")));
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                ssl.setSSLParameters(parameters);
                ssl.setSoTimeout(3500);
                ssl.startHandshake();

                String path = "/dns-query?name=" + java.net.URLEncoder.encode(host, "UTF-8") + "&type=A";
                java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(ssl.getOutputStream(), StandardCharsets.US_ASCII);
                writer.write("GET " + path + " HTTP/1.1\r\nHost: cloudflare-dns.com\r\nAccept: application/dns-json\r\nConnection: close\r\nUser-Agent: Abolix/1.0\r\n\r\n");
                writer.flush();

                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(ssl.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                if (status == null || !status.contains(" 200 ")) throw new Exception("Cloudflare DoH HTTP response invalid");
                int contentLength = -1;
                boolean chunked = false;
                String header;
                while ((header = reader.readLine()) != null && !header.isEmpty()) {
                    String lower = header.toLowerCase(java.util.Locale.ROOT);
                    if (lower.startsWith("content-length:")) contentLength = Integer.parseInt(header.substring(header.indexOf(':') + 1).trim());
                    if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
                }
                StringBuilder body = new StringBuilder();
                if (chunked) {
                    while (true) {
                        String sizeLine = reader.readLine();
                        if (sizeLine == null) break;
                        int size;
                        try { size = Integer.parseInt(sizeLine.trim().split(";", 2)[0], 16); }
                        catch (Exception e) { break; }
                        if (size == 0) break;
                        int remaining = size;
                        char[] data = new char[Math.min(size, 8192)];
                        while (remaining > 0) {
                            int n = reader.read(data, 0, Math.min(remaining, data.length));
                            if (n < 0) break;
                            body.append(data, 0, n);
                            remaining -= n;
                        }
                        reader.readLine();
                        if (body.length() > 60000) break;
                    }
                } else if (contentLength >= 0) {
                    char[] data = new char[2048];
                    int remaining = Math.min(contentLength, 60000);
                    while (remaining > 0) {
                        int n = reader.read(data, 0, Math.min(remaining, data.length));
                        if (n < 0) break;
                        body.append(data, 0, n);
                        remaining -= n;
                    }
                } else {
                    char[] data = new char[2048];
                    int n;
                    while ((n = reader.read(data)) != -1 && body.length() < 60000) body.append(data, 0, Math.min(n, 60000 - body.length()));
                }
                JSONObject json = new JSONObject(body.toString());
                JSONArray answers = json.optJSONArray("Answer");
                ArrayList<String> ips = new ArrayList<>();
                if (answers != null) for (int i = 0; i < answers.length(); i++) {
                    JSONObject answer = answers.optJSONObject(i);
                    if (answer != null && answer.optInt("type") == 1) {
                        String ip = answer.optString("data");
                        if (ip.matches("[0-9.]+") && !ips.contains(ip)) ips.add(ip);
                    }
                }
                return ips;
            } finally {
                try { if (ssl != null) ssl.close(); else plain.close(); } catch (Exception ignored) { }
            }
        }
    }
}
