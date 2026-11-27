package io.self.videosaver;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 极简 HTTP 工具：只做 GET/POST + 手动跟随重定向 + 收 Set-Cookie。 */
public final class Http {

    public static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; PLQ110) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private Http() {}

    /** 跟随重定向后的最终 URL（HEAD 逐跳）。返回最后一跳 URL；失败返回原 URL。 */
    public static String resolveRedirects(String url, Map<String, String> headers, int maxHops) {
        String cur = url;
        for (int i = 0; i < maxHops; i++) {
            HttpURLConnection c = null;
            try {
                c = open(cur, "HEAD", headers);
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    cur = absolutize(cur, loc);
                    continue;
                }
                return cur;
            } catch (IOException e) {
                return cur;
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return cur;
    }

    /** 单跳取 Location，取不到返回 null。 */
    public static String locationOf(String url, Map<String, String> headers) {
        HttpURLConnection c = null;
        try {
            c = open(url, "HEAD", headers);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) return c.getHeaderField("Location");
            return null;
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String get(String url, Map<String, String> headers, boolean followRedirects) throws IOException {
        return body(open(url, "GET", headers, followRedirects));
    }

    /** 二进制下载到文件（用于先落盘视频再提取音轨）。返回写入字节数。 */
    /** 下载进度回调；total<=0 表示服务端未给 Content-Length，此时 percent=-1（改用已下载字节数显示）。 */
    public interface ProgressCallback {
        void onProgress(int percent, long done, long total);
    }

    public static long downloadToFile(String url, Map<String, String> headers, File dest) throws IOException {
        return downloadToFile(url, headers, dest, null);
    }

    /** 带进度回调的二进制下载。回调发生在下载线程，调用方需自行切回 UI 线程。 */
    public static long downloadToFile(String url, Map<String, String> headers, File dest,
                                      ProgressCallback cb) throws IOException {
        HttpURLConnection c = open(url, "GET", headers, true);
        InputStream in = null;
        java.io.OutputStream os = null;
        try {
            int code = c.getResponseCode();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code + " 无响应体");
            String enc = c.getContentEncoding();
            boolean gz = enc != null && enc.toLowerCase().contains("gzip");
            if (gz) in = new GZIPInputStream(in);
            // gzip 解压后字节数与 Content-Length 不一致，这类响应用未知总长
            long expect = gz ? -1 : c.getHeaderFieldLong("Content-Length", -1);
            os = new java.io.FileOutputStream(dest);
            byte[] buf = new byte[16384];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
                if (cb != null) {
                    int pct = expect > 0 ? (int) (total * 100 / expect) : -1;
                    if (pct > 100) pct = 100;
                    cb.onProgress(pct, total, expect);
                }
            }
            return total;
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            try { if (os != null) os.close(); } catch (IOException ignored) {}
            c.disconnect();
        }
    }

    /** 下载并解码图片为 Bitmap。reqSize>0 时按目标边长缩放（缩略图省内存），0 表示原图。 */
    public static android.graphics.Bitmap fetchBitmap(String url, Map<String, String> headers, int reqSize) throws IOException {
        HttpURLConnection c = open(url, "GET", headers, true);
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code + " 无响应体");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 12 * 1024 * 1024) throw new IOException("图片超过 12MB");
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            if (reqSize > 0) {
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int side = Math.max(o.outWidth, o.outHeight);
                int sample = side > reqSize ? Math.max(1, side / reqSize) : 1;
                o = new android.graphics.BitmapFactory.Options();
                o.inSampleSize = sample;
            }
            return android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            c.disconnect();
        }
    }

    public static String post(String url, String jsonBody, Map<String, String> headers) throws IOException {
        HttpURLConnection c = open(url, "POST", headers, true);
        c.setDoOutput(true);
        OutputStream os = null;
        try {
            os = c.getOutputStream();
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        } finally {
            if (os != null) os.close();
        }
        return body(c);
    }

    /** 返回响应头里的全部 Set-Cookie 值。 */
    public static String postCaptureCookie(String url, String jsonBody, Map<String, String> headers,
                                           StringBuilder cookieOut) throws IOException {
        HttpURLConnection c = open(url, "POST", headers, true);
        c.setDoOutput(true);
        OutputStream os = null;
        try {
            os = c.getOutputStream();
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        } finally {
            if (os != null) os.close();
        }
        Map<String, List<String>> hf = c.getHeaderFields();
        if (hf != null) {
            for (Map.Entry<String, List<String>> e : hf.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie") && e.getValue() != null) {
                    for (String v : e.getValue()) cookieOut.append(v).append("\n");
                }
            }
        }
        return body(c);
    }

    private static String body(HttpURLConnection c) throws IOException {
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return "";
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method, Map<String, String> headers,
                                          boolean followRedirects) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(followRedirects);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return c;
    }

    private static HttpURLConnection open(String url, String method, Map<String, String> headers) throws IOException {
        return open(url, method, headers, false);
    }

    private static String absolutize(String base, String loc) {
        if (loc.startsWith("http://") || loc.startsWith("https://")) return loc;
        try {
            return new URL(new URL(base), loc).toString();
        } catch (IOException e) {
            return base;
        }
    }
}
