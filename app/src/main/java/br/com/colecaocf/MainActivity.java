package br.com.colecaocf;

import android.app.Activity;
import android.content.res.ColorStateList;
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
import android.widget.Toast;

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
import java.net.URL;
import java.security.MessageDigest;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
            patchGameScoreBridge(webRoot);
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
        scoreDetailsView.setText("FRUTRIS  " + fmt.format(frutris) + "\nCOBRINHA  " + fmt.format(cobrinha));
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
        gameOpen = false;
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
        body.setPadding(dp(24), dp(30), dp(24), dp(28));
        scroll.addView(body);

        TextView eyebrow = label("COLEÇÃO DE JOGOS", 11, MUTED, true);
        eyebrow.setLetterSpacing(.16f);
        body.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(28)));

        TextView title = label("C-F", 42, TEXT, true);
        title.setLetterSpacing(-.04f);
        body.addView(title, new LinearLayout.LayoutParams(-1, dp(60)));

        TextView subtitle = label("Três experiências. Uma coleção.", 15, MUTED, false);
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(-1, dp(32));
        sub.bottomMargin = dp(20);
        body.addView(subtitle, sub);

        View accent = new View(this);
        accent.setBackground(rounded(Color.rgb(211, 47, 47), 3, Color.TRANSPARENT));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(46), dp(4));
        line.bottomMargin = dp(24);
        body.addView(accent, line);

        LinearLayout scoreCard = new LinearLayout(this);
        scoreCard.setOrientation(LinearLayout.VERTICAL);
        scoreCard.setPadding(dp(18), dp(12), dp(18), dp(12));
        scoreCard.setBackground(rounded(CARD, 18, Color.rgb(70, 61, 88)));
        TextView scoreCaption = label("PLACAR GERAL", 11, MUTED, true);
        scoreCaption.setLetterSpacing(.12f);
        scoreCard.addView(scoreCaption, new LinearLayout.LayoutParams(-1, dp(20)));
        scoreTotalView = label("0 PTS", 30, TEXT, true);
        scoreCard.addView(scoreTotalView, new LinearLayout.LayoutParams(-1, dp(40)));
        scoreDetailsView = label("", 10, MUTED, false);
        scoreDetailsView.setMaxLines(2);
        scoreDetailsView.setGravity(Gravity.CENTER_VERTICAL);
        scoreCard.addView(scoreDetailsView, new LinearLayout.LayoutParams(-1, dp(36)));
        scoreCard.setMinimumHeight(dp(120));
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

        TextView updateButton = label("VERIFICAR ATUALIZAÇÕES", 12, TEXT, true);
        updateButton.setGravity(Gravity.CENTER);
        updateButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.argb(35, 255, 255, 255)),
                rounded(CARD, 14, Color.rgb(91, 78, 116)), null));
        updateButton.setClickable(true);
        updateButton.setFocusable(true);
        updateButton.setOnClickListener(v -> checkForGameUpdates(updateButton, false));
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(-1, dp(48));
        updateParams.topMargin = dp(16);
        body.addView(updateButton, updateParams);

        TextView footer = label(assetsReady ? "TOQUE EM UM JOGO PARA ABRIR" : "NÃO FOI POSSÍVEL PREPARAR OS JOGOS",
                10, assetsReady ? MUTED : Color.rgb(255, 138, 128), true);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(.12f);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, dp(60));
        fp.topMargin = dp(22);
        body.addView(footer, fp);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
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
        webView.loadUrl(siteGame ? AlgorithmusNet.REAL_ORIGIN + "/index.html" : "https://" + HOST + "/local/apps/" + id + "/index.html");
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
