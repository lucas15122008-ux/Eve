#!/usr/bin/env bash
# Instala a Eve no emulador, abre em pé e deitada e tira prints.
set -x
PKG=app.eve.assistente
mkdir -p screens
adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in RECORD_AUDIO READ_CALENDAR WRITE_CALENDAR POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p || true
done
# Configuração de teste (chave falsa) para não abrir a tela de configurações.
adb shell "run-as $PKG mkdir -p shared_prefs"
adb shell "run-as $PKG sh -c 'echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?><map><string name=\\\"provider\\\">gemini</string><string name=\\\"gemini_api_key\\\">chave-de-teste</string></map>\" > shared_prefs/eve_settings.xml'"
adb shell "run-as $PKG cat shared_prefs/eve_settings.xml"
adb shell dumpsys package $PKG | grep -E "versionName|versionCode" > screens/versao.txt
adb logcat -c
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell am start -W -n $PKG/.MainActivity
sleep 8
adb exec-out screencap -p > screens/1-em-pe.png
sleep 1
adb exec-out screencap -p > screens/2-em-pe-depois.png
adb shell settings put system user_rotation 1
sleep 6
adb exec-out screencap -p > screens/3-deitado.png
adb shell settings put system user_rotation 0
sleep 4
adb exec-out screencap -p > screens/4-em-pe-de-novo.png
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml screens/ui.xml
adb logcat -d -v brief | grep -iE "eve|AndroidRuntime|compose|FATAL" | tail -300 > screens/logcat.txt
ls -la screens
exit 0
