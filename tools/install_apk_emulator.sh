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

echo "Instalando no Android Emulator..."
adb install -r "$APK"

echo "Confirmando pacote instalado..."
adb shell pm path "$PACKAGE"
adb shell dumpsys package "$PACKAGE" | grep -E "versionCode=|versionName="
echo "Instalação confirmada pelo Android PackageManager."
