#!/bin/bash
# Teste automático no emulador: instala, roda as 3 opções, tira prints e procura travamentos.
set -u
PKG=br.com.checkupcelular
OUT=test-out
mkdir -p $OUT
n=0
shot() { n=$((n+1)); f=$(printf "%s/%02d-%s.png" $OUT $n "$1"); adb exec-out screencap -p > "$f"; echo "print: $f"; }
texts() { python3 scripts/ui.py texts > "$OUT/$(printf %02d $n)-$1.txt"; }
tap() {
  sleep 3   # espera a tela parar de rolar (o dump falha durante a rolagem)
  for i in $(seq 1 10); do
    p=$(python3 scripts/ui.py "$1") && { adb shell input tap $p; echo "toquei: $1"; return 0; }
    if [ $i -le 3 ]; then sleep 2; else adb shell input swipe 540 1600 540 900 300; sleep 2; fi
  done
  echo "NAO ACHEI: $1" | tee -a $OUT/erros.txt; return 1
}
wait_text() {
  for i in $(seq 1 $2); do python3 scripts/ui.py "$1" >/dev/null && { echo "apareceu: $1 (${i}x5s)"; return 0; }; sleep 5; done
  echo "TEMPO ESGOTADO esperando: $1" | tee -a $OUT/erros.txt; return 1
}
top() { for i in 1 2 3 4 5 6 7 8 9 10; do adb shell input swipe 540 600 540 1800 150; done; sleep 2; }
pages() { # tira print de cada "página" do relatório
  for i in 1 2 3 4 5 6 7 8; do shot "$1-p$i"; texts "$1-p$i"; adb shell input swipe 540 1700 540 500 400; sleep 1; done
}

adb logcat -c
# App de teste com nome de pacote de um app espião conhecido (deve aparecer como VÍRUS CONFIRMADO)
adb install -r espiao-teste.apk && echo "instalado app espião de teste (com.thetruth)"
adb install -r -g app.apk
adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell appops set $PKG GET_USAGE_STATS allow
# Instalador APK "esquecido" na pasta Download (deve aparecer na limpeza)
adb push espiao-teste.apk /sdcard/Download/WhatsApp-atualizado.apk
adb shell "echo lixo > /sdcard/Download/teste.tmp"

adb shell am start -n $PKG/.MainActivity; sleep 4
shot inicio; texts inicio

# ---- Varredura rápida ----
tap "Varredura rápida"; sleep 2; shot rapida-andamento
wait_text "Nota:" 36
top; pages rapida

# ---- Varredura completa ----
top; tap "Varredura completa"
wait_text "Resultado da varredura completa" 60
top; pages completa

# ---- Otimizar ----
top; tap "Otimizar"
wait_text "Memória RAM" 36
top; pages otimizar

# ---- Tela do VirusTotal ----
top; tap "Configurar VirusTotal"; sleep 2; shot virustotal; texts virustotal
adb shell input keyevent 4

adb logcat -d > $OUT/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG|ANR in $PKG" -A20 $OUT/logcat.txt > $OUT/crashes.txt || true
if [ -s $OUT/crashes.txt ]; then echo "TRAVAMENTO ENCONTRADO"; cat $OUT/crashes.txt; else echo "Nenhum travamento" > $OUT/crashes.txt; fi
grep -E "$PKG|CheckupCelular" $OUT/logcat.txt | grep -iE " E |W/System.err|Exception" | head -60 > $OUT/avisos.txt || true
touch $OUT/erros.txt
echo "FIM"
