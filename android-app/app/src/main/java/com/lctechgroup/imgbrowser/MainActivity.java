package com.lctechgroup.imgbrowser;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import android.content.res.AssetFileDescriptor;

public class MainActivity extends Activity {

    private static final int REQ_TREE = 1001;
    private static final String HOST = "appimg.local";
    private static final String DIR_MIME = "vnd.android.document/directory";

    private WebView web;
    private SharedPreferences prefs;
    private Uri treeUri;
    private volatile boolean viewerOn = false;
    // JS 侧实时上报：当前界面按返回是否还留在 App 内
    private volatile boolean backable = false;
    private static final String K_RECORDS = "records";
    private static final int MAX_RECORDS = 20;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    // 视频封面抓取：独立线程池，与目录遍历隔离，单个视频卡住不堵其他
    private final ExecutorService thumbPool = Executors.newFixedThreadPool(2);
    private final java.util.concurrent.ScheduledExecutorService thumbTimer =
            Executors.newSingleThreadScheduledExecutor();
    // 目录树并行扫描池：深树（每话一个子文件夹）时多个 SAF 查询并发，4 路为 binder 甜点
    private final ExecutorService scanPool = Executors.newFixedThreadPool(4);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("app", MODE_PRIVATE);

        Window w = getWindow();
        // API35 起标记过时，但 minSdk23 下无全版本替代，仍需用它把系统栏着成黑色
        paintSystemBars(w);
        // 安卓15 + targetSdk35 默认强制 edge-to-edge，直接关闭，由系统为状态栏/导航栏让出空间
        setDecorFits(w, true);

