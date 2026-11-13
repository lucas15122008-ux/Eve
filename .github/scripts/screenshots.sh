#!/usr/bin/env bash
# Instala a Eve no emulador, conversa com a IA de demonstração e tira prints em pé e deitada.
set -x
PKG=app.eve.assistente
ACT=$PKG/.MainActivity
mkdir -p screens
shot() { sleep "${2:-0}"; adb exec-out screencap -p > "screens/$1.png"; }
ask() { adb shell am start -n $ACT --activity-single-top --es eve_teste_pergunta "\"$1\""; }
rotate() { adb shell wm user-rotation lock "$1" || adb shell settings put system user_rotation "$1"; }

adb install -r app/build/outputs/apk/debug/app-debug.apk
for p in RECORD_AUDIO READ_CALENDAR WRITE_CALENDAR POST_NOTIFICATIONS; do
  adb shell pm grant $PKG android.permission.$p || true
done
# Usa a IA de demonstração (não precisa de internet nem de chave).
adb shell "run-as $PKG mkdir -p shared_prefs"
adb shell "run-as $PKG sh -c 'echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?><map><string name=\\\"provider\\\">demo</string><string name=\\\"demo_api_key\\\">x</string></map>\" > shared_prefs/eve_settings.xml'"
adb shell dumpsys package $PKG | grep -E "versionName|versionCode" > screens/versao.txt
adb logcat -c
adb shell settings put system accelerometer_rotation 0
rotate 0

adb shell am start -W -n $ACT
shot 01-inicio 8
ask "o que eu tenho na agenda hoje"
shot 02-pensando 1
shot 03-agenda 5
ask "na verdade e as 6"
shot 04-pensando 1
shot 05-resposta 5
shot 06-resposta-depois 6
rotate 1
shot 07-deitado-resposta 6
ask "o que eu tenho na agenda hoje"
shot 08-deitado-agenda 7
rotate 0
shot 09-em-pe-de-novo 6

# Fase 3: vendas do Mercado Livre (vendas de exemplo, nada real).
ask "quanto eu vendi essa semana"
shot 12-vendas 8
adb shell input swipe 540 1900 540 900 400
shot 13-vendas-rolado 2
rotate 1
shot 14-deitado-vendas 6
rotate 0
sleep 4
# Perguntas, mensagens e reclamações (de exemplo).
ask "tem pergunta pra responder no mercado livre"
shot 17-pendencias 8
# Configurações, com os campos do Mercado Livre.
adb shell input tap 996 212
shot 15-configuracoes 3
adb shell input swipe 540 1700 540 700 400
shot 16-configuracoes-ml 2
adb shell input keyevent KEYCODE_BACK
sleep 1

# De novo, com as animações do sistema desligadas (como "Remover animações" do celular).
adb shell settings put global animator_duration_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global window_animation_scale 0
adb shell am force-stop $PKG
adb shell am start -W -n $ACT
shot 10-sem-animacoes-inicio 6
ask "na verdade e as 6"
shot 11-sem-animacoes-resposta 7

# Humores da Eve (com as animações do sistema ligadas de novo).
adb shell settings put global animator_duration_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global window_animation_scale 1
adb shell am force-stop $PKG
adb shell am start -W -n $ACT
sleep 4
n=20
for m in normal surpresa preocupada sonolenta piscadinha triste animada confusa; do
  adb shell am start -n $ACT --activity-single-top --es eve_teste_humor $m
  shot "$n-humor-$m" 2
  n=$((n + 1))
done

adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml screens/ui.xml
PID=$(adb shell pidof $PKG)
adb logcat -d -v brief | grep -E "\($PID\)|AndroidRuntime|FATAL" | tail -300 > screens/logcat.txt
ls -la screens
exit 0
