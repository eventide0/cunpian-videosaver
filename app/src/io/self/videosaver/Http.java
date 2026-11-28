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
import java.util.HashMap;
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

    /**
     * 单连接流式下载 + Range 断点续传 + 失败抖动重试。回调发生在下载线程，调用方需自行切回 UI 线程。
     *
     * 弱网优化的关键是"少传字节"：连接断了从已落盘偏移继续，绝不删文件从头重下。
     * 没有 HEAD/测速预探测 —— 那两段没有进度回调，在慢网会让 UI 长时间停在 0%。
     * gzip 或服务端忽略 Range（回 200）时退回从头覆盖，进度仍按 Content-Length 正常上报。
     */
    public static long downloadToFile(String url, Map<String, String> headers, File dest,
                                      ProgressCallback cb) throws IOException {
        IOException last = null;
        long committed = dest.exists() ? dest.length() : 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection c = null;
            InputStream in = null;
            OutputStream os = null;
            try {
                Map<String, String> h = new HashMap<String, String>();
                if (headers != null) h.putAll(headers);
                if (committed > 0) h.put("Range", "bytes=" + committed + "-");
                c = open(url, "GET", h, true);
                int code = c.getResponseCode();
                if (code == 416) {           // 偏移越界（文件已完整）：从头重来
                    committed = 0;
                    throw new IOException("HTTP 416 Range 越界");
                }
                if (code >= 400) throw new IOException("HTTP " + code);
                boolean partial = (code == 206);
                in = c.getInputStream();
                String enc = c.getContentEncoding();
                boolean gz = enc != null && enc.toLowerCase().contains("gzip");
                if (gz) in = new GZIPInputStream(in);
                long len = c.getHeaderFieldLong("Content-Length", -1);
                long offset = (partial && !gz) ? committed : 0;
                long total = gz ? -1 : (partial && len > 0 ? offset + len : len);
                os = new java.io.FileOutputStream(dest, offset > 0);
                byte[] buf = new byte[16384];
                long done = offset;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (cb != null) {
                        int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        if (pct > 100) pct = 100;
                        cb.onProgress(pct, done, total);
                    }
                }
                os.flush();
                return done;
            } catch (IOException e) {
                last = e;
                committed = dest.exists() ? dest.length() : 0;
                if (attempt < 2) {
                    // 抖动退避：避免弱网下重试撞在同一拥塞时刻
                    try { Thread.sleep(300 + (long) (Math.random() * 500) + attempt * 700); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                }
            } finally {
                try { if (in != null) in.close(); } catch (IOException ignored) {}
                try { if (os != null) os.close(); } catch (IOException ignored) {}
                if (c != null) c.disconnect();
            }
        }
        throw last != null ? last : new IOException("下载失败");
    }

    /**
     * Range 取视频头部字节，用于解析 mp4 moov 里的真实分辨率。
     * 服务器忽略 Range（回 200）时只读前 maxBytes 就断开，不会把整个文件拉完。
     */
    public static byte[] fetchHead(String url, Map<String, String> headers, int maxBytes) throws IOException {
        Map<String, String> h = new HashMap<String, String>();
        if (headers != null) h.putAll(headers);
        h.put("Range", "bytes=0-" + (maxBytes - 1));
        HttpURLConnection c = open(url, "GET", h, true);
        InputStream in = null;
        try {
            int code = c.getResponseCode();
            in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code + " 无响应体");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while (total < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - total))) > 0) {
                bos.write(buf, 0, n);
                total += n;
            }
            return bos.toByteArray();
        } finally {
            try { if (in != null) in.close(); } catch (IOException ignored) {}
            c.disconnect();
        }
    }

    /**
     * 从 mp4 头部字节里取真实编码分辨率，返回 "宽x高"；解析不到返回 null。
     * 只读 stsd 里的 avc1/hvc1/av01（编码盒）；tkhd 是显示尺寸，B站会写成 404x720 这种错值，不能用。
     */
    public static String mp4Size(byte[] b) {
        if (b == null) return null;
        // ftyp 兼容列表里第一个 "avc1" 不是 box，需跳过；找到多个时取宽高都合理的
        for (int i = 0; i + 36 <= b.length; i++) {
            boolean codec = (b[i] == 'a' && b[i + 1] == 'v' && b[i + 2] == 'c' && b[i + 3] == '1')
                    || (b[i] == 'h' && b[i + 1] == 'e' && b[i + 2] == 'v' && b[i + 3] == '1')
                    || (b[i] == 'h' && b[i + 1] == 'v' && b[i + 2] == 'c' && b[i + 3] == '1')
                    || (b[i] == 'a' && b[i + 1] == 'v' && b[i + 2] == '0' && b[i + 3] == '1');
            if (!codec) continue;
            int boxSize = ((b[i - 4] & 0xff) << 24) | ((b[i - 3] & 0xff) << 16)
                    | ((b[i - 2] & 0xff) << 8) | (b[i - 1] & 0xff);
            if (boxSize < 80 || boxSize > 4096) continue;
            int W = ((b[i + 28] & 0xff) << 8) | (b[i + 29] & 0xff);
            int H = ((b[i + 30] & 0xff) << 8) | (b[i + 31] & 0xff);
            if (W >= 320 && W <= 7680 && H >= 240 && H <= 4320) return W + "x" + H;
        }
        return null;
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