        web = new WebView(this);
        WebSettings st = web.getSettings();
        st.setJavaScriptEnabled(true);
        st.setDomStorageEnabled(true);
        st.setLoadWithOverviewMode(true);
        st.setUseWideViewPort(true);
        st.setAllowFileAccess(false);
        st.setAllowContentAccess(false);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    return serveMedia(u, request.getRequestHeaders());
                }
                return null;
            }
        });

        setContentView(web);

        String saved = prefs.getString("treeUri", null);
        if (saved != null) {
            treeUri = Uri.parse(saved);
        }
        web.loadUrl("file:///android_asset/index.html");
    }

    // 用 openFileDescriptor 的 statSize 拿真实大小，比 openAssetFileDescriptor 可靠
    private long queryDocSize(Uri doc) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = getContentResolver().openFileDescriptor(doc, "r");
            if (pfd != null) {
                long s = pfd.getStatSize();
                if (s > 0) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
        Cursor c = null;
        try {
            c = getContentResolver().query(doc, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return -1;
    }

    // 虚拟主机媒体请求：按文档 id 从 SAF 取流，支持视频拖动所需的 Range(206)
    private WebResourceResponse serveMedia(Uri u, Map<String, String> reqHeaders) {
        Uri tree = treeUri;
        String id = u.getQueryParameter("d");
        if (tree == null || id == null || id.length() == 0) {
            return emptyResponse();
        }
        AssetFileDescriptor afd = null;
        try {
            final Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            String mime = getContentResolver().getType(doc);
            if (mime == null || mime.length() == 0 || "application/octet-stream".equals(mime)
                    || "content/unknown".equals(mime)) {
                String n = u.getQueryParameter("n");
                String gm = guessMime(n == null ? "" : n);
                if (gm != null) {
                    mime = gm;
                }
            }
            if (mime == null) {
                mime = "application/octet-stream";
            }
            // 直接以普通文件方式打开，base=0，再取一次真实长度
            long total = queryDocSize(doc);
            afd = getContentResolver().openAssetFileDescriptor(doc, "r");
            if (afd == null) {
                return emptyResponse();
            }
            long declared = afd.getDeclaredLength();
            if (declared > 0) {
                total = declared;
            }
            final long baseOff = afd.getStartOffset();
            final long totalLen = total;
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept-Ranges", "bytes");
            // 允许 file:// 页面把视频帧画入 canvas 生成封面
            headers.put("Access-Control-Allow-Origin", "*");
            // 视频分片禁止缓存（拖进度必须重新请求 Range）；图片允许 WebView 缓存，
            // 网格来回滚动时直接命中缓存，不再重开 SAF 描述符重复解码原图
            headers.put("Cache-Control",
                    mime != null && mime.startsWith("video/") ? "no-store" : "max-age=604800");
            FileInputStream fis = new FileInputStream(afd.getFileDescriptor());
            String range = reqHeaders != null ? reqHeaders.get("Range") : null;
            if (range == null) {
                range = reqHeaders != null ? reqHeaders.get("range") : null;
            }
            // 没有可靠长度或无 Range：从 0 给流，交给内核处理
            if (totalLen <= 0 || range == null || !range.startsWith("bytes=")) {
                fis.getChannel().position(baseOff);
                return new WebResourceResponse(mime, null, 200, "OK", headers,
                        new BoundedInputStream(fis, totalLen > 0 ? totalLen : Long.MAX_VALUE, afd));
            }
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            long start;
            long end = totalLen - 1;
            if (dash == 0) {
                long suffix = Long.parseLong(spec.substring(1).trim());
                start = Math.max(0, totalLen - suffix);
            } else {
                start = Long.parseLong(spec.substring(0, dash).trim());
                String tail = dash + 1 < spec.length() ? spec.substring(dash + 1).trim() : "";
                if (tail.length() > 0) {
                    long e2 = Long.parseLong(tail);
                    if (e2 < end) {
                        end = e2;
                    }
                }
            }
            if (start > end || start >= totalLen) {
                Map<String, String> rh = new HashMap<>();
                rh.put("Content-Range", "bytes */" + totalLen);
                rh.put("Access-Control-Allow-Origin", "*");
                fis.close();
                afd.close();
                return new WebResourceResponse(null, null, 416, "Requested Range Not Satisfiable", rh,
                        new ByteArrayInputStream(new byte[0]));
            }
            long len = end - start + 1;
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + totalLen);
            fis.getChannel().position(baseOff + start);
            return new WebResourceResponse(mime, null, 206, "Partial Content", headers,
                    new BoundedInputStream(fis, len, afd));
        } catch (Exception e) {
            if (afd != null) {
                try {
                    afd.close();
                } catch (Exception ignored) {
                }
            }
            return emptyResponse();
        }
    }

    // 限制读取字节数，读完自动关闭底层描述符
    private static final class BoundedInputStream extends FilterInputStream {
        private long left;
        private final AssetFileDescriptor afd;
        private BoundedInputStream(InputStream in, long limit, AssetFileDescriptor fd) {
            super(in);
            left = limit;
            afd = fd;
        }
        @Override
        public void close() throws IOException {
            super.close();
            if (afd != null) {
                afd.close();
            }
        }
        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int r = super.read();
            if (r >= 0) {
                left--;
            }
            if (r < 0 || left == 0) {
                close();
            }
            return r;
        }
        @Override
        public int read(byte[] b, int off, int cnt) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(cnt, left));
            if (n > 0) {
                left -= n;
                if (left == 0) {
                    close();
                }
            }
            return n;
        }
    }

    private static String guessMime(String n) {
        String s = n.toLowerCase();
        if (s.endsWith(".mp4") || s.endsWith(".m4v")) {
            return "video/mp4";
        }
        if (s.endsWith(".webm")) {
            return "video/webm";
        }
        if (s.endsWith(".mkv")) {
            return "video/x-matroska";
        }
        if (s.endsWith(".mov")) {
            return "video/quicktime";
        }
        if (s.endsWith(".3gp")) {
            return "video/3gpp";
        }
        if (s.endsWith(".ts")) {
            return "video/mp2t";
        }
        return URLConnection.guessContentTypeFromName(n);
    }

    private WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    // evaluateJavascript 必须在主线程调用
    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    private void startTreePicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_TREE);
        } catch (Exception e) {
            js("window.__onTreeError&&window.__onTreeError(" + JSONObject.quote(e.toString()) + ")");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_TREE) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            js("window.__onTreeCancel&&window.__onTreeCancel()");
            return;
        }
        Uri uri = data.getData();
        try {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            treeUri = uri;
            prefs.edit().putString("treeUri", uri.toString()).apply();
            scanTree(uri);
        } catch (Exception e) {
            js("window.__onTreeError&&window.__onTreeError(" + JSONObject.quote(e.toString()) + ")");
        }
    }

    // 新选目录 / 手动刷新：显 loading 全量扫描，成功后覆盖树缓存
    private void scanTree(final Uri tree) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                runFullScan(tree, false);
            }
        });
    }

    // 历史重开：缓存命中则先秒进，再后台静默全量重扫；无缓存走正常 loading 扫描
    private void scanTreeCachedFirst(final Uri tree) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                String cached = readTreeCache(tree);
                if (cached != null) {
                    js("window.__onTreeCached&&window.__onTreeCached(" + cached + ")");
                    runFullScan(tree, true);
                } else {
                    runFullScan(tree, false);
                }
            }
        });
    }

    // 全量扫描：4 路并行查询子目录，Phaser 协调；background 为静默模式（不报错、走更新回调）
    private void runFullScan(Uri tree, boolean background) {
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            String rootName = queryName(tree, rootId);
            // 根文档查不到：文件夹已被删除或存储未挂载（授权记录可能仍在，不能再兜底成空树）
            if (rootName == null || rootName.length() == 0) {
                String uriJson = JSONObject.quote(tree.toString());
                if (background) {
                    js("window.__onTreeGone&&window.__onTreeGone(" + uriJson + ")");
                } else {
                    js("window.__onTreeError&&window.__onTreeError("
                            + JSONObject.quote("文件夹不存在或无法访问") + "," + uriJson + ")");
                }
                return;
            }
            prefs.edit().putString("treeName", rootName).apply();
            addRecord(tree.toString(), rootName);

            final JSONObject root = new JSONObject();
            root.put("name", rootName);
            root.put("path", "");
            root.put("uri", tree.toString());
            root.put("dirs", new JSONArray());
            root.put("files", new JSONArray());

            final java.util.concurrent.atomic.AtomicInteger count =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            // 进度回调 500ms 节流，避免上万项时上百次 JS 桥往返
            final java.util.concurrent.atomic.AtomicLong lastUi =
                    new java.util.concurrent.atomic.AtomicLong(0L);
            final java.util.concurrent.atomic.AtomicReference<Exception> firstErr =
                    new java.util.concurrent.atomic.AtomicReference<Exception>(null);
            final java.util.concurrent.Phaser phaser = new java.util.concurrent.Phaser(1);
            phaser.register();
            scanPool.execute(new DirTask(tree, rootId, root, "", phaser, count, lastUi, firstErr));
            phaser.arriveAndAwaitAdvance();

            Exception err = firstErr.get();
            if (err != null) {
                if (!background) {
                    js("window.__onTreeError&&window.__onTreeError(" + JSONObject.quote(err.toString()) + ")");
                }
                return;
            }

            String json = root.toString()
                    .replace("\u2028", "\\u2028")
                    .replace("\u2029", "\\u2029");
            writeTreeCache(tree, json);
            String cb = background
                    ? "window.__onTreeUpdate&&window.__onTreeUpdate("
                    : "window.__onTree&&window.__onTree(";
            js(cb + json + ")");
        } catch (Exception e) {
            // 前台加载失败显式报错并带 uri 供标记失效；后台瞬时异常静默，不打扰也不误标
            if (!background) {
                js("window.__onTreeError&&window.__onTreeError("
                        + JSONObject.quote(e.toString()) + ","
                        + JSONObject.quote(tree.toString()) + ")");
            }
        }
    }

    // 单个目录的扫描任务：结束必须 arriveAndDeregister
    private final class DirTask implements Runnable {
        private final Uri tree;
        private final String docId;
        private final JSONObject node;
        private final String path;
        private final java.util.concurrent.Phaser phaser;
        private final java.util.concurrent.atomic.AtomicInteger count;
        private final java.util.concurrent.atomic.AtomicLong lastUi;
        private final java.util.concurrent.atomic.AtomicReference<Exception> firstErr;

        DirTask(Uri tree, String docId, JSONObject node, String path,
                java.util.concurrent.Phaser phaser,
                java.util.concurrent.atomic.AtomicInteger count,
                java.util.concurrent.atomic.AtomicLong lastUi,
                java.util.concurrent.atomic.AtomicReference<Exception> firstErr) {
            this.tree = tree;
            this.docId = docId;
            this.node = node;
            this.path = path;
            this.phaser = phaser;
            this.count = count;
            this.lastUi = lastUi;
            this.firstErr = firstErr;
        }

        @Override
        public void run() {
            try {
                walkOnce();
            } finally {
                phaser.arriveAndDeregister();
            }
        }

        // 只查一层目录：子目录先按 query 顺序登记进父节点，游标关闭后再提交并行任务
        private void walkOnce() {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
            Cursor c = null;
            try {
                c = getContentResolver().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                }, null, null, null);
                if (c == null) {
                    return;
                }
                java.util.ArrayList<DirTask> pending = null;
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    String mime = c.getString(2);
                    long modified = c.isNull(3) ? 0L : c.getLong(3);
                    if (id == null || name == null) {
                        continue;
                    }
                    String childPath = path + "/" + name;
                    if (DIR_MIME.equals(mime)) {
                        JSONObject d = new JSONObject();
                        d.put("name", name);
                        d.put("path", childPath);
                        d.put("dirs", new JSONArray());
                        d.put("files", new JSONArray());
                        // 父节点数组只由本任务写入，保持 query 顺序；子任务稍后只填充 d 内部
                        node.getJSONArray("dirs").put(d);
                        if (pending == null) {
                            pending = new java.util.ArrayList<DirTask>();
                        }
                        pending.add(new DirTask(tree, id, d, childPath, phaser, count, lastUi, firstErr));
                    } else {
                        JSONObject f = new JSONObject();
                        f.put("name", name);
                        f.put("path", childPath);
                        f.put("id", (isImage(name) || isVideo(name) || isMht(name) || isTxt(name)) ? id : "");
                        if (modified != 0L) {
                            f.put("m", modified);
                        }
                        node.getJSONArray("files").put(f);
                    }
                    int n = count.incrementAndGet();
                    if ((n & 255) == 0) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        long prev = lastUi.get();
                        if (now - prev >= 500 && lastUi.compareAndSet(prev, now)) {
                            final int fn = n;
                            js("window.__onWalkProgress&&window.__onWalkProgress(" + fn + ")");
                        }
                    }
                }
                c.close();
                c = null;
                if (pending != null) {
                    for (DirTask t : pending) {
                        // register 必须先于 execute，杜绝任务先 arrive 导致 Phaser 提前放行
                        phaser.register();
                        scanPool.execute(t);
                    }
                }
            } catch (Exception e) {
                firstErr.compareAndSet(null, e);
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }
    }

    // ============ 目录树索引缓存（filesDir/treecache，与历史记录一一对应） ============

    private java.io.File treeCacheFile(Uri tree) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(tree.toString().getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xff));
            }
            java.io.File dir = new java.io.File(getFilesDir(), "treecache");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            return new java.io.File(dir, sb.toString() + ".json");
        } catch (Exception e) {
            return null;
        }
    }

    private String readTreeCache(Uri tree) {
        java.io.FileInputStream fis = null;
        try {
            java.io.File f = treeCacheFile(tree);
            if (f == null || !f.isFile() || f.length() == 0) {
                return null;
            }
            int max = 64 * 1024 * 1024;
            int len = (int) Math.min(f.length(), max);
            byte[] b = new byte[len];
            fis = new java.io.FileInputStream(f);
            int off = 0, r;
            while (off < len && (r = fis.read(b, off, len - off)) > 0) {
                off += r;
            }
            return off > 0 ? new String(b, 0, off, "UTF-8") : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void writeTreeCache(Uri tree, String json) {
        java.io.FileOutputStream fos = null;
        try {
            java.io.File f = treeCacheFile(tree);
            if (f == null) {
                return;
            }
            fos = new java.io.FileOutputStream(f);
            fos.write(json.getBytes("UTF-8"));
        } catch (Exception ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private String queryName(Uri tree, String docId) {
        Uri self = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = getContentResolver().query(self,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null && c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    private static boolean isImage(String n) {
        String s = n.toLowerCase();
        return s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".jfif")
                || s.endsWith(".png") || s.endsWith(".gif") || s.endsWith(".webp")
                || s.endsWith(".bmp") || s.endsWith(".avif") || s.endsWith(".svg");
    }

    private static boolean isVideo(String n) {
        String s = n.toLowerCase();
        return s.endsWith(".mp4") || s.endsWith(".m4v") || s.endsWith(".webm")
                || s.endsWith(".mkv") || s.endsWith(".mov") || s.endsWith(".3gp") || s.endsWith(".ts");
    }

    private static boolean isMht(String n) {
        String s = n.toLowerCase();
        return s.endsWith(".mht") || s.endsWith(".mhtml");
    }

    private static boolean isTxt(String n) {
        return n.toLowerCase().endsWith(".txt");
    }

    // ============ 文件夹授权记录（最新在前） ============
    private JSONArray recordsJson() {
        try {
            return new JSONArray(prefs.getString(K_RECORDS, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void addRecord(String uri, String name) {
        try {
            JSONArray old = recordsJson();
            JSONArray out = new JSONArray();
            JSONObject first = new JSONObject();
            first.put("uri", uri);
            first.put("name", name);
            out.put(first);
            for (int i = 0; i < old.length() && out.length() < MAX_RECORDS; i++) {
                JSONObject r = old.optJSONObject(i);
                if (r != null && !uri.equals(r.optString("uri"))) {
                    out.put(r);
                }
            }
            prefs.edit()
                    .putString(K_RECORDS, out.toString())
                    .putString("treeUri", uri)
                    .putString("treeName", name)
                    .apply();
        } catch (Exception ignored) {
        }
    }

    // 删除记录并尝试释放持久授权；返回删的是不是当前正在看的文件夹
    private boolean removeRecord(String uri) {
        JSONArray old = recordsJson();
        JSONArray out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject r = old.optJSONObject(i);
            if (r != null && !uri.equals(r.optString("uri"))) {
                out.put(r);
            }
        }
        prefs.edit().putString(K_RECORDS, out.toString()).apply();
        // 与历史记录一一对应的目录树索引一并删除
        java.io.File cf = treeCacheFile(Uri.parse(uri));
        if (cf != null && cf.isFile()) {
            cf.delete();
        }
        try {
            getContentResolver().releasePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        boolean current = treeUri != null && uri.equals(treeUri.toString());
        if (current) {
            treeUri = null;
            prefs.edit().remove("treeUri").remove("treeName").apply();
        }
        return current;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (viewerOn && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                // 音量减前进一页，音量加后退一页；rl 方向由 JS 内部处理
                int dir = code == KeyEvent.KEYCODE_VOLUME_DOWN ? 1 : -1;
                web.evaluateJavascript("window.readerGo&&readerGo(" + dir + ")", null);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // 返回逻辑完全由 JS 按当前界面决定；backable=false 时直接退出 App
        if (backable) {
            web.evaluateJavascript("window.appBack&&appBack()", null);
        } else {
            super.onBackPressed();
        }
    }

    // 系统栏着色：Window.setStatusBarColor/setNavigationBarColor 在 API35 标记过时，
    // 但 minSdk23 下无全版本替代 API，集中在此显式抑制
    @SuppressWarnings("deprecation")
    static void paintSystemBars(Window w) {
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
    }

    // setDecorFitsSystemWindows 在 API35 标记过时（新 edge-to-edge API 需 30+），minSdk23 仍需它
    @SuppressWarnings("deprecation")
    static void setDecorFits(Window w, boolean fit) {
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(fit);
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        scanPool.shutdownNow();
        thumbPool.shutdownNow();
        thumbTimer.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    private final class Bridge {

        @JavascriptInterface
        public void pickDirectory() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    startTreePicker();
                }
            });
        }

        @JavascriptInterface
        public String getRecords() {
            return prefs.getString(K_RECORDS, "[]");
        }

        @JavascriptInterface
        public boolean hasSavedTree() {
            JSONArray a = recordsJson();
            if (a.length() == 0) {
                return false;
            }
            JSONObject r = a.optJSONObject(0);
            String s = r != null ? r.optString("uri") : null;
            if (s == null || s.length() == 0) {
                return false;
            }
            List<UriPermission> ps = getContentResolver().getPersistedUriPermissions();
            for (UriPermission p : ps) {
                if (s.equals(p.getUri().toString()) && p.isReadPermission()) {
                    return true;
                }
            }
            return false;
        }

        // 指定 uri 的持久读授权是否仍在，供主页标记失效历史
        @JavascriptInterface
        public boolean uriAlive(String uri) {
            if (uri == null || uri.length() == 0) {
                return false;
            }
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (uri.equals(p.getUri().toString()) && p.isReadPermission()) {
                    return true;
                }
            }
            return false;
        }

        @JavascriptInterface
        public String rootName() {
            JSONArray a = recordsJson();
            JSONObject r = a.optJSONObject(0);
            return r != null ? r.optString("name", "上次的文件夹") : "上次的文件夹";
        }

        @JavascriptInterface
        public void loadSaved() {
            JSONArray a = recordsJson();
            JSONObject r = a.optJSONObject(0);
            String s = r != null ? r.optString("uri") : null;
            if (s == null || s.length() == 0) {
                js("window.__onTreeError&&window.__onTreeError('无已保存文件夹')");
                return;
            }
            treeUri = Uri.parse(s);
            scanTreeCachedFirst(treeUri);
        }

        // 手动刷新：重新扫描当前根目录
        @JavascriptInterface
        public void reloadTree() {
            if (treeUri == null) {
                js("window.__onTreeError&&window.__onTreeError('无文件夹')");
                return;
            }
            scanTree(treeUri);
        }

        // 最近一条历史记录的根 uri，供 JS 判断当前树是否已加载而无需重新扫描
        @JavascriptInterface
        public String latestUri() {
            JSONArray a = recordsJson();
            return a.length() > 0 ? a.optJSONObject(0).optString("uri") : "";
        }

        @JavascriptInterface
        public void loadRecord(String uri) {
            if (uri == null || uri.length() == 0) {
                return;
            }
            treeUri = Uri.parse(uri);
            scanTreeCachedFirst(treeUri);
        }

        @JavascriptInterface
        public boolean deleteRecord(String uri) {
            return removeRecord(uri);
        }

        @JavascriptInterface
        public void setBackable(boolean can) {
            backable = can;
        }

        // 清 WebView 磁盘缓存（包括虚拟主机图片的缓存响应）
        @JavascriptInterface
        public void clearCache() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    web.clearCache(true);
                }
            });
            // MHT 离线网页浏览时落在 cache/mht 的临时归档一并清掉
            io.execute(new Runnable() {
                @Override
                public void run() {
                    java.io.File mhtDir = new java.io.File(getCacheDir(), "mht");
                    if (mhtDir.isDirectory()) {
                        java.io.File[] fs = mhtDir.listFiles();
                        if (fs != null) {
                            for (java.io.File f : fs) {
                                f.delete();
                            }
                        }
                    }
                }
            });
        }

        // 清目录树索引缓存（filesDir/treecache），与历史记录一一对应；索引在扫描优化启用后写入
        @JavascriptInterface
        public void clearTreeCache() {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    java.io.File dir = new java.io.File(getFilesDir(), "treecache");
                    if (dir.isDirectory()) {
                        java.io.File[] fs = dir.listFiles();
                        if (fs != null) {
                            for (java.io.File f : fs) {
                                f.delete();
                            }
                        }
                    }
                }
            });
        }

        // 清阅读器个性设置（视频长按倍速、循环单次）；其余设置在 JS localStorage
        @JavascriptInterface
        public void clearReaderPrefs() {
            prefs.edit().remove("vpLoop").remove("vpHoldSpeed").apply();
        }

        // 异步抓视频首帧，立即返回；完成或超时后通过 window.__onThumb 回调，绝不阻塞 JS
        @JavascriptInterface
        public void requestVideoThumb(final String id, final String token) {
            if (id == null || id.length() == 0) {
                js("window.__onThumb&&window.__onThumb(" + JSONObject.quote(token) + ",'')");
                return;
            }
            final Uri tree = treeUri;
            if (tree == null) {
                js("window.__onThumb&&window.__onThumb(" + JSONObject.quote(token) + ",'')");
                return;
            }
            final java.util.concurrent.atomic.AtomicBoolean done =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            thumbPool.execute(new Runnable() {
                @Override
                public void run() {
                    String out = "";
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    try {
                        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                        r.setDataSource(MainActivity.this, doc);
                        Bitmap bmp = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (bmp == null) {
                            bmp = r.getFrameAtTime(200000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        }
                        if (bmp == null) {
                            // 视频内嵌封面图（部分 MP4 带，无需解码视频帧）
                            byte[] embedded = r.getEmbeddedPicture();
                            if (embedded != null && embedded.length > 0) {
                                bmp = android.graphics.BitmapFactory.decodeByteArray(embedded, 0, embedded.length);
                            }
                        }
                        if (bmp != null) {
                            int w = Math.min(360, bmp.getWidth());
                            int h = (int) ((long) bmp.getHeight() * w / bmp.getWidth());
                            Bitmap scaled = bmp;
                            if (w != bmp.getWidth()) {
                                scaled = Bitmap.createScaledBitmap(bmp, w, h, true);
                            }
                            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                            scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos);
                            out = "data:image/jpeg;base64," + android.util.Base64.encodeToString(
                                    bos.toByteArray(), android.util.Base64.NO_WRAP);
                            if (scaled != bmp) {
                                scaled.recycle();
                            }
                            bmp.recycle();
                        }
                    } catch (Exception e) {
                        out = "";
                    } finally {
                        try {
                            r.release();
                        } catch (Exception ignored) {
                        }
                    }
                    if (done.compareAndSet(false, true)) {
                        final String result = out;
                        js("window.__onThumb&&window.__onThumb(" + JSONObject.quote(token) + ","
                                + JSONObject.quote(result) + ")");
                    }
                }
            });
            // 6 秒抓不到就让前端放弃；底层任务随后自然结束，不影响其他封面
            thumbTimer.schedule(new Runnable() {
                @Override
                public void run() {
                    if (done.compareAndSet(false, true)) {
                        js("window.__onThumb&&window.__onThumb(" + JSONObject.quote(token) + ",'')");
                    }
                }
            }, 6, TimeUnit.SECONDS);
        }

        // 拉起原生 ExoPlayer 播放页；ids/names 为同文件夹视频列表，index 为当前项
        @JavascriptInterface
        public void playVideo(String id, String name, float speed, float hold,
                              String[] ids, String[] names, int index) {
            if (id == null || id.length() == 0 || treeUri == null) {
                return;
            }
            Intent in = new Intent(MainActivity.this, VideoActivity.class);
            in.putExtra(VideoActivity.EX_TREE, treeUri.toString());
            in.putExtra(VideoActivity.EX_ID, id);
            in.putExtra(VideoActivity.EX_NAME, name == null ? "" : name);
            in.putExtra(VideoActivity.EX_SPEED, speed);
            in.putExtra(VideoActivity.EX_HOLD, hold);
            if (ids != null && ids.length > 1) {
                java.util.ArrayList<String> idList = new java.util.ArrayList<>(java.util.Arrays.asList(ids));
                java.util.ArrayList<String> nameList = names != null
                        ? new java.util.ArrayList<>(java.util.Arrays.asList(names))
                        : new java.util.ArrayList<String>();
                in.putStringArrayListExtra(VideoActivity.EX_LIST, idList);
                in.putStringArrayListExtra(VideoActivity.EX_LIST + "n", nameList);
                in.putExtra(VideoActivity.EX_INDEX, index);
            }
            startActivity(in);
        }

        // 拉起 MHT/MHTML 网页归档浏览页
        @JavascriptInterface
        public void openMht(String id, String name) {
            if (id == null || id.length() == 0 || treeUri == null) {
                return;
            }
            Intent in = new Intent(MainActivity.this, MhtActivity.class);
            in.putExtra(MhtActivity.EX_TREE, treeUri.toString());
            in.putExtra(MhtActivity.EX_ID, id);
            in.putExtra(MhtActivity.EX_NAME, name == null ? "" : name);
            startActivity(in);
        }

        @JavascriptInterface
        public String vurl(String id, String name) {
            try {
                return "https://" + HOST + "/i?d=" + URLEncoder.encode(id, "UTF-8")
                        + "&n=" + URLEncoder.encode(name == null ? "" : name, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void setViewer(final boolean on) {
            viewerOn = on;
            // 离开阅读器时兜底清掉屏幕常亮，防止标志残留
            if (!on) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                });
            }
        }

        // 图片阅读器屏幕常亮开关
        @JavascriptInterface
        public void setKeepScreen(final boolean on) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Window w = getWindow();
                    if (on) {
                        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } else {
                        w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    }
                }
            });
        }

        // 阅读器内横屏锁定：1 锁横屏，0 恢复跟随系统
        @JavascriptInterface
        public void setOrient(final int o) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    setRequestedOrientation(o == 1
                            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                }
            });
        }
    }
}
