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
import posixpath
import re
import shutil
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
 
REQUIRED_APP_PAGES = [
    "/apps/mensagens/index.html",
    "/apps/y/index.html",
    "/apps/telefone/index.html",
    "/apps/galeria/index.html",
    "/apps/tetris/index.html",
    "/apps/cobrinha/index.html",
    "/apps/calculadora/index.html",
    "/apps/configuracoes/index.html",
    "/apps/sem-nome/index.html",
    "/apps/sem-nome/rabiscos/index.html",
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
    """Resolve a reference and keep only files hosted by the original game."""
    if not isinstance(ref, str):
        return None
    ref = ref.strip()
    if not ref or ref.startswith(("data:", "blob:", "javascript:", "mailto:",
                                  "tel:", "#")):
        return None

    # urljoin also handles root-relative, relative, absolute and protocol-relative
    # references. Removing query/fragment is safe because the local mirror stores
    # one canonical copy of each path; the query is only a cache-buster.
    base_path = page_path.split("?", 1)[0]
    absolute = urllib.parse.urljoin(BASE + base_path, ref)
    parsed = urllib.parse.urlparse(absolute)
    if parsed.scheme not in ("http", "https"):
        return None
    if (parsed.hostname or "").lower() != "algorithomus.com.br":
        return None

    decoded = urllib.parse.unquote(parsed.path or "/")
    normalized = posixpath.normpath("/" + decoded.lstrip("/"))
    if normalized == "/.." or normalized.startswith("/../"):
        return None
    return normalized


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
    """Return every same-origin media URL, including singular fallbackUrl."""
    found = []

    def walk(obj):
        if isinstance(obj, dict):
            for key in ("url", "fallbackUrl"):
                value = obj.get(key)
                if isinstance(value, str) and value:
                    found.append(value)
            fallbacks = obj.get("fallbackUrls")
            if isinstance(fallbacks, list):
                found.extend(value for value in fallbacks
                             if isinstance(value, str) and value)
            for value in obj.values():
                if isinstance(value, (dict, list)):
                    walk(value)
        elif isinstance(obj, list):
            for value in obj:
                walk(value)

    try:
        walk(json.loads(data.decode("utf-8", errors="replace")))
    except Exception as exc:
        log("AVISO: manifesto ilegível (%s)" % exc)
    return found


def manifest_asset_paths(data):
    """Get canonical local paths for primary and fallback media assets."""
    try:
        manifest = json.loads(data.decode("utf-8", errors="replace"))
    except Exception as exc:
        raise ValueError("Manifesto de mídia inválido: %s" % exc)
    assets = manifest.get("assets") if isinstance(manifest, dict) else None
    if not isinstance(assets, list):
        raise ValueError("Manifesto de mídia sem a lista 'assets'.")
    primary, fallback = [], []
    for asset in assets:
        if not isinstance(asset, dict):
            continue
        primary_url = asset.get("url")
        if isinstance(primary_url, str) and primary_url:
            path = normalize_ref(primary_url, "/")
            if path:
                primary.append(path)
        for key in ("fallbackUrl", "fallbackUrls"):
            value = asset.get(key)
            values = value if isinstance(value, list) else [value]
            for candidate in values:
                if isinstance(candidate, str) and candidate:
                    path = normalize_ref(candidate, "/")
                    if path:
                        fallback.append(path)
    return sorted(set(primary)), sorted(set(fallback))


def main():
    # Never mix a new download with stale files from an earlier local build.
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT, exist_ok=True)

    downloaded = {}   # canonical path -> bytes (or None after a failed request)
    failed = []
    queue = ["/index.html", MANIFEST_URL] + APP_PAGES + DIARY_SEQUENCE
    queued = set()
    for item in queue:
        queued.add(item.split("?", 1)[0])

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
        if data is None and "?" not in url:
            data = fetch(url + "?v=1")
        if data is None:
            failed.append(path)
            downloaded[path] = None
            continue

        downloaded[path] = data
        log("OK %s (%d bytes)" % (path, len(data)))

        if path == "/media-manifest.json":
            for ref in parse_manifest(data):
                norm = normalize_ref(ref, "/")
                if norm and norm not in queued:
                    queued.add(norm)
                    queue.append(norm)

        if path.lower().endswith(TEXT_EXT):
            for ref in extract_refs(data, path):
                if ref not in queued:
                    queued.add(ref)
                    queue.append(ref)

    ok = 0
    for path, data in downloaded.items():
        if data is None:
            continue
        save(path, data)
        ok += 1

    log("Baixados: %d | Falhas: %d | Total: %d" %
        (ok, len(failed), len(downloaded)))
    for path in failed:
        log("FALHOU: " + path)

    # A build must never publish a simulator shell with missing core apps or
    # manifest-declared primary media. Optional codec fallbacks are warnings.
    required = ["/index.html", "/media-manifest.json"] + REQUIRED_APP_PAGES
    missing = [path for path in required if downloaded.get(path) is None]
    manifest_bytes = downloaded.get("/media-manifest.json")
    if manifest_bytes:
        try:
            primary, fallbacks = manifest_asset_paths(manifest_bytes)
            missing.extend(path for path in primary if downloaded.get(path) is None)
            missing_fallbacks = [path for path in fallbacks
                                 if downloaded.get(path) is None]
        except ValueError as exc:
            log("ERRO FATAL: %s" % exc)
            return 1
    else:
        missing_fallbacks = []

    missing = sorted(set(missing))
    if missing:
        log("ERRO FATAL: espelho incompleto; arquivos essenciais ausentes:")
        for path in missing:
            log("AUSENTE: " + path)
        return 1

    for path in sorted(set(missing_fallbacks)):
        log("AVISO: fallback de mídia indisponível: " + path)

    if queue:
        log("ERRO FATAL: limite de %d arquivos atingido com %d itens ainda na fila." %
            (MAX_FILES, len(queue)))
        return 1

    if not os.path.isfile(os.path.join(OUT, "index.html")):
        log("ERRO FATAL: index.html não foi espelhado; abortando o build.")
        return 1

    log("Espelho completo: páginas principais e todas as mídias primárias validadas.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
