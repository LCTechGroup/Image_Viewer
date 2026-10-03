package com.lctechgroup.imgbrowser;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// MHT/MHTML 网页归档浏览页：落成本地 .mht 文件后让 WebView 按 multipart/related 归档方式加载，
// 直接把整段 MIME 文本喂给 loadDataWithBaseURL 在多数 WebView 版本不会解析内嵌分段而白屏
public class MhtActivity extends Activity {

    public static final String EX_TREE = "tree";
    public static final String EX_ID = "id";
    public static final String EX_NAME = "name";

    private WebView web;
    private TextView title;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private File localFile;
    private byte[] raw;
    private boolean stringFallbackTried = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        MainActivity.paintSystemBars(w);
        // 自己接管状态栏避让：顶栏垫真实状态栏高度，避免系统内边距与手动padding叠加
        MainActivity.setDecorFits(w, false);

        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        // 底部让出三按钮导航栏，避免网页末尾被遮挡；仅接管安全区时手动避让
        int navH = 0;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            int navRes = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            navH = navRes > 0 ? getResources().getDimensionPixelSize(navRes) : 0;
        }
        root.setPadding(0, 0, 0, navH);

        // 顶部栏：返回键 + 文件名，风格与主界面顶栏一致；顶部让出状态栏高度，避免与时间重叠
        int sbH = 0;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            int sbRes = getResources().getIdentifier("status_bar_height", "dimen", "android");
            sbH = sbRes > 0 ? getResources().getDimensionPixelSize(sbRes) : (int) (24 * density);
        }
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF141414);
        bar.setPadding(0, sbH, (int) (10 * density), 0);

        TextView back = new TextView(this);
        back.setText("\u2039");
        back.setTextColor(0xFFEEEEEE);
        back.setTextSize(28);
        back.setGravity(Gravity.CENTER);
        back.setPadding((int) (14 * density), (int) (6 * density), (int) (14 * density), (int) (8 * density));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(back, new LinearLayout.LayoutParams((int) (48 * density),
                (int) (44 * density)));

        title = new TextView(this);
        title.setTextColor(0xFFDDDDDD);
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setText(getIntent().getStringExtra(EX_NAME));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, tlp);
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        // 本地 file:// 归档必须放开文件访问，否则主文档被拦截
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(false);
        lockFileOrigin(web);
        web.getSettings().setLoadWithOverviewMode(true);
        web.getSettings().setUseWideViewPort(true);
        web.getSettings().setBuiltInZoomControls(true);
        web.getSettings().setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                // 归档内链接不跳转外站，保持纯离线浏览
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, android.webkit.WebResourceError err) {
                if (!req.isForMainFrame()) return;
                ui.post(new Runnable() {
                    public void run() {
                        if (!stringFallbackTried && raw != null) {
                            stringFallbackTried = true;
                            loadAsString();
                        }
                    }
                });
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                // 归档加载成功与否都可能走到这里，延迟探测 DOM 是否真的有内容
                ui.postDelayed(new Runnable() {
                    public void run() { verifyRendered(); }
                }, 400);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        loadArchive();
    }

    // 白屏探测：节点数和正文长度都为 0 视为没渲染出来，回退一次字符串加载；再失败给提示
    private void verifyRendered() {
        if (web == null) return;
        web.evaluateJavascript(
            "(function(){try{var b=document.body;return (b?b.innerText.length:0)+':'+document.querySelectorAll('*').length}catch(e){return '0:0'}})()",
            new android.webkit.ValueCallback<String>() {
                public void onReceiveValue(String val) {
                    boolean empty = val == null || val.contains("\"0:0\"");
                    if (!empty) return;
                    if (!stringFallbackTried && raw != null) {
                        stringFallbackTried = true;
                        loadAsString();
                    } else {
                        title.setText("无法解析该网页归档（可能不是标准 MHT）");
                    }
                }
            });
    }

    private void loadAsString() {
        try {
            String content = new String(raw, "UTF-8");
            String base = localFile != null ? Uri.fromFile(localFile).toString() : null;
            web.loadDataWithBaseURL(base, content, "multipart/related", "UTF-8", null);
        } catch (Exception e) {
            title.setText("无法解析该网页归档（可能不是标准 MHT）");
        }
    }

    private void loadArchive() {
        final String treeStr = getIntent().getStringExtra(EX_TREE);
        final String id = getIntent().getStringExtra(EX_ID);
        if (treeStr == null || id == null || id.length() == 0) {
            title.setText("无法打开：缺少文件信息");
            return;
        }
        title.setText("正在打开网页归档…");
        io.execute(new Runnable() {
            public void run() {
                InputStream is = null;
                try {
                    Uri tree = Uri.parse(treeStr);
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    is = getContentResolver().openInputStream(doc);
                    if (is == null) throw new Exception("open failed");
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                    raw = bos.toByteArray();
                    // 固定文件名覆盖写入，扩展名必须是 .mht，Chromium 据此识别为 MHTML 归档
                    File dir = new File(getCacheDir(), "mht");
                    if (!dir.exists()) dir.mkdirs();
                    localFile = new File(dir, "archive.mht");
                    FileOutputStream fos = new FileOutputStream(localFile);
                    try { fos.write(raw); } finally { fos.close(); }
                    ui.post(new Runnable() {
                        public void run() {
                            if (isFinishing() || web == null) return;
                            title.setText(getIntent().getStringExtra(EX_NAME));
                            web.loadUrl(Uri.fromFile(localFile).toString());
                        }
                    });
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            if (!isFinishing()) title.setText("无法读取该网页归档");
                        }
                    });
                } finally {
                    if (is != null) try { is.close(); } catch (Exception ignored) {}
                }
            }
        });
    }

    // 两个 file 域开关在 API30 标记过时，但 minSdk23 下仍需显式关闭做同源加固
    @SuppressWarnings("deprecation")
    private static void lockFileOrigin(WebView web) {
        web.getSettings().setAllowFileAccessFromFileURLs(false);
        web.getSettings().setAllowUniversalAccessFromFileURLs(false);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        if (web != null) {
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
