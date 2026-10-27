package com.maxplayer.app;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_DIR = 11;
    private static final int REQ_FILES = 12;
    private static final int REQ_NOTIFICATIONS = 13;

    private static MainActivity current;

    private WebView web;
    private WebViewAssetLoader assets;
    private SharedPreferences prefs;
    private final MediaLibrary library = new MediaLibrary();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // ---- вызовы из службы воспроизведения (любой поток) ----

    static void command(final String cmd) {
        final MainActivity a = current;
        if (a == null) return;
        a.js("window.__maxNative&&window.__maxNative.cmd('" + cmd + "')");
    }

    static void quit() {
        final MainActivity a = current;
        if (a == null) return;
        a.runOnUiThread(new Runnable() {
            @Override public void run() { a.finishAndRemoveTask(); }
        });
    }

    // ---- жизненный цикл ----

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        current = this;
        prefs = getSharedPreferences("max_player", MODE_PRIVATE);

        assets = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        web.addJavascriptInterface(new Bridge(), "MaxNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) {
                    String p = u.getPath();
                    if (p != null) {
                        if (p.startsWith("/media/")) {
                            try {
                                return serveMedia(request, Integer.parseInt(p.substring(7)));
                            } catch (NumberFormatException e) {
                                return plain(404, "Not Found");
                            }
                        }
                        if ("/library.json".equals(p)) return libraryJson();
                    }
                }
                return assets.shouldInterceptRequest(u);
            }
        });

        boolean dark = isDark();
        applyBars(dark);
        setContentView(web);
        web.loadUrl("https://" + HOST + "/assets/index.html?theme=" + (dark ? "dark" : "light"));

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        boolean dark = isDark();
        applyBars(dark);
        js("document.documentElement.setAttribute('data-theme','" + (dark ? "dark" : "light") + "')");
    }

    @Override
    public void onBackPressed() {
        // Сначала закрываем окно эквалайзера, иначе прячем приложение и оставляем музыку играть.
        web.evaluateJavascript("(window.__maxNative&&window.__maxNative.back())===true", new android.webkit.ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"true".equals(value)) moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        io.shutdownNow();
        stopService(new Intent(this, PlaybackService.class));
        if (web != null) {
            web.removeJavascriptInterface("MaxNative");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    // ---- оформление ----

    private boolean isDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyBars(boolean dark) {
        int bg = dark ? 0xFF0D1117 : 0xFFE5E8EC;
        Window w = getWindow();
        w.setStatusBarColor(bg);
        w.setNavigationBarColor(bg);
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark ? (flags & ~light) : (flags | light));
        if (web != null) web.setBackgroundColor(bg);
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    // ---- выбор папки и файлов ----

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_DIR) {
            Uri tree = data.getData();
            if (tree == null) return;
            try {
                getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
                // без постоянного доступа папка просто не запомнится
            }
            prefs.edit().putString("tree", tree.toString()).apply();
            scanTree(tree, true);
        } else if (req == REQ_FILES) {
            final List<Uri> picked = new ArrayList<>();
            ClipData clip = data.getClipData();
            if (clip != null) {
                for (int i = 0; i < clip.getItemCount(); i++) picked.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (picked.isEmpty()) return;
            js("window.__maxNative&&window.__maxNative.scanning()");
            io.execute(new Runnable() {
                @Override
                public void run() {
                    List<MediaLibrary.Item> items = new ArrayList<>();
                    for (Uri u : picked) {
                        MediaLibrary.Item it = describe(u);
                        if (it != null) items.add(it);
                    }
                    library.set(new MediaLibrary.Scan("", items));
                    js("window.__maxNative&&window.__maxNative.libraryReady(true)");
                }
            });
        }
    }

    private MediaLibrary.Item describe(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE }, null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                long size = c.isNull(1) ? 0 : c.getLong(1);
                if (name != null) return new MediaLibrary.Item(uri, name, "", size);
            }
        } catch (Exception ignored) {
            // пропускаем файл, который не удалось описать
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private void scanTree(final Uri tree, final boolean announce) {
        if (announce) js("window.__maxNative&&window.__maxNative.scanning()");
        io.execute(new Runnable() {
            @Override
            public void run() {
                MediaLibrary.Scan scan;
                try {
                    scan = MediaLibrary.scanTree(getContentResolver(), tree);
                } catch (Exception e) {
                    scan = new MediaLibrary.Scan("", new ArrayList<MediaLibrary.Item>());
                }
                library.set(scan);
                js("window.__maxNative&&window.__maxNative.libraryReady(" + announce + ")");
            }
        });
    }

    // ---- отдача файлов странице ----

    private WebResourceResponse libraryJson() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        byte[] body = library.toJson().getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse("application/json", "UTF-8", 200, "OK", h, new ByteArrayInputStream(body));
    }

    private WebResourceResponse plain(int code, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", code, reason,
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (Exception ignored) {
            // уже закрыт
        }
    }

    /** Отдаёт трек с поддержкой Range, чтобы работала перемотка. */
    private WebResourceResponse serveMedia(WebResourceRequest req, int id) {
        MediaLibrary.Item item = library.get(id);
        if (item == null) return plain(404, "Not Found");

        ParcelFileDescriptor pfd = null;
        try {
            pfd = getContentResolver().openFileDescriptor(item.uri, "r");
            if (pfd == null) return plain(404, "Not Found");

            long size = pfd.getStatSize();
            if (size <= 0) size = item.size;

            long[] range = RangeUtil.parse(header(req.getRequestHeaders(), "Range"), size);
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Cache-Control", "no-store");

            if (range == RangeUtil.UNSATISFIABLE) {
                closeQuietly(pfd);
                h.put("Content-Range", "bytes */" + size);
                return new WebResourceResponse("text/plain", "UTF-8", 416, "Range Not Satisfiable", h,
                        new ByteArrayInputStream(new byte[0]));
            }

            long start = 0;
            long length = size > 0 ? size : -1;
            int status = 200;
            String reason = "OK";
            if (range != null) {
                start = range[0];
                length = range[1] - range[0] + 1;
                status = 206;
                reason = "Partial Content";
                h.put("Content-Range", "bytes " + range[0] + "-" + range[1] + "/" + size);
            }
            if (length >= 0) h.put("Content-Length", String.valueOf(length));

            InputStream body = new FileRangeStream(pfd, start, length);
            pfd = null; // дальше им владеет поток
            return new WebResourceResponse(MediaLibrary.mimeFor(item.name), null, status, reason, h, body);
        } catch (Exception e) {
            closeQuietly(pfd);
            return plain(404, "Not Found");
        }
    }

    // ---- мост для страницы ----

    private final class Bridge {
        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                    startActivityForResult(i, REQ_DIR);
                }
            });
        }

        @JavascriptInterface
        public void pickFiles() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("audio/*");
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(i, REQ_FILES);
                }
            });
        }

        /** Страница просит заново открыть папку, выбранную в прошлый раз. */
        @JavascriptInterface
        public void restore() {
            String saved = prefs.getString("tree", null);
            if (saved == null) return;
            Uri tree = Uri.parse(saved);
            boolean allowed = false;
            for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
                if (p.getUri().equals(tree) && p.isReadPermission()) allowed = true;
            }
            if (!allowed) {
                prefs.edit().remove("tree").apply();
                return;
            }
            scanTree(tree, false);
        }

        @JavascriptInterface
        public void state(final boolean playing, final String title, final String artist,
                          double posSec, double durSec) {
            final long pos = (long) (posSec * 1000);
            final long dur = (long) (durSec * 1000);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    PlaybackService.update(getApplicationContext(), playing, title, artist, pos, dur);
                }
            });
        }
    }
}
