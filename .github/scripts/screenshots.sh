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

# Alterar e excluir lembretes e compromissos.
# O emulador não tem conta Google: cria uma agenda local para a Eve poder marcar compromissos.
adb shell content insert --uri "'content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=eve&account_type=LOCAL'" \
  --bind account_name:s:eve --bind account_type:s:LOCAL --bind name:s:Eve --bind calendar_displayName:s:Eve \
  --bind ownerAccount:s:eve --bind calendar_access_level:i:700 --bind visible:i:1 --bind sync_events:i:1 \
  --bind calendar_timezone:s:UTC --bind isPrimary:i:1 || true
adb shell content query --uri content://com.android.calendar/calendars --projection _id:name:account_type > screens/agendas.txt 2>&1 || true
ask "me lembra de tirar o lixo"
shot 30-lembrete-criado 6
ask "muda o lembrete do lixo para amanha cedo"
shot 31-lembrete-alterado 6
ask "marca uma reuniao amanha as 15"
shot 32-evento-criado 6
ask "muda a reuniao para as 16"
shot 33-evento-alterado 6
ask "desmarca a reuniao"
shot 34-evento-excluido 6
adb shell content query --uri content://com.android.calendar/events --projection _id:title:dtstart:eventLocation:deleted > screens/eventos.txt 2>&1 || true

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
