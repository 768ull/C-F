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
 * Ponte entre o JavaScript do jogo Algorithomus e o aplicativo. O jogo roda no
 * endereço real https://algorithomus.com.br dentro do WebView: com internet,
 * as chamadas /api/ seguem direto para os servidores originais pelo próprio
 * WebView (mesma origem do jogo); sem internet, a ponte devolve imediatamente
 * o erro "sem-internet" para o jogo continuar utilizável no modo offline com
 * os arquivos espelhados no APK.
 */
final class AlgorithmusApiBridge {

    private final Context context;

    AlgorithmusApiBridge(Context context) {
        this.context = context;
    }

    /** Indica ao jogo se há conexão de internet utilizável no momento. */
    @JavascriptInterface
    public boolean isOnline() {
        return AlgorithmusNet.isOnline(context);
    }

    /**
     * Chamadas POST /api/&lt;ação&gt; do jogo (wifi-state, wifi-clues, call, pattern,
     * secret-state, diary/questions, diary/answer, diary/prepare, diary/final-media).
     * Executada na thread de ponte do WebView; retorna a resposta JSON como String.
     * Usada como fallback quando não há conexão (devolve "sem-internet").
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
     * apps) antes de qualquer código do site rodar: redefine window.fetch para
     * que as chamadas em /api/ (relativas ou absolutas, no endereço real do
     * jogo) sejam tratadas de forma híbrida. Com internet, a chamada segue para
     * o fetch nativo do WebView, que conversa diretamente com os servidores
     * originais (mesma origem do jogo, com cookies e TLS reais). Sem internet,
     * respostas de leitura já obtidas podem ser reutilizadas do cache local; ações
     * sem cache recebem HTTP 503. As demais chamadas
     * seguem sempre para o fetch nativo.
     */
    public static final String FETCH_SHIM = """
            (function () {
              if (window.__CF_ARG_SHIM__) { return; }
              window.__CF_ARG_SHIM__ = true;

              var nativeFetch = window.fetch ? window.fetch.bind(window) : null;
              var ORIGIN = 'https://algorithomus.com.br';
              var API_BASE = ORIGIN + '/api/';
              var ACTION_OK = /^[A-Za-z0-9/_-]+$/;
              var CACHE_PREFIX = '__cf_hybrid_api_v1:';
              var MAX_CACHE_CHARS = 400000;

              function apiAction(url) {
                if (typeof url !== 'string') { return null; }
                var rest = null;
                if (url.indexOf(API_BASE) === 0) {
                  rest = url.substring(API_BASE.length);
                } else if (url.indexOf('/api/') === 0) {
                  rest = url.substring(5);
                } else {
                  return null;
                }
                var action = rest.split('?')[0].split('#')[0];
                if (!ACTION_OK.test(action)) { return null; }
                return action;
              }

              // Cache only read-only story data. Mutations and protected actions
              // must still be confirmed by the server when the device is online.
              function cacheable(action) {
                return action === 'messages/list' ||
                       action === 'messages/open' ||
                       action === 'messages/final' ||
                       action === 'diary/state' ||
                       action === 'diary/open';
              }

              function cacheKey(action, body) {
                // These read endpoints use tiny stable bodies (empty, contact id or day).
                // Encoding the body avoids hash collisions between different contacts.
                return CACHE_PREFIX + action + ':' + encodeURIComponent(body || '{}');
              }

              function readCache(action, body) {
                if (!cacheable(action)) { return null; }
                try { return window.localStorage.getItem(cacheKey(action, body)); }
                catch (e) { return null; }
              }

              function writeCache(action, body, text) {
                if (!cacheable(action) || typeof text !== 'string' ||
                    text.length === 0 || text.length > MAX_CACHE_CHARS) return;
                try { window.localStorage.setItem(cacheKey(action, body), text); }
                catch (e) { /* armazenamento cheio: mantém a resposta online */ }
              }

              function unavailableResponse(text) {
                return Promise.resolve(new Response(text, {
                  status: 503,
                  statusText: 'Service Unavailable',
                  headers: { 'Content-Type': 'application/json' }
                }));
              }

              function offlineResponse(action, body) {
                var cached = readCache(action, body);
                if (cached !== null) {
                  return Promise.resolve(new Response(cached, {
                    status: 200,
                    statusText: 'OK',
                    headers: {
                      'Content-Type': 'application/json',
                      'X-CF-Offline-Cache': 'hit'
                    }
                  }));
                }
                return unavailableResponse('{"ok":false,"error":"sem-internet"}');
              }

              window.fetch = function (input, init) {
                var url = null;
                var body = '{}';
                try {
                  if (typeof input === 'string') {
                    url = input;
                  } else if (input && typeof input.url === 'string') {
                    url = input.url;
                    if (input.body && typeof input.body === 'string') { body = input.body; }
                  }
                  if (init && typeof init.body === 'string') { body = init.body; }
                  var action = apiAction(url);
                  if (action !== null) {
                    if (ApiBridge.isOnline()) {
                      // Send each online POST once. On a network failure, reuse
                      // only a previously cached read response when one exists.
                      if (!nativeFetch) {
                        return Promise.resolve(new Response(ApiBridge.fetchApi(action, body), {
                          status: 200,
                          statusText: 'OK',
                          headers: { 'Content-Type': 'application/json' }
                        }));
                      }
                      return nativeFetch(input, init).then(function (response) {
                        var contentType = response.headers ?
                            (response.headers.get('Content-Type') || '') : '';
                        if (response.status >= 500 && cacheable(action)) {
                          return offlineResponse(action, body);
                        }
                        if (!response.ok || !cacheable(action) ||
                            contentType.toLowerCase().indexOf('json') === -1) {
                          return response;
                        }
                        return response.clone().text().then(function (text) {
                          try {
                            var payload = JSON.parse(text);
                            if (payload && payload.ok === false) return response;
                          } catch (e) { return response; }
                          writeCache(action, body, text);
                          return new Response(text, {
                            status: response.status,
                            statusText: response.statusText,
                            headers: response.headers
                          });
                        }).catch(function () { return response; });
                      }).catch(function () { return offlineResponse(action, body); });
                    }
                    return offlineResponse(action, body);
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
