package app.keeper.wallet;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.EnumMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String HOST = "appassets.androidplatform.net";
    private WebView web;
    private SharedPreferences prefs;
    private WebViewAssetLoader loader;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("keeper", MODE_PRIVATE);

        loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF10151F);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u.toString());
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript("(window.__back?window.__back():false)", v -> {
                    if (!"true".equals(v)) moveTaskToBack(true);
                });
            }
        });

        web.loadUrl("https://" + HOST + "/assets/index.html");
    }

    private void js(final String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private void openExternal(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ JS bridge
    public class Bridge {

        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("serverUrl", prefs.getString("server", BuildConfig.SERVER_URL));
                o.put("versionCode", BuildConfig.VERSION_CODE);
                o.put("versionName", BuildConfig.VERSION_NAME);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        // ---- простое приватное хранилище (токен, хэш ПИН и т.д.), переживает обновления APK
        @JavascriptInterface
        public String get(String k) {
            return prefs.getString("kv_" + k, null);
        }

        @JavascriptInterface
        public void set(String k, String v) {
            // commit(): пишем на диск сразу, чтобы ПИН/токен не потерялись, если систему убьют приложение
            if (k.equals("server")) prefs.edit().putString("server", v).commit();
            else prefs.edit().putString("kv_" + k, v).commit();
        }

        @JavascriptInterface
        public void del(String k) {
            prefs.edit().remove("kv_" + k).commit();
        }

        // ---- биометрия
        @JavascriptInterface
        public boolean bioAvailable() {
            int r = BiometricManager.from(MainActivity.this)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
            return r == BiometricManager.BIOMETRIC_SUCCESS;
        }

        @JavascriptInterface
        public void bio(final String id, final String title) {
            runOnUiThread(() -> {
                try {
                    BiometricPrompt p = new BiometricPrompt(MainActivity.this,
                            ContextCompat.getMainExecutor(MainActivity.this),
                            new BiometricPrompt.AuthenticationCallback() {
                                @Override
                                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                                    js("window.__bio('" + id + "',true)");
                                }

                                @Override
                                public void onAuthenticationError(int code, CharSequence msg) {
                                    js("window.__bio('" + id + "',false)");
                                }
                            });
                    BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                            .setTitle(title)
                            .setNegativeButtonText("Ввести ПИН-код")
                            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                            .build();
                    p.authenticate(info);
                } catch (Exception e) {
                    js("window.__bio('" + id + "',false)");
                }
            });
        }

        // ---- QR (SVG)
        @JavascriptInterface
        public String qr(String text) {
            try {
                Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class);
                h.put(EncodeHintType.MARGIN, 0);
                h.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
                BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, h);
                int w = m.getWidth(), hh = m.getHeight();
                StringBuilder p = new StringBuilder();
                for (int y = 0; y < hh; y++) {
                    int x = 0;
                    while (x < w) {
                        if (m.get(x, y)) {
                            int st = x;
                            while (x < w && m.get(x, y)) x++;
                            p.append('M').append(st).append(' ').append(y)
                                    .append('h').append(x - st).append("v1h-").append(x - st).append('z');
                        } else x++;
                    }
                }
                return "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 " + w + " " + hh
                        + "' shape-rendering='crispEdges'><path fill='#000' d='" + p + "'/></svg>";
            } catch (Exception e) {
                return "";
            }
        }

        // ---- буфер обмена / ссылки / мелочи
        @JavascriptInterface
        public void copy(String text) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("keeper", text));
        }

        @JavascriptInterface
        public String paste() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                    return t == null ? "" : t.toString();
                }
            } catch (Exception ignored) {
            }
            return "";
        }

        @JavascriptInterface
        public void share(String text) {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, text);
            Intent c = Intent.createChooser(i, "Поделиться");
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(c);
        }

        @JavascriptInterface
        public void openUrl(String url) {
            openExternal(url);
        }

        @JavascriptInterface
        public void toast(final String t) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, t, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void vibrate() {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                v.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) {
            }
        }

        // ---- обновление APK поверх установленного (подпись та же => данные сохраняются)
        @JavascriptInterface
        public void update(final String url) {
            new Thread(() -> {
                try {
                    File dir = new File(getCacheDir(), "updates");
                    if (!dir.exists()) dir.mkdirs();
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    File out = new File(dir, "keeper-update.apk");

                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.connect();
                    if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                    long total = c.getContentLengthLong();
                    long done = 0;
                    int lastPct = -1;
                    try (InputStream in = c.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                        byte[] buf = new byte[32768];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fo.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                int pct = (int) (done * 100 / total);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    js("window.__upd(" + pct + ")");
                                }
                            }
                        }
                    }
                    ui.post(() -> install(out));
                } catch (Exception e) {
                    js("window.__updErr(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
                }
            }).start();
        }
    }

    private void install(File apk) {
        if (!getPackageManager().canRequestPackageInstalls()) {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "Разреши установку из этого приложения и нажми «Обновить» ещё раз",
                    Toast.LENGTH_LONG).show();
            js("window.__updErr('Нужно разрешение на установку')");
            return;
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        js("window.__upd(100)");
    }
}
