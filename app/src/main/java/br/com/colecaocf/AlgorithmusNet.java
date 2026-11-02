package br.com.colecaocf;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Rede do jogo Algorithomus. O jogo roda no endereço real
 * https://algorithomus.com.br dentro do WebView: com internet, o próprio
 * WebView conversa diretamente com os servidores originais do jogo; sem
 * internet, as requisições são atendidas pelo espelho local embutido no APK.
 * Esta classe cuida da verificação de conectividade, das respostas de erro
 * offline e do proxy legado de arquivos individuais.
 */
final class AlgorithmusNet {

    public static final String REAL_ORIGIN = "https://algorithomus.com.br";
    public static final String REAL_HOST = "algorithomus.com.br";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private AlgorithmusNet() {}

    public static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            Network network = cm.getActiveNetwork();
            if (network == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Busca no servidor real do jogo e devolve os dados prontos para o WebView.
     * Retorna null em caso de falha ou ausência de conexão; o chamador então
     * serve uma resposta vazia.
     */
    public static WebResourceResponseLike proxyGet(Context context, String pathAndQuery) {
        if (!isOnline(context)) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(REAL_ORIGIN + pathAndQuery);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            // evita gzip para entregar o stream cru ao WebView
            conn.setRequestProperty("Accept-Encoding", "identity");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                return null;
            }
            String contentType = conn.getContentType();
            String mime = contentType != null ? contentType.split(";")[0].trim() : null;
            if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
                mime = AlgorithmusSiteHandler.guessMime(pathAndQuery);
            }
            InputStream in = new BufferedInputStream(conn.getInputStream());
            WebResourceResponseLike out = new WebResourceResponseLike(mime, in);
            out.statusCode = code;
            out.lengthBytes = conn.getContentLength();
            return out;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        // conn NÃO é desconectado aqui: o WebView consome o stream depois.
    }

    /** Resposta vazia (usada para bloquear o beacon do Cloudflare). */
    public static InputStream emptyStream() {
        return new ByteArrayInputStream(new byte[0]);
    }

    /** Versão textual simples, usada para retornos de erro JSON. */
    public static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + message + "\"}";
    }

    /** Pacote de dados de resposta do proxy. */
    public static final class WebResourceResponseLike {
        public final String mime;
        public final InputStream stream;
        public int statusCode = 200;
        public long lengthBytes = -1;

        WebResourceResponseLike(String mime, InputStream stream) {
            this.mime = mime.toLowerCase(Locale.ROOT);
            this.stream = stream;
        }

        public Map<String, String> headers() {
            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "https://" + REAL_HOST);
            if (lengthBytes >= 0) {
                h.put("Content-Length", String.valueOf(lengthBytes));
            }
            return h;
        }
    }
}
