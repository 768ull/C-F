#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Espelha o conteúdo público de https://algorithomus.com.br para dentro do APK
do C-F (app/src/main/assets/site), permitindo o funcionamento híbrido
offline/online do jogo Algorithomus dentro do aplicativo.

Estratégia:
  1. Baixa o index.html e o /media-manifest.json.
  2. Baixa as páginas dos apps (apps/<id>/) e a lista fixa de mídias do boot-loader
     (áudios do Diário, áudios da Maya, frames dos rabiscos, vídeos e áudios do sistema).
  3. Extrai, por expressão regular, todas as referências de HTML/CSS/JS
     (src, href, url(...), caminhos citados em strings) e baixa o que for da mesma origem.
  4. Falhas individuais NÃO derrubam o build; a ausência do index.html sim.

Usa apenas a biblioteca padrão (urllib), para rodar no GitHub Actions sem dependências.
"""

import json
import os
import re
import sys
import urllib.parse
import urllib.request

BASE = "https://algorithomus.com.br"
OUT = os.path.join("app", "src", "main", "assets", "site")
MANIFEST_URL = "/media-manifest.json?v=protected-dia6"
USER_AGENT = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
MAX_FILES = 1500

# Páginas dos apps carregadas em iframe pelo shell (js/config).
APP_PAGES = [
    "/apps/mensagens/?v=12",
    "/apps/y/?v=5",
    "/apps/telefone/?v=2",
    "/apps/galeria/?v=9",
    "/apps/tetris/?v=2",
    "/apps/cobrinha/?v=2",
    "/apps/calculadora/?v=2",
    "/apps/configuracoes/?v=10",
    "/apps/sem-nome/?v=5",
    "/apps/sem-nome/rabiscos/index.html?v=2",
]

# Lista fixa de mídias do Diário/Mensagens/sistema citadas no core/boot-loader.
DIARY_SEQUENCE = [
    "/apps/sem-nome/audios/DIA 1.mp3",
    "/apps/sem-nome/audios/DIA 2.mp3",
    "/apps/sem-nome/audios/DIA 3.mp3",
    "/apps/sem-nome/audios/DIA 4.mp3",
    "/apps/sem-nome/audios/DIA 5.mp3",
    "/apps/sem-nome/audios/DIA 6.mp3",
    "/apps/sem-nome/audios/LOOP FAÍSCAS.mp3",
    "/apps/sem-nome/rabiscos/frame1.png",
    "/apps/sem-nome/rabiscos/frame2.png",
    "/apps/sem-nome/rabiscos/frame3.png",
    "/apps/sem-nome/rabiscos/frame4.png",
    "/apps/sem-nome/rabiscos/frame5.png",
    "/apps/sem-nome/video/Horizontal.mp4",
    "/apps/sem-nome/video/vertical.mp4",
    "/apps/mensagens/audio/Maya/MAYA 1.mp3",
    "/apps/mensagens/audio/Maya/MAYA 2.mp3",
    "/apps/mensagens/audio/Maya/MAYA LIGAÇÃO.mp3",
    "/apps/mensagens/audio/vibrando.mp3",
    "/apps/mensagens/pfp/imagem-012.webp",
    "/assets/img/logo/logotest.png",
    "/assets/audio/audio-001.ogg",
    "/assets/audio/audio-002.ogg",
    "/assets/audio/audio-003.ogg",
    "/assets/audio/audio-004.ogg",
    "/assets/audio/audio-005.ogg",
    "/assets/audio/audio-006.ogg",
    "/assets/audio/audio-007.ogg",
    "/assets/audio/audio-008.ogg",
    "/assets/audio/audio-009.ogg",
]

TEXT_EXT = (".html", ".htm", ".css", ".js", ".json", ".svg", ".txt", ".xml")

ATTR_RE = re.compile(
    r'(?:src|href|poster|data-src|action)\s*=\s*["\']([^"\']+)["\']', re.I)
CSS_URL_RE = re.compile(r'url\(\s*["\']?([^"\')]+)["\']?\s*\)', re.I)
FILE_RE = re.compile(
    r'["\']([^"\']*?\.(?:html?|css|js|json|png|jpe?g|webp|svg|gif|ico|mp3|ogg|oga|wav|m4a|mp4|webm|woff2?|ttf))(?:\?[^"\']*)?["\']',
    re.I)


def log(msg):
    print("[mirror] " + msg, flush=True)


def fetch(url, retries=1):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT,
                                               "Accept": "*/*"})
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(req, timeout=15) as resp:
                return resp.read()
        except Exception as exc:
            if attempt >= retries:
                log("AVISO: falha ao baixar %s (%s)" % (url, exc))
                return None
    return None


def normalize_ref(ref, page_path):
    """Converte uma referência encontrada em caminho absoluto do site, ou None."""
    if not ref:
        return None
    ref = ref.strip()
    if not ref or ref.startswith(("data:", "blob:", "javascript:", "mailto:",
                                  "tel:", "#")):
        return None
    if ref.startswith(("http://", "https://")):
        parsed = urllib.parse.urlparse(ref)
        if parsed.netloc != "algorithomus.com.br":
            return None  # externo (Cloudflare/YouTube): não espelha
        ref = parsed.path
        if not ref:
            return None
    ref = ref.split("#", 1)[0]
    if not ref:
        return None
    # caminhos relativos usados pelo cenário do jogo (pasta de avatares)
    if ref.startswith("pfp/"):
        return "/apps/mensagens/" + ref
    # resolve relativo ao diretório da página que citou
    base_dir = page_path if page_path.endswith("/") else page_path.rsplit("/", 1)[0] + "/"
    joined = urllib.parse.urljoin(base_dir, ref)
    path = urllib.parse.unquote(joined)
    if not path.startswith("/"):
        path = "/" + path
    return path


def disk_path(path):
    """Caminho relativo dentro de assets/site a partir do caminho da URL."""
    p = path
    if p.endswith("/"):
        p += "index.html"
    return p.lstrip("/")


def save(path, data):
    rel = disk_path(path)
    full = os.path.join(OUT, *rel.split("/"))
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "wb") as fh:
        fh.write(data)


def extract_refs(data, page_path):
    """Extrai referências de um arquivo de texto (HTML/CSS/JS) baixado."""
    try:
        text = data.decode("utf-8", errors="replace")
    except Exception:
        return []
    refs = []
    for regex in (ATTR_RE, CSS_URL_RE, FILE_RE):
        refs.extend(regex.findall(text))
    out = []
    for r in refs:
        p = normalize_ref(r, page_path)
        if p:
            out.append(p)
    return out


def parse_manifest(data):
    """Percorre o manifesto e devolve todos os caminhos citados (url e fallbackUrls)."""
    found = []

    def walk(obj):
        if isinstance(obj, dict):
            url = obj.get("url")
            if isinstance(url, str):
                found.append(url)
            for fb in (obj.get("fallbackUrls") or []):
                if isinstance(fb, str):
                    found.append(fb)
            for value in obj.values():
                walk(value)
        elif isinstance(obj, list):
            for value in obj:
                walk(value)

    try:
        walk(json.loads(data.decode("utf-8", errors="replace")))
    except Exception as exc:
        log("AVISO: manifesto ilegível (%s)" % exc)
    return found


def main():
    os.makedirs(OUT, exist_ok=True)
    downloaded = {}   # caminho -> bytes
    failed = []
    queue = ["/index.html", MANIFEST_URL] + APP_PAGES + DIARY_SEQUENCE
    queued = set()
    for item in queue:
        queued.add(item.split("?", 1)[0])
    queued.discard(None)

    while queue and len(downloaded) < MAX_FILES:
        entry = queue.pop(0)
        if "?" in entry:
            path, query = entry.split("?", 1)
            url = BASE + urllib.parse.quote(path) + "?" + query
        else:
            path = entry
            url = BASE + urllib.parse.quote(path)

        if path in downloaded:
            continue

        data = fetch(url)
        if data is None:
            # GET sem query (alguns servidores exigem o ?v= do cache busting)
            if "?" not in url:
                data = fetch(url + "?v=1")
        if data is None:
            failed.append(path)
            downloaded[path] = None
            continue

        downloaded[path] = data
        log("OK %s (%d bytes)" % (path, len(data)))

        # o manifesto aponta para as mídias oficiais
        if path == "/media-manifest.json":
            for ref in parse_manifest(data):
                norm = normalize_ref(ref, "/")
                if norm and norm.split("?", 1)[0] not in queued:
                    queued.add(norm.split("?", 1)[0])
                    queue.append(norm)

        # extrai referências de arquivos de texto
        lower = path.lower()
        if lower.endswith(TEXT_EXT):
            for ref in extract_refs(data, path):
                key = ref.split("?", 1)[0]
                if key not in queued:
                    queued.add(key)
                    queue.append(ref)

    ok = 0
    for path, data in downloaded.items():
        if data is None:
            continue
        save(path, data)
        ok += 1

    log("Baixados: %d | Falhas: %d | Total: %d" % (ok, len(failed), len(downloaded)))
    for p in failed:
        log("FALHOU: " + p)

    if not os.path.exists(os.path.join(OUT, "index.html")):
        log("ERRO FATAL: index.html não foi espelhado; abortando o build.")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
