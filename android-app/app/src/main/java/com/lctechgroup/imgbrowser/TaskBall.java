package com.lctechgroup.imgbrowser;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;

// 全 App 共用的多开悬浮球：MainActivity / VideoActivity / MhtActivity 各建一个实例，
// 位置与任务快照统一存 "app" SharedPreferences，页面切换天然同步，无需 intent 回传。
// 纯旁路 View，不碰播放器 / WebView 主链路；切页 / 新建动作通过 Listener 上抛。
public class TaskBall {

    public interface Listener {
        void onAction(String action, int targetId);
    }

    public static final String ACTION_SWITCH = "switch";
    public static final String ACTION_NEW = "new";
    // 删除指定任务（不一定是当前任务）：WebView 页直接执行，视频 / MHT 页发广播且不离开本页
    public static final String ACTION_CLOSE_TASK = "closeTask";
    public static final String BROADCAST_TASK_ACTION = "com.lctechgroup.imgbrowser.TASK_ACTION";

    public static final String PREF_X = "taskBallX";
    public static final String PREF_Y = "taskBallY";
    public static final String PREF_JSON = "taskBallJson";
    public static final String PREF_CUR = "taskBallCur";

    private final Activity act;
    private final SharedPreferences prefs;
    private final Listener listener;
    private final float density;
    private final int ballPx;
    private final int marginPx;
    private BallView ball;
    private LinearLayout panel;
    private LinearLayout rows;
    private View backdrop;
    // 宿主内容区底部被导航栏占用的高度（MHT 自己接管避让时传入），其余页传 0
    private int bottomInset = 0;
    // 沉浸阅读态：静止 0.2；浏览态静止 1.0；按下/展开统一 0.8
    private boolean dim = false;
    private boolean expanded = false;
    private boolean dragging = false;
    private boolean moved = false;
    private float downX, downY;
    private int downLeft, downTop;
    private float curX = 0f, curY = 1f;

    public TaskBall(Activity activity, SharedPreferences p, Listener l) {
        act = activity;
        prefs = p;
        listener = l;
        density = activity.getResources().getDisplayMetrics().density;
        ballPx = dp(44);
        marginPx = dp(8);
        curX = clamp01(prefs.getFloat(PREF_X, 0f));
        curY = clamp01(prefs.getFloat(PREF_Y, 1f));
        addBall();
        buildPanel();
        refreshTasks();
    }

    public void setBottomInset(int px) {
        bottomInset = Math.max(0, px);
        placeBall();
    }

    // 清个性设置时复位到左下角默认位置
    public void resetPosition() {
        curX = 0f;
        curY = 1f;
        placeBall();
    }

    // 从共享 prefs 重读位置：页面切回（MainActivity 一直存活）时同步其它页拖动后的坐标
    public void reloadPosition() {
        curX = clamp01(prefs.getFloat(PREF_X, 0f));
        curY = clamp01(prefs.getFloat(PREF_Y, 1f));
        placeBall();
    }

