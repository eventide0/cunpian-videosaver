package io.self.videosaver;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B 站解析器：
 * BV → view 拿 cid → playurl(fnval=1, platform=html5, high_quality=1, qn=80) → 单文件 mp4。
 * 下面的 Referer 仅用于 view/playurl 接口请求，mp4 直链下载不需要。
 */
public class BiliParser implements Parser {

    private static final Pattern BV = Pattern.compile("(BV[0-9A-Za-z]{10})");

    @Override
    public boolean match(String url) {
        return url.contains("bilibili.com") || url.contains("b23.tv");
    }

    @Override
    public ParseResult parse(String rawUrl) throws Exception {
        String url = DouyinParser.firstUrl(rawUrl);
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", Http.DESKTOP_UA);
        h.put("Referer", "https://www.bilibili.com/");

        // b23.tv 短链 → 最终链接
        if (url.contains("b23.tv")) url = Http.resolveRedirects(url, h, 5);
        Matcher m = BV.matcher(url);
        if (!m.find()) throw new Exception("链接里没找到 BV 号");
        String bvid = m.group(1);

        // 1) view
        JSONObject view = new JSONObject(Http.get(
                "https://api.bilibili.com/x/web-interface/view?bvid=" + bvid, h, true));
        if (view.optInt("code", -1) != 0) throw new Exception("B站详情接口失败: " + view.optString("message"));
        JSONObject data = view.optJSONObject("data");
        if (data == null) throw new Exception("B站详情无数据");

        long aid = data.optLong("aid");
        long cid = data.optLong("cid");
        if (cid == 0) throw new Exception("没拿到 cid（可能是多P，暂只支持第一P）");

        // 2) playurl：单文件 mp4（免 ffmpeg 合流，APK 省体积）
        // qn=80：不传则 B 站默认回 16（360P）；传高码后服务端自动回落到视频支持的最高档，无需登录/签名
        JSONObject play = new JSONObject(Http.get(
                "https://api.bilibili.com/x/player/playurl?avid=" + aid + "&cid=" + cid
                        + "&fnval=1&platform=html5&high_quality=1&qn=80", h, true));
        if (play.optInt("code", -1) != 0) throw new Exception("B站播放地址接口失败: " + play.optString("message"));
        JSONObject pdata = play.optJSONObject("data");
        if (pdata == null) throw new Exception("B站播放无数据");

        org.json.JSONArray durl = pdata.optJSONArray("durl");
        if (durl == null || durl.length() == 0) throw new Exception("B站没有返回可下载地址");
        String durl0 = durl.optJSONObject(0).optString("url");

        // data.quality 可能虚标，用 mp4 头里的真实分辨率为准
        // 用 Range 取 mp4 头解析 moov，拿真实宽高；失败退回档位名
        String label = "视频 " + qualityName(pdata.optInt("quality", 0));
        try {
            byte[] head = Http.fetchHead(durl0, h, 8 * 1024);
            String real = Http.mp4Size(head);
            if (real != null) label = "视频 " + real;
        } catch (Exception ignored) {}

        ParseResult r = new ParseResult();
        r.platform = "B站";
        r.title = data.optString("title", "B站视频");
        JSONObject owner = data.optJSONObject("owner");
        r.author = owner != null ? owner.optString("name", "") : "";
        r.cover = data.optString("pic", "");
        r.items.add(new Parser.MediaItem(label, durl0, "video", "mp4", "https://www.bilibili.com/"));
        return r;
    }

    /** B 站清晰度码 → 中文档位；html5 无登录传 qn=80 可取视频最高公开档。 */
    private static String qualityName(int q) {
        switch (q) {
            case 6:   return "240P";
            case 16:  return "360P";
            case 32:  return "480P";
            case 64:  return "720P";
            case 74:  return "720P60";
            case 80:  return "1080P";
            case 112: return "1080P+";
            case 116: return "1080P60";
            case 120: return "4K";
            case 125: return "HDR";
            case 126: return "杜比视界";
            case 127: return "8K";
            default:  return q > 0 ? ("Q" + q) : "未知档位";
        }
    }
}
