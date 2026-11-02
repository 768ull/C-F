package br.com.colecaocf;

import android.content.Context;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Ponte entre o JavaScript do jogo Algorithomus e o aplicativo:
 * intercepta as chamadas fetch('/api/...') e as encaminha ao servidor real
 * (https://algorithomus.com.br) mesmo com o jogo rodando no domínio virtual,
 * mantendo o WebView sem acesso direto à rede. Quando não há conexão, devolve
 * imediatamente o erro "sem-internet" para o jogo continuar utilizável no modo
 * offline com os arquivos espelhados.
 */
final class AlgorithmusApiBridge {

    private final Context context;

    AlgorithmusApiBridge(Context context) {
        this.context = context;
    }

    /**
     * Chamadas POST /api/&lt;ação&gt; do jogo (wifi-state, wifi-clues, call, pattern,
     * secret-state, diary/questions, diary/answer, diary/prepare, diary/final-media).
     * Executada na thread de ponte do WebView; retorna a resposta JSON como String.
     */
    @JavascriptInterface
    public String fetchApi(String action, String body) {
        if (action == null) {
            return AlgorithmusNet.errorJson("acao-invalida");
        }
        String clean = action.replaceAll("[^A-Za-z0-9/_-]", "");
        if (clean.isEmpty()) {
            return AlgorithmusNet.errorJson("acao-invalida");
        }
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (!AlgorithmusNet.isOnline(context)) {
            return AlgorithmusNet.errorJson("sem-internet");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(AlgorithmusNet.REAL_ORIGIN + "/api/" + clean);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 400
                    ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(stream);
            if (code >= 200 && code < 400) {
                return text;
            }
            return AlgorithmusNet.errorJson("http-" + code);
        } catch (IOException | RuntimeException e) {
            return AlgorithmusNet.errorJson("sem-internet");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "{}";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
            if (buffer.size() > 4 * 1024 * 1024) {
                break; // limite de segurança de 4 MB
            }
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Script injetado no início de cada documento (página principal e iframes dos
     * apps) antes de qualquer código do site rodar: redefine window.fetch para que
     * chamadas em /api/ sejam atendidas por AlgorithmusApiBridge.fetchApi (POST ao
     * servidor real), mantendo a mesma assinatura de Promise/Response que o jogo
     * espera. As demais chamadas seguem para o fetch nativo, que o aplicativo
     * mantém restrito ao domínio virtual.
     */
    public static final String FETCH_SHIM = """
            (function () {
              if (window.__CF_ARG_SHIM__) { return; }
              window.__CF_ARG_SHIM__ = true;

              var nativeFetch = window.fetch ? window.fetch.bind(window) : null;
              var ACTION_OK = /^[A-Za-z0-9/_-]+$/;

              function apiAction(url) {
                if (typeof url !== 'string') { return null; }
                if (url.indexOf('/api/') !== 0) { return null; }
                var rest = url.substring(5);
                var action = rest.split('?')[0].split('#')[0];
                if (!ACTION_OK.test(action)) { return null; }
                return action;
              }

              window.fetch = function (input, init) {
                var url = null;
                var body = '{}';
                try {
                  if (typeof input === 'string') {
                    url = input;
                  } else if (input && typeof input.url === 'string') {
                    url = input.url;
                    if (input.body) { body = String(input.body); }
                  }
                  if (init && init.body) { body = String(init.body); }
                  var action = apiAction(url);
                  if (action !== null) {
                    var text = ApiBridge.fetchApi(action, body);
                    return Promise.resolve(
                      new Response(text, {
                        status: 200,
                        statusText: 'OK',
                        headers: { 'Content-Type': 'application/json' }
                      })
                    );
                  }
                } catch (e) {
                  /* segue para o fetch nativo */
                }
                if (nativeFetch) { return nativeFetch(input, init); }
                return Promise.reject(new Error('fetch indisponivel'));
              };
            })();
            """;
}
