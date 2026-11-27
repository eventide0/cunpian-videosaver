package io.self.videosaver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音解析器：短链→数字 id → ttwid → 分享页 SSR(_ROUTER_DATA) → play_addr.uri → play 接口 302。
 * play 接口需要 uri 而非数字 id；分享页需要 ttwid；UA 使用移动 Chrome。
 */
public class DouyinParser implements Parser {

    /** 兼容三种形态：/share/video/{id}、/video/{id}、?modal_id={id}。 */
    private static final Pattern ID_IN_URL = Pattern.compile("/(?:share/)?(video|note)/(\\d+)");
    private static final Pattern MODAL_ID = Pattern.compile("[?&]modal_id=(\\d+)");
    private static final Pattern ROUTER_DATA = Pattern.compile("window\\._ROUTER_DATA\\s*=\\s*(\\{.*\\})\\s*</script>");
    private static final Pattern TTWID = Pattern.compile("ttwid=([^;\\s]+)");
    /** 页面/接口自身的错误标记，用于把"风控/改版"和"链接错了"分开报。 */
    private static final Pattern PAGE_ERROR = Pattern.compile("抱歉出错了|页面不存在|视频不存在");

    /** 进程内缓存；解析失败即置空重取（见 parse() 的 attempt 循环）。 */
    private static volatile String sTtwid;

    @Override
    public boolean match(String url) {
        return url.contains("douyin.com") || url.contains("iesdouyin.com");
    }

    @Override
    public ParseResult parse(String rawUrl) throws Exception {
        String url = firstUrl(rawUrl);
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.MOBILE_UA);

        // 1) 先从原文里直接取 id（短链没有 id，才走跳转）
        String kind = null, id = null;
        Matcher modal = MODAL_ID.matcher(url);
        if (modal.find()) { kind = "video"; id = modal.group(1); }
        if (id == null) {
            Matcher direct = ID_IN_URL.matcher(url);
            if (direct.find()) { kind = direct.group(1); id = direct.group(2); }
        }
        if (id == null) {
            String pageUrl = Http.resolveRedirects(url, h, 5);
            Matcher m = ID_IN_URL.matcher(pageUrl);
            if (!m.find()) throw new Exception("没找到视频 id（支持 v.douyin.com 短链 / douyin.com/video/数字id / ?modal_id=数字id）：" + pageUrl);
            kind = m.group(1);
            id = m.group(2);
        }

        // 2) ttwid + 3) 分享页 SSR；失败一次就丢掉缓存的 ttwid 重来，避免一条死 cookie 拖死整个进程
        JSONObject item = null;
        String lastErr = null;
        for (int attempt = 0; attempt < 2 && item == null; attempt++) {
            String ttwid = sTtwid;
            if (ttwid == null) ttwid = sTtwid = registerTtwid();
            if (ttwid == null) throw new Exception("ttwid 注册失败（网络不通或注册接口变动）");

            String html = fetchSharePage(kind, id, ttwid);
            item = extractFirstItem(html);
            if (item == null && !"note".equals(kind)) {      // 视频页拿不到时换图集页兜底
                html = fetchSharePage("note", id, ttwid);
                item = extractFirstItem(html);
            }
            if (item == null) {
                sTtwid = null;                              // 关键：失效即丢弃
                lastErr = PAGE_ERROR.matcher(html).find()
                        ? "分享页返回错误页（ttwid 被风控或视频已被删）"
                        : "分享页里没有视频数据（页面结构可能改版）";
            }
        }
        if (item == null) throw new Exception(lastErr != null ? lastErr : "分享页解析失败");

        ParseResult r = new ParseResult();
        r.platform = "抖音";
        r.title = optString(item, "desc", "抖音视频");
        r.author = "";
        JSONObject author = item.optJSONObject("author");
        if (author != null) r.author = author.optString("nickname", "");
        r.cover = firstOfUrlList(item.optJSONObject("video"), "cover");

