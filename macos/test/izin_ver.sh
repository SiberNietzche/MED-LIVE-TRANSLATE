#!/bin/bash
# YALNIZCA TEST MAKİNESİ İÇİN: "Ekran ve Sistem Sesi Kaydı" iznini önceden verir (GitHub'ın Mac
# sunucularında SIP kapalıdır; gerçek kullanıcıda bu izni kullanıcı kendisi verir).
# Kullanım: izin_ver.sh <izin verilecek program yolu>...
SISTEM_DB="/Library/Application Support/com.apple.TCC/TCC.db"
KULLANICI_DB="$HOME/Library/Application Support/com.apple.TCC/TCC.db"
SIMDI=$(date +%s)
for istemci in "$@"; do
  for db in "$SISTEM_DB" "$KULLANICI_DB"; do
    [ -f "$db" ] || continue
    for hizmet in kTCCServiceScreenCapture kTCCServiceAudioCapture; do
      sudo sqlite3 "$db" "INSERT OR REPLACE INTO access (service, client, client_type, auth_value, auth_reason, auth_version, flags, last_modified) VALUES ('$hizmet', '$istemci', 1, 2, 4, 1, 0, $SIMDI);" 2>&1 | head -2
    done
  done
  echo "izin: $istemci"
done
