package br.com.colecaocf;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Servidor local do jogo Algorithomus: atende, no domínio virtual do aplicativo,
 * os arquivos públicos espelhados em assets/site (baixados pelo tools/mirror_site.py
 * durante a compilação). Como o espelho reproduz a raiz do site original, os
 * caminhos absolutos do jogo (/apps/..., /assets/..., /media-manifest.json)
 * continuam resolvendo dentro do aplicativo.
 */
final class AlgorithmusSiteHandler {

    private static final String HOST = "appassets.androidplatform.net";

    private final AssetManager assets;

    AlgorithmusSiteHandler(Context context) {
        this.assets = context.getAssets();
    }

    WebResourceResponse open(Uri url) {
        String path = url.getPath();
        if (path == null) {
            path = "/";
        }
        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (IllegalArgumentException ignored) {
            // mantém o caminho original
        } catch (UnsupportedEncodingException e) {
            // impossível na prática: UTF-8 é sempre suportado
        }
        WebResourceResponse response = openAsset(path);
        if (response != null) {
            response.setResponseHeaders(headersFor());
        }
        return response;
    }

    private WebResourceResponse openAsset(String rawPath) {
        String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (path.isEmpty()) {
            path = "index.html";
        }

        String candidate = path;
        if (path.endsWith("/")) {
            candidate = path + "index.html";
        }

        // 1) arquivo direto do espelho
        try {
            InputStream in = assets.open("site/" + candidate);
            return responseFor(candidate, in);
        } catch (IOException ignored) {
            // segue para as variações
        }

        // 2) diretório sem barra final -> index.html interno
        if (!path.endsWith("/") && !hasExtension(path)) {
            try {
                InputStream in = assets.open("site/" + path + "/index.html");
                return responseFor(path + "/index.html", in);
            } catch (IOException ignored) {
                // segue
            }
        }

        // 3) ausente do espelho: o chamador decide entre proxy online e 404
        return null;
    }

    private static boolean hasExtension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash + 1;
    }

    private WebResourceResponse responseFor(String assetPath, InputStream in) {
        WebResourceResponse response = new WebResourceResponse(guessMime(assetPath), null, in);
        response.setStatusCodeAndReasonPhrase(200, "OK");
        return response;
    }

    private static Map<String, String> headersFor() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "https://" + HOST);
        return h;
    }

    static String guessMime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        int cut = p.indexOf('?');
        if (cut >= 0) {
            p = p.substring(0, cut);
        }
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".json") || p.endsWith(".map")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".m4a")) return "audio/mp4";
        if (p.endsWith(".ogg") || p.endsWith(".oga")) return "audio/ogg";
        if (p.endsWith(".wav")) return "audio/wav";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".webm")) return "video/webm";
        if (p.endsWith(".txt")) return "text/plain";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }

    /** Resposta vazia com código 404, usada como fallback offline. */
    static WebResourceResponse notFound(String mime) {
        WebResourceResponse response = new WebResourceResponse(
                mime, "utf-8", new ByteArrayInputStream(new byte[0]));
        response.setStatusCodeAndReasonPhrase(404, "Not Found");
        return response;
    }
}
