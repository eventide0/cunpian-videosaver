package io.self.videosaver;

import java.util.ArrayList;
import java.util.List;

/** 解析器抽象：按平台一个实现，可注册/可替换（热更的挂载点）。 */
public interface Parser {

    /** 是否能处理这个链接。 */
    boolean match(String url);

    /** 解析。失败抛 Exception（错误消息要能直接给用户看）。 */
    ParseResult parse(String url) throws Exception;

    /** 解析产物：一个视频/音频/图片集。 */
    class MediaItem {
        public String label;    // 展示名，如 "视频 720P"
        public String url;      // 下载地址（可含 302，DownloadManager 会跟）
        public String type;     // video / audio / image
        public String ext;      // mp4 / m4a / jpg
        public String referer;  // 部分站点下载要 Referer（如 B 站），可为 null

        public MediaItem(String label, String url, String type, String ext, String referer) {
            this.label = label; this.url = url; this.type = type; this.ext = ext; this.referer = referer;
        }
    }

    class ParseResult {
        public String platform;
        public String title;
        public String author;
        public String cover;
        public List<MediaItem> items = new ArrayList<>();
    }
}
