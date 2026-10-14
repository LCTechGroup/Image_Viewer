package com.lctechgroup.imgbrowser;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.LruCache;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import static androidx.media3.common.Player.REPEAT_MODE_OFF;
import static androidx.media3.common.Player.REPEAT_MODE_ONE;

// ExoPlayer 原生播放页：直接读 SAF content uri，seek/高码率走系统硬解管线
public class VideoActivity extends Activity {

    public static final String EX_TREE = "tree";
    public static final String EX_ID = "id";
    public static final String EX_NAME = "name";
    public static final String EX_SPEED = "speed";
    public static final String EX_HOLD = "hold";
    public static final String EX_LIST = "list";
    public static final String EX_INDEX = "index";
    private static final float STEP = 0.25f, MAX_SPEED = 5f;

    private ExoPlayer player;
    private PlayerView playerView;
    private TextView vTitle, btnSpeed, timeText, holdTip, seekTip, btnBack, btnLoop, btnList;
    private boolean landscape = false;
    private TextView spVal, spVal2;
    private SeekBar seekBar, spRange, spRange2;
    private View topBar, bottomBar, root, speedPanel, speedMask;
    private ImageView btnPlay, btnOrient;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean loop = false;
    // 播单：当前文件夹全部视频的 id/name，按自然顺序
    private String[] listIds, listNames;
    private int currentIndex = 0;

    private float normalSpeed = 1f, holdSpeed = 2f;
    // 当前视频的进度存储键（播单切集时随 id 更新）
    private String posKey = null;
    private long lastSaveTick = 0L;
    private boolean holding = false, scrubbing = false;
    private boolean uiVisible = true;
    private Runnable uiHideTask;

    // 手势：拖动起点的播放位置，避免随手指不断叠加导致过头
    private float sx, sy;
    private long scrubStartPos, lastTapTime;
    private boolean moved, maybeHold;
    // 整屏横向滑动只映射到视频 40% 时长，降低灵敏度
    private static final float SEEK_FRACTION = 0.4f;
    private final Runnable singleTapTask = new Runnable() {
        public void run() {
            if (lastTapTime > 0) {
                lastTapTime = 0;
                showUi(!uiVisible);
            }
        }
    };
    private final Runnable holdTrigger = new Runnable() {
        @Override
        public void run() {
            if (!moved && maybeHold) {
                holding = true;
                holdTip.setText("x" + fmtSpeed(holdSpeed));
                holdTip.setVisibility(View.VISIBLE);
                player.setPlaybackSpeed(holdSpeed);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        MainActivity.paintSystemBars(w);
        MainActivity.setDecorFits(w, true);
        setContentView(R.layout.activity_video);

        prefs = getSharedPreferences("app", MODE_PRIVATE);
        // 正常播放倍速不持久化，每次进入默认 1x（intent 可临时覆盖）
        normalSpeed = clampSpeed(getIntent().getFloatExtra(EX_SPEED, 1f));
        // 长按倍速以原生 prefs 为准（本页滑条改过只写这里），intent 仅在从未存过时给初值
        // 不能让 intent 优先：JS 侧网页播放器已阉割，传来的恒为默认 2，会覆盖用户的持久设置
        holdSpeed = readSpeed("vpHoldSpeed", clampSpeed(getIntent().getFloatExtra(EX_HOLD, 2f)));
        // 循环 / 单次设置持久化
        loop = prefs.getBoolean("vpLoop", false);

        root = findViewById(R.id.vRoot);
        playerView = findViewById(R.id.playerView);
        vTitle = findViewById(R.id.vTitle);
        btnSpeed = findViewById(R.id.btnSpeed);
        timeText = findViewById(R.id.timeText);
        holdTip = findViewById(R.id.holdTip);
        seekTip = findViewById(R.id.seekTip);
        btnBack = findViewById(R.id.btnBack);
        btnPlay = findViewById(R.id.btnPlay);
        btnLoop = findViewById(R.id.btnLoop);
        btnList = findViewById(R.id.btnList);
        seekBar = findViewById(R.id.seekBar);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        speedPanel = findViewById(R.id.speedPanel);
        speedMask = findViewById(R.id.speedMask);
        spVal = findViewById(R.id.spVal);
        spVal2 = findViewById(R.id.spVal2);
        spRange = findViewById(R.id.spRange);
        spRange2 = findViewById(R.id.spRange2);

        // 播放键圆形深色底
        btnPlay.setBackgroundResource(R.drawable.vp_circle);
        speedMask.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setSpeedPanel(false); }
        });

