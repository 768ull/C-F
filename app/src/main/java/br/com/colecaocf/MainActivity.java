package br.com.colecaocf;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;
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
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.content.pm.ActivityInfo;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.net.URL;
import java.security.MessageDigest;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import android.app.Dialog;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Window;
import java.io.OutputStream;
import io.nayuki.qrcodegen.QrCode;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(16, 17, 20);
    private static final int CARD = Color.rgb(29, 31, 36);
    private static final int TEXT = Color.rgb(246, 246, 248);
    private static final int MUTED = Color.rgb(157, 160, 169);
    private static final String HOST = "appassets.androidplatform.net";
    private static final String UPDATE_MANIFEST_URL = "https://raw.githubusercontent.com/768ull/C-F/main/updates/latest.json";
    private static final String UPDATE_SIGNATURE_URL = "https://raw.githubusercontent.com/768ull/C-F/main/updates/latest.json.sig.b64";
    private static final String UPDATE_BUNDLE_URL = "https://raw.githubusercontent.com/768ull/C-F/main/updates/games.bundle.zip.b64";
    private static final String PREFS_NAME = "cf-persistent-data";
    private static final int INITIAL_BUNDLE_VERSION = 1;
    private static final String BUNDLE_MARKER = ".bundle-v7";

    private FrameLayout root;
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private boolean videoOpen;
    private View videoCustomView;
    private WebChromeClient.CustomViewCallback videoCustomViewCallback;
    private View videoTopBar;
    private AlgorithmusSiteHandler algorithmusSite;
    private File webRoot;
    private volatile boolean gameOpen;
    private volatile String activeGameId;
    private int lastReportedScore;
    private int scoreSession;
    private boolean assetsReady;
    private SharedPreferences appPrefs;
    private TextView scoreTotalView;
    private TextView scoreDetailsView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        appPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!appPrefs.contains("bundle_version")) {
            appPrefs.edit().putInt("bundle_version", INITIAL_BUNDLE_VERSION).apply();
        }
        cleanupRemovedOfflineVideos();
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
        algorithmusSite = new AlgorithmusSiteHandler(this);
        showCollection();
        enterImmersive();
        maybeCheckForGameUpdates();
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
        File marker = new File(webRoot, BUNDLE_MARKER);
        File f = new File(webRoot, "apps/frutris/index.html");
        File c = new File(webRoot, "apps/cobrinha/index.html");
        File frutrisIcon = new File(webRoot, "assets/img/Icones/frutris.webp");
        File cobrinhaIcon = new File(webRoot, "assets/img/Icones/cobrinha.webp");
        File exitScript = new File(webRoot, "js/exit-game.js");
        if (marker.isFile() && f.isFile() && c.isFile() && frutrisIcon.isFile() && cobrinhaIcon.isFile() && exitScript.isFile()) {
            try {
                // Aplica correções novas também a instalações que já têm o pacote extraído.
                patchGameScoreBridge(webRoot);
                patchCobrinhaApple(webRoot);
                return true;
            } catch (Exception patchFailure) {
                deleteTree(webRoot);
            }
        }
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
            patchGameScoreBridge(webRoot);
            patchCobrinhaApple(webRoot);
            try (FileOutputStream out = new FileOutputStream(marker)) { out.write(1); }
            return true;
        } catch (Exception ex) {
            deleteTree(webRoot);
            return false;
        }
    }


    private long pointsFor(String gameId) {
        return Math.max(0L, appPrefs.getLong(gameId + "_points", 0L));
    }

    private void refreshScoreBoard() {
        if (scoreTotalView == null || scoreDetailsView == null) return;
        long frutris = pointsFor("frutris");
        long cobrinha = pointsFor("cobrinha");
        long total = frutris > Long.MAX_VALUE - cobrinha ? Long.MAX_VALUE : frutris + cobrinha;
        java.text.NumberFormat fmt = java.text.NumberFormat.getIntegerInstance(new Locale("pt", "BR"));
        scoreTotalView.setText(fmt.format(total) + " PTS");
        int storyDay = appPrefs.getInt("algorithomus_story_day", 0);
        String storyProgress = storyDay >= 1 && storyDay <= 7
                ? Math.round((storyDay - 1) * 100f / 6f) + "% DA HISTÓRIA"
                : "--% (AGUARDANDO SINCRONIZAÇÃO)";
        scoreDetailsView.setText("FRUTRIS  " + fmt.format(frutris)
                + "\nCOBRINHA  " + fmt.format(cobrinha)
                + "\nALGORITHOMUS  " + storyProgress);
    }

    private void maybeCheckForGameUpdates() {
        long last = appPrefs.getLong("last_update_check", 0L);
        if (System.currentTimeMillis() - last >= 24L * 60L * 60L * 1000L) {
            checkForGameUpdates(null, true);
        }
    }

    private void checkForGameUpdates(TextView statusView, boolean silent) {
        if (statusView != null) statusView.setText("VERIFICANDO ATUALIZAÇÕES...");
        new Thread(() -> {
            try {
                byte[] manifestBytes = readHttps(UPDATE_MANIFEST_URL, 32768);
                byte[] signatureText = readHttps(UPDATE_SIGNATURE_URL, 8192);
                byte[] signature = Base64.decode(new String(signatureText, "UTF-8").trim(), Base64.DEFAULT);
                if (!UpdateSignatureVerifier.verify(manifestBytes, signature)) {
                    throw new SecurityException("A assinatura da atualização não é válida.");
                }
                JSONObject manifest = new JSONObject(new String(manifestBytes, "UTF-8"));
                if (manifest.getInt("schema") != 1) throw new SecurityException("Formato de atualização desconhecido.");
                if (!UPDATE_BUNDLE_URL.equals(manifest.getString("bundleUrl"))) {
                    throw new SecurityException("O endereço do pacote não está autorizado.");
                }
                int remoteVersion = manifest.getInt("bundleVersion");
                int localVersion = appPrefs.getInt("bundle_version", INITIAL_BUNDLE_VERSION);
                if (remoteVersion <= localVersion) {
                    appPrefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
                    updateStatus(statusView, "OS JOGOS JÁ ESTÃO ATUALIZADOS", silent, false);
                    return;
                }
                byte[] encodedBundle = readHttps(UPDATE_BUNDLE_URL, 2 * 1024 * 1024);
                byte[] bundle = Base64.decode(new String(encodedBundle, "UTF-8").trim(), Base64.DEFAULT);
                String actualHash = sha256(bundle);
                if (!actualHash.equalsIgnoreCase(manifest.getString("sha256"))) {
                    throw new SecurityException("O pacote baixado não passou na conferência SHA-256.");
                }
                installVerifiedBundle(bundle);
                appPrefs.edit()
                        .putInt("bundle_version", remoteVersion)
                        .putLong("last_update_check", System.currentTimeMillis())
                        .apply();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Jogos atualizados com segurança.", Toast.LENGTH_LONG).show();
                    if (!gameOpen) {
                        showCollection();
                        enterImmersive();
                    }
                });
            } catch (Exception error) {
                appPrefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
                String message = error instanceof SecurityException
                        ? "ATUALIZAÇÃO RECUSADA: VERIFICAÇÃO DE SEGURANÇA"
                        : "NÃO FOI POSSÍVEL VERIFICAR ATUALIZAÇÕES";
                updateStatus(statusView, message, silent, true);
            }
        }, "CF-Game-Updater").start();
    }

    private void updateStatus(TextView statusView, String message, boolean silent, boolean error) {
        runOnUiThread(() -> {
            if (statusView != null) statusView.setText(message);
            if (!silent) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private byte[] readHttps(String address, int maxBytes) throws Exception {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"raw.githubusercontent.com".equalsIgnoreCase(url.getHost())) {
            throw new SecurityException("Servidor de atualização não autorizado.");
        }
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent", "CF-Android-Updater/1");
        try {
            if (connection.getResponseCode() != HttpsURLConnection.HTTP_OK) {
                throw new java.io.IOException("Servidor de atualização retornou HTTP " + connection.getResponseCode());
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > maxBytes) throw new SecurityException("Arquivo de atualização excede o limite.");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder out = new StringBuilder();
        for (byte value : digest) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }

    private void installVerifiedBundle(byte[] archive) throws Exception {
        File staging = new File(getFilesDir(), "web-games-staging");
        File backup = new File(getFilesDir(), "web-games-backup");
        deleteTree(staging);
        deleteTree(backup);
        if (!staging.mkdirs()) throw new java.io.IOException("Não foi possível criar a pasta temporária.");
        String rootPath = staging.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File output = new File(staging, entry.getName());
                if (!output.getCanonicalPath().startsWith(rootPath)) throw new SecurityException("Entrada ZIP inválida.");
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) throw new java.io.IOException("Não foi possível criar pasta.");
                } else {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Não foi possível criar pasta.");
                    try (FileOutputStream file = new FileOutputStream(output)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) file.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
        if (!new File(staging, "apps/frutris/index.html").isFile()
                || !new File(staging, "apps/cobrinha/index.html").isFile()
                || !new File(staging, "assets/img/Icones/frutris.webp").isFile()
                || !new File(staging, "assets/img/Icones/cobrinha.webp").isFile()
                || !new File(staging, "js/exit-game.js").isFile()) {
            throw new SecurityException("O pacote assinado não contém os dois jogos completos.");
        }
        patchFrutrisStyle(staging);
        patchGameScoreBridge(staging);
        patchCobrinhaApple(staging);
        if (webRoot.exists() && !webRoot.renameTo(backup)) throw new java.io.IOException("Não foi possível guardar os dados anteriores.");
        if (!staging.renameTo(webRoot)) {
            if (backup.exists()) backup.renameTo(webRoot);
            throw new java.io.IOException("Não foi possível instalar o pacote novo.");
        }
        deleteTree(backup);
        try (FileOutputStream out = new FileOutputStream(new File(webRoot, BUNDLE_MARKER))) { out.write(1); }
        assetsReady = true;
    }

    private void patchFrutrisStyle(File base) throws Exception {
        File css = new File(base, "apps/frutris/css/components.css");
        if (!css.isFile()) throw new java.io.IOException("CSS do Frutris ausente.");
        String marker = "/* C-F Android: safe exit spacing */";
        String current = readUtf8(css);
        if (current.contains(marker)) return;
        try (InputStream patch = getAssets().open("frutris-exit-fix.css");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = patch.read(buffer)) != -1) output.write(buffer, 0, count);
            try (FileOutputStream file = new FileOutputStream(css, true)) {
                file.write(("\n" + marker + "\n").getBytes("UTF-8"));
                file.write(output.toByteArray());
            }
        }
    }

    private void patchGameScoreBridge(File base) throws Exception {
        patchText(new File(base, "apps/frutris/js/engine.js"),
                "state.score += T.TSPIN_SCORE;",
                "state.score += T.TSPIN_SCORE; if (window.CFAndroid) window.CFAndroid.reportScore('frutris', state.score); /*CF_SCORE_TSPIN*/",
                "CF_SCORE_TSPIN");
        patchText(new File(base, "apps/frutris/js/engine.js"),
                "state.score += baseScore;",
                "state.score += baseScore; if (window.CFAndroid) window.CFAndroid.reportScore('frutris', state.score); /*CF_SCORE_LINES*/",
                "CF_SCORE_LINES");
        patchText(new File(base, "apps/frutris/js/state.js"),
                "Object.assign(T.state, T.createInitialState());",
                "Object.assign(T.state, T.createInitialState()); if (window.CFAndroid) window.CFAndroid.reportScore('frutris', 0); /*CF_SCORE_RESET*/",
                "CF_SCORE_RESET");
        patchText(new File(base, "apps/cobrinha/js/engine.js"),
                "s.score += C.SCORE.perFood;",
                "s.score += C.SCORE.perFood; if (window.CFAndroid) window.CFAndroid.reportScore('cobrinha', s.score); /*CF_SCORE_FOOD*/",
                "CF_SCORE_FOOD");
        patchText(new File(base, "apps/cobrinha/js/state.js"),
                "s.score = 0;",
                "s.score = 0; if (window.CFAndroid) window.CFAndroid.reportScore('cobrinha', 0); /*CF_SCORE_RESET*/",
                "CF_SCORE_RESET");
    }


    /**
     * Divide visualmente a fruta da Cobrinha em duas metades iguais:
     * verde à esquerda e vermelha à direita. O recorte mantém a silhueta original.
     */
    private void patchCobrinhaApple(File base) throws Exception {
        String original =
                "var fruit = ctx.createLinearGradient(0, -r, 0, r);\n" +
                "        fruit.addColorStop(0, \"#df7863\");\n" +
                "        fruit.addColorStop(.5, pal[\"--food\"]);\n" +
                "        fruit.addColorStop(1, pal[\"--food-shade\"]);\n" +
                "        ctx.fillStyle = fruit;\n" +
                "        ctx.fill();";
        String replacement =
                "ctx.save();\n" +
                "        ctx.clip();\n" +
                "        ctx.fillStyle = \"#2E7D32\";\n" +
                "        ctx.fillRect(-r * 1.2, -r * 1.3, r * 1.2, r * 2.6);\n" +
                "        ctx.fillStyle = \"#E53935\";\n" +
                "        ctx.fillRect(0, -r * 1.3, r * 1.2, r * 2.6);\n" +
                "        ctx.restore(); /*CF_APPLE_HALF_SPLIT*/";
        patchText(new File(base, "apps/cobrinha/js/render.js"),
                original, replacement, "CF_APPLE_HALF_SPLIT");
    }

    private void patchText(File file, String original, String replacement, String marker) throws Exception {
        if (!file.isFile()) throw new java.io.IOException("Arquivo de jogo não encontrado: " + file.getName());
        String source = readUtf8(file);
        if (source.contains(marker)) return;
        if (!source.contains(original)) throw new java.io.IOException("Ponto de integração não encontrado em " + file.getName());
        writeUtf8(file, source.replace(original, replacement));
    }

    private String readUtf8(File file) throws Exception {
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), "UTF-8");
        }
    }

    private void writeUtf8(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes("UTF-8"));
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
        showCollection(false, false);
    }

    private void showCollection(boolean linksTab) {
        showCollection(linksTab, false);
    }

    private void showCollection(boolean linksTab, boolean videosTab) {
        if (videoCustomView != null) hideCustomVideoView(true);
        gameOpen = false;
        videoOpen = false;
        videoTopBar = null;
        activeGameId = null;
        if (webView != null) {
            webView.stopLoading();
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        root.removeAllViews();
        root.setBackgroundColor(BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(30), dp(24), dp(104));
        scroll.addView(body);

        TextView eyebrow = label(linksTab ? "APOIO E LINKS OFICIAIS" :
                (videosTab ? "VÍDEOS DO TORAJO" : "COLEÇÃO DE JOGOS E APLICATIVOS"), 11, MUTED, true);
        eyebrow.setLetterSpacing(.16f);
        body.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(28)));

        TextView title = label("C-F-A", 42, TEXT, true);
        title.setLetterSpacing(-.04f);
        body.addView(title, new LinearLayout.LayoutParams(-1, dp(60)));

        TextView subtitle = label(linksTab ? "Acompanhe e apoie o Mundo Torajo." :
                (videosTab ? "Assista aos vídeos sem sair do aplicativo." : "Três experiências. Uma coleção."), 15, MUTED, false);
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(-1, dp(32));
        sub.bottomMargin = dp(20);
        body.addView(subtitle, sub);

        View accent = new View(this);
        accent.setBackground(rounded(Color.rgb(211, 47, 47), 3, Color.TRANSPARENT));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(46), dp(4));
        line.bottomMargin = dp(24);
        body.addView(accent, line);

        if (linksTab) {
            LinearLayout supportCard = new LinearLayout(this);
            supportCard.setOrientation(LinearLayout.VERTICAL);
            supportCard.setPadding(dp(16), dp(14), dp(16), dp(12));
            supportCard.setBackground(rounded(CARD, 18, Color.rgb(70, 61, 88)));
            body.addView(supportCard, new LinearLayout.LayoutParams(-1, -2));

            TextView supportTitle = label("APOIE O TORAJO", 16, TEXT, true);
            supportTitle.setLetterSpacing(.06f);
            supportCard.addView(supportTitle, new LinearLayout.LayoutParams(-1, dp(30)));
            TextView supportSubtitle = label("Escolha uma opção para conhecer, acompanhar e participar.", 12, MUTED, false);
            supportSubtitle.setMaxLines(3);
            supportCard.addView(supportSubtitle, new LinearLayout.LayoutParams(-1, -2));

            supportCard.addView(supportLinkItem(
                    "CANAL NO YOUTUBE",
                    "Inscreva-se e acompanhe os vídeos",
                    "https://youtube.com/@torajo?si=K1F2cw5ktRqczQt6"));
            supportCard.addView(supportDivider());
            supportCard.addView(supportLinkItem(
                    "TORAJO JOVEM",
                    "Canal no YouTube · Torajo Jovem",
                    "https://youtube.com/@torajojovem?si=b4PeV6UjEUCsLCgk"));
            supportCard.addView(supportDivider());
            supportCard.addView(supportLinkItem(
                    "MDT PRODUÇÕES",
                    "Canal no YouTube · MDT Produções",
                    "https://youtube.com/@mdtproducoes?si=WFUuVesWT3qemu-y"));
            supportCard.addView(supportDivider());
            supportCard.addView(supportLinkItem(
                    "BRUNO GEMIN",
                    "Canal no YouTube · Bruno Gemin",
                    "https://youtube.com/@bruno_gemin?si=RyFIVsfKmgto5IQv"));
            supportCard.addView(supportDivider());
            supportCard.addView(supportLinkItem(
                    "TORAJO KIDS",
                    "Canal no YouTube · Torajo Kids",
                    "https://youtube.com/@torajokids?si=nNoHEkF09K1uWDg9"));
            supportCard.addView(supportDivider());
            supportCard.addView(supportLinkItem(
                    "SITE OFICIAL",
                    "Acesse o Mundo Torajo",
                    "https://mundotorajo.com.br/?atualizar=1"));
            supportCard.addView(supportDivider());
            supportCard.addView(supportLinkItem(
                    "INGRESSOS",
                    "Confira eventos e ingressos",
                    "https://mundotorajo.com.br/ingressos/"));

            TextView note = label("Os links abrem fora do aplicativo.", 10, MUTED, false);
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
            noteParams.topMargin = dp(12);
            supportCard.addView(note, noteParams);

            LinearLayout socialCard = new LinearLayout(this);
            socialCard.setOrientation(LinearLayout.VERTICAL);
            socialCard.setPadding(dp(16), dp(14), dp(16), dp(12));
            socialCard.setBackground(rounded(CARD, 18, Color.rgb(47, 49, 55)));
            LinearLayout.LayoutParams socialCardParams = new LinearLayout.LayoutParams(-1, -2);
            socialCardParams.topMargin = dp(18);
            body.addView(socialCard, socialCardParams);

            TextView socialTitle = label("REDES SOCIAIS DO TORAJO", 16, TEXT, true);
            socialTitle.setLetterSpacing(.06f);
            socialCard.addView(socialTitle, new LinearLayout.LayoutParams(-1, dp(30)));
            TextView socialSubtitle = label("Siga o Torajo em todas as redes e ouça as músicas oficiais.", 12, MUTED, false);
            socialSubtitle.setMaxLines(3);
            socialCard.addView(socialSubtitle, new LinearLayout.LayoutParams(-1, -2));

            socialCard.addView(supportLinkItem("INSTAGRAM", "Perfil oficial @mundo.torajo", "https://www.instagram.com/mundo.torajo/"));
            socialCard.addView(supportDivider());
            socialCard.addView(supportLinkItem("TIKTOK", "Perfil oficial @torajo_yt", "https://www.tiktok.com/@torajo_yt"));
            socialCard.addView(supportDivider());
            socialCard.addView(supportLinkItem("X · TWITTER", "Perfil oficial @Torajo_yt", "https://x.com/Torajo_yt"));
            socialCard.addView(supportDivider());
            socialCard.addView(supportLinkItem("DISCORD", "Servidor oficial do Mundo Torajo", "https://discord.gg/mundotorajo"));
            socialCard.addView(supportDivider());
            socialCard.addView(supportLinkItem("MENSAGENS · LIVEPIX", "Mande uma mensagem ao Torajo", "https://livepix.gg/torajo"));
            socialCard.addView(supportDivider());
            socialCard.addView(supportLinkItem("SPOTIFY", "Ouça as músicas oficiais no Spotify", "https://open.spotify.com/artist/1cWyhd5vnaH3hVAksS9TTe"));
            socialCard.addView(supportDivider());
            socialCard.addView(supportLinkItem("YOUTUBE MUSIC", "Ouça as músicas oficiais no YouTube Music", "https://music.youtube.com/channel/UCFnAcfR_-EsLkNJ3FkqB1AA"));

            LinearLayout qrCard = new LinearLayout(this);
            qrCard.setOrientation(LinearLayout.VERTICAL);
            qrCard.setPadding(dp(16), dp(14), dp(16), dp(12));
            qrCard.setBackground(rounded(CARD, 18, Color.rgb(47, 49, 55)));
            LinearLayout.LayoutParams qrCardParams = new LinearLayout.LayoutParams(-1, -2);
            qrCardParams.topMargin = dp(18);
            body.addView(qrCard, qrCardParams);

            TextView qrTitle = label("QR CODES DOS CANAIS E DO APLICATIVO", 16, TEXT, true);
            qrTitle.setLetterSpacing(.06f);
            qrCard.addView(qrTitle, new LinearLayout.LayoutParams(-1, dp(30)));
            TextView qrSubtitle = label("Mostre o código para outro celular escanear ou salve a imagem.", 12, MUTED, false);
            qrSubtitle.setMaxLines(3);
            qrCard.addView(qrSubtitle, new LinearLayout.LayoutParams(-1, -2));

            qrCard.addView(qrItem("CANAL NO YOUTUBE", "QR code do canal Torajo", "TORAJO", "https://youtube.com/@torajo"));
            qrCard.addView(supportDivider());
            qrCard.addView(qrItem("TORAJO JOVEM", "QR code do canal Torajo Jovem", "TORAJO-JOVEM", "https://youtube.com/@torajojovem"));
            qrCard.addView(supportDivider());
            qrCard.addView(qrItem("MDT PRODUÇÕES", "QR code do canal MDT Produções", "MDT-PRODUCOES", "https://youtube.com/@mdtproducoes"));
            qrCard.addView(supportDivider());
            qrCard.addView(qrItem("BRUNO GEMIN", "QR code do canal Bruno Gemin", "BRUNO-GEMIN", "https://youtube.com/@bruno_gemin"));
            qrCard.addView(supportDivider());
            qrCard.addView(qrItem("TORAJO KIDS", "QR code do canal Torajo Kids", "TORAJO-KIDS", "https://youtube.com/@torajokids"));
            qrCard.addView(supportDivider());
            qrCard.addView(qrItem("BAIXAR O APLICATIVO", "Outro celular escaneia e baixa o C-F-A", "CFA", "https://github.com/768ull/C-F/releases/latest"));

            TextView qrNote = label("Aponte a câmera de outro celular para o código mostrado na tela.", 10, MUTED, false);
            LinearLayout.LayoutParams qrNoteParams = new LinearLayout.LayoutParams(-1, -2);
            qrNoteParams.topMargin = dp(12);
            qrCard.addView(qrNote, qrNoteParams);
        } else if (videosTab) {
            TextView youtubeNote = label(
                    "OS TRÊS VÍDEOS DO CANAL ASSISTEM AQUI DENTRO, PELO PLAYER OFICIAL DO YOUTUBE.",
                    12, MUTED, false);
            youtubeNote.setMaxLines(3);
            LinearLayout.LayoutParams youtubeNoteParams = new LinearLayout.LayoutParams(-1, -2);
            youtubeNoteParams.bottomMargin = dp(12);
            body.addView(youtubeNote, youtubeNoteParams);
            body.addView(youtubeCard("VÍDEO 1", "oTXwEcxFlUg"));
            View youtubeGap1 = new View(this);
            body.addView(youtubeGap1, new LinearLayout.LayoutParams(1, dp(14)));
            body.addView(youtubeCard("VÍDEO 2", "VVeCPiyARPk"));
            View youtubeGap2 = new View(this);
            body.addView(youtubeGap2, new LinearLayout.LayoutParams(1, dp(14)));
            body.addView(youtubeCard("VÍDEO 3", "LDzCd4y4Iv8"));
        } else {
            LinearLayout scoreCard = new LinearLayout(this);
            scoreCard.setOrientation(LinearLayout.VERTICAL);
            scoreCard.setPadding(dp(18), dp(12), dp(18), dp(12));
            scoreCard.setBackground(rounded(CARD, 18, Color.rgb(70, 61, 88)));
            TextView scoreCaption = label("PLACAR GERAL E PROGRESSO DA HISTÓRIA", 9, MUTED, true);
            scoreCaption.setLetterSpacing(.05f);
            scoreCard.addView(scoreCaption, new LinearLayout.LayoutParams(-1, dp(20)));
            scoreTotalView = label("0 PTS", 30, TEXT, true);
            scoreCard.addView(scoreTotalView, new LinearLayout.LayoutParams(-1, dp(40)));
            scoreDetailsView = label("", 10, MUTED, false);
            scoreDetailsView.setMaxLines(3);
            scoreDetailsView.setTextSize(9);
            scoreDetailsView.setGravity(Gravity.CENTER_VERTICAL);
            scoreCard.addView(scoreDetailsView, new LinearLayout.LayoutParams(-1, dp(48)));
            scoreCard.setMinimumHeight(dp(132));
            LinearLayout.LayoutParams scoreParams = new LinearLayout.LayoutParams(-1, -2);
            scoreParams.bottomMargin = dp(18);
            body.addView(scoreCard, scoreParams);
            refreshScoreBoard();

            body.addView(gameCard("Frutris", "Jogo de blocos · controles por gestos", "frutris.webp", "frutris", 0));
            View gap = new View(this);
            body.addView(gap, new LinearLayout.LayoutParams(1, dp(14)));
            body.addView(gameCard("Cobrinha", "Jogo da cobrinha · deslize para direcionar", "cobrinha.webp", "cobrinha", 0));
            View siteGap = new View(this);
            body.addView(siteGap, new LinearLayout.LayoutParams(1, dp(14)));
            body.addView(gameCard("Simulador completo", "Algorithomus · telefone interativo com todos os aplicativos", null, "algorithomus", R.drawable.ic_algorithomus));
        }

        TextView footer = label(linksTab ? "APOIE E ACOMPANHE O TORAJO" :
                (videosTab ? "TOQUE EM UM VÍDEO PARA ASSISTIR" :
                        (assetsReady ? "TOQUE EM UM JOGO PARA ABRIR" : "NÃO FOI POSSÍVEL PREPARAR OS JOGOS")),
                10, assetsReady ? MUTED : Color.rgb(255, 138, 128), true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(.12f);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(60));
        fp.topMargin = dp(22);
        body.addView(footer, fp);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setGravity(Gravity.CENTER);
        navigation.setPadding(dp(12), dp(8), dp(12), dp(8));
        navigation.setBackgroundColor(Color.rgb(22, 23, 27));
        FrameLayout.LayoutParams navigationParams = new FrameLayout.LayoutParams(
                -1, dp(68), Gravity.BOTTOM);
        root.addView(navigation, navigationParams);
        navigation.addView(collectionTabButton("JOGOS", !linksTab && !videosTab, R.drawable.ic_launcher_cfa),
                new LinearLayout.LayoutParams(0, -1, 1f));
        navigation.addView(collectionTabButton("VÍDEOS", videosTab, android.R.drawable.ic_media_play),
                new LinearLayout.LayoutParams(0, -1, 1f));
        navigation.addView(collectionTabButton("LINKS", linksTab, android.R.drawable.ic_menu_share),
                new LinearLayout.LayoutParams(0, -1, 1f));
    }

    private View collectionTabButton(String title, boolean selected, int iconRes) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER);
        tab.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(35, 255, 255, 255)),
                rounded(selected ? Color.rgb(70, 61, 88) : Color.TRANSPARENT, 14, Color.TRANSPARENT),
                null));
        tab.setClickable(true);
        tab.setFocusable(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        icon.setColorFilter(selected ? TEXT : MUTED);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconParams.rightMargin = dp(8);
        tab.addView(icon, iconParams);
        TextView text = label(title, 12, selected ? TEXT : MUTED, true);
        text.setGravity(Gravity.CENTER_VERTICAL);
        tab.addView(text);
        tab.setOnClickListener(v -> showCollection("LINKS".equals(title), "VÍDEOS".equals(title)));
        return tab;
    }


    /** Remove os MP4 importados pela versão antiga: os vídeos agora vêm somente do YouTube. */
    private void cleanupRemovedOfflineVideos() {
        deleteTree(new File(getFilesDir(), "cfa-offline-videos"));
    }

    /** Cartão dos vídeos oficiais do canal: abre o player incorporado do YouTube. */
    private View youtubeCard(String title, String videoId) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(35, 255, 255, 255)),
                rounded(CARD, 20, Color.rgb(47, 49, 55)), null));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription("Assistir " + title + " no YouTube dentro do C-F-A");

        TextView play = label("▶", 24, Color.WHITE, true);
        play.setGravity(Gravity.CENTER);
        play.setBackground(rounded(Color.rgb(211, 47, 47), 16, Color.TRANSPARENT));
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        playParams.rightMargin = dp(16);
        card.addView(play, playParams);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.addView(label(title, 17, TEXT, true),
                new LinearLayout.LayoutParams(-1, dp(28)));
        labels.addView(label("PLAYER OFICIAL DO YOUTUBE • ASSISTE AQUI", 10, Color.rgb(255, 138, 128), true),
                new LinearLayout.LayoutParams(-1, dp(26)));
        card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = label("›", 26, MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        card.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(42)));
        card.setOnClickListener(v -> openYouTubeVideo(videoId));
        return card;
    }

    /**
     * Abre um vídeo do YouTube dentro do aplicativo com o player incorporado
     * oficial (IFrame), que não exige chave de API. É a única forma aceita
     * pelos termos do YouTube: os players nativos (MediaPlayer/Media3) não
     * reproduzem vídeos do serviço, porque o YouTube não publica um endereço
     * direto do arquivo de vídeo.
     */
    private void openYouTubeVideo(String videoId) {
        if (videoCustomView != null) hideCustomVideoView(true);
        gameOpen = false;
        videoOpen = true;
        activeGameId = null;
        synchronized (this) { scoreSession++; }
        if (webView != null) {
            webView.stopLoading();
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        root.removeAllViews();
        root.setBackgroundColor(Color.BLACK);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            // Somente o próprio player incorporado permanece dentro do aplicativo.
            // A página completa do YouTube (tocando no título do vídeo) não é
            // exibida no WebView porque, no modo retrato, ela aparece reduzida
            // como numa tela de computador, com o vídeo pequeno no canto.
            // O player incorporado mostra o vídeo completo em qualquer
            // orientação; endereços externos abrem no YouTube ou no navegador.
            private boolean staysInsidePlayer(Uri uri) {
                String scheme = uri.getScheme();
                if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) return false;
                String host = uri.getHost();
                return host != null && (host.equals("youtube-nocookie.com")
                        || host.equals("www.youtube-nocookie.com"));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (staysInsidePlayer(request.getUrl())) return false;
                openVideoAddressOutside(request.getUrl());
                return true;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (staysInsidePlayer(uri)) return false;
                openVideoAddressOutside(uri);
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (videoCustomView != null) {
                    try { callback.onCustomViewHidden(); } catch (Exception ignored) { }
                    return;
                }
                videoCustomView = view;
                videoCustomViewCallback = callback;
                if (videoTopBar != null) videoTopBar.setVisibility(View.GONE);
                root.setBackgroundColor(Color.BLACK);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                enterImmersive();
            }
            @Override public void onHideCustomView() {
                hideCustomVideoView(true);
            }
        });

        FrameLayout.LayoutParams playerParams = new FrameLayout.LayoutParams(-1, -1);
        playerParams.topMargin = dp(56);
        root.addView(webView, playerParams);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(18), 0, dp(18), 0);
        topBar.setBackgroundColor(Color.rgb(22, 23, 27));
        TextView back = label("‹  VOLTAR AOS VÍDEOS", 13, TEXT, true);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setContentDescription("Voltar para a lista de vídeos");
        back.setOnClickListener(v -> {
            showCollection(false, true);
            enterImmersive();
        });
        topBar.addView(back, new LinearLayout.LayoutParams(-1, -1));
        videoTopBar = topBar;
        root.addView(topBar, new FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP));

        String embedUrl = "https://www.youtube-nocookie.com/embed/" + videoId
                + "?autoplay=0&controls=1&playsinline=1&rel=0&hl=pt-BR&fs=1";
        // O YouTube exige cabeçalho Referer em players incorporados dentro de WebView.
        Map<String, String> playerHeaders = Collections.singletonMap(
                "Referer", "https://" + HOST + "/cfa-android/");
        webView.loadUrl(embedUrl, playerHeaders);
        enterImmersive();
    }

    /**
     * Abre fora do aplicativo os endereços tocados dentro do player, como a
     * página completa do YouTube. No aplicativo do YouTube ou no navegador,
     * o vídeo completo é exibido corretamente em pé, no modo retrato.
     */
    private void openVideoAddressOutside(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
            Toast.makeText(this, "ABRINDO FORA DO APLICATIVO.", Toast.LENGTH_SHORT).show();
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "Nenhum aplicativo disponível para abrir este endereço.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível abrir este endereço.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void hideCustomVideoView(boolean notifyPlayer) {
        if (videoCustomView == null) return;
        View custom = videoCustomView;
        if (custom.getParent() instanceof ViewGroup) {
            ((ViewGroup) custom.getParent()).removeView(custom);
        }
        WebChromeClient.CustomViewCallback callback = videoCustomViewCallback;
        videoCustomView = null;
        videoCustomViewCallback = null;
        if (videoTopBar != null) videoTopBar.setVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (notifyPlayer && callback != null) callback.onCustomViewHidden();
        enterImmersive();
    }

    private View supportLinkItem(String title, String detail, String url) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(8), dp(7), dp(8), dp(7));
        item.setMinimumHeight(dp(58));
        item.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(35, 255, 255, 255)),
                rounded(Color.TRANSPARENT, 10, Color.TRANSPARENT), null));
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription(title + ". " + detail);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(label(title, 12, TEXT, true),
                new LinearLayout.LayoutParams(-1, dp(22)));
        copy.addView(label(detail, 10, MUTED, false),
                new LinearLayout.LayoutParams(-1, dp(20)));
        item.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = label("↗", 20, MUTED, true);
        arrow.setGravity(Gravity.CENTER);
        item.addView(arrow, new LinearLayout.LayoutParams(dp(30), dp(40)));
        item.setOnClickListener(v -> openSupportUrl(url));
        return item;
    }

    private View supportDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(Color.argb(35, 255, 255, 255));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.topMargin = dp(2);
        params.bottomMargin = dp(2);
        divider.setLayoutParams(params);
        return divider;
    }

    private void openSupportUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "Nenhum navegador ou aplicativo disponível para abrir o link.",
                    Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível abrir o link.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Item da lista de QR codes da aba LINKS. Abre o visualizador do código ao ser tocado. */
    private View qrItem(String title, String detail, String name, String url) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(8), dp(7), dp(8), dp(7));
        item.setMinimumHeight(dp(58));
        item.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(35, 255, 255, 255)),
                rounded(Color.TRANSPARENT, 10, Color.TRANSPARENT), null));
        item.setClickable(true);
        item.setFocusable(true);
        item.setContentDescription("Mostrar QR code: " + title);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.addView(label(title, 12, TEXT, true),
                new LinearLayout.LayoutParams(-1, dp(22)));
        copy.addView(label(detail, 10, MUTED, false),
                new LinearLayout.LayoutParams(-1, dp(20)));
        item.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView icon = label("QR", 11, Color.rgb(129, 199, 132), true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(Color.rgb(35, 56, 44), 10, Color.TRANSPARENT));
        item.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        item.setOnClickListener(v -> showQrCode(title, name, url));
        return item;
    }

    /** Visualizador de QR code: outro celular pode escanear a tela ou salvar a imagem. */
    private void showQrCode(String title, String name, String url) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(rounded(Color.rgb(24, 25, 29), 20, Color.TRANSPARENT));

        TextView heading = label(title, 15, TEXT, true);
        heading.setGravity(Gravity.CENTER);
        box.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        ImageView image = new ImageView(this);
        image.setImageBitmap(qrBitmap(url, 12));
        image.setBackground(rounded(Color.WHITE, 14, Color.TRANSPARENT));
        image.setPadding(dp(6), dp(6), dp(6), dp(6));
        int qrSize = Math.min(dp(300), Math.max(dp(220), getResources().getDisplayMetrics().widthPixels - dp(52)));
        box.addView(image, new LinearLayout.LayoutParams(qrSize, qrSize));

        TextView hint = label("Aponte a câmera de outro celular para o código.", 11, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setMaxLines(2);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(8);
        box.addView(hint, hintParams);

        TextView save = label("SALVAR IMAGEM", 12, Color.WHITE, true);
        save.setGravity(Gravity.CENTER);
        save.setLetterSpacing(.05f);
        save.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
                rounded(Color.rgb(170, 38, 38), 20, Color.TRANSPARENT), null));
        save.setClickable(true);
        save.setFocusable(true);
        save.setContentDescription("Salvar o QR code como imagem na galeria");
        save.setOnClickListener(v -> {
            saveQrImage(name, url);
        });
        save.setMinimumHeight(dp(46));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, -2);
        saveParams.topMargin = dp(10);
        box.addView(save, saveParams);

        TextView close = label("FECHAR", 12, MUTED, true);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(10), dp(6), dp(10), dp(6));
        close.setClickable(true);
        close.setFocusable(true);
        close.setOnClickListener(v -> dialog.dismiss());
        close.setMinimumHeight(dp(38));
        box.addView(close, new LinearLayout.LayoutParams(-2, -2));

        dialog.setContentView(box);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.argb(190, 0, 0, 0)));
            window.setLayout(Math.min(dp(342), getResources().getDisplayMetrics().widthPixels - dp(24)), -2);
        }
    }

    /** Gera a imagem do QR code a partir do endereço, com borda branca de segurança. */
    private Bitmap qrBitmap(String url, int modulePx) {
        QrCode qr = QrCode.encodeText(url, QrCode.Ecc.MEDIUM);
        int border = 4;
        int size = (qr.size + border * 2) * modulePx;
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint dark = new Paint();
        dark.setColor(Color.BLACK);
        dark.setStyle(Paint.Style.FILL);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.getModule(x, y)) {
                    canvas.drawRect((x + border) * modulePx, (y + border) * modulePx,
                            (x + border + 1) * modulePx, (y + border + 1) * modulePx, dark);
                }
            }
        }
        return bitmap;
    }

    /** Salva o QR code como imagem PNG na galeria (Android 10 ou superior) ou na pasta do aplicativo. */
    private void saveQrImage(String name, String url) {
        try {
            Bitmap bitmap = qrBitmap(url, 26);
            String fileName = "QR-" + name + ".png";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/C-F-A");
                Uri saved = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (saved == null) throw new java.io.IOException("Não foi possível criar o arquivo.");
                try (OutputStream output = getContentResolver().openOutputStream(saved)) {
                    if (output == null) throw new java.io.IOException("Não foi possível abrir o arquivo.");
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    output.flush();
                }
                Toast.makeText(this, "QR CODE SALVO EM IMAGENS › C-F-A.", Toast.LENGTH_LONG).show();
            } else {
                File base = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
                if (base == null) base = getFilesDir();
                File directory = new File(base, "C-F-A");
                if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                    throw new java.io.IOException("Não foi possível criar a pasta.");
                File file = new File(directory, fileName);
                try (FileOutputStream output = new FileOutputStream(file)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                    output.flush();
                }
                Toast.makeText(this, "QR CODE SALVO NA PASTA DE IMAGENS DO C-F-A.", Toast.LENGTH_LONG).show();
            }
        } catch (Exception error) {
            Toast.makeText(this, "Não foi possível salvar a imagem do QR code.", Toast.LENGTH_LONG).show();
        }
    }

    private View gameCard(String title, String detail, String iconName, String id, int iconRes) {
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
        if (iconRes != 0) {
            icon.setImageResource(iconRes);
        } else {
            File iconFile = new File(webRoot, "assets/img/Icones/" + iconName);
            icon.setImageBitmap(BitmapFactory.decodeFile(iconFile.getAbsolutePath()));
        }
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

    private WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain", "UTF-8", 403, "Offline",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private boolean algorithmusMirrorAvailable() {
        // Do not open a simulator shell unless its core pages and app entry points
        // were embedded by the build-time mirror. This catches partial bundles.
        String[] requiredAssets = {
                "site/index.html",
                "site/media-manifest.json",
                "site/js/main.js",
                "site/js/core/boot-loader.js",
                "site/css/system.css",
                "site/apps/mensagens/index.html",
                "site/apps/y/index.html",
                "site/apps/telefone/index.html",
                "site/apps/galeria/index.html",
                "site/apps/tetris/index.html",
                "site/apps/cobrinha/index.html",
                "site/apps/calculadora/index.html",
                "site/apps/configuracoes/index.html",
                "site/apps/sem-nome/index.html",
                "site/apps/sem-nome/rabiscos/index.html"
        };
        for (String path : requiredAssets) {
            try (InputStream in = getAssets().open(path)) {
                if (in.read() == -1) return false;
            } catch (Exception missing) {
                return false;
            }
        }
        return true;
    }

    private boolean algorithmusOpen() {
        return gameOpen && "algorithomus".equals(activeGameId);
    }

    /**
     * Roteamento híbrido das requisições do WebView. Durante o Algorithomus, o
     * jogo roda no endereço real https://algorithomus.com.br: com internet, as
     * requisições ao domínio real seguem direto para os servidores originais do
     * jogo (retorno null deixa o WebView falar com a rede); sem internet, as
     * mesmas requisições são atendidas pelo espelho embutido no APK. O domínio
     * virtual continua atendendo os jogos Frutris e Cobrinha. Qualquer outro
     * endereço externo permanece bloqueado.
     */
    private WebResourceResponse route(Uri u) {
        String host = u.getHost();
        if (algorithmusOpen() && AlgorithmusNet.REAL_HOST.equals(host)) {
            return serveAlgorithmus(u, true);
        }
        if (!HOST.equals(host)) return blocked();
        String path = u.getPath();
        if (path == null) path = "/";
        if (path.startsWith("/local/")) {
            return assetLoader.shouldInterceptRequest(u);
        }
        if (algorithmusOpen()) {
            return serveAlgorithmus(u, false);
        }
        return assetLoader.shouldInterceptRequest(u);
    }

    /**
     * Atende uma requisição do jogo Algorithomus. Com allowNetwork e conexão
     * ativa, o retorno null entrega a requisição ao servidor real do jogo
     * (inclusive as chamadas /api/, que o jogo faz por fetch na mesma origem).
     * O espelho embutido serve primeiro os arquivos estáticos; offline, ele
     * passa a responder tudo que existir no espelho.
     */
    private WebResourceResponse serveAlgorithmus(Uri u, boolean allowNetwork) {
        String path = u.getPath();
        if (path == null) path = "/";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("cloudflareinsights") || lower.contains("beacon.min.js")) {
            return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                    Collections.emptyMap(), AlgorithmusNet.emptyStream());
        }
        if (path.startsWith("/api/")) {
            return allowNetwork && AlgorithmusNet.isOnline(this) ? null : apiOfflineJson();
        }
        WebResourceResponse mirrored = algorithmusSite.open(u);
        if (mirrored != null) return mirrored;
        return allowNetwork && AlgorithmusNet.isOnline(this) ? null
                : AlgorithmusSiteHandler.notFound(AlgorithmusSiteHandler.guessMime(path));
    }

    /** Resposta JSON de servidor indisponível, usada no modo offline. */
    private WebResourceResponse apiOfflineJson() {
        byte[] body = AlgorithmusNet.errorJson("sem-internet").getBytes(StandardCharsets.UTF_8);
        return new WebResourceResponse("application/json", "UTF-8", 503, "Service Unavailable",
                Collections.emptyMap(), new ByteArrayInputStream(body));
    }

    /**
     * Botão nativo sempre visível durante o Algorithomus, independente da
     * tela ou do aplicativo interno aberto no simulador.
     */
    private void addAlgorithomusExitButton() {
        TextView exitButton = label("SAIR", 12, Color.WHITE, true);
        exitButton.setGravity(Gravity.CENTER);
        exitButton.setLetterSpacing(0.08f);
        exitButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
                rounded(Color.rgb(170, 38, 38), 22, Color.rgb(220, 92, 92)),
                null));
        exitButton.setClickable(true);
        exitButton.setFocusable(true);
        exitButton.setElevation(dp(8));
        exitButton.setContentDescription("Sair do Algorithomus e voltar à coleção C-F-A");
        exitButton.setOnClickListener(v -> {
            showCollection();
            enterImmersive();
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(84), dp(42), Gravity.TOP | Gravity.END);
        params.topMargin = dp(14);
        params.rightMargin = dp(14);
        root.addView(exitButton, params);
    }

    private void openGame(String id) {
        boolean siteGame = "algorithomus".equals(id);
        if (siteGame) {
            if (!algorithmusMirrorAvailable()) {
                Toast.makeText(this, "O espelho do Algorithomus não está incluído nesta compilação.", Toast.LENGTH_LONG).show();
                return;
            }
        } else if (!assetsReady) {
            showCollection();
            return;
        }
        final int session;
        synchronized (this) {
            session = ++scoreSession;
            lastReportedScore = 0;
        }
        activeGameId = id;
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
        s.setBlockNetworkLoads(!siteGame);
        if (siteGame) {
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            webView.addJavascriptInterface(new AlgorithmusApiBridge(this), "ApiBridge");
            WebViewCompat.addDocumentStartJavaScript(webView, AlgorithmusApiBridge.FETCH_SHIM, Collections.singleton(AlgorithmusNet.REAL_ORIGIN));
        }
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface public void exitGame() {
                runOnUiThread(() -> {
                    if (session != scoreSession) return;
                    if (gameOpen) { showCollection(); enterImmersive(); }
                });
            }

            @JavascriptInterface public void reportStoryDay(int unlockedDay) {
                // O diário original tem seis dias; dia 7 significa história concluída.
                if (unlockedDay < 1 || unlockedDay > 7) return;
                appPrefs.edit().putInt("algorithomus_story_day", unlockedDay).apply();
            }
            @JavascriptInterface public void reportScore(String gameId, int score) {
                if (score < 0 || score > 1000000000) return;
                synchronized (MainActivity.this) {
                    if (session != scoreSession) return;
                    if (!gameOpen || activeGameId == null || !activeGameId.equals(gameId)) return;
                    if (score < lastReportedScore) {
                        lastReportedScore = score;
                        return;
                    }
                    int delta = score - lastReportedScore;
                    if (delta <= 0) return;
                    lastReportedScore = score;
                    String key = gameId + "_points";
                    long current = appPrefs.getLong(key, 0L);
                    long updated = current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
                    appPrefs.edit().putLong(key, updated).apply();
                }
            }
        }, "CFAndroid");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, WebChromeClient.CustomViewCallback callback) {
                if (videoCustomView != null) {
                    try { callback.onCustomViewHidden(); } catch (Exception ignored) { }
                    return;
                }
                videoCustomView = view;
                videoCustomViewCallback = callback;
                root.setBackgroundColor(Color.BLACK);
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                enterImmersive();
            }
            @Override public void onHideCustomView() {
                hideCustomVideoView(true);
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                if ("frutris".equals(id)) {
                    v.evaluateJavascript(
                            "(function(){var b=document.getElementById('exitGameBtn');var h=document.querySelector('.game-hud');if(b&&h&&b.parentNode!==h)h.appendChild(b);})();",
                            null);
                }
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return route(r.getUrl());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
                return route(Uri.parse(url));
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String h = r.getUrl().getHost();
                if (HOST.equals(h)) return false;
                return !(siteGame && AlgorithmusNet.REAL_HOST.equals(h));
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        if (siteGame) addAlgorithomusExitButton();
        webView.loadUrl(siteGame ? AlgorithmusNet.REAL_ORIGIN + "/index.html" : "https://" + HOST + "/local/apps/" + id + "/index.html");
        enterImmersive();
    }

    @Override @SuppressWarnings("deprecation") public void onBackPressed() {
        if (videoCustomView != null) { hideCustomVideoView(true); return; }
        if (videoOpen) { showCollection(false, true); enterImmersive(); return; }
        if (gameOpen) { showCollection(); enterImmersive(); return; }
        super.onBackPressed();
    }
    @Override protected void onResume() {
        super.onResume();
        enterImmersive();
        if (webView != null) webView.onResume();
    }
    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }
}
