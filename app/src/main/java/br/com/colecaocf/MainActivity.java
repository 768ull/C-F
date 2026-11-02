package br.com.colecaocf;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 17, 20);
    private static final int CARD = Color.rgb(29, 31, 36);
    private static final int TEXT = Color.rgb(246, 246, 248);
    private static final int MUTED = Color.rgb(157, 160, 169);
    private static final String HOST = "appassets.androidplatform.net";

    private FrameLayout root;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private File webRoot;
    private boolean gameOpen;
    private boolean assetsReady;
    private View nativeExitButton;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);
        webRoot = new File(getFilesDir(), "web-games");
        assetsReady = installGameFiles();
        assetLoader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/local/", new WebViewAssetLoader.InternalStoragePathHandler(this, webRoot))
                .build();
        showCollection();
        enterImmersive();
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    private boolean installGameFiles() {
        File marker = new File(webRoot, ".bundle-v5");
        File f = new File(webRoot, "apps/frutris/index.html");
        File c = new File(webRoot, "apps/cobrinha/index.html");
        File frutrisIcon = new File(webRoot, "assets/img/Icones/frutris.webp");
        File cobrinhaIcon = new File(webRoot, "assets/img/Icones/cobrinha.webp");
        File exitScript = new File(webRoot, "js/exit-game.js");
        if (marker.isFile() && f.isFile() && c.isFile() && frutrisIcon.isFile() && cobrinhaIcon.isFile() && exitScript.isFile()) return true;
        deleteTree(webRoot);
        if (!webRoot.mkdirs() && !webRoot.isDirectory()) return false;
        try (InputStream in = getAssets().open("games.bundle.zip");
             ZipInputStream zip = new ZipInputStream(in)) {
            String base = webRoot.getCanonicalPath() + File.separator;
            byte[] buffer = new byte[8192];
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                File out = new File(webRoot, e.getName());
                if (!out.getCanonicalPath().startsWith(base)) throw new SecurityException("Invalid bundle entry");
                if (e.isDirectory()) {
                    if (!out.mkdirs() && !out.isDirectory()) throw new IllegalStateException("Cannot create folder");
                } else {
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                        throw new IllegalStateException("Cannot create folder");
                    try (FileOutputStream stream = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buffer)) != -1) stream.write(buffer, 0, n);
                    }
                }
                zip.closeEntry();
            }
            if (!f.isFile() || !c.isFile() || !frutrisIcon.isFile() || !cobrinhaIcon.isFile() || !exitScript.isFile()) {
                throw new IllegalStateException("O pacote não contém os dois jogos, os ícones originais e o script de saída.");
            }
            File frutrisCss = new File(webRoot, "apps/frutris/css/components.css");
            if (!frutrisCss.isFile()) {
                throw new IllegalStateException("A folha de estilo original do Frutris não foi encontrada.");
            }
            try (InputStream patch = getAssets().open("frutris-exit-fix.css");
                 FileOutputStream stream = new FileOutputStream(frutrisCss, true)) {
                byte[] patchBuffer = new byte[2048];
                int count;
                while ((count = patch.read(patchBuffer)) != -1) stream.write(patchBuffer, 0, count);
            }
            try (FileOutputStream out = new FileOutputStream(marker)) { out.write(1); }
            return true;
        } catch (Exception ex) {
            deleteTree(webRoot);
            return false;
        }
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void showCollection() {
        gameOpen = false;
        if (webView != null) {
            webView.stopLoading();
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        root.removeAllViews();
        nativeExitButton = null;
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(30), dp(24), dp(28));
        scroll.addView(body);

        TextView eyebrow = label("COLEÇÃO DE JOGOS", 11, MUTED, true);
        eyebrow.setLetterSpacing(.16f);
        body.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(28)));

        TextView title = label("C-F", 42, TEXT, true);
        title.setLetterSpacing(-.04f);
        body.addView(title, new LinearLayout.LayoutParams(-1, dp(60)));

        TextView subtitle = label("Dois jogos. Uma coleção.", 15, MUTED, false);
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(-1, dp(32));
        sub.bottomMargin = dp(20);
        body.addView(subtitle, sub);

        View accent = new View(this);
        accent.setBackground(rounded(Color.rgb(211, 47, 47), 3, Color.TRANSPARENT));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(46), dp(4));
        line.bottomMargin = dp(24);
        body.addView(accent, line);

        body.addView(gameCard("Frutris", "Jogo de blocos · controles por gestos", "frutris.webp", "frutris"));
        View gap = new View(this);
        body.addView(gap, new LinearLayout.LayoutParams(1, dp(14)));
        body.addView(gameCard("Cobrinha", "Jogo da cobrinha · deslize para direcionar", "cobrinha.webp", "cobrinha"));

        TextView footer = label(assetsReady ? "TOQUE EM UM JOGO PARA ABRIR" : "NÃO FOI POSSÍVEL PREPARAR OS JOGOS",
                10, assetsReady ? MUTED : Color.rgb(255, 138, 128), true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(.12f);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(60));
        fp.topMargin = dp(22);
        body.addView(footer, fp);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    private View gameCard(String title, String detail, String iconName, String id) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(35,255,255,255)),
                rounded(CARD, 22, Color.rgb(47,49,55)), null));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("Abrir " + title);

        ImageView icon = new ImageView(this);
        File iconFile = new File(webRoot, "assets/img/Icones/" + iconName);
        icon.setImageBitmap(BitmapFactory.decodeFile(iconFile.getAbsolutePath()));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setBackground(rounded(Color.rgb(35,36,40), 16, Color.TRANSPARENT));
        icon.setClipToOutline(true);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(72), dp(72));
        ip.rightMargin = dp(16);
        card.addView(icon, ip);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(label(title, 20, TEXT, true), new LinearLayout.LayoutParams(-1, dp(30)));
        TextView desc = label(detail, 12, MUTED, false);
        desc.setMaxLines(2);
        labels.addView(desc, new LinearLayout.LayoutParams(-1, dp(38)));
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = label("›", 30, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(42)));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(104));
        card.setLayoutParams(cp);
        card.setOnClickListener(v -> openGame(id));
        return card;
    }

    private TextView createNativeExitButton() {
        TextView exit = label("SAIR", 11, TEXT, true);
        exit.setGravity(Gravity.CENTER);
        exit.setLetterSpacing(.08f);
        exit.setContentDescription("Sair do Frutris e voltar à coleção");
        exit.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(48, 255, 255, 255)),
                rounded(Color.rgb(25, 26, 36), 6, Color.rgb(91, 78, 116)),
                null));
        exit.setClickable(true);
        exit.setFocusable(true);
        exit.setOnClickListener(v -> {
            if (gameOpen) {
                showCollection();
                enterImmersive();
            }
        });
        return exit;
    }
    private WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Offline",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private void openGame(String id) {
        if (!assetsReady) { showCollection(); return; }
        gameOpen = true;
        root.removeAllViews();
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setBlockNetworkLoads(true);
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface public void exitGame() {
                runOnUiThread(() -> { if (gameOpen) { showCollection(); enterImmersive(); } });
            }
        }, "CFAndroid");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if ("frutris".equals(id)) {
                    v.evaluateJavascript("(function(){var b=document.getElementById('exitGameBtn');if(b)b.style.display='none';})();", null);
                }
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                return HOST.equals(u.getHost()) ? assetLoader.shouldInterceptRequest(u) : blocked();
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                Uri u = Uri.parse(url);
                return HOST.equals(u.getHost()) ? assetLoader.shouldInterceptRequest(u) : blocked();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !HOST.equals(r.getUrl().getHost());
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        if ("frutris".equals(id)) {
            nativeExitButton = createNativeExitButton();
            FrameLayout.LayoutParams exitParams = new FrameLayout.LayoutParams(
                    dp(56), dp(34), Gravity.TOP | Gravity.END);
            exitParams.topMargin = dp(8);
            exitParams.rightMargin = dp(10);
            root.addView(nativeExitButton, exitParams);
        }
        webView.loadUrl("https://" + HOST + "/local/apps/" + id + "/index.html");
        enterImmersive();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (gameOpen) { showCollection(); enterImmersive(); return; }
        super.onBackPressed();
    }
    @Override protected void onResume() {
        super.onResume(); enterImmersive(); if (webView != null) webView.onResume();
    }
    @Override protected void onPause() {
        if (webView != null) webView.onPause(); super.onPause();
    }
}
