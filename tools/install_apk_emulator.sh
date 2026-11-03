#!/usr/bin/env bash
set -euo pipefail

if [[ "${CF_STABLE_SIGNING_AVAILABLE:-false}" == "true" ]]; then
  APK="app/build/outputs/apk/release/app-release.apk"
  PACKAGE="br.com.cfa"
else
  APK="app/build/outputs/apk/release/app-release.apk"
  PACKAGE="br.com.cfa.debug"
fi

echo "APK selecionado: $APK"
echo "Pacote esperado: $PACKAGE"
test -s "$APK"

adb wait-for-device
BOOT_STATUS="$(adb shell getprop sys.boot_completed | tr -d '\r')"
if [[ "$BOOT_STATUS" != "1" ]]; then
  echo "::error::O emulador Android não terminou a inicialização (sys.boot_completed=$BOOT_STATUS)."
  exit 1
fi

echo "Instalando no Android Emulator (limite de 120 segundos)..."
if ! timeout 120s adb install -r "$APK"; then
  echo "::error::O Android PackageManager rejeitou ou não concluiu a instalação do APK."
  echo "Informações do dispositivo:"
  adb shell getprop ro.build.version.release || true
  adb shell getprop ro.build.version.sdk || true
  echo "Estado do pacote:"
  adb shell dumpsys package "$PACKAGE" 2>&1 | tail -n 80 || true
  echo "Eventos recentes do instalador:"
  adb logcat -d -t 600 2>&1 | grep -iE 'PackageManager|PackageInstaller|INSTALL_FAILED|INSTALL_PARSE_FAILED|parse.*package|signature|verif' | tail -n 100 || true
  exit 1
fi

echo "Confirmando pacote instalado..."
adb shell pm path "$PACKAGE"
adb shell dumpsys package "$PACKAGE" | grep -E "versionCode=|versionName="
echo "Instalação confirmada pelo Android PackageManager."
