package io.self.videosaver;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 纯 Java 音轨提取：用 Android 自带的 MediaExtractor + MediaMuxer 把 mp4 里的音轨
 * 单独封装成 m4a。零第三方依赖，APK 体积不变。
 *
 * 常见抖音视频源为 vide + soun 双轨，音轨为 AAC，可直接 remux，无需解码。
 */
public final class AudioExtractor {

    private AudioExtractor() {}

    /** 从 src mp4 提取音轨写到 dst.m4a。返回写入字节数；无音轨抛异常。 */
    public static long extract(File src, File dst) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mux = null;
        try {
            ex.setDataSource(src.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat af = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    af = f;
                    break;
                }
            }
            if (audioTrack < 0 || af == null) throw new Exception("视频里没有音轨");

            ex.selectTrack(audioTrack);
            mux = new MediaMuxer(dst.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = mux.addTrack(af);
            mux.start();

            ByteBuffer buf = ByteBuffer.allocateDirect(256 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long bytes = 0;
            while (true) {
                int n = ex.readSampleData(buf, 0);
                if (n < 0) break;
                info.offset = 0;
                info.size = n;
                info.presentationTimeUs = ex.getSampleTime();
                info.flags = ex.getSampleFlags();
                mux.writeSampleData(outTrack, buf, info);
                bytes += n;
                ex.advance();
            }
            mux.stop();
            return bytes;
        } finally {
            if (mux != null) mux.release();
            ex.release();
        }
    }
}
