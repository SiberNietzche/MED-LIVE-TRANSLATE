; MED LIVE TRANSLATE - NSIS kurulum kancalari (Tauri sablonuna eklenir)
; Kurulum programi yalnizca uygulamayi kopyalar ve saniyeler icinde biter. Python ve modeller
; programin ILK ACILISINDA, ekranda yuzde / hiz / kalan sure gosterilerek indirilir.
; Kaldirmada: sonradan indirilen bilesenler de silinir (guncellemede silinmez).

!macro MLT_PS_BUL
  StrCpy $R9 "$WINDIR\Sysnative\WindowsPowerShell\v1.0\powershell.exe"
  ${IfNot} ${FileExists} "$R9"
    StrCpy $R9 "$WINDIR\System32\WindowsPowerShell\v1.0\powershell.exe"
  ${EndIf}
!macroend

; Program klasorunden calisan her islemi (ozellikle Python motoru) kapatir. Acik kalan bir motor
; python.exe / python311.dll'i kilitler; kaldirici bu dosyalari silemeyip takiliyordu.
!macro MLT_SURECLERI_KAPAT
  !insertmacro MLT_PS_BUL
  DetailPrint "Acik program islemleri kapatiliyor..."
  ; klasor yolu komut metnine yazilmaz, ortam degiskeniyle gecer: yolda kesme isareti (') olsa da bozulmaz
  System::Call 'Kernel32::SetEnvironmentVariable(t "MLT_KLASOR", t "$INSTDIR\")'
  nsExec::Exec `"$R9" -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $$_.ExecutablePath -and $$_.ExecutablePath.StartsWith($$env:MLT_KLASOR, [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { Stop-Process -Id $$_.ProcessId -Force -ErrorAction SilentlyContinue }"`
  Pop $0
  Sleep 1000
!macroend

; $1 klasoru $0'in icindeyse (buyuk/kucuk harf duyarsiz) $2 = 1
!macro MLT_ICINDE ANA
  StrCpy $1 "${ANA}"
  StrLen $3 $1
  StrCpy $4 $INSTDIR $3
  ${If} $3 > 0
  ${AndIf} $4 == $1
    StrCpy $2 1
  ${EndIf}
!macroend

; Masaustu / Belgeler / Indirilenler / OneDrive klasorleri Windows'un fidye yazilimi korumasi,
; OneDrive esitlemesi ve antivirusler tarafindan izlenir; dosya yazimi engellenebilir ve
; ~1,8 GB model bu klasorlere iner. Program Files / Windows yonetici izni ister.
; Boyle bir klasor secildiyse onerilen klasore (%LOCALAPPDATA%) gecmeyi teklif eder.
!macro MLT_KLASOR_DENETLE
  StrCpy $2 0
  !insertmacro MLT_ICINDE "$DESKTOP"
  !insertmacro MLT_ICINDE "$DOCUMENTS"
  !insertmacro MLT_ICINDE "$PROFILE\Downloads"
  !insertmacro MLT_ICINDE "$PROGRAMFILES"
  !insertmacro MLT_ICINDE "$PROGRAMFILES64"
  !insertmacro MLT_ICINDE "$WINDIR"
  ClearErrors
  ${WordFind} "$INSTDIR" "\OneDrive" "E+1{" $5
  ${IfNot} ${Errors}
    StrCpy $2 1
  ${EndIf}
  ClearErrors
  ${WordFind} "$INSTDIR" "\Desktop" "E+1{" $5
  ${IfNot} ${Errors}
    StrCpy $2 1
  ${EndIf}
  ; C:\Users altinda ama kendi kullanici klasorunuzun disinda (or. C:\Users\Desktop)
  StrCpy $6 $PROFILE
  ${GetParent} $6 $6
  StrLen $3 "$6\"
  StrCpy $4 $INSTDIR $3
  ${If} $4 == "$6\"
    StrLen $3 "$PROFILE\"
    StrCpy $4 $INSTDIR $3
    ${If} $4 != "$PROFILE\"
      StrCpy $2 1
    ${EndIf}
  ${EndIf}
  ${If} $2 == 1
    StrCpy $8 $INSTDIR
    StrCpy $7 "$LOCALAPPDATA\${PRODUCTNAME}"
    ${If} $PassiveMode == 1
      StrCpy $INSTDIR $7
    ${Else}
      MessageBox MB_YESNO|MB_ICONEXCLAMATION "Seçilen klasör uygun değil:$\r$\n$INSTDIR$\r$\n$\r$\nMasaüstü, Belgeler, İndirilenler ve OneDrive klasörleri Windows koruması ve antivirüs programları tarafından izlenir; kurulum dosyaları engellenebilir ve ~1,8 GB model bu klasöre iner.$\r$\n$\r$\nÖnerilen klasöre kurulsun mu? (Evet önerilir)$\r$\n$7" /SD IDYES IDNO mlt_klasor_son
      StrCpy $INSTDIR $7
    ${EndIf}
    CreateDirectory $INSTDIR
    SetOutPath $INSTDIR
    RMDir $8 ; Install bolumunun actigi bos klasor (icinde dosya varsa dokunmaz)
    DetailPrint "Kurulum klasörü: $INSTDIR"
  ${EndIf}
  mlt_klasor_son:
!macroend

!macro NSIS_HOOK_PREINSTALL
  !insertmacro MLT_KLASOR_DENETLE
  !insertmacro MLT_SURECLERI_KAPAT
  ; eski surumlerin (PyTorch'lu, ~10 GB) artiklari: yeni surum bunlari kullanmaz
  ${If} ${FileExists} "$INSTDIR\.venv\*.*"
    DetailPrint "Eski surum bilesenleri siliniyor (.venv, birkac dakika surebilir)..."
    RMDir /r "$INSTDIR\.venv"
  ${EndIf}
  ${If} ${FileExists} "$INSTDIR\python\tools\*.*"
    DetailPrint "Eski surum Python'u siliniyor..."
    RMDir /r "$INSTDIR\python"
  ${EndIf}
  RMDir /r "$INSTDIR\modeller\nllb-1.3B-ct2"
  RMDir /r "$INSTDIR\modeller\_gecici_kurmanji"
  RMDir /r "$INSTDIR\kurulum"
  Delete "$SMPROGRAMS\${PRODUCTNAME} - Bilesen Kurulumu.lnk"
!macroend

!macro NSIS_HOOK_POSTINSTALL
  DetailPrint "Tamamlandi. Programi ilk actiginizda konusma ve ceviri modelleri (~1,8 GB) indirilecek;"
  DetailPrint "ilerleme yuzdesi programin penceresinde gorunur."
  ; Windows simge onbellegini yenile (eski surumden kalan simge gorunmesin)
  StrCpy $0 "$SYSDIR\ie4uinit.exe"
  IfFileExists "$WINDIR\Sysnative\ie4uinit.exe" 0 +2
    StrCpy $0 "$WINDIR\Sysnative\ie4uinit.exe"
  nsExec::Exec '"$0" -show'
  Pop $0
  System::Call 'shell32::SHChangeNotify(i 0x08000000, i 0, p 0, p 0)'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  !insertmacro MLT_SURECLERI_KAPAT
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  Delete "$SMPROGRAMS\${PRODUCTNAME} - Bilesen Kurulumu.lnk"
  ${If} $UpdateMode <> 1
    ; binlerce dosya: her klasor ekranda yazilir ki "takildi" sanilmasin
    DetailPrint "Siliniyor: Python ve paketler..."
    RMDir /r "$INSTDIR\python"
    RMDir /r "$INSTDIR\.venv"
    DetailPrint "Siliniyor: modeller..."
    RMDir /r "$INSTDIR\modeller"
    RMDir /r "$INSTDIR\indirilenler"
    DetailPrint "Siliniyor: program dosyalari..."
    RMDir /r "$INSTDIR\canli_altyazi"
    RMDir /r "$INSTDIR\whisper"
    RMDir /r "$INSTDIR\kurulum"
    Delete "$INSTDIR\motor_gunluk.txt"
    Delete "$INSTDIR\kurulum_gunluk.txt"
    Delete "$INSTDIR\kurulum_ayrinti.txt"
    Delete "$INSTDIR\bilesenler_tamam.txt"
    Delete "$INSTDIR\kaliteli_tamam.txt"
    Delete "$INSTDIR\hizlandirma_tamam.txt"
    Delete "$INSTDIR\hizlandirma_kmr_tamam.txt"
    Delete "$INSTDIR\ekran_karti_gunluk.txt"
    Delete "$INSTDIR\gemini_api_anahtari.txt"
    Delete "$INSTDIR\google_api_anahtari.txt"
    RMDir "$INSTDIR"
  ${EndIf}
!macroend