        String name = getIntent().getStringExtra(EX_NAME);
        vTitle.setText(name == null ? "" : name);
        updateSpeedBtn();

        final String treeStr = getIntent().getStringExtra(EX_TREE);
        Uri tree = Uri.parse(treeStr);
        String id = getIntent().getStringExtra(EX_ID);
        posKey = TaskBall.vposKey(treeStr, id);
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);

        // 走 AudioTrack.setPlaybackParams 即时变速：默认 MediaCodec 通道切速在很多设备有数百 ms
        // 延迟（按下慢半拍、松手还冲一段），AudioTrack 通道在 framework 层立即生效
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableAudioTrackPlaybackParams(true);
        player = new ExoPlayer.Builder(this, renderers).build();
        playerView.setPlayer(player);
        player.setMediaItem(MediaItem.fromUri(doc));
        player.setPlaybackSpeed(normalSpeed);
        player.setRepeatMode(loop ? REPEAT_MODE_ONE : REPEAT_MODE_OFF);
        // prepare 前 seekTo 会作为起始位置生效：重开即续到上次退出的毫秒点
        long savedPos = prefs.getLong(posKey, 0L);
        if (savedPos > 1000L) player.seekTo(savedPos);
        player.prepare();
        player.setPlayWhenReady(true);
        updateLoopBtn();
        btnPlay.setColorFilter(0xFFEEEEEE);
        player.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                btnPlay.setImageResource(isPlaying
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play);
                btnPlay.setColorFilter(0xFFEEEEEE);
                // 播放中默认屏幕常亮，暂停或播完恢复系统自动息屏
                if (isPlaying) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                // 自然播完删掉进度，下次重开从头播放
                if (state == Player.STATE_ENDED && posKey != null) {
                    prefs.edit().remove(posKey).apply();
                }
            }
        });

        btnBack.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (player.isPlaying()) player.pause(); else player.play();
                showUi(true);
            }
        });
        btnSpeed.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showSpeedDialog(); }
        });
        btnLoop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loop = !loop;
                player.setRepeatMode(loop ? REPEAT_MODE_ONE : REPEAT_MODE_OFF);
                prefs.edit().putBoolean("vpLoop", loop).apply();
                updateLoopBtn();
            }
        });
        btnList.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showPlaylist(); }
        });

        // 横屏切换：仅影响本播放页，退出回主界面不锁定；图标/配色随状态在放大/缩小间切换
        btnOrient = findViewById(R.id.btnOrient);
        updateOrientBtn();
        btnOrient.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                landscape = !landscape;
                if (landscape) {
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    applyFullscreen(true);
                } else {
                    applyFullscreen(false);
                    // 恢复系统重力感应/自动旋转，不再硬锁竖屏
                    setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                }
                updateOrientBtn();
            }
        });

        // 读入播单
        java.util.ArrayList<String> ids = getIntent().getStringArrayListExtra(EX_LIST);
        if (ids != null && ids.size() > 0) {
            listIds = ids.toArray(new String[0]);
            currentIndex = getIntent().getIntExtra(EX_INDEX, 0);
            if (currentIndex < 0) currentIndex = 0;
            if (currentIndex >= listIds.length) currentIndex = 0;
            // 名称与 id 用同一个分隔符打包，按行对应；这里直接用 id 末段兜底，真实名称另传
            java.util.ArrayList<String> names = getIntent().getStringArrayListExtra(EX_LIST + "n");
            listNames = names != null ? names.toArray(new String[0]) : null;
        }
        btnList.setVisibility(listIds != null && listIds.length > 1 ? View.VISIBLE : View.GONE);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && player.getDuration() > 0) {
                    long t = progress * player.getDuration() / 1000;
                    timeText.setText(fmtT(t) + " / " + fmtT(player.getDuration()));
                }
            }
            public void onStartTrackingTouch(SeekBar sb) { scrubbing = true; }
            public void onStopTrackingTouch(SeekBar sb) {
                if (player.getDuration() > 0) {
                    player.seekTo(sb.getProgress() * player.getDuration() / 1000);
                }
                scrubbing = false;
            }
        });

        setupGestures();
        showUi(true);
        attachTaskBall();
    }

    private float readSpeed(String key, float def) {
        try {
            return clampSpeed(Float.parseFloat(prefs.getString(key, String.valueOf(def))));
        } catch (Exception e) {
            return def;
        }
    }

    private float clampSpeed(float s) {
        if (!(s > 0)) s = 1f;
        return Math.max(STEP, Math.min(MAX_SPEED, Math.round(s / STEP) * STEP));
    }

    private String fmtSpeed(float s) {
        int r = Math.round(s * 100);
        if (r % 100 == 0) return String.valueOf(r / 100);
        if (r % 10 == 0) return String.valueOf(r / 100f);
        return String.valueOf(r / 100f);
    }

    private void updateSpeedBtn() {
        btnSpeed.setText("x" + fmtSpeed(normalSpeed));
    }

    private void updateLoopBtn() {
        // 启用循环：白底深色 ↻，与图片阅读器切换键启用态一致；单次：深底灰字 1x
        if (loop) {
            btnLoop.setBackgroundResource(R.drawable.vp_pill_on);
            btnLoop.setText("↻");
            btnLoop.setTextColor(0xFF232323);
        } else {
            btnLoop.setBackgroundResource(R.drawable.vp_pill);
            btnLoop.setText("1x");
            btnLoop.setTextColor(0xFF9AA0AA);
        }
    }

    // 横屏键启用态：白底黑图标（缩小）；未启用：深色半透明底白图标（放大）
    private void updateOrientBtn() {
        btnOrient.setBackgroundResource(landscape ? R.drawable.vp_pill_on : R.drawable.vp_pill);
        btnOrient.setImageResource(landscape ? R.drawable.vp_compress : R.drawable.vp_expand);
        btnOrient.setColorFilter(landscape ? 0xFF232323 : 0xFFEEEEEE);
    }

    // 横屏=真全屏：隐藏状态栏与导航栏，内容铺到整屏；退出恢复系统栏与常规边距
    @SuppressWarnings("deprecation")
    private void applyFullscreen(boolean on) {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            MainActivity.setDecorFits(w, !on);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (on) {
                    c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                } else {
                    c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
        } else {
            root.setSystemUiVisibility(on
                    ? (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN)
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
        root.setFitsSystemWindows(!on);
        // fitsSystemWindows 切 false 不会清除此前按竖屏 inset 打入的 padding，
        // 必须显式清零，否则旋转后顶/底栏仍到不了屏幕边缘
        if (on) root.setPadding(0, 0, 0, 0);
        root.requestApplyInsets();
        // 旋转完成后新 inset 会再派发一次，延后补一刀确保 padding 仍为 0
        if (on) root.postDelayed(new Runnable() {
            public void run() { if (landscape) root.setPadding(0, 0, 0, 0); }
        }, 300);
    }

    // configChanges 已声明旋转不重建 Activity；旋转后按当前状态重新施加全屏
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (landscape) applyFullscreen(true);
    }

    // 临时滑出的系统栏在沉浸模式下会自动收回；重新获得焦点时若仍在横屏则再次隐藏
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && landscape) applyFullscreen(true);
    }

    // 纵向播单：RecyclerView 复用行视图，缩略图按目标尺寸降采样、Lru 缓存、仅可见行加载
    private void showPlaylist() {
        if (listIds == null || listIds.length == 0) {
            return;
        }
        final float density = getResources().getDisplayMetrics().density;
        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCanceledOnTouchOutside(true);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(0xF2202020);
        panelBg.setCornerRadius(14 * density);
        panelBg.setStroke(1, 0x33FFFFFF);
        panel.setBackground(panelBg);
        int pad = (int) (14 * density);
        panel.setPadding(pad, pad, pad, pad);

        // 标题行：标题 + 关闭
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("本文件夹视频（" + listIds.length + "）");
        title.setTextColor(0xFFEEEEEE);
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(0xFFCCCCCC);
        close.setTextSize(24);
        close.setGravity(android.view.Gravity.CENTER);
        close.setPadding((int) (8 * density), 0, (int) (4 * density), (int) (4 * density));
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dlg.dismiss(); }
        });
        head.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(head);

        // 列表高度固定为屏幕 62%，由 RecyclerView 回收视图保证滚动流畅
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setHasFixedSize(false);
        rv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        rv.setPadding(0, (int) (6 * density), 0, 0);
        rv.setClipToPadding(false);
        rv.setItemAnimator(null);
        int listH = (int) (getResources().getDisplayMetrics().heightPixels * 0.62f);
        panel.addView(rv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, listH));

        final PlaylistAdapter adapter = new PlaylistAdapter(density);
        dlgRef = dlg;
        rv.setAdapter(adapter);

        dlg.setContentView(panel);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screenW = getResources().getDisplayMetrics().widthPixels;
            w.setLayout((int) Math.min(screenW * 0.88f, 380 * density),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dlg.show();
        // 打开即把当前播放项放到列表中部：行高约 71dp（50 缩略图+16 上下 padding+5 间距）
        int rowEst = (int) (71 * density);
        ((LinearLayoutManager) rv.getLayoutManager()).scrollToPositionWithOffset(
                currentIndex, Math.max(0, listH / 2 - rowEst / 2));
    }

    // 播单行 ViewHolder

    private class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.VH> {
        private final float density;
        private final GradientDrawable bgCur, bgNormal, thumbBg;
        // 已入队的缩略图去重，快速滑动时同一视频只抓一次帧
        private final java.util.Set<String> queued =
                java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

        PlaylistAdapter(float density) {
            this.density = density;
            bgCur = new GradientDrawable();
            bgCur.setCornerRadius(10 * density);
            bgCur.setColor(0x40FFFFFF);
            bgNormal = new GradientDrawable();
            bgNormal.setCornerRadius(10 * density);
            bgNormal.setColor(0x1AFFFFFF);
            thumbBg = new GradientDrawable();
            thumbBg.setCornerRadius(6 * density);
            thumbBg.setColor(0xFF111111);
            // 播单数据不变，位置即可作为稳定 id
            setHasStableIds(true);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(VideoActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int rv = (int) (10 * density);
            row.setPadding(rv, (int) (8 * density), rv, (int) (8 * density));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (5 * density);
            row.setLayoutParams(lp);

            ImageView thumb = new ImageView(VideoActivity.this);
            int tw = (int) (84 * density), th = (int) (50 * density);
            thumb.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
            thumb.setBackground(thumbBg);
            thumb.setClipToOutline(true);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            row.addView(thumb);

            TextView label = new TextView(VideoActivity.this);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            llp.setMarginStart((int) (10 * density));
            label.setLayoutParams(llp);
            label.setTextSize(13);
            label.setMaxLines(2);
            row.addView(label);
            return new VH(row, thumb, label);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            String nm = listNames != null && position < listNames.length
                    ? listNames[position] : ("视频 " + (position + 1));
            boolean cur = position == currentIndex;
            h.itemView.setBackground(cur ? bgCur : bgNormal);
            h.label.setText((cur ? "▶ " : "") + nm);
            h.label.setTextColor(cur ? 0xFFFFFFFF : 0xFFB8BCC4);
            h.label.setTypeface(null, cur ? Typeface.BOLD : Typeface.NORMAL);
            final int idx = position;
            h.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    playIndex(idx);
                    h.dialog.dismiss();
                }
            });
            // 缩略图：先取缓存，没有则占位，未入队过的才抓帧（避免快速滑动重复排队）
            Bitmap cached = thumbCache.get(listIds[position]);
            h.thumb.setImageBitmap(cached);
            if (cached == null) {
                h.thumb.setTag(listIds[position]);
                if (queued.add(listIds[position])) {
                    loadListThumb(h.thumb, listIds[position], queued);
                }
            } else {
                h.thumb.setTag(null);
            }
        }

        @Override
        public void onViewRecycled(@NonNull VH h) {
            // 滚走的行取消尚未回填的抓帧结果，避免错位与无效解码
            h.thumb.setTag(null);
            h.thumb.setImageDrawable(null);
            super.onViewRecycled(h);
        }

        @Override
        public int getItemCount() {
            return listIds.length;
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView label;
            final Dialog dialog;

            VH(@NonNull View itemView, ImageView thumb, TextView label) {
                super(itemView);
                this.thumb = thumb;
                this.label = label;
                this.dialog = dlgRef;
            }
        }
    }

    // 当前播单弹窗引用（供 ViewHolder 点击关闭）
    private Dialog dlgRef;

    // 缩略图内存缓存：按字节计量，最多 32 MiB 且不超过可用堆的 1/8。
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>((int) Math.min(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };

    // 播单行缩略图：Android10+ 走 SAF 提供方缩略图通道，旧版抓整帧后缩到目标尺寸，双线程后台执行
    private final java.util.concurrent.ExecutorService listThumbPool =
            java.util.concurrent.Executors.newFixedThreadPool(2);
    private void loadListThumb(final ImageView target, final String vid, final java.util.Set<String> queued) {
        listThumbPool.execute(new Runnable() {
            public void run() {
                if (isFinishing() || isDestroyed()) { queued.remove(vid); return; }
                float density = getResources().getDisplayMetrics().density;
                final int tw = Math.min(640, Math.max(210, Math.round(84 * density * 2.5f)));
                final int th = Math.max(1, Math.round(tw * 50f / 84f));
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(
                        Uri.parse(getIntent().getStringExtra(EX_TREE)), vid);
                Bitmap bmp = null;
                // API29+ 优先用 ContentResolver 的缩略图通道，由提供方直接给小图，避免整帧解码
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        bmp = getContentResolver().loadThumbnail(doc, new Size(tw, th), null);
                    } catch (Exception ignored) {
                        bmp = null;
                    }
                }
                // API23-28 或提供方不支持时，抓关键帧整帧再中心裁剪缩小
                if (bmp == null) {
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    Bitmap raw = null;
                    try {
                        r.setDataSource(VideoActivity.this, doc);
                        if (Build.VERSION.SDK_INT >= 27) {
                            raw = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th);
                            if (raw == null) raw = r.getScaledFrameAtTime(200000,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC, tw, th);
                        } else {
                            raw = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                            if (raw == null) raw = r.getFrameAtTime(200000,
                                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try { r.release(); } catch (Exception ignored) {}
                    }
                    if (raw != null) bmp = cropThumb(raw, tw, th);
                }
                queued.remove(vid);
                if (isFinishing() || isDestroyed()) { if (bmp != null) bmp.recycle(); return; }
                final Bitmap fb = bmp;
                if (fb != null) {
                    thumbCache.put(vid, fb);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            // 行已被复用给其它视频则不回填，防止错位
                            if (!isFinishing() && !isDestroyed() && vid.equals(target.getTag())) target.setImageBitmap(fb);
                        }
                    });
                }
            }
        });
    }

    // 整帧中心裁剪缩到列表缩略图尺寸，输出 RGB_565 减半内存，原图立即回收
    private Bitmap cropThumb(Bitmap src, int tw, int th) {
        try {
            Bitmap out = Bitmap.createBitmap(tw, th, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(out);
            float s = Math.max((float) tw / src.getWidth(), (float) th / src.getHeight());
            Matrix m = new Matrix();
            m.setScale(s, s);
            m.postTranslate((tw - src.getWidth() * s) / 2f,
                    (th - src.getHeight() * s) / 2f);
            c.drawColor(Color.BLACK);
            c.drawBitmap(src, m, null);
            return out;
        } finally {
            src.recycle();
        }
    }

    private void playIndex(int i) {
        if (listIds == null || i < 0 || i >= listIds.length) {
            return;
        }
        currentIndex = i;
        String id2 = listIds[i];
        String treeStr2 = getIntent().getStringExtra(EX_TREE);
        Uri tree2 = Uri.parse(treeStr2);
        Uri doc2 = DocumentsContract.buildDocumentUriUsingTree(tree2, id2);
        posKey = TaskBall.vposKey(treeStr2, id2);
        player.setMediaItem(MediaItem.fromUri(doc2));
        player.setPlaybackSpeed(normalSpeed);
        long saved2 = prefs.getLong(posKey, 0L);
        if (saved2 > 1000L) player.seekTo(saved2);
        player.prepare();
        player.play();
        if (listNames != null && i < listNames.length) {
            vTitle.setText(listNames[i]);
        }
    }

    // 黑色半透明圆角倍速覆盖面板
    private void setSpeedPanel(boolean show) {
        speedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        speedMask.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            spRange.setProgress(Math.round(normalSpeed / STEP));
            spVal.setText("x" + fmtSpeed(normalSpeed));
            spRange2.setProgress(Math.round(holdSpeed / STEP));
            spVal2.setText("x" + fmtSpeed(holdSpeed));
        }
    }

    private void showSpeedDialog() {
        spRange.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean f) {
                normalSpeed = Math.max(STEP, p * STEP);
                spVal.setText("x" + fmtSpeed(normalSpeed));
                if (!holding) player.setPlaybackSpeed(normalSpeed);
                updateSpeedBtn();
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        spRange2.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar sb, int p, boolean f) {
                holdSpeed = Math.max(STEP, p * STEP);
                spVal2.setText("x" + fmtSpeed(holdSpeed));
            }
            public void onStartTrackingTouch(SeekBar sb) {}
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putString("vpHoldSpeed", String.valueOf(holdSpeed)).apply();
            }
        });
        setSpeedPanel(true);
    }

    private void setupGestures() {
        root.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getX(); sy = e.getY(); moved = false; maybeHold = true;
                        ui.postDelayed(holdTrigger, 150);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getX() - sx, dy = e.getY() - sy;
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true;
                        if (holding) return true;
                        if (maybeHold && Math.abs(dx) > 50
                                && Math.abs(dx) > Math.abs(dy) * 1.4f
                                && player.getDuration() > 0) {
                            maybeHold = false;
                            ui.removeCallbacks(holdTrigger);
                            scrubbing = true;
                            scrubStartPos = player.getCurrentPosition();
                        }
                        if (scrubbing) {
                            long dur = player.getDuration();
                            float w = root.getWidth();
                            long nt = Math.max(0, Math.min(dur,
                                    scrubStartPos + (long) (dx / w * dur * SEEK_FRACTION)));
                            player.seekTo(nt);
                            seekTip.setText(fmtT(nt) + " / " + fmtT(dur));
                            seekTip.setVisibility(View.VISIBLE);
                            timeText.setText(fmtT(nt) + " / " + fmtT(dur));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(holdTrigger);
                        // 先恢复倍速让 renderer 消息尽早排队，再做 UI 更新，松手即回原速不"冲"一段
                        if (holding) {
                            holding = false;
                            player.setPlaybackSpeed(normalSpeed);
                            holdTip.setVisibility(View.GONE);
                        }
                        seekTip.setVisibility(View.GONE);
                        if (scrubbing) {
                            scrubbing = false;
                            showUi(true);
                        } else if (!moved && e.getEventTime() - e.getDownTime() < 300) {
                            long now = e.getEventTime();
                            if (now - lastTapTime < 280) {
                                // 双击：直接暂停/播放，不切 UI
                                lastTapTime = 0;
                                ui.removeCallbacks(singleTapTask);
                                if (player.isPlaying()) player.pause(); else player.play();
                            } else {
                                lastTapTime = now;
                                ui.removeCallbacks(singleTapTask);
                                ui.postDelayed(singleTapTask, 290);
                            }
                        }
                        maybeHold = false;
                        return true;
                }
                return true;
            }
        });
    }

    private void showUi(boolean show) {
        uiVisible = show;
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(show ? View.VISIBLE : View.GONE);
        // 横屏键与其他控件同进同退
        btnOrient.setVisibility(show ? View.VISIBLE : View.GONE);
        ui.removeCallbacks(uiHideTask);
        if (show && player.isPlaying()) {
            uiHideTask = new Runnable() {
                public void run() { showUi(false); }
            };
            ui.postDelayed(uiHideTask, 4000);
        }
    }

    private final Runnable ticker = new Runnable() {
        public void run() {
            if (player != null) {
                long d = player.getDuration(), c = player.getCurrentPosition();
                if (!scrubbing && d > 0) seekBar.setProgress((int) (c * 1000 / d));
                timeText.setText(fmtT(c) + " / " + fmtT(d));
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastSaveTick > 2000) {
                    lastSaveTick = now;
                    savePos();
                }
            }
            ui.postDelayed(this, 500);
        }
    };

    // 保存当前视频毫秒位置；距结尾不足 3 秒视为看完，删进度保证下次从头播
    private void savePos() {
        if (player == null || posKey == null) return;
        long d = player.getDuration(), c = player.getCurrentPosition();
        if (d > 0 && c >= d - 3000L) {
            prefs.edit().remove(posKey).apply();
        } else if (c > 1000L) {
            prefs.edit().putLong(posKey, c).apply();
        }
    }

    // 多开悬浮球：位置 / 任务快照直接读共享 prefs；切页 / 新建先存进度再回传动作
    private TaskBall taskBall;
    private void attachTaskBall() {
        taskBall = new TaskBall(this, prefs, new TaskBall.Listener() {
            @Override
            public void onAction(String action, int target) {
                if (TaskBall.ACTION_CLOSE_TASK.equals(action)
                        && target != prefs.getInt(TaskBall.PREF_CUR, 0)) {
                    // 删除的是其他任务：不退出播放，广播给 MainActivity 的 JS 执行
                    taskBall.removeTaskLocally(target);
                    taskBall.sendTaskActionBroadcast(action, target);
                    return;
                }
                // 切页 / 新建 / 删除当前任务：按正常退出流程，先存进度再回传动作
                savePos();
                Intent out = new Intent();
                out.putExtra(MainActivity.RES_ACTION, action);
                out.putExtra(MainActivity.RES_TARGET, target);
                setResult(RESULT_OK, out);
                finish();
            }
        });
        // 播放器沉浸态：球静止半透明
        taskBall.setDim(true);
    }

    private static String fmtT(long s) {
        if (s < 0 || s == Long.MIN_VALUE) s = 0;
        long h = s / 3600000, m = (s % 3600000) / 60000, sec = (s % 60000) / 1000;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, sec);
        return String.format("%02d:%02d", m, sec);
    }

    // 切到后台立即暂停，不做后台播放；同时落一次进度
    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            player.pause();
            savePos();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.post(ticker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回前台时以 JS 回写的权威快照为准（修正删除失败等乐观更新偏差），并重载共享坐标
        if (taskBall != null) {
            taskBall.reloadPosition();
            taskBall.refreshTasks();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        listThumbPool.shutdownNow();
        thumbCache.evictAll();
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