        // 4) 视频：play_addr.uri → snssdk play 无水印口
        JSONObject video = item.optJSONObject("video");
        if (video != null) {
            JSONObject playAddr = video.optJSONObject("play_addr");
            if (playAddr != null) {
                String uri = playAddr.optString("uri", "");
                // 图文作品没有视频流：uri 是原声 mp3 直链，不是 video_id，直接作为音频项。
                if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    r.items.add(new Parser.MediaItem("音频 原声", uri, "audio", "mp3", null));
                } else if (!uri.isEmpty()) {
                    String nowm = "https://aweme.snssdk.com/aweme/v1/play/?video_id=" + uri + "&line=0";
                    // 解析最终直链；解析不出就用原口（DownloadManager 自己会跟 302）
                    Map<String, String> dh = new HashMap<>();
                    dh.put("User-Agent", Http.MOBILE_UA);
                    dh.put("Referer", "https://www.douyin.com/");
                    String finalUrl = Http.locationOf(nowm, dh);
                    r.items.add(new Parser.MediaItem("视频 无水印", finalUrl != null ? finalUrl : nowm,
                            "video", "mp4", null));
                    // 带水印口兜底（url_list[0]）——仅真视频作品才有意义
                    String wm = firstOfUrlList(playAddr, "url_list");
                    if (wm != null) {
                        r.items.add(new Parser.MediaItem("视频 带水印(备用)", wm, "video", "mp4", null));
                    }
                }
            }
        }

        // 图集：images
        JSONArray images = item.optJSONArray("images");
        if (images != null) {
            for (int i = 0; i < images.length() && i < 40; i++) {
                JSONObject img = images.optJSONObject(i);
                if (img == null) continue;
                JSONArray urls = img.optJSONArray("url_list");
                if (urls != null && urls.length() > 0) {
                    r.items.add(new Parser.MediaItem("图片 " + (i + 1), urls.optString(0), "image", "jpg", null));
                }
            }
        }

        // 音乐
        JSONObject music = item.optJSONObject("music");
        if (music != null) {
            JSONObject pu = music.optJSONObject("play_url");
            String murl = firstOfUrlList(pu, "url_list");
            if (murl != null) r.items.add(new Parser.MediaItem("音乐", murl, "audio", "m4a", null));
        }

        if (r.items.isEmpty()) throw new Exception("解析到标题但没有可下载的媒体");
        return r;
    }

    private static String fetchSharePage(String kind, String id, String ttwid) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.MOBILE_UA);
        h.put("Cookie", "ttwid=" + ttwid);
        return Http.get("https://www.iesdouyin.com/share/" + kind + "/" + id + "/", h, false);
    }

    private static JSONObject extractFirstItem(String html) {
        try {
            Matcher m = ROUTER_DATA.matcher(html);
            if (!m.find()) return null;
            JSONObject root = new JSONObject(m.group(1));
            JSONObject loader = root.optJSONObject("loaderData");
            if (loader == null) return null;
            java.util.Iterator<String> keys = loader.keys();
            while (keys.hasNext()) {
                JSONObject page = loader.optJSONObject(keys.next());
                if (page == null) continue;
                JSONObject res = page.optJSONObject("videoInfoRes");
                if (res == null) continue;
                JSONArray list = res.optJSONArray("item_list");
                if (list != null && list.length() > 0) return list.optJSONObject(0);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String registerTtwid() {
        try {
            String body = "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,"
                    + "\"service\":\"www.ixigua.com\",\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},"
                    + "\"cbUrlProtocol\":\"https\",\"union\":true}";
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", Http.MOBILE_UA);
            h.put("Content-Type", "application/json");
            StringBuilder cookies = new StringBuilder();
            Http.postCaptureCookie("https://ttwid.bytedance.com/ttwid/union/register/", body, h, cookies);
            Matcher m = TTWID.matcher(cookies);
            // 保留百分号编码原值，避免拼 Cookie 时产生差异
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return null;
    }

    static String firstUrl(String text) {
        Matcher m = Pattern.compile("https?://[0-9A-Za-z./?_=&%-]+").matcher(text);
        return m.find() ? m.group() : text.trim();
    }

    static String optString(JSONObject o, String key, String def) {
        if (o == null) return def;
        String v = o.optString(key, def);
        return v.isEmpty() ? def : v;
    }

    static String firstOfUrlList(JSONObject container, String key) {
        if (container == null) return null;
        JSONObject inner = key.equals("url_list") ? container : container.optJSONObject(key);
        if (inner == null) return null;
        JSONArray urls = inner.optJSONArray("url_list");
        return (urls != null && urls.length() > 0) ? urls.optString(0) : null;
    }
}
