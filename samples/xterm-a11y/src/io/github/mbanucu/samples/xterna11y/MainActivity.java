package io.github.mbanucu.samples.xterna11y;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * xterm.js in a WebView, with xterm's own screen-reader mode switchable, so the
 * accessibility tree the WebView publishes can be compared for both settings.
 *
 * <pre>
 * am start -n io.github.mbanucu.samples.xterna11y/.MainActivity --ez sr true
 * </pre>
 *
 * The page prints a fixed screen once and then one new line a second, which is
 * what a terminal looks like to a service: text that arrives while nobody is
 * touching the screen.
 */
public final class MainActivity extends Activity {

    private static final String URL = "file:///android_asset/terminal.html";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        boolean screenReader = intent != null && intent.getBooleanExtra("sr", false);
        int font = intent == null ? 0 : intent.getIntExtra("font", 0);

        WebView web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);

        StringBuilder url = new StringBuilder(URL).append("?sr=").append(screenReader ? 1 : 0);
        if (font > 0) {
            url.append("&font=").append(font);
        }
        web.loadUrl(url.toString());
    }

    /** The page's reports, so a run can be checked without a screenshot. */
    private final class Bridge {

        @JavascriptInterface
        public void onScreen(String text) {
            write("screen.txt", text);
        }

        @JavascriptInterface
        public void onLog(String line) {
            write("events.log", line + "\n");
        }
    }

    private void write(String name, String body) {
        File file = new File(getExternalFilesDir(null), name);
        boolean append = "events.log".equals(name);
        try (FileOutputStream out = new FileOutputStream(file, append)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // a sample has nothing better to do here
        }
    }
}
