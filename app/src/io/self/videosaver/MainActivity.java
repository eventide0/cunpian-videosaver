package io.self.videosaver;

import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 存片主界面：浅玻璃 + scrim 分层；壁纸/模糊收进底部设置面板。 */
public class MainActivity extends Activity {

    private static final int REQ_WALLPAPER = 41;

    private EditText mInput;
    private LinearLayout mResults;
    private LinearLayout mHistory;
    private ImageView mWall;
    private TextView mDlSummary;
    // 下载队列统计（仅统计当前结果区点过的下载）
    private int mDlTotal, mDlDone, mDlFailed, mDlActive;
    private float mBlurPx = 40f;
    private static final Parser[] PARSERS = { new DouyinParser(), new BiliParser() };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mInput = findViewById(R.id.input);
        mResults = findViewById(R.id.results);
        mHistory = findViewById(R.id.history);
        mWall = findViewById(R.id.wall);
        mDlSummary = findViewById(R.id.dl_summary);

        loadSavedWallpaper();
        mBlurPx = getSharedPreferences("ui", Context.MODE_PRIVATE).getFloat("blur_px", 40f);
        applyGlassBlur(mWall);

        findViewById(R.id.cta).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String t = mInput.getText().toString().trim();
                if (t.isEmpty()) {
                    Toast.makeText(MainActivity.this, "先粘贴视频链接", Toast.LENGTH_SHORT).show();
                    return;
                }
                parseAsync(t);
            }
        });
        pressScale(findViewById(R.id.cta), 0.97f);

        findViewById(R.id.btn_paste).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                    CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
                    if (cs != null && !cs.toString().trim().isEmpty()) {
                        mInput.setText(cs.toString());
                        parseAsync(cs.toString());
                        return;
                    }
                }
                Toast.makeText(MainActivity.this, "剪贴板里没有链接", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mInput.setText("");
                mResults.removeAllViews();
                resetDlSummary();
                showEmptyState();
                Toast.makeText(MainActivity.this, "已清空", Toast.LENGTH_SHORT).show();
            }
        });
        pressScale(findViewById(R.id.btn_clear), 0.94f);

        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openSheet(); }
        });
        pressScale(findViewById(R.id.btn_settings), 0.92f);

        renderHistory();
        showEmptyState();

        handleShareIntent(getIntent());
    }

    /** App 已在前台时再次分享走这里（Manifest 设了 singleTop），否则第二次分享不触发解析。 */
    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent it) {
        if (it == null || !Intent.ACTION_SEND.equals(it.getAction())) return;
        String shared = it.getStringExtra(Intent.EXTRA_TEXT);
        if (shared == null || shared.trim().isEmpty()) return;
        String text = shared.trim();
        mInput.setText(text);
        parseAsync(text);
    }

    /** 底部设置面板：选壁纸（长按恢复默认）+ 玻璃模糊滑杆。 */
    private void openSheet() {
        final Dialog d = new Dialog(this);
        View v = getLayoutInflater().inflate(R.layout.sheet_dialog, null);
        d.setContentView(v);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }

        View pick = v.findViewById(R.id.sheet_pick_wall);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View vv) {
                Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                it.setType("image/*");
                startActivityForResult(Intent.createChooser(it, "选择壁纸"), REQ_WALLPAPER);
            }
        });
        pick.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View vv) {
                new File(getFilesDir(), "wallpaper.jpg").delete();
                mWall.setImageResource(R.drawable.wall_default);
                applyGlassBlur(mWall);
                Toast.makeText(MainActivity.this, "已恢复默认壁纸", Toast.LENGTH_SHORT).show();
                d.dismiss();
                return true;
            }
        });

        final TextView blurVal = (TextView) v.findViewById(R.id.blur_val);
        final ProgressBar seek = (ProgressBar) v.findViewById(R.id.blur_seek);
        seek.setProgress(Math.round(mBlurPx));
        blurVal.setText(Math.round(mBlurPx) + "px");
        seek.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View vv, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE: {
                        int pw = vv.getWidth();
                        int p = (int) Math.max(0, Math.min(60,
                                (ev.getX() - dp(6)) * 60f / Math.max(1, pw - dp(12))));
                        seek.setProgress(p);
                        mBlurPx = p;
                        blurVal.setText(p + "px");
                        applyGlassBlur(mWall);   // 拖动全程实时生效，壁纸在面板后面直接可见
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        getSharedPreferences("ui", Context.MODE_PRIVATE)
                                .edit().putFloat("blur_px", mBlurPx).apply();
                        return true;
                }
                return false;
            }
        });

        d.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_WALLPAPER && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri uri = data.getData();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                InputStream is = getContentResolver().openInputStream(uri);
                BitmapFactory.decodeStream(is, null, o);
                if (is != null) is.close();
                o.inSampleSize = Math.max(1, Math.min(o.outWidth, o.outHeight) / 1100);
                o.inJustDecodeBounds = false;
                is = getContentResolver().openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is, null, o);
                if (is != null) is.close();
                if (bmp == null) return;
                FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), "wallpaper.jpg"));
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, fo);
                fo.close();
                setWallBitmap(bmp);
                Toast.makeText(this, "壁纸已更换", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "壁纸设置失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadSavedWallpaper() {
        File f = new File(getFilesDir(), "wallpaper.jpg");
        if (!f.exists()) return;
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp != null) setWallBitmap(bmp);
    }

    private void setWallBitmap(Bitmap bmp) {
        mWall.setImageBitmap(bmp);
        applyGlassBlur(mWall);
    }

    /** 玻璃分层：壁纸 GPU 叠加 高斯模糊 N px + 饱和 180%，再由 scrim 统一压暗。 */
    private void applyGlassBlur(ImageView wall) {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        try {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.8f);
            RenderEffect blur = RenderEffect.createBlurEffect(mBlurPx, mBlurPx, Shader.TileMode.CLAMP);
            RenderEffect fx = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(cm), blur);
            wall.setRenderEffect(fx);
        } catch (Throwable ignored) {
            // 模糊不可用时由 scrim 保底
        }
    }

    private void pressScale(View v, final float scale) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View vv, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        vv.animate().scaleX(scale).scaleY(scale).setDuration(80).start();
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        vv.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                        break;
                }
                return false;
            }
        });
    }

    // ---------- 解析流程 ----------

    private void parseAsync(final String text) {
        mResults.removeAllViews();
        resetDlSummary();
        LinearLayout loading = groupContainer();
        LinearLayout lr = new LinearLayout(this);
        lr.setOrientation(LinearLayout.HORIZONTAL);
        lr.setGravity(Gravity.CENTER_VERTICAL);
        ProgressBar pb = new ProgressBar(this);
        pb.setLayoutParams(new LinearLayout.LayoutParams(dp(16), dp(16)));
        lr.addView(pb);
        TextView lt = new TextView(this);
        lt.setText("  正在解析…");
        lt.setTextSize(13);
        lt.setTextColor(0xB3FFFFFF);
        lr.addView(lt);
        loading.addView(lr, matchWrap());
        mResults.addView(loading, matchWrap());

        new Thread(new Runnable() { @Override public void run() {
            Parser.ParseResult r = null;
            String err = null;
            for (Parser p : PARSERS) {
                if (p.match(text)) {
                    try { r = p.parse(text); break; }
                    catch (Exception e) { err = e.getMessage() != null ? e.getMessage() : e.toString(); }
                }
            }
            if (r == null && err == null) err = "没有匹配的解析器（支持抖音 / B站）";
            final Parser.ParseResult fr = r;
            final String ferr = err;
            runOnUiThread(new Runnable() { @Override public void run() {
                mResults.removeAllViews();
                if (fr != null) {
                    renderResult(fr);
                    History.add(MainActivity.this, text);
                    renderHistory();
                } else {
                    LinearLayout box = groupContainer();
                    TextView et = new TextView(MainActivity.this);
                    et.setText("解析失败：" + ferr);
                    et.setTextSize(12);
                    et.setTextColor(getColor(R.color.error));
                    int p = dp(14);
                    et.setPadding(p, p, p, p);
                    box.addView(et, matchWrap());
                    mResults.addView(box, matchWrap());
                }
            }});
        }}).start();
    }

    private LinearLayout groupContainer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.mat_group);
        return box;
    }

    private void showEmptyState() {
        if (mResults.getChildCount() > 0) return;
        LinearLayout box = groupContainer();
        TextView t = new TextView(this);
        t.setText("粘贴链接，开始解析\n支持抖音 · B站");
        t.setTextSize(13);
        t.setTextColor(0x73FFFFFF);
        t.setGravity(Gravity.CENTER);
        int p = dp(18);
        t.setPadding(p, p, p, p);
        box.addView(t, matchWrap());
        mResults.addView(box, matchWrap());
    }

    private void renderResult(final Parser.ParseResult r) {
        LinearLayout card = groupContainer();

        TextView title = new TextView(this);
        title.setText(r.title);
        title.setTextSize(13.5f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        int p = dp(14);
        title.setPadding(p, p, p, dp(2));
        card.addView(title, matchWrap());

        TextView meta = new TextView(this);
        String m = (r.author == null || r.author.isEmpty()) ? "" : "@" + r.author + " · ";
        meta.setText(m + r.platform + " · 可下载 " + r.items.size() + " 项");
        meta.setTextSize(10.5f);
        meta.setTextColor(0x73FFFFFF);
        meta.setPadding(p, 0, p, dp(6));
        card.addView(meta, matchWrap());

        LinearLayout noteRow = new LinearLayout(this);
        noteRow.setOrientation(LinearLayout.HORIZONTAL);
        noteRow.setGravity(Gravity.RIGHT);
        noteRow.setPadding(p, 0, p, dp(8));
        TextView noteBtn = new TextView(this);
        noteBtn.setText("存文案到便签");
        noteBtn.setTextSize(10.5f);
        noteBtn.setGravity(Gravity.CENTER);
        noteBtn.setBackgroundResource(R.drawable.btn_ghost);
        noteBtn.setTextColor(0xBFFFFFFF);
        noteBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
        noteRow.addView(noteBtn, wrapWrap());
        pressScale(noteBtn, 0.94f);
        noteBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (r.title == null || r.title.isEmpty()) {
                    Toast.makeText(MainActivity.this, "这条没有文案", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent si = new Intent(Intent.ACTION_SEND);
                si.setType("text/plain");
                si.putExtra(Intent.EXTRA_TEXT, r.title);
                try {
                    startActivity(Intent.createChooser(si, "存文案到便签"));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "没有可用的便签 App", Toast.LENGTH_SHORT).show();
                }
            }
        });
        card.addView(noteRow, matchWrap());

        // 图片项单独成缩略图网格；点开全屏预览，每张保留独立下载按钮
        final java.util.List<Parser.MediaItem> imgs = new java.util.ArrayList<>();
        for (Parser.MediaItem mi : r.items) if ("image".equals(mi.type)) imgs.add(mi);
        if (!imgs.isEmpty()) {
            TextView cap = new TextView(this);
            cap.setText("图片 · " + imgs.size() + " 张（点开看大图）");
            cap.setTextSize(10.5f);
            cap.setTextColor(0x73FFFFFF);
            cap.setPadding(p, dp(2), p, dp(4));
            card.addView(cap, matchWrap());
            card.addView(buildImageGrid(r, imgs), matchWrap());
        }

        int shown = 0;
        for (int i = 0; i < r.items.size(); i++) {
            final Parser.MediaItem mi = r.items.get(i);
            if ("image".equals(mi.type)) continue;
            if (shown > 0) card.addView(hairline(), matchWrap());
            card.addView(buildItemCell(mi, r));
            shown++;
        }
        mResults.addView(card, matchWrap());
    }

    /** 图片缩略图网格（3 列），纯 Java 异步加载，无第三方图片库。 */
    private View buildImageGrid(final Parser.ParseResult r, final java.util.List<Parser.MediaItem> imgs) {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(10), dp(2), dp(10), dp(6));
        for (int start = 0; start < imgs.size(); start += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3 && start + c < imgs.size(); c++) {
                final int idx = start + c;
                final Parser.MediaItem mi = imgs.get(idx);
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(dp(3), dp(3), dp(3), dp(3));

                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(0xFF1C1C1E);
                iv.setContentDescription("图片 " + (idx + 1) + " 加载中");
                iv.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(88)));
                loadThumb(iv, mi);
                iv.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openImagePreview(r, imgs, idx); }
                });
                cell.addView(iv);

                final TextView dl = new TextView(this);
                dl.setText("下载");
                dl.setTextSize(10);
                dl.setGravity(Gravity.CENTER);
                dl.setSingleLine(true);
                dl.setMinWidth(dp(64));
                dl.setTextColor(getColor(R.color.blue));
                dl.setPadding(0, dp(4), 0, 0);
                dl.setLayoutParams(matchWrap());
                final ProgressBar bar = newDlBar();
                dl.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { downloadDirectAsync(r.title, mi, dl, bar); }
                });
                cell.addView(dl);
                cell.addView(bar, matchWrap());

                row.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            grid.addView(row, matchWrap());
        }
        return grid;
    }

    private void loadThumb(final ImageView iv, final Parser.MediaItem mi) {
        new Thread(new Runnable() { @Override public void run() {
            try {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", Http.MOBILE_UA);
                if (mi.referer != null) h.put("Referer", mi.referer);
                final Bitmap b = Http.fetchBitmap(mi.url, h, 240);
                final Bitmap loaded = b;
                if (loaded != null) {
                    runOnUiThread(new Runnable() { @Override public void run() {
                        iv.setImageBitmap(loaded);
                        iv.setContentDescription("图片缩略图已加载");
                    }});
                }
            } catch (Exception e) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    iv.setContentDescription("图片缩略图加载失败");
                }});
            }
        }}).start();
    }

    /** 全屏图片预览：左右滑动 / 箭头切换，独立下载按钮。 */
    private void openImagePreview(final Parser.ParseResult r, final java.util.List<Parser.MediaItem> imgs, int startIdx) {
        final Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        final ImageView big = new ImageView(this);
        big.setScaleType(ImageView.ScaleType.FIT_CENTER);
        big.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(big);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int bp = dp(12);
        bar.setPadding(bp, bp, bp, bp);
        final TextView counter = new TextView(this);
        counter.setTextSize(12);
        counter.setTextColor(0xB3FFFFFF);
        counter.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView prev = new TextView(this);
        prev.setText("‹ 上一张");
        TextView next = new TextView(this);
        next.setText("下一张 ›");
        final TextView save = new TextView(this);
        save.setText("下载");
        TextView close = new TextView(this);
        close.setText("关闭");
        android.view.View[] btns = { prev, next, save, close };
        for (android.view.View b : btns) {
            TextView tv = (TextView) b;
            tv.setTextSize(12);
            tv.setTextColor(0xFFFFFFFF);
            tv.setPadding(dp(10), dp(8), dp(10), dp(8));
            bar.addView(tv, wrapWrap());
        }
        root.addView(bar, matchWrap());

        final int[] cur = { startIdx };
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (cur[0] > 0) showPreviewAt(big, counter, imgs, --cur[0]); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (cur[0] < imgs.size() - 1) showPreviewAt(big, counter, imgs, ++cur[0]); }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { downloadDirectAsync(r.title, imgs.get(cur[0]), save, null); }
        });
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        final float[] downX = { 0f };
        big.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) downX[0] = e.getX();
                else if (e.getAction() == android.view.MotionEvent.ACTION_UP) {
                    float dx = e.getX() - downX[0];
                    if (dx < -dp(50) && cur[0] < imgs.size() - 1) showPreviewAt(big, counter, imgs, ++cur[0]);
                    else if (dx > dp(50) && cur[0] > 0) showPreviewAt(big, counter, imgs, --cur[0]);
                }
                return true;
            }
        });
        showPreviewAt(big, counter, imgs, cur[0]);
        d.setContentView(root);
        d.show();
    }

    private void showPreviewAt(final ImageView iv, final TextView counter,
                               final java.util.List<Parser.MediaItem> imgs, final int idx) {
        counter.setText((idx + 1) + " / " + imgs.size());
        final Parser.MediaItem mi = imgs.get(idx);
        new Thread(new Runnable() { @Override public void run() {
            try {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", Http.MOBILE_UA);
                if (mi.referer != null) h.put("Referer", mi.referer);
                final Bitmap b = Http.fetchBitmap(mi.url, h, 0);
                if (b != null) runOnUiThread(new Runnable() { @Override public void run() { iv.setImageBitmap(b); }});
            } catch (Exception ignored) {
                // 加载失败时保留上一帧
            }
        }}).start();
    }

    private View buildItemCell(final Parser.MediaItem mi, final Parser.ParseResult r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(12);
        row.setPadding(p, dp(11), p, dp(11));

        TextView ic = new TextView(this);
        ic.setText(iconOf(mi));
        ic.setTextSize(13);
        ic.setGravity(Gravity.CENTER);
        ic.setBackgroundResource(R.drawable.ic_tile);
        row.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView nm = new TextView(this);
        nm.setText(mi.label);
        nm.setTextSize(13);
        nm.setTextColor(0xFFFFFFFF);
        mid.addView(nm, wrapWrap());
        TextView ds = new TextView(this);
        ds.setText(mi.ext.toUpperCase() + " · 存到 Downloads/存片");
        ds.setTextSize(10.5f);
        ds.setTextColor(0x73FFFFFF);
        mid.addView(ds, wrapWrap());
        LinearLayout.LayoutParams midp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        midp.leftMargin = dp(11);
        row.addView(mid, midp);

        boolean main = "video".equals(mi.type);
        if (main) {
            final TextView au = new TextView(this);
            au.setText("♪音频");
            au.setTextSize(11);
            au.setGravity(Gravity.CENTER);
            au.setBackgroundResource(R.drawable.btn_ghost);
            au.setTextColor(0xBFFFFFFF);
            au.setPadding(dp(10), dp(7), dp(10), dp(7));
            LinearLayout.LayoutParams aulp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            aulp.rightMargin = dp(8);
            row.addView(au, aulp);
            pressScale(au, 0.94f);
            au.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { extractAudioAsync(r.title, mi, au); }
            });
        }
        final TextView dl = new TextView(this);
        dl.setText("下载");
        dl.setTextSize(12);
        dl.setTypeface(null, android.graphics.Typeface.BOLD);
        dl.setGravity(Gravity.CENTER);
        dl.setMinWidth(dp(78));   // 进度文字比"下载"长，固定最小宽度避免行内抖动
        dl.setBackgroundResource(main ? R.drawable.btn_dl : R.drawable.btn_ghost);
        dl.setTextColor(main ? getColor(R.color.blue) : 0xBFFFFFFF);
        dl.setPadding(dp(12), dp(7), dp(12), dp(7));
        row.addView(dl, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pressScale(dl, 0.94f);
        final ProgressBar bar = newDlBar();
        dl.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { downloadDirectAsync(r.title, mi, dl, bar); }
        });
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row, matchWrap());
        wrap.addView(bar, matchWrap());
        return wrap;
    }

    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(14);
        v.setLayoutParams(lp);
        return v;
    }

    private static String iconOf(Parser.MediaItem mi) {
        if ("audio".equals(mi.type)) return "🎵";
        if ("image".equals(mi.type)) return "🖼";
        return "🎬";
    }

    private void enqueueDownload(String title, final Parser.MediaItem mi) {
        try {
            String safe = title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
            if (safe.length() > 60) safe = safe.substring(0, 60);
            String fname = safe + "_" + System.currentTimeMillis() % 100000 + "." + mi.ext;

            // 先向 MediaStore 申请带正确扩展名的目标 URI，再交给 DownloadManager 写入。
            // 走 setDestinationInExternalPublicDir 时，系统清理含中文/特殊字符的长文件名会吞掉后缀。
            String mime = "video/mp4";
            if ("audio".equals(mi.type)) mime = "audio/mpeg";
            else if ("image".equals(mi.type)) mime = "image/jpeg";
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fname);
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/存片");
            }
            Uri dest = getContentResolver().insert(
                    android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
            if (dest == null) throw new Exception("MediaStore 未返回目标 URI");

            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(mi.url));
            req.setTitle(mi.label + " · " + truncate(title, 40));
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationUri(dest);
            req.addRequestHeader("User-Agent", Http.MOBILE_UA);
            if (mi.referer != null) req.addRequestHeader("Referer", mi.referer);

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            dm.enqueue(req);
            Toast.makeText(this, "已加入下载队列", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 视频项“♪音频”：先拉视频到私有缓存 → 纯 Java 提音轨 → 存 m4a 到 Downloads/存片。 */
    private void extractAudioAsync(final String title, final Parser.MediaItem mi, final TextView btn) {
        if (btn != null) dlStart(btn, null);
        else Toast.makeText(this, "正在提取音频…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { @Override public void run() {
            File tmpVideo = null, tmpM4a = null;
            final int[] lastPct = { -2 };
            try {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", Http.MOBILE_UA);
                if (mi.referer != null) h.put("Referer", mi.referer);
                tmpVideo = new File(getCacheDir(), "audio_src_" + System.currentTimeMillis() + ".mp4");
                Http.ProgressCallback cb = new Http.ProgressCallback() {
                    @Override public void onProgress(int percent, long done, long total) {
                        if (percent == lastPct[0]) return;
                        lastPct[0] = percent;
                        dlProgress(btn, null, percent, done);
                    }
                };
                long sz = Http.downloadToFile(mi.url, h, tmpVideo, cb);
                if (sz < 1024) throw new Exception("视频下载不完整(" + sz + "B)");

                tmpM4a = new File(getCacheDir(), "audio_out_" + System.currentTimeMillis() + ".m4a");
                long written = AudioExtractor.extract(tmpVideo, tmpM4a);
                if (written < 512) throw new Exception("没提取到音轨");

                String safe = title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
                if (safe.length() > 60) safe = safe.substring(0, 60);
                String fname = safe + "_" + System.currentTimeMillis() % 100000 + ".m4a";
                saveMediaToDownloads(tmpM4a, fname, "audio/mp4");
                runOnUiThread(new Runnable() { @Override public void run() {
                    dlFinish(btn, null, true, "♪ 已存");
                    Toast.makeText(MainActivity.this, "音频已存到 Downloads/存片", Toast.LENGTH_SHORT).show();
                }});
            } catch (final Exception e) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    dlFinish(btn, null, false, "♪ 失败");
                    Toast.makeText(MainActivity.this, "音频提取失败：" + (e.getMessage() != null ? e.getMessage() : e),
                            Toast.LENGTH_LONG).show();
                }});
            } finally {
                if (tmpVideo != null) tmpVideo.delete();
                if (tmpM4a != null) tmpM4a.delete();
            }
        }}).start();
    }

    /** 重置下载汇总（换结果或点清空时调用）。 */
    private void resetDlSummary() {
        mDlTotal = 0; mDlDone = 0; mDlFailed = 0; mDlActive = 0;
        mDlSummary.setVisibility(View.GONE);
        mDlSummary.setText("");
    }

    /** 顶部汇总：已完成 X/Y · 进行中 Z · 失败 F。必须在 UI 线程调用。 */
    private void refreshDlSummary() {
        if (mDlTotal == 0) { mDlSummary.setVisibility(View.GONE); return; }
        mDlSummary.setVisibility(View.VISIBLE);
        StringBuilder sb = new StringBuilder();
        sb.append("下载进度：已完成 ").append(mDlDone).append('/').append(mDlTotal);
        if (mDlActive > 0) sb.append(" · 进行中 ").append(mDlActive);
        if (mDlFailed > 0) sb.append(" · 失败 ").append(mDlFailed);
        mDlSummary.setText(sb.toString());
    }

    /** 每项下载按钮下的细进度条。 */
    private ProgressBar newDlBar() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        bar.setVisibility(View.GONE);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));
        return bar;
    }

    /** 下载开始：按钮进入进度态。必须在 UI 线程调用。 */
    private void dlStart(TextView btn, ProgressBar bar) {
        if (btn == null) return;
        mDlTotal++; mDlActive++;
        btn.setEnabled(false);
        btn.setText("下载中 0%");
        if (bar != null) { bar.setProgress(0); bar.setVisibility(View.VISIBLE); }
        refreshDlSummary();
    }

    /** 进度回调来自后台线程，统一切回 UI；调用方按 1% 节流后再调这里。 */
    private void dlProgress(final TextView btn, final ProgressBar bar,
                            final int pct, final long done) {
        if (btn == null) return;
        runOnUiThread(new Runnable() { @Override public void run() {
            btn.setText(pct < 0 ? "下载中 " + (done / 1024) + "KB" : "下载中 " + pct + "%");
            if (bar != null) bar.setProgress(pct < 0 ? 0 : pct);
        }});
    }

    /** 下载结束（成功/失败），更新按钮与汇总。必须在 UI 线程调用。 */
    private void dlFinish(TextView btn, ProgressBar bar, boolean ok, String text) {
        if (btn == null) return;
        if (ok) mDlDone++; else mDlFailed++;
        mDlActive--;
        btn.setText(text);
        btn.setEnabled(true);
        if (bar != null) bar.setVisibility(View.GONE);
        refreshDlSummary();
    }

    /** 所有媒体项“下载”：Http 拉到私有缓存 → MediaStore 直写 Downloads/存片（绕开 DownloadManager 的长中文文件名清理丢扩展名问题）。 */
    private void downloadDirectAsync(final String title, final Parser.MediaItem mi) {
        downloadDirectAsync(title, mi, null, null);
    }

    /** btn 非空时显示原地进度并计入顶部汇总；bar 可空（全屏预览里只改按钮文字）。 */
    private void downloadDirectAsync(final String title, final Parser.MediaItem mi,
                                     final TextView btn, final ProgressBar bar) {
        if (btn != null) dlStart(btn, bar);
        else Toast.makeText(this, "正在下载…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { @Override public void run() {
            File tmp = null;
            final int[] lastPct = { -2 };
            try {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", Http.MOBILE_UA);
                if (mi.referer != null) h.put("Referer", mi.referer);
                tmp = new File(getCacheDir(), "dl_src_" + System.currentTimeMillis() + "." + mi.ext);
                Http.ProgressCallback cb = new Http.ProgressCallback() {
                    @Override public void onProgress(int percent, long done, long total) {
                        if (percent == lastPct[0]) return;   // 1% 粒度节流，避免每 16KB 重绘
                        lastPct[0] = percent;
                        dlProgress(btn, bar, percent, done);
                    }
                };
                long sz = Http.downloadToFile(mi.url, h, tmp, cb);
                if (sz < 1024) throw new Exception("下载不完整(" + sz + "B)");

                String safe = title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
                if (safe.length() > 60) safe = safe.substring(0, 60);
                String fname = safe + "_" + System.currentTimeMillis() % 100000 + "." + mi.ext;
                String mime = "video/mp4";
                if ("image".equals(mi.type)) mime = "image/jpeg";
                else if ("audio".equals(mi.type)) mime = "audio/mpeg";
                saveMediaToDownloads(tmp, fname, mime);
                runOnUiThread(new Runnable() { @Override public void run() {
                    dlFinish(btn, bar, true, "✓ 已存");
                    Toast.makeText(MainActivity.this, "已存到 Downloads/存片", Toast.LENGTH_SHORT).show();
                }});
            } catch (final Exception e) {
                runOnUiThread(new Runnable() { @Override public void run() {
                    dlFinish(btn, bar, false, "失败·重试");
                    Toast.makeText(MainActivity.this, "下载失败：" + (e.getMessage() != null ? e.getMessage() : e),
                            Toast.LENGTH_LONG).show();
                }});
            } finally {
                if (tmp != null) tmp.delete();
            }
        }}).start();
    }

    /** 通用 MediaStore 写入 Downloads/存片（兼容 Android 10+ scoped storage），扩展名与 MIME 显式指定。 */
    private void saveMediaToDownloads(File src, String fname, String mime) throws Exception {
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fname);
        cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/存片");
        }
        Uri uri = getContentResolver().insert(
                android.provider.MediaStore.Files.getContentUri("external"), cv);
        if (uri == null) throw new Exception("MediaStore 插入失败");
        InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream os = getContentResolver().openOutputStream(uri);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
            if (os != null) try { os.close(); } catch (Exception ignored) {}
        }
    }

    private void renderHistory() {
        mHistory.removeAllViews();
        List<String> links = History.get(this);
        if (links.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("暂无历史");
            t.setTextSize(12);
            t.setTextColor(0x73FFFFFF);
            int p = dp(14);
            t.setPadding(p, dp(11), p, dp(11));
            mHistory.addView(t, matchWrap());
            return;
        }
        for (int i = 0; i < links.size(); i++) {
            final String link = links.get(i);
            if (i > 0) mHistory.addView(hairline(), matchWrap());

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int p = dp(12);
            row.setPadding(p, dp(11), p, dp(11));

            TextView ic = new TextView(this);
            ic.setText(platformTag(link));
            ic.setTextSize(10);
            ic.setTypeface(null, android.graphics.Typeface.BOLD);
            ic.setGravity(Gravity.CENTER);
            ic.setBackgroundResource(R.drawable.ic_tile);
            LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(dp(30), dp(30));
            iclp.rightMargin = dp(11);
            mHistory.addView(ic, iclp);

            TextView tx = new TextView(this);
            tx.setText(shorten(link, 40));
            tx.setTextSize(12);
            tx.setTextColor(0xBFFFFFFF);
            row.addView(tx, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView chev = new TextView(this);
            chev.setText("›");
            chev.setTextSize(15);
            chev.setTextColor(0x59FFFFFF);
            row.addView(chev, wrapWrap());

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mInput.setText(link);
                    parseAsync(link);
                }
            });
            mHistory.addView(row, matchWrap());
        }
    }

    private static String platformTag(String link) {
        if (link.contains("bilibili") || link.contains("b23.tv")) return "B站";
        return "抖";
    }

    private static String truncate(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }
    private static String shorten(String s, int n) {
        s = s.trim();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}

/** 历史记录：SharedPreferences 存最近 10 条链接。 */
final class History {
    private History() {}

    static List<String> get(Context c) {
        List<String> out = new java.util.ArrayList<>();
        String raw = c.getSharedPreferences("history", Context.MODE_PRIVATE).getString("links", "");
        if (!raw.isEmpty()) {
            for (String s : raw.split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    static void add(Context c, String link) {
        List<String> cur = get(c);
        cur.remove(link);
        cur.add(0, link);
        while (cur.size() > 10) cur.remove(cur.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String s : cur) sb.append(s).append('\n');
        c.getSharedPreferences("history", Context.MODE_PRIVATE)
                .edit().putString("links", sb.toString()).apply();
    }
}