    // 视频 / MHT 页删除任务时的本地乐观更新：从共享快照移除该行立即重绘；
    // 若 JS 校验失败（至少保留一页），回前台时会被 syncTaskBall 的权威快照修正
    public void removeTaskLocally(int id) {
        try {
            JSONArray arr = new JSONArray(prefs.getString(PREF_JSON, "[]"));
            JSONArray out = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t != null && t.optInt("id") != id) out.put(t);
            }
            prefs.edit().putString(PREF_JSON, out.toString()).apply();
        } catch (Exception ignored) {
        }
        refreshTasks();
    }

    public void sendTaskActionBroadcast(String action, int targetId) {
        android.content.Intent bi = new android.content.Intent(BROADCAST_TASK_ACTION);
        bi.setPackage(act.getPackageName());
        bi.putExtra("action", action);
        bi.putExtra("target", targetId);
        act.sendBroadcast(bi);
    }

    // JS 同步任务快照后调用：重建行与球上数字
    public void refreshTasks() {
        if (rows == null) return;
        rows.removeAllViews();
        String json = prefs.getString(PREF_JSON, "[]");
        int curId = prefs.getInt(PREF_CUR, 0);
        int count = 0;
        try {
            JSONArray arr = new JSONArray(json == null ? "[]" : json);
            count = arr.length();
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject t = arr.optJSONObject(i);
                if (t == null) continue;
                final int id = t.optInt("id");
                boolean cur = id == curId;
                boolean dead = t.optBoolean("dead", false);
                String name = t.optString("name", "浏览页");

                // 每行：任务名（点按切换）+ × 删除
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);

                TextView r = makeRow(name, cur, dead, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 点当前页也走 switch，JS 侧识别为仅收起并清视频 / MHT 挂起
                        fire(ACTION_SWITCH, id);
                    }
                });
                row.addView(r, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView x = makeRow("×", false, false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        // 删除后保持面板展开，宿主刷新后该行消失；至少一页由 JS 拦截
                        listener.onAction(ACTION_CLOSE_TASK, id);
                    }
                });
                x.setGravity(Gravity.CENTER);
                x.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(dp(42), dp(40));
                xp.leftMargin = dp(8);
                row.addView(x, xp);

                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(8);
                rows.addView(row, rp);
            }
        } catch (Exception ignored) {
        }
        if (ball != null) ball.setCount(count > 0 ? count : 1);
    }

    public void setDim(boolean d) {
        dim = d;
        applyIdleAlpha();
    }

    private void fire(String action, int target) {
        setExpanded(false);
        listener.onAction(action, target);
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private static float clamp01(float v) {
        if (!(v >= 0f)) return 0f;
        return Math.min(1f, v);
    }

    private GradientDrawable round(int color, float radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp((int) radiusDp));
        if (strokeDp > 0) g.setStroke(dp(strokeDp), strokeColor);
        return g;
    }

    // 比例换算：left 范围 [8, w-52]，top 范围 [8, h-60]
    private int winW() {
        View decor = act.getWindow().getDecorView();
        int w = decor.getWidth();
        return w > 0 ? w : act.getResources().getDisplayMetrics().widthPixels;
    }

    private int winH() {
        View decor = act.getWindow().getDecorView();
        int h = decor.getHeight();
        if (h <= 0) h = act.getResources().getDisplayMetrics().heightPixels;
        return Math.max(ballPx + dp(32), h - bottomInset);
    }

    private int clampLeft(int x) {
        return Math.max(marginPx, Math.min(winW() - ballPx - marginPx, x));
    }

    private int clampTop(int y) {
        return Math.max(marginPx, Math.min(winH() - ballPx - dp(16), y));
    }

    private void persistPos() {
        prefs.edit().putFloat(PREF_X, curX).putFloat(PREF_Y, curY).apply();
    }

    private void applyIdleAlpha() {
        if (ball == null) return;
        ball.setAlpha(expanded ? 0.8f : (dim ? 0.2f : 1.0f));
    }

    private void addBall() {
        ball = new BallView(act);
        ball.setBackground(round(0xFFEEEEEE, 22, 0xFF777777, 1));
        applyIdleAlpha();

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(ballPx, ballPx);
        act.addContentView(ball, bp);
        placeBall();

        ball.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ball.getLayoutParams();
                        downLeft = p.leftMargin;
                        downTop = p.topMargin;
                        dragging = true;
                        moved = false;
                        ball.setAlpha(0.8f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) return true;
                        int nx = downLeft + Math.round(e.getRawX() - downX);
                        int ny = downTop + Math.round(e.getRawY() - downY);
                        if (Math.abs(e.getRawX() - downX) + Math.abs(e.getRawY() - downY) > dp(8)) {
                            moved = true;
                        }
                        FrameLayout.LayoutParams mp = (FrameLayout.LayoutParams) ball.getLayoutParams();
                        mp.leftMargin = clampLeft(nx);
                        mp.topMargin = clampTop(ny);
                        mp.gravity = Gravity.LEFT | Gravity.TOP;
                        ball.setLayoutParams(mp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        FrameLayout.LayoutParams up = (FrameLayout.LayoutParams) ball.getLayoutParams();
                        curX = up.leftMargin / (float) Math.max(1, winW() - ballPx - marginPx);
                        curY = up.topMargin / (float) Math.max(1, winH() - ballPx - dp(16));
                        if (!moved) setExpanded(!expanded);
                        else persistPos();
                        applyIdleAlpha();
                        return true;
                }
                return true;
            }
        });
    }

    private void placeBall() {
        if (ball == null) return;
        ball.post(new Runnable() {
            @Override
            public void run() {
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) ball.getLayoutParams();
                p.gravity = Gravity.LEFT | Gravity.TOP;
                p.leftMargin = clampLeft(Math.round(curX * (winW() - ballPx - marginPx)));
                p.topMargin = clampTop(Math.round(curY * (winH() - ballPx - dp(16))));
                ball.setLayoutParams(p);
            }
        });
    }

    private TextView makeRow(String text, boolean current, boolean dead,
                            View.OnClickListener click) {
        TextView b = new TextView(act);
        b.setText(text);
        b.setTextColor(dead ? 0xFF888888 : (current ? 0xFF111111 : 0xFFEEEEEE));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        // 路径在 JS 侧已压成 根名/…/末层，单行尾部省略兜底极端超长的根名 / 目录名
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER_VERTICAL);
        if (current) b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setBackground(round(current ? 0xFFFFFFFF : 0xFF383838, 8, 0, 0));
        b.setOnClickListener(click);
        return b;
    }

    private void buildPanel() {
        // 透明背板：点面板外区域收起，与网页版点空白关闭行为一致
        backdrop = new View(act);
        backdrop.setVisibility(View.GONE);
        backdrop.setClickable(true);
        backdrop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setExpanded(false);
            }
        });
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        act.addContentView(backdrop, kp);

        panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(4));
        panel.setBackground(round(0xF5202020, 14, 0xFF555555, 1));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(act);
        title.setText("浏览页 · 最多 32 个");
        title.setTextColor(0xFFDDDDDD);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        head.addView(title, tlp);

        TextView add = makeRow("＋ 新建", false, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fire(ACTION_NEW, 0);
            }
        });
        head.addView(add);

        // 面板主关闭键白底黑字，与每行的白底活跃行 / 深色 × 关闭做区分
        TextView close = makeRow("×", false, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setExpanded(false);
            }
        });
        close.setBackground(round(0xFFFFFFFF, 8, 0, 0));
        close.setTextColor(0xFF111111);
        close.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        close.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(44), dp(40));
        cp.leftMargin = dp(8);
        head.addView(close, cp);
        panel.addView(head);

        // 行数多时列表区限高 55vh 可滚动，头部固定；横屏也不会溢出
        final ScrollView scroll = new ScrollView(act);
        rows = new LinearLayout(act);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        final LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panel.addView(scroll, slp);
        scroll.post(new Runnable() {
            @Override
            public void run() {
                View child = scroll.getChildAt(0);
                int cap = Math.round(winH() * 0.55f);
                if (child != null && child.getHeight() > cap) {
                    slp.height = cap;
                    scroll.setLayoutParams(slp);
                }
            }
        });

        // 面板屏幕居中
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                Math.min(dp(340), winW() - dp(24)), ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.CENTER;
        act.addContentView(panel, pp);
    }

    private void setExpanded(boolean on) {
        expanded = on;
        if (panel != null) panel.setVisibility(on ? View.VISIBLE : View.GONE);
        if (backdrop != null) backdrop.setVisibility(on ? View.VISIBLE : View.GONE);
        if (ball != null) ball.setSwapped(on);
        applyIdleAlpha();
    }

    // 视频播放位置与 MHT 滚动比例按 tree+id 哈希存 SharedPreferences
    public static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "x" + s.hashCode();
        }
    }

    public static String vposKey(String tree, String id) {
        return "vpos:" + hash(tree + "|" + id);
    }

    public static String mposKey(String tree, String id) {
        return "mpos:" + hash(tree + "|" + id);
    }

    // 自绘多任务图标：后方浅卡片 + 前方深色卡片内白色数字，几何比例与设计稿一致
    public static final class BallView extends View {
        private final Paint backFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frontFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frontStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int count = 1;
        // 面板展开时交换两卡片深 / 浅色，表示处于展开状态；数字色随前卡片底色翻转
        private boolean swapped = false;

        public BallView(Context c) {
            super(c);
            backFill.setStyle(Paint.Style.FILL);
            backStroke.setStyle(Paint.Style.STROKE);
            frontFill.setStyle(Paint.Style.FILL);
            frontStroke.setStyle(Paint.Style.STROKE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            applyPalette();
        }

        private void applyPalette() {
            // 常态：后浅前深、白字；展开：后深前浅、黑字
            backFill.setColor(swapped ? 0xFF2B2B2B : 0xFFDCDCDC);
            backStroke.setColor(swapped ? 0xFF111111 : 0xFF666666);
            frontFill.setColor(swapped ? 0xFFEDEDED : 0xFF2B2B2B);
            frontStroke.setColor(swapped ? 0xFF777777 : 0xFF111111);
            textPaint.setColor(swapped ? 0xFF111111 : 0xFFFFFFFF);
        }

        public void setCount(int n) {
            count = n;
            invalidate();
        }

        public void setSwapped(boolean s) {
            if (swapped == s) return;
            swapped = s;
            applyPalette();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            backStroke.setStrokeWidth(w * 1.6f / 44f);
            frontStroke.setStrokeWidth(w * 1.4f / 44f);
            // 后卡片
            rect.set(w * 14 / 44f, h * 9 / 44f, w * 34 / 44f, h * 24 / 44f);
            float r1 = w * 3 / 44f;
            canvas.drawRoundRect(rect, r1, r1, backFill);
            canvas.drawRoundRect(rect, r1, r1, backStroke);
            // 前卡片
            rect.set(w * 9 / 44f, h * 19 / 44f, w * 32 / 44f, h * 35 / 44f);
            float r2 = w * 3.5f / 44f;
            canvas.drawRoundRect(rect, r2, r2, frontFill);
            canvas.drawRoundRect(rect, r2, r2, frontStroke);
            // 数字
            textPaint.setTextSize(w * 11.5f / 44f);
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float cy = h * 27f / 44f - (fm.ascent + fm.descent) / 2f;
            canvas.drawText(String.valueOf(count), w * 20.5f / 44f, cy, textPaint);
        }
    }
}
