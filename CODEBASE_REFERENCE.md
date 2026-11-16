# GSM2SIP (`com.callagent.gateway`) — Kapsamlı Kod Tabanı ve Mimari Referans Belgesi

> **Amaç:** Bu belge, `gsm2sip` projesinin tüm kaynak dosyalarını, sınıflarını, metodlarını, durum makinelerini (state machines), ses/ALSA mixer yönlendirme profillerini, SIP/RTP/SRTP/DTMF/SMS protokol detaylarını, Android sürüm kısıtlamaları için uygulanan çözümleri (workarounds), Magisk modül betiklerini ve Go tabanlı `tinymix` aracını eksiksiz olarak indeksler. Sonraki geliştirme ve hata ayıklama adımlarında kod tabanını tekrar tekrar taramaya gerek kalmadan doğrudan referans olarak kullanılmak üzere hazırlanmıştır.

---

## 1. Proje Özeti ve Derleme Yapılandırması

- **Paket Adı / Namespace:** `com.callagent.gateway`
- **Uygulama Adı:** `gsm2sip`
- **Sürüm:** `versionName = "1.4.14"`, `versionCode = 455` (`magisk/module.prop` içinde `v1.4.14`, `versionCode=377`)
- **SDK Seviyeleri:** `compileSdk = 34`, `minSdk = 26` (Android 8.0), `targetSdk = 34` (Android 14)
- **Dil / Toolchain:** Kotlin `1.9.22`, Android Gradle Plugin `8.2.2`, Java 17 (`VERSION_17`), Go (statik `tinymix` derlemesi için)
- **Bağımlılıklar (`app/build.gradle.kts`):**
  - `androidx.core:core-ktx:1.12.0`
  - `androidx.appcompat:appcompat:1.6.1`
  - `com.google.android.material:material:1.11.0`
  - `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`
  - Test: `junit:junit:4.13.2`
  - *(Harici SIP, RTP, SRTP, STUN veya JSON kütüphanesi yoktur; tamamı sıfırdan Kotlin stdlib ve Android/JDK API'leri ile yazılmıştır.)*
- **İmzalama (Signing) Kuralı (`app/build.gradle.kts` satır 18–31):**
  - Hem `debug` hem `release` derlemeleri `~/.android/debug.keystore` (`androiddebugkey` / `android`) ile imzalanır.
  - **Nedeni:** `build.sh` varsayılan olarak `assembleRelease` derler çünkü `debuggable=true` olduğunda Android Runtime (ART) ses döngülerini (`RtpSession` / `G722Codec`) deoptimize edilmiş modda çalıştırarak 20ms frame bütçesini aşar ve ses kopmalarına yol açar. Aynı debug anahtarıyla imzalanması sayesinde Magisk `priv-app` içindeki APK ile `adb install -r` güncellemeleri imza çakışması (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) yaşamaz.

---

## 2. Dizin ve Dosya Haritası

```text
gsm2sip-main/
├── build.gradle.kts                     # Root Gradle yapılandırması
├── settings.gradle.kts                  # Gradle proje adı ve modül tanımı (:app)
├── gradle.properties                    # JVM argümanları ve AndroidX bayrakları
├── setup.sh                             # SDK/JDK/Go ortam kurulum betiği (1–219)
├── build.sh                             # APK + Go tinymix (arm64/arm32) + Magisk ZIP derleme betiği (1–266)
├── README.md                            # Proje dokümantasyonu ve donanım notları (1–788)
├── tools/
│   ├── check-device.sh                  # Cihazın Qualcomm incall_music / VOC_REC desteğini denetleyen betik (1–134)
│   ├── adb-over-network.sh              # LineageOS'ta her açılışta ADB TCP:5555 aktif eden сервис betiği (1–29)
│   └── tinymix/
│       ├── main.go                      # Sıfır bağımlılıklı Go ALSA /dev/snd/controlC0 ioctl aracı (1–524)
│       ├── abi64.go                     # arm64/amd64 için ALSA struct boyutları (elemList=80B, elemValue=1224B) (1–74)
│       └── abi32.go                     # arm/386 için ALSA struct boyutları (elemList=72B, elemValue=712B) (1–74)
├── magisk/
│   ├── module.prop                      # Magisk modül kimliği (id=sip-gsm-gateway) (1–6)
│   ├── install.sh                       # Kurulum betiği: priv-app kopyalama, ABI'ye göre tinymix seçimi, PermissionController gizleme (1–148)
│   ├── post-fs-data.sh                  # skip_mount dosyasını silerek overlay'i garanti altına alır (1–6)
│   ├── service.sh                       # Boot sonrası: APK senkronizasyonu, Magisk sqlite su policy=2, pm grant, appops RECORD_AUDIO, SMS limit kaldırma (1–307)
│   ├── system.prop                      # Qualcomm/Exynos ses ve eşzamanlı kayıt (concurrent_capture) özellikleri (1–28)
│   └── system/etc/permissions/
│       └── privapp-permissions-gateway.xml # CAPTURE_AUDIO_OUTPUT, MODIFY_PHONE_STATE vb. priv-app izin beyaz listesi (1–28)
└── app/src/
    ├── main/
    │   ├── AndroidManifest.xml          # İzinler, Activity, InCallService, ConnectionService, GatewayService, BroadcastReceiver'lar (1–156)
    │   ├── java/com/callagent/gateway/
    │   │   ├── GatewayApp.kt            # Application sınıfı; StrictMode ağ kısıtlamasını kaldırır (1–19)
    │   │   ├── RootShell.kt             # Kalıcı (persistent) root `su` kabuğu tekil (singleton) yöneticisi (1–313)
    │   │   ├── DeviceProfile.kt         # SoC/Cihaz tespiti ve ALSA mixer / ses yönlendirme profilleri (1–1154)
    │   │   ├── MainActivity.kt          # Tek-aktiviteli kullanıcı arayüzü (Home, Config, Logs, Modallar, Sağlık kontrolleri) (1–2033)
    │   │   ├── bridge/
    │   │   │   └── CallOrchestrator.kt  # GSM <-> SIP çağrı durum makinesi ve ses köprüsü orkestratörü (1–1285)
    │   │   ├── gsm/
    │   │   │   ├── CallConnectionService.kt # Telecom ConnectionService (Self-Managed / Call Provider) (1–58)
    │   │   │   ├── GsmCallService.kt    # Android InCallService bağlayıcısı (1–65)
    │   │   │   └── GsmCallManager.kt    # GSM çağrı kontrolü, çift SIM eşleştirme, ses seviyesi ve mixer yönetimi (1–845)
    │   │   ├── net/
    │   │   │   └── StunClient.kt        # RFC 5389 STUN istemcisi (7 sunucu, harici IP:port keşfi) (1–184)
    │   │   ├── rtp/
    │   │   │   ├── RtpPacket.kt         # RTP başlık (header) encode/decode (extension & padding destekli) (1–94)
    │   │   │   ├── G722Codec.kt         # ITU-T G.722 (16kHz wideband SB-ADPCM, 8kHz RTP clock) enkoder/dekoder (1–426)
    │   │   │   ├── Srtp.kt              # RFC 3711 (AES_CM_128_HMAC_SHA1_80) ve RFC 7714 (AEAD_AES_128/256_GCM) + SDES (1–337)
    │   │   │   ├── Dtmf.kt              # RFC 4733 telephone-event, SIP INFO DTMF ayrıştırıcı ve DtmfRelay (1–293)
    │   │   │   └── RtpSession.kt        # Çift yönlü RTP/SRTP ses motoru, Jitter Buffer, 4-tap Catmull-Rom upsampler, PcmaCodec (1–2408)
    │   │   ├── service/
    │   │   │   ├── BootReceiver.kt      # BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / MY_PACKAGE_REPLACED alıcısı (1–167)
    │   │   │   ├── CallLogStore.kt      # Çağrı ve SMS geçmişi kalıcı JSON deposu (SharedPreferences `call_history`, max 50) (1–188)
    │   │   │   └── GatewayService.kt    # Ön plan (Foreground) servisi, ağ seçimi (Cellular vs WiFi), SIP yaşam döngüsü, SMS kuyrukları (1–1858)
    │   │   ├── sip/
    │   │   │   ├── SipAuth.kt           # RFC 2617 MD5 Digest kimlik doğrulama (qop=auth destekli) (1–81)
    │   │   │   ├── SipTransport.kt      # TLS 1.2/1.3 (SSLSocket) çerçeveleyici (Content-Length tabanlı) (1–300)
    │   │   │   ├── SipMessage.kt        # SIP mesaj ayrıştırıcı (compact header destekli) ve SipBuilder + SdpInfo (1–671)
    │   │   │   ├── SipCall.kt           # Gelen/Giden SIP INVITE işlemleri (100/180/200/ACK/BYE/CANCEL/401) (1–486)
    │   │   │   └── SipClient.kt         # UDP/TLS SIP istemcisi, REGISTER, OPTIONS, MESSAGE, NAT keepalive, RegisterBackoff (1–993)
    │   │   └── sms/
    │   │       ├── SmsReceiver.kt       # Gelen GSM SMS (SMS_RECEIVED) yakalayıcı ve multipart birleştirici (1–117)
    │   │       ├── SmsStore.kt          # Gelen SMS kalıcı kuyruğu (`sms_queue` -> `pending`, max 200) (1–114)
    │   │       ├── SmsOutbox.kt         # Giden SMS kalıcı kuyruğu ve durum takibi (`sms_outbox` -> `messages`, max 200) (1–154)
    │   │       ├── SmsSender.kt         # Multipart SMS gönderimi, SIM subId seçimi, GSM 04.11 hata kodları (1–174)
    │   │       ├── SmsSendReceiver.kt   # SMS_SENT ve SMS_DELIVERED broadcast alıcısı, SMSC PDU ayrıştırma (1–129)
    │   │       └── Transliterate.kt     # GSM 7-bit karakter bozulmasını önleyen ASCII transliterasyon (1–116)
    │   └── res/
    │       ├── layout/activity_main.xml # Tüm UI sekmelerini barındıran tekil XML yerleşimi
    │       ├── values/colors.xml        # Renk paleti (accent=#34D399, primary=#2563EB, tls_badge=#CA8A04, srtp_badge=#2563EB, monitor_badge=#DC2626)
    │       └── values/strings.xml       # Bildirim ve uygulama metinleri
    └── test/java/com/callagent/gateway/rtp/
        ├── DtmfTest.kt                  # RFC 4733, SipInfoDtmf, DtmfDetector, DtmfRelay, RtpPacket birim testleri (1–176)
        └── PcmaCodecTest.kt             # ITU G.711 A-law referans karşılaştırma ve niceleme (quantization) testleri (1–56)
```

---

## 3. Paket ve Sınıf Bazında Detaylı Referans

### 3.1. Kök Paket (`com.callagent.gateway`)

#### 3.1.1. [`GatewayApp.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/GatewayApp.kt) (Satır 1–19)
- `Application` alt sınıfıdır (`AndroidManifest.xml` içinde `android:name=".GatewayApp"`).
- `onCreate()` içinde `StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())` çağırır.
- **Amacı:** GSM durum değişiklikleri (`InCallService` callback'leri) ana iş parçacığında (main thread) tetiklenir ve doğrudan SIP `BYE` veya `200 OK` UDP paketi gönderebilir; Android'in `NetworkOnMainThreadException` fırlatmasını engeller.

#### 3.1.2. [`RootShell.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/RootShell.kt) (Satır 1–313)
Her `tinymix`, `appops` veya `service call` komutu için yeni bir `su` süreci başlatmak ~50–100ms sürdüğünden ve Magisk'te bildirim fırtınası yarattığından, tek bir `su` sürecini açık tutan thread-safe tekil (singleton) nesnedir.
- **Mimarisi:**
  - `queue: LinkedBlockingQueue<Command>` ile komutları sıralar.
  - `RootShell-Worker` adlı tek bir daemon thread komutları sırayla yazar ve çıktı sonundaki `___ROOT_SHELL_DONE___ <exitCode>` işaretçisini (marker) bekler.
  - `RootShell-Err` thread'i `stderr` akışını sürekli boşaltır (aksi takdirde 64KB boru dolduğunda `su` kilitlenir).
  - `shellGen: Int` nesil sayacı: Zaman aşımı işleyicisi (watchdog) eski kabuğu kapatırken yeni açılmış kabuğu yanlışlıkla öldürmesini önler.
  - `deniedUntilMs`: Magisk root iznini reddederse (`su exited immediately`), arka arkaya `su` çağrılarıyla sistemi kilitlememek için **10 saniye** (`DENIED_BACKOFF_MS = 10_000L`) boyunca yeni denemeleri kısa devre yapar.
- **Temel Metodlar:**
  - `exec(cmd: String, timeoutMs: Long = 5000): String`: Senkron komut çalıştırır, stdout döner.
  - `execWithStatus(cmd: String, timeoutMs: Long = 5000): Pair<Int, String>`: Hem çıkış kodunu (`exitCode`) hem çıktıyı döner (`RtpSession.probeAlsaCaptureDevices` tarafından kullanılır).
  - `execAsync(cmd: String, timeoutMs: Long = 5000)`: Ateşle ve unut (fire-and-forget); kuyrukta 32'den fazla komut birikmişse düşürür.
  - `execBatch( commands: List<String>, timeoutMs: Long = 5000): String`: Komutları `\n` ile birleştirip tek seferde çalıştırır.
  - `warmUp()`: Servis başlarken `su` sürecini önceden hazırlar.
  - `shutdown()`: Servis kapanırken kabuğu temizler.

#### 3.1.3. [`DeviceProfile.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/DeviceProfile.kt) (Satır 1–1154)
Donanım (SoC / ses kodeği / HAL) farklılıklarını soyutlayan veri sınıfı (`data class DeviceProfile`) ve fabrika (`companion object`) metodlarını barındırır.
- **Tespit Mekanizması (`DeviceProfile.detect()` — Satır 508–577):**
  - `ro.board.platform`, `Build.SOC_MODEL` (API 31+) veya `ro.soc.model`, `/sys/devices/soc0/machine`, `/sys/devices/soc0/soc_id`, `Build.DEVICE`, `Build.BOARD`, `Build.HARDWARE` değerlerini okur (`getSystemProperty` ve `readSysfs`).
- **Yapılandırma Alanları (Satır 28–505):**
  - `name`, `chipset`, `PreferVoiceRecognition`, `silenceLocalAudio`, `voiceDownlinkWorks`, `playbackToTelephonyTx`, `incallMusicBeforeTrack`, `playbackStereo`, `playbackBufferMs`, `audioRouteSettleDelayMs`, `audioStopSettleDelayMs`, `primeCaptureBeforePlayback`, `silenceFallback`, `playbackFadeIn`, `captureGain`, `playbackGain`, `musicVolPercent`, `playbackLeaksIntoCapture`, `noiseGateThreshold`, `halCallActiveVsids`, `mixerSetupCmd`, `mixerCaptureCmd`, `mixerTeardownCmd`, `mixerDiagnosticPattern`.
- **Tanımlı 6 Cihaz Profili:**
  1. **`msm8930()` — Samsung Galaxy S4 Mini (`serranolte`, GT-I9195, MSM8930 / WCD9304, LineageOS 16 Android 9) (Satır 587–772):**
     - `preferVoiceRecognition = true`: `VOICE_CALL` ve `VOICE_DOWNLINK` HAL'de bozuk olduğu için `AudioSource.VOICE_RECOGNITION` kullanılır ve ALSA mixer ile `MultiMedia1 Mixer VOC_REC_DL=1`, `VOC_REC_UL=0` ayarlanarak sadece karşı tarafın sesi (downlink) yakalanır.
     - `silenceLocalAudio = false`, `musicVolPercent = 100`: `Incall_Music Audio Mixer MultiMedia2 = 1` ile `USAGE_MEDIA` akışı GSM uplink'e basılır. Hoparlörden ses çıkmaması için `PRI_RX Audio Mixer MultiMedia1 = 0` ve `MultiMedia2 = 0` yapılır (böylece `STREAM_MUSIC` %100'de kalsa bile hoparlör susar, yazılım kazancı 1x kalır).
     - `captureGain = 1`, `playbackGain = 1`, `noiseGateThreshold = 0`, `playbackLeaksIntoCapture = false`, `primeCaptureBeforePlayback = false`, `silenceFallback = false`, `playbackFadeIn = false`.
     - `halCallActiveVsids = ["10c01000", "10dc1000", "11c05000"]`.
  2. **`sm6150()` — Qualcomm SM6150 / SM7150 + WCD9375 (Xiaomi Poco X3 NFC `surya`/`karna`, Google Pixel 4a `sunfish`, `talos`/`sdmmagpie`, `soc_id` 355/365/366) (Satır 859–1086):**
     - **Kritik Donanım Notu:** Xiaomi stok vendor imajında `USECASE_INCALL_REC_*` derlenmemiştir (`VOC_REC_DL` kontrolü yoktur), bu nedenle Poco X3 NFC'de yakalama akustiktir; ancak **Pixel 4a (`sunfish`)** üzerinde tam dijital çift yönlü ses çalışır.
     - `halCallActiveVsids = ["11c05000", "11dc5000"]` (Voice1 ve Voice2).
     - `silenceLocalAudio = true`: `Voice Rx Device Mute = 1 4294967295 20` ve `Voice Tx Device Mute = 1 4294967295 20` (3 elemanlı integer array: `[enable, DEVICE_NONE=0xFFFFFFFF, ramp_ms=20]`). Go `tinymix` tek argüman verildiğinde dizinin tamamına aynı sayıyı yazdığı için bu kontroller `echo -en` + `/dev/snd/controlC0` veya sıralı komutlarla değil, doğrudan `tinymix` yerine `DeviceProfile.sm6150MuteWrite` / `tinymix` 3 argümanlı veya özel komutlarla yönetilir (bkz. satır 966–990: `tinymix 'Voice Rx Device Mute'` 3 değer bekler, `mixerSetupCmd` içinde `RX_CDC_DMA_RX_0 Audio Mixer MultiMedia1/2/5 = 0` ile hoparlör susturulur).
     - `voiceDownlinkWorks = true` (`AudioSource.VOICE_DOWNLINK` = 6 doğrudan çalışır).
     - `playbackToTelephonyTx = true`: `AUDIO_DEVICE_OUT_TELEPHONY_TX` cihazına yönlendirilir; `incall_music_uplink` mixPort'u `AUDIO_CHANNEL_OUT_STEREO` beklediği için `playbackStereo = true` zorunludur.
     - `incallMusicBeforeTrack = true`: Mixer (`Incall_Music Audio Mixer MultiMedia9 = 1` ve `MultiMedia2 = 1`) `AudioTrack` oluşturulmadan **önce** kurulmalıdır, aksi halde HAL `pcm_open` çağrısı `-ENODEV` ile başarısız olur ve tüm ses çerçeveleri sessizce çöpe gider.
     - `playbackBufferMs = 100`, `audioRouteSettleDelayMs = 500`, `audioStopSettleDelayMs = 18_000` (Xiaomi `audio.primary.sm6150.so` içindeki `out_standby` 15 saniye blokladığı için 18s tavan süre).
  3. **`exynos9820()` — Samsung Galaxy S10e (SM-G970F, Exynos 9820 + Cirrus Logic CS47L93) (Satır 778–853):**
     - Samsung ABOX DSP yönlendirmesi (`ABOX NSRC0`/`NSRC1` -> `SIFS0`). *Not: `README.md` ve `check-device.sh` içinde belirtildiği üzere Exynos S10e'de modeme dijital uplink yolu bulunmadığından gateway olarak kullanılamaz.*
  4. **`genericQualcomm()` (Satır 1091–1123):** Bilinmeyen Qualcomm SoC'ler için `MultiMedia1/2` üzerinde `Incall_Music` ve `VOC_REC_DL` dener.
  5. **`genericExynos()` (Satır 1125–1139):** Bilinmeyen Exynos SoC'ler için temel profil.
  6. **`generic()` (Satır 1141–1153):** Yedek profil.

#### 3.1.4. [`MainActivity.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/MainActivity.kt) (Satır 1–2033)
Tek aktiviteli (Single-Activity) kullanıcı arayüzüdür.
- **Android 12+ Mikrofon İzin Çözümü (`EXTRA_MIC_WARMUP` — Satır 55, 83–101, 470–490):**
  - Android 12+ cihazlarda `PermissionController` gizlenmemişse, ön plan servisi (`GatewayService`) ekran kilitliyken veya uygulama arka plandayken başladığında `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE` yeteneğini alamaz (`AudioRecord` sıfır/sessizlik döner).
  - `BootReceiver`, servisi başlatmadan önce `MainActivity`'yi `EXTRA_MIC_WARMUP = true` ile başlatır; `MainActivity` `onResume` içinde servisi başlatıp 500ms sonra `moveTaskToBack(true)` ile kendini arka plana atar.
- **Sekmeler ve Görünümler:**
  - `tabHome`:
    - **Canlı Çağrı Kartı (`liveCallCard`):** Aktif çağrı varken görünür. Çağrı yönü (`INBOUND`/`OUTBOUND`), karşı numara, SIM etiketi, canlı süre sayacı (`callTimerTick`), **Mute Agent** (`btnLiveMute` -> `RtpSession.txMuted`), **Snoop / Off** (`btnLiveSpeaker` -> `GsmCallManager.setMonitoring` + `RtpSession.monitoring`) ve **End** (`btnLiveHangup`) düğmelerini içerir.
    - **Trafik Listesi (`callHistoryList`):** `CallLogStore.load(this)` verilerini gösterir. Filtre çipleri: `All`, `Inbound`, `Outbound`, `SMS`. Bir SMS satırına tıklandığında `showSmsDetail()` ile tam metin, SIM, SMSC, durum ve hata detaylarını içeren modal açılır.
  - `tabConfig`:
    - SIP Sunucu (`sip_server`), Port (`sip_port`), Kullanıcı (`sip_user`), Şifre (`sip_password`), Outbound CID (`outbound_cid`), SIM başına kendi numarası (`own_number_slot_0`, `own_number_slot_1`, fallback `own_number`).
    - **Codec Seçici (`codec`):** `g722` (Wideband 16 kHz — varsayılan), `both` (G.722 + G.711 A-law), `g711` (G.711 A-law 8 kHz).
    - **Şifreleme Anahtarları:** `sip_tls` (SIP over TLS — seçildiğinde port 5060 ise otomatik 5061 yapar) ve `srtp` (SRTP media encryption).
    - **Ses ve Bildirim Ayarları:**
      - `agent_volume_step`: `-3` ile `+3` arası 7 kademeli kaydırıcı (`VOLUME_GAINS = [0.40f, 0.56f, 0.75f, 1.0f, 1.4f, 2.0f, 2.8f]`). Değiştirildiğinde aktif çağrı varsa `RtpSession.livePlaybackGain` anında güncellenir.
      - `call_monitoring`: Tüm çağrıları telefon hoparlöründen sesli dinleme (Snoop varsayılan açık).
      - `ascii_sms`: Giden SMS'lerde GSM 7-bit bozulmasını önlemek için `Transliterate.toAsciiOrNull` kullanımı (varsayılan `true`).
      - `hide_statusbar_icon`: Bildirim kanalını `IMPORTANCE_MIN` (`gateway_service_quiet`) yaparak durum çubuğu ikonunu gizler.
    - **Default Phone App Kartı (`roleCard`):** Uygulama `ROLE_DIALER` / varsayılan telefon uygulaması değilse uyarı kartı gösterir (`requestDialerRole()`).
  - `tabLogs`:
    - `GatewayService` tarafından yayınlanan (`BROADCAST_LOG`) son 500 satırlık canlı log (`logBuffer`), **Copy**, **Clear**, **Check** (`runGatewayChecks()`) ve ** Audio Dump** (`toggleAudioDump()` — `filesDir/dump_audio` bayrak dosyasını oluşturur/siler ve son çağrının `cap.raw`/`play.raw` istatistiklerini gösterir) düğmeleri.
- **Üst Bilgi (Header) Rozetleri ve Link Details Modalı:**
  - Durum rozeti (`Online` yeşil / `Connecting` sarı / `Offline` kırmızı), `TLS` kalkan rozeti, `SRTP` anahtar rozeti, `Call Monitor` kırmızı telefon rozeti.
  - Durum rozetine tıklandığında **`showConnectionDetails()` (Satır 955–1340)** açılır:
    - Seçili ağ yolu (`Mobile data` veya `Wi-Fi`), yerel IP, STUN/SIP harici IP, DNS sunucuları, aktif hücre kulesi (`describeCells`: LTE/NR/WCDMA/GSM bant, EARFCN/ARFCN, PCI/CID, RSRP/RSSI, TA), SIM 1 / SIM 2 operatör ve sinyal durumu, Wi-Fi SSID/BSSID/frekans/kanal/link hızı/güvenlik tipi ve paralel `pingAvg` gecikme ölçümlerini canlı olarak (1 saniyede bir yenileyerek) gösterir.
- **`runGatewayChecks()` (Satır 1609–1854):**
  - Root erişimi (`id`), SELinux durumu (`getenforce`), `CAPTURE_AUDIO_OUTPUT` / `MODIFY_PHONE_STATE` / `RECORD_AUDIO` izinleri, `ROLE_DIALER` durumu, `PermissionController` paketinin gizli olup olmadığı, `appops get RECORD_AUDIO` modu, `tinymix` varlığı, sistem özellikleri (`ro.qc.sdk.audio.fluencetype` vb.), aktif ağ arayüzleri ve SIM/IMEI bilgilerini denetleyip log sekmesine raporlar.

---

### 3.2. Çağrı Köprüsü Paketi (`com.callagent.gateway.bridge`)

#### 3.2.1. [`CallOrchestrator.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/bridge/CallOrchestrator.kt) (Satır 1–1285)
GSM telefon katmanı (`GsmCallManager`) ile SIP/RTP katmanı (`SipClient`, `SipCall`, `RtpSession`) arasındaki tüm çağrı yaşam döngüsünü yöneten merkezi durum makinesidir.

- **Durum Makinesi (`enum class State` — Satır 55–64):**
  - `IDLE`: Aktif çağrı yok.
  - `GSM_RINGING`: Gelen GSM çağrısı çalıyor (henüz SIP INVITE gönderilmedi).
  - `SIP_CALLING`: Gelen GSM çağrısı için SIP sunucusuna `INVITE` gönderildi, yanıt bekleniyor.
  - `SIP_RINGING`: Gelen SIP `INVITE` alındı, GSM araması başlatılıyor.
  - `GSM_DIALING`: Giden GSM çağrısı çevriliyor (`STATE_DIALING` / `STATE_RINGING`), karşı tarafın açması bekleniyor.
  - `GSM_ANSWERED`: GSM çağrısı açıldı, SIP tarafı tamamlanıyor.
  - `BRIDGED`: Her iki taraf (`GSM` ve `SIP`) aktif, `RtpSession` çalışıyor ve ses köprüleniyor.
  - `TEARING_DOWN`: Çağrı sonlandırılıyor ve ses/donanım kaynakları temizleniyor.

- **Eşzamanlılık ve Yarış Durumu (Race Condition) Korumaları:**
  - `stateLock = Any()` ve `@Volatile var state: State`.
  - `generation: Int`: Her çağrı temizliğinde (`cleanup()`) artırılır. Gecikmeli zamanlayıcılar (timeout, drain wait, watchdog) başladıkları `generation` değerini kontrol ederek bir sonraki çağrıyı yanlışlıkla kesmez.
  - `gsmStateWatchdog` (Satır 119–162): `GSM_DIALING` ve `BRIDGED` durumlarında her **250ms**'de bir `GsmCallManager.currentCall` durumunu yoklar (`InCallService` callback'inin kaçırılması ihtimaline karşı).

- **Yön 1: Gelen GSM Çağrısı -> SIP (`onIncomingGsmCall` — Satır 198–391):**
  1. GSM çaldığında (`STATE_RINGING`), eğer `state != IDLE` ise (meşgul) `GsmCallManager.rejectCall()` ile reddedilir; önceki çağrının `audioStopThread` (HAL drain) işlemi hala sürüyorsa `waitForPendingAudioStop()` ile bitmesi beklenir.
  2. `state = SIP_CALLING` yapılır. SIM yuvasına (`slotIndex`) ait `own_number_slot_<N>` numarası E.164 formatında (`digitsOnly`) hedef SIP uzantısı (`targetExt`) olarak seçilir. Numara boşsa çağrı reddedilir.
  3. SIP `INVITE` gönderilir (`From` başlığında arayanın GSM numarası veya gizli ise `anonymous`, `To` başlığında SIM'in kendi numarası).
  4. **GSM henüz cevaplanmaz** (arayan kişi gerçek operatör çalma sesini duyar, ücretlendirme başlamaz).
  5. SIP sunucusu `200 OK` (+ SDP) döndüğünde (`onSipAnswered`):
     - `GsmCallManager.answerCall()` çağrılır.
     - GSM `STATE_ACTIVE` olduğunda (`onGsmCallActive` -> `startBridgeWhenGsmActive`), `startAudioBridge()` çağrılarak ALSA mixer kurulur ve `RtpSession` başlatılır (`state = BRIDGED`).
  6. SIP sunucusu `486 Busy Here` veya `603 Decline` dönerse (`onSipDeclined`), `GsmCallManager.rejectCall()` ile GSM araması meşgul/reddedildi olarak kapatılır.
  7. 30 saniye içinde SIP cevap vermezse zaman aşımı devreye girer (`CANCEL` gönderilir, GSM reddedilir).

- **Yön 2: Gelen SIP Çağrısı -> Giden GSM Çağrısı (`onIncomingSipCall` — Satır 467–751):**
  1. SIP sunucusundan `INVITE` gelir.
  2. **Hedef Numara Tespiti:** Önce `X-GSM-Forward` özel SIP başlığına bakılır; yoksa `Request-URI` (`INVITE sip:NUMBER@host`) içindeki kullanıcı kısmı alınır.
  3. **SIM Seçimi (Çift SIM):** `X-Call-Ed` / `To` / `From` (P-Asserted-Identity / Remote-Party-ID) içindeki arayan kimliği (`outboundCid`) okunur ve `GsmCallManager.findPhoneAccountForCid(context, outboundCid)` ile `own_number_slot_<N>` eşleşmesi aranır. Eşleşen SIM varsa o SIM üzerinden, yoksa varsayılan SIM üzerinden arama yapılır.
  4. **MMI / USSD Kodları (`*100#`, `*#06#` vb.):** `GsmCallManager.isMmiCode(targetNumber)` true ise sesli arama yerine `GsmCallManager.sendMmi()` çalıştırılır; USSD yanıtı geldiğinde SIP `MESSAGE` olarak sunucuya iletilir.
  5. Normal numara ise SIP tarafına SDP içermeyen **`180 Ringing`** gönderilir (SIP henüz `200 OK` ile cevaplanmaz!).
  6. `GsmCallManager.makeCall(context, targetNumber, phoneAccount)` ile GSM araması başlatılır (`state = GSM_DIALING`).
  7. **45 Saniye Cevap Zaman Aşımı (`GSM_ANSWER_TIMEOUT_MS = 45_000L`):** Karşı taraf 45 saniye içinde açmazsa GSM araması kapatılır ve SIP'e `480 Temporarily Unavailable` dönülür.
  8. Karşı taraf telefonu açtığında (`STATE_ACTIVE` -> `onGsmCallActive`):
     - `sipCall.acceptIncoming()` ile SIP tarafına **`200 OK` (+ SDP)** gönderilir.
     - `startAudioBridge()` ile ALSA mixer ve `RtpSession` başlatılır (`state = BRIDGED`).
  9. Karşı taraf meşgulse, reddederse veya şebeke hatası olursa (`onGsmCallDisconnected` while `GSM_DIALING`):
     - `sipStatusFor(cause: DisconnectCause)` (Satır 1168–1255) GSM kapanma nedenini tam SIP durum koduna çevirir:
       - `BUSY` -> `486 Busy Here`
       - `REJECTED` -> `603 Decline`
       - `INVALID_NUMBER` / `UNOBTAINABLE_NUMBER` -> `404 Not Found`
       - `ADDRESS_INCOMPLETE` -> `484 Address Incomplete`
       - `NO_ROUTE_TO_DEST` -> `404 Not Found`
       - `CALL_BARRED` / `FDN_BLOCKED` -> `403 Forbidden`
       - `CONGESTION` / `LIMIT_EXCEEDED` / `ERROR_UNSPECIFIED` -> `503 Service Unavailable`
       - `OUT_OF_SERVICE` / `POWER_OFF` -> `503 Service Unavailable`
       - `NO_ANSWER` / `TIMED_OUT` -> `480 Temporarily Unavailable`

- **Ses Köprüsü Başlatma ve Kapatma (`startAudioBridge` & `cleanup` — Satır 841–1166):**
  - `forceAllowRecordAudio()`: `RootShell.exec("appops set --uid com.callagent.gateway RECORD_AUDIO allow && appops set com.callagent.gateway RECORD_AUDIO allow")` çalıştırarak mikrofon engelini kaldırır.
  - `deviceProfile.incallMusicBeforeTrack` (`sm6150`) ise `GsmCallManager.configureAudioBridge()` (`tinymix` komutları) `RtpSession.start()` öncesinde çalıştırılır; değilse (`msm8930`) önce `RtpSession.start()` (`AudioRecord` + `AudioTrack`) başlatılıp HAL'in PCM yolunu açması için 300ms beklenir, ardından `configureAudioBridge()` çalıştırılır.
  - **`waitForAudioHalDrain()` (Satır 1074–1139):** `sm6150` gibi `audioStopSettleDelayMs > 0` olan cihazlarda çağrı bittiğinde `dumpsys media.audio_flinger` çıktısını 500ms aralıklarla tarayarak `AUDIO_DEVICE_OUT_TELEPHONY_TX` / `incall_music` çıkış thread'inin `Standby: yes` durumuna geçmesini bekler (maksimum 18 saniye). Böylece arka arkaya gelen çağrılarda HAL kilitlenmesi önlenir.

---

### 3.3. GSM ve Telecom Paketi (`com.callagent.gateway.gsm`)

#### 3.3.1. [`CallConnectionService.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/gsm/CallConnectionService.kt) (Satır 1–58)
- `android.telecom.ConnectionService` alt sınıfıdır. Uygulamanın Android Telecom çerçevesinde tam yetkili bir telefon sağlayıcısı olarak tanınmasını sağlar.

#### 3.3.2. [`GsmCallService.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/gsm/GsmCallService.kt) (Satır 1–65)
- `android.telecom.InCallService` alt sınıfıdır (`android.permission.BIND_INCALL_SERVICE`).
- Uygulama varsayılan telefon uygulaması (`ROLE_DIALER`) olarak atandığında Android her gelen/giden GSM çağrısında bu servisi bağlar ve `onCallAdded` / `onCallRemoved` olaylarını `GsmCallManager`'a iletir.
- Ayrıca `GatewayService` henüz çalışmıyorsa (ör. soğuk açılışta hemen gelen arama) `GatewayService`'i başlatır.

#### 3.3.3. [`GsmCallManager.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/gsm/GsmCallManager.kt) (Satır 1–845)
Tüm GSM çağrı işlemlerini, çift SIM hesap çözümlemesini, ses seviyelerini ve ALSA mixer komutlarını yöneten tekil (`object`) sınıftır.
- **Arayan Numara ve Gizlilik Çözümlemesi (`callerOf(call: Call)` — Satır 55–94):**
  - `call.details.handle?.schemeSpecificPart` ve `call.details.callerNumberVerificationStatus` / `handlePresentation` değerlerini okur.
  - Numara gizliyse (`PRESENTATION_RESTRICTED`), ankesörlü ise (`PRESENTATION_PAYPHONE`) veya bilinmiyorsa (`PRESENTATION_UNKNOWN`), `CallerId(user = "anonymous", displayName = "Withheld" / "Payphone" / "Unknown")` döner; böylece SIP `From` başlığı RFC 3261 uyumlu `"Withheld" <sip:anonymous@host>` olarak oluşturulur.
- **Çift SIM Eşleştirme (`subscriptionIdOf`, `slotIndexForCall`, `findPhoneAccountForCid` — Satır 632–810):**
  - `TelephonyManager.getSubscriptionId(PhoneAccountHandle)` API 30'da eklendiği için Android 9'da `NoSuchMethodError` fırlatır (`Error` sınıfı `Exception` değildir!). Bu yüzden `Throwable` yakalanır ve API 30 altında `PhoneAccountHandle.id` değeri aktif aboneliklerin `subscriptionId` ve `iccId` değerleriyle karşılaştırılarak SIM yuvası (`0` veya `1`) bulunur.
  - `findPhoneAccountForCid(context, cid)`: Giden aramalarda SIP'ten gelen `outboundCid` ile `own_number_slot_0` / `own_number_slot_1` numaralarını (`digitsOnly`, son 7+ hane sonek eşleşmesi dahil) karşılaştırıp doğru SIM'in `PhoneAccountHandle` nesnesini döner.
- **Giden Arama Başlatma (`makeCall` — Satır 261–354):**
  - Android 15 (`targetSdk 34`) arka plan aktivite başlatma engeli (`BAL_BLOCK`) nedeniyle `Intent.ACTION_CALL` (`startActivity`) sessizce reddedilir.
  - Bu nedenle birincil yöntem olarak **`TelecomManager.placeCall(uri, extras)`** kullanılır (aktivite başlatmaz, doğrudan Telecom servisine gider).
  - İkincil yedek olarak `Intent.ACTION_CALL`, üçüncü yedek olarak `RootShell` üzerinden `am start -a android.intent.action.CALL -d tel:...` kullanılır.
- **Ses Köprüsü ve ALSA Mixer Yönetimi (`configureAudioBridge`, `resetAudioRouting` — Satır 387–589):**
  - `findTinymix()`: Sırasıyla `/data/local/tmp/tinymix`, `/vendor/bin/tinymix`, `/system/bin/tinymix`, `/system/xbin/tinymix` yollarını kontrol eder.
  - `configureAudioBridge(context)`:
    - `InCallService.setAudioRoute(CallAudioState.ROUTE_EARPIECE)` ile sesi ahizeye alır (hoparlörden akustik geri beslemeyi önler).
    - `AudioManager.isMicrophoneMute = true` yapar (fiziksel mikrofonun ortam sesini GSM'e basmasını engeller; `Incall_Music` dijital enjeksiyonu bundan etkilenmez).
    - `enforceVolumes()` ile `STREAM_VOICE_CALL` maksimuma, `STREAM_MUSIC` ise `profile.musicVolPercent` değerine ayarlanır ve çağrı boyunca her 2 saniyede bir (`volumeWatchdog`) denetlenir.
    - `profile.halCallActiveVsids` listesindeki her VSID için `AudioManager.setParameters("vsid=<hex>;call_state=2")` çağırarak Qualcomm HAL'de ses oturumunu aktif işaretler.
    - `profile.mixerSetupCmd` ve `profile.mixerCaptureCmd` içindeki `tinymix` komutlarını `RootShell` üzerinden çalıştırır.
- **Canlı Dinleme / Snoop (`setMonitoring(enabled: Boolean)` — Satır 202–245):**
  - Açıldığında (`monitoring = true`) GSM tarafını (arayan kişiyi) duymak için `InCallService.setAudioRoute(ROUTE_SPEAKER)` yapar ve `msm8930` / `sm6150` üzerinde susturulmuş olan RX/hoparlör mixer kontrollerini (`Voice Rx Device Mute = 0` veya `PRI_RX Audio Mixer MultiMedia1 = 1`) geri açar. Ajanın sesi ise `RtpSession` içindeki `monitorTrack` (`USAGE_VOICE_COMMUNICATION`) tarafından çalınır.
- **DTMF Gönderimi (`dtmfRelay` — Satır 104–118):**
  - `DtmfRelay` nesnesini `Call.playDtmfTone(digit)` ve `Call.stopDtmfTone()` metodlarına bağlar.

---

### 3.4. Ağ Paketi (`com.callagent.gateway.net`)

#### 3.4.1. [`StunClient.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/net/StunClient.kt) (Satır 1–184)
- RFC 5389 uyumlu minimal STUN istemcisidir.
- **Sunucu Listesi (`STUN_SERVERS` — Satır 20–28):**
  1. `stun.l.google.com:19302`
  2. `stun1.l.google.com:19302`
  3. `stun2.l.google.com:19302`
  4. `stun3.l.google.com:19302`
  5. `stun4.l.google.com:19302`
  6. `stun.cloudflare.com:3478`
  7. `stun.nextcloud.com:443`
- **`discover(socket: DatagramSocket?, network: Network? = null): MappedAddress?`:**
  - Mevcut UDP soketi (ör. SIP soketi veya RTP soketi) üzerinden 20 baytlık STUN Binding Request (`0x0001`, Magic Cookie `0x2112A442` + 12 bayt rastgele Transaction ID) gönderir (`STUN_TIMEOUT_MS = 1500`).
  - Yanıttaki `XOR-MAPPED-ADDRESS` (`0x0020`) veya `MAPPED-ADDRESS` (`0x0001`) özniteliğini ayrıştırarak NAT arkasındaki genel (public) IP ve portu döner.

---

### 3.5. RTP, SRTP, Kodek ve Ses İşleme Paketi (`com.callagent.gateway.rtp`)

#### 3.5.1. [`RtpPacket.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/rtp/RtpPacket.kt) (Satır 1–94)
- 12 baytlık standart RTP başlığını (Version=2, Padding, Extension, CSRC count, Marker, PayloadType, SequenceNumber, Timestamp, SSRC) kodlar (`encode`, `encodeInto`) ve çözer (`decode`).
- `decode()` metodu CSRC listesini (`cc * 4`), RTP başlık uzantısını (RFC 3550 §5.3.1: `extBit`, 4 bayt başlık + `extWords * 4` bayt) ve dolgu baytlarını (`padBit`) doğru şekilde atlayarak saf yükü (`payload`) çıkarır.

#### 3.5.2. [`G722Codec.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/rtp/G722Codec.kt) (Satır 1–426)
- ITU-T G.722 (64 kbit/s, 16 kHz wideband ses, Sub-Band ADPCM) enkoder ve dekoderinin saf Kotlin uygulamasıdır.
- **Çalışma Prensibi:**
  - 24-tap Quadrature Mirror Filter (QMF) ile 16 kHz ses sinyali iki adet 8 kHz alt banda (alt bant 0–4 kHz: 6-bit ADPCM; üst bant 4–8 kHz: 2-bit ADPCM) bölünür.
  - Her 2 adet 16-bit PCM örneği (sample) 1 baytlık G.722 verisine sıkıştırılır: 20ms frame = **320 PCM örneği (16 kHz)** = **160 bayt G.722 RTP yükü**.
  - **RTP Saat Hızı Tuzağı (RFC 3551 §4.5.2):** G.722 16 kHz örnekleme hızında çalışmasına rağmen, tarihi bir RFC hatası nedeniyle SDP'de `G722/8000` olarak ilan edilir ve RTP zaman damgası (timestamp) her 20ms pakette 320 değil **160** artırılır (`RtpSession` bunu dikkate alır).
  - Sıfır GC baskısı (zero-allocation) için `encodeInto(pcm: ShortArray, count: Int, out: ByteArray, outOffset: Int)` ve `decodeInto(data: ByteArray, dataOffset: Int, dataLength: Int, out: ShortArray)` metodları önceden ayrılmış diziler üzerinde çalışır.

#### 3.5.3. [`Srtp.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/rtp/Srtp.kt) (Satır 1–337)
SDES anahtar değişimi (RFC 4568 `a=crypto:`) ile çalışan tam kapsamlı SRTP şifreleme/çözme motorudur.
- **Desteklenen 3 Şifreleme Kümesi (`enum class SrtpSuite`):**
  1. `AEAD_AES_256_GCM` (RFC 7714): 32 bayt anahtar + 12 bayt tuz (44 bayt, 60 karakter Base64), 16 bayt GCM kimlik doğrulama etiketi (tag).
  2. `AEAD_AES_128_GCM` (RFC 7714): 16 bayt anahtar + 12 bayt tuz (28 bayt, 40 karakter Base64), 16 bayt GCM etiketi.
  3. `AES_CM_128_HMAC_SHA1_80` (RFC 3711): 16 bayt anahtar + 14 bayt tuz (30 bayt, 40 karakter Base64), AES-CTR şifreleme + 10 bayt (80-bit) HMAC-SHA1 etiketi.
- **Anahtar Türetme (`deriveKey` — RFC 3711 §4.3.1):** Master key ve master salt'tan `label=0x00` (cipher key), `label=0x01` (auth key, 20 bayt) ve `label=0x02` (salting key) tek blok AES-ECB ile türetilir.
- **ROC (Rollover Counter) ve Tekrar Saldırısı (Replay) Koruması (`SrtpContext`):**
  - 16-bit RTP sıra numarası (`seq`) 65535'i geçtiğinde 32-bit `roc` sayacını RFC 3711 §3.3.1 kurallarına göre tahmin eder (`estimateIndex`).
  - `unprotect()` metodu **64-paketlik kayan pencere (`replayWindow: Long`)** ile tekrarlanan (duplicate) veya çok eski paketleri reddeder ve doğrulama (HMAC veya GCM tag) başarılı olmadan `roc` / `s_l` durumunu değiştirmez.
  - Her çağrı yönü (`txSrtp` ve `rxSrtp`) kendi bağımsız `SrtpContext` nesnesine sahiptir (`@Synchronized`).

#### 3.5.4. [`Dtmf.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/rtp/Dtmf.kt) (Satır 1–293)
SIP sunucusundan gelen DTMF tuşlamalarını yakalayıp GSM aramasına gerçek zamanlı ton olarak basar.
- **`Rfc4733` (Satır 18–65):** RTP `telephone-event` (PT 101) 4 baytlık yükünü (`event`, `E` end bit, `volume`, `duration`) ayrıştırır ve oluşturur (`0-9`, `*`=10, `#`=11, `A-D`=12-15).
- **`DtmfDetector` (Satır 79–139):** RTP akışındaki `telephone-event` paketlerini izler. Aynı RTP timestamp'ine sahip paketleri tek bir tuş basımı olarak gruplar: ilk pakette `onBegin(char)`, `E=1` bitli pakette (veya bir sonraki tuş başladığında) `onEnd(char, durationMs)` tetikler.
- **`SipInfoDtmf` (Satır 145–176):** `Content-Type: application/dtmf-relay` (`Signal=5\r\nDuration=250`) ve `Content-Type: application/dtmf` (`5`) gövdeli SIP `INFO` mesajlarını ayrıştırır.
- **`DtmfRelay` (Satır 198–293):** Tek iş parçacıklı zamanlayıcı (`ScheduledExecutorService`) ile GSM modemine `start(digit)` / `stop()` komutlarını iletir.
  - Çok hızlı basılıp bırakılan tuşları GSM şebekesinin tanıması için en az **120ms** (`minToneMs = 120`) tutar.
  - İki tuş arasında en az **50ms** (`interDigitGapMs = 50`) sessizlik boşluğu bırakır.
  - `E=1` bitli bitiş paketi UDP'de kaybolursa tonun sonsuza kadar çalmaması için **2000ms** (`maxToneMs = 2000`) sonunda otomatik durdurur.

#### 3.5.5. [`RtpSession.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/rtp/RtpSession.kt) (Satır 1–2408)
Uygulamanın gerçek zamanlı ses motorudur.
- **`PcmaCodec` (Satır 2328–2407):** ITU-T G.711 A-law (PT 8, 8 kHz) codec'i. Başlangıçta 256 girişli `decodeTable` (`ShortArray(256)`) ve 65536 girişli `encodeTable` (`ByteArray(65536)`) oluşturarak tüm kodlama/çözme işlemlerini **O(1) tablo aramasıyla (lookup)** sıfır dallanma (branchless) ile yapar.
- **4 İş Parçacıklı (Thread) Mimari:**
  1. **`RTP-Recv` (`receiveLoop` — Satır 562–728):**
     - `THREAD_PRIORITY_URGENT_AUDIO` önceliğinde çalışır.
     - Gelen UDP paketini okur (`SO_RCVTIMEO = 500ms`), varsa `rxSrtp.unprotect()` ile deşifre eder, `RtpPacket.decode()` ile ayrıştırır.
     - Paket `telephoneEventPt` (örn. 101) ise `dtmfDetector.onPacket()` çağırır.
     - Ses paketi ise (G.722 PT 9 veya PCMA PT 8) PCM'e çözer; PCMA (8 kHz) gelmişse ve `playbackSampleRate == 16000` ise **4-tap Catmull-Rom kübik interpolasyon filtresi (`upsample8kTo16k` — Satır 741–777)** ile 8 kHz'den 16 kHz'e yükseltir (lineer interpolasyonun yarattığı metalik ayna görüntüsü harmoniklerini ~15 dB bastırır).
     - Çözülen 20ms frame'i `jitterBuffer` (`ArrayBlockingQueue<ShortArray>(12)`) kuyruğuna ekler. Kuyruk `JITTER_HIGH_WATER = 7` (140ms) seviyesini aşarsa en eski frame'i düşürerek gecikme birikmesini (latency drift) engeller.
  2. **`RTP-Play` (`playbackLoop` — Satır 788–1034):**
     - `jitterBuffer.poll(30, MILLISECONDS)` ile frame çeker ve `AudioTrack.write(..., WRITE_BLOCKING)` ile GSM uplink'e (`Incall_Music` / `TELEPHONY_TX`) yazar.
     - `livePlaybackGain` (UI'daki Agent Volume kaydırıcısı) 1.0f'den farklıysa örneklere kazanç uygular ve `softLimit(sample)` (Satır 2305–2324: `±24576` eşiğinden sonra 4:1 sıkıştırma uygulayan yumuşak sınırlayıcı) ile sert kırpılmayı (hard clipping) önler.
     - `monitoring == true` (Snoop açık) ise aynı PCM verisini `monitorTrack` (`USAGE_VOICE_COMMUNICATION`, hoparlör) üzerinden yerel olarak da çalar.
  3. **`RTP-Capt` (`captureLoop` — Satır 1043–1496):**
     - `AudioRecord` üzerinden GSM downlink (arayan kişinin sesi) örneklerini 20ms (8kHz'de 160, 16kHz'de 320 örnek) bloklar halinde okur.
     - **Yakalama Kaynağı Seçimi (`createAudioRecord` — Satır 1666–1856):**
       - `profile.preferVoiceRecognition` (`msm8930`) ise önce `VOICE_RECOGNITION` (6), değilse ve `profile.voiceDownlinkWorks` (`sm6150`) ise `VOICE_DOWNLINK` (3) -> `VOICE_CALL` (4) -> `VOICE_RECOGNITION` (6) -> `MIC` (1) sırasıyla dener.
       - Qualcomm `VOC_REC_DL` stereo/mono kanal farklarını otomatik algılar; gerekirse 2 kanallı kayıttan enerjisi yüksek olan kanalı seçer.
     - **Sıfır Okuma / Sessizlik Kurtarma (Fallback):** `AudioRecord` arka arkaya `ZERO_READ_FALLBACK_FRAMES = 75` (1.5 saniye) boyunca tamamen sıfır (`peak == 0`) dönerse ve `profile.silenceFallback == true` ise alternatif `AudioSource`'a veya ALSA `tinycap` yakalamasına (`probeAlsaCaptureDevices`) geçer.
     - `txMuted == true` (UI'da Mute Agent basılı) ise yakalanan ses yerine sessizlik (0) gönderilir; değilse kodek ile kodlanır, RTP başlığı eklenir, varsa `txSrtp.protect()` ile şifrelenir ve SIP sunucusunun RTP adresine gönderilir.
  4. **`RTP-Timeout` (`timeoutLoop` — Satır 526–556):**
     - 30 saniye (`RTP_TIMEOUT_MS = 30_000L`) boyunca karşı taraftan hiçbir RTP paketi gelmezse çağrının koptuğunu anlayıp `onRtpTimeout` (`CallOrchestrator` -> `BYE` + `hangup`) tetikler.
- **Ham Ses Kaydı (`Audio Dump` — Satır 198–208, 467–518):**
  - `filesDir/dump_audio` dosyası mevcutsa, çağrı boyunca yakalanan (`cap.raw`) ve çalınan (`play.raw`) ham 16-bit LE mono PCM verilerini `filesDir/dump/` altına yazar (`MainActivity` Logs sekmesindeki `Audio Dump` düğmesiyle açılır/kapatılır).

---

### 3.6. Servis ve Kalıcılık Paketi (`com.callagent.gateway.service`)

#### 3.6.1. [`BootReceiver.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/service/BootReceiver.kt) (Satır 1–167)
- `android:directBootAware="true"` olarak tanımlıdır.
- Dinlediği Intent'ler: `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `com.htc.intent.action.QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`.
- Cihaz şifreli (File-Based Encryption) olsa bile ekran kilidi açılmadan (`createDeviceProtectedStorageContext()`) yapılandırmayı okur ve `GatewayService`'i başlatır.
- Android 12+ (`SDK_INT >= 31`) cihazlarda eğer `PermissionController` hala sistemde görünüyorsa (`isPermissionControllerPresent`), mikrofon izni (`MODE_FOREGROUND`) engeline takılmamak için önce `MainActivity`'yi `EXTRA_MIC_WARMUP` ile başlatır.

#### 3.6.2. [`CallLogStore.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/service/CallLogStore.kt) (Satır 1–188)
- `call_history` adlı `SharedPreferences` içinde `entries` JSON dizisi olarak son **50** (`MAX_ENTRIES = 50`) çağrı ve SMS kaydını tutar.
- `Entry` alanları: `direction` (`"in"`, `"out"`, `"sms_in"`, `"sms_out"`), `number`, `timestamp`, `durationSec`, `status`, `text`, `simSlot`, `carrier`, `recipient`, `smsId`, `smsc`, `error`.
- `updateSms(context, smsId, transform)`: Çok parçalı SMS'lerin gönderim/teslimat durumlarını `smsId` üzerinden yerinde (in-place) günceller.

#### 3.6.3. [`GatewayService.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/service/GatewayService.kt) (Satır 1–1858)
Uygulamanın 7/24 çalışan ana arka plan/ön plan (Foreground) servisidir.
- **Depolama Göçü (`prefs(context)` — Satır 101–125):**
  - Ayarları `DeviceProtectedStorage` (`gateway_prefs`) içinde tutar; böylece cihaz yeniden başladığında PIN girilmeden önce (`LOCKED_BOOT_COMPLETED`) SIP kaydı yapılabilir. Eski credential-protected depodaki ayarları ilk çalışmada otomatik taşır.
- **Akıllı Ağ Seçimi ve Soket Bağlama (`selectSocketNetwork`, `probeGatewayReachable` — Satır 988–1385):**
  - Hem Hücresel Veri (`TRANSPORT_CELLULAR`) hem Wi-Fi (`TRANSPORT_WIFI`) mevcut olduğunda, bazı ortamlarda Wi-Fi'nin internet çıkışı olmayabilir (örn. sadece yerel ADB için kurulu Wi-Fi).
  - `GatewayService`, aktif ağların her birinde SIP sunucusuna TCP/UDP erişilebilirlik testi (`probeGatewayReachable`) yapar:
    - Hücresel veri çalışıyorsa veya Wi-Fi'de internet/SIP erişimi yoksa, **Wi-Fi bağlı kalsa bile** SIP ve RTP soketlerini `Network.bindSocket()` ile doğrudan Hücresel Veri ağına (`socketNetwork`) bağlar.
    - `ConnectivityManager.NetworkCallback` ile ağ değişikliklerini izler; IP değiştiğinde veya aktif ağ düştüğünde **3 saniyelik debounce (`scheduleReconnect`)** ile SIP istemcisini yeniden bağlar.
- **Otomatik SIM Numarası Keşfi (`discoverOwnNumbers` — Satır 705–814):**
  - SIM kartta `SubscriptionInfo.number` / `TelephonyManager.line1Number` kayıtlıysa ve kullanıcı ilgili `own_number_slot_<N>` alanını boş bırakmışsa, servis başlarken otomatik olarak okur ve kaydeder.
- **Watchdog ve Sağlık Kontrolleri:**
  - `WakeLock` (`PARTIAL_WAKE_LOCK`, `"GatewayService::SipWakeLock"`) ve `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) tutar.
  - Her 30 saniyede bir (`startNetworkWatchdog`) yerel IP adresinin değişip değişmediğini kontrol eder; aktif bir çağrı yokken IP değişmişse `reconnect()` tetikler.
- **SMS Kuyruk İşleyicileri (`flushPendingSms`, `handleIncomingSipMessage`, `reportSmsProgress` — Satır 474–703):**
  - SIP kaydı (`onRegistered`) tamamlandığında bekleyen tüm gelen SMS'leri (`SmsStore`) ve gönderilmeyi/raporlanmayı bekleyen giden SMS'leri (`SmsOutbox`) sırayla işler.

---

### 3.7. SIP Protokol Paketi (`com.callagent.gateway.sip`)

#### 3.7.1. [`SipAuth.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sip/SipAuth.kt) (Satır 1–81)
- RFC 2617 HTTP/SIP Digest MD5 kimlik doğrulamasını uygular.
- `WWW-Authenticate` veya `Proxy-Authenticate` başlığındaki `realm`, `nonce`, `opaque`, `qop` (`auth`) parametrelerini ayrıştırır.
- `HA1 = MD5(username:realm:password)`, `HA2 = MD5(method:uri)` ve `qop=auth` varsa `MD5(HA1:nonce:nc:cnonce:qop:HA2)`, yoksa `MD5(HA1:nonce:HA2)` hesaplayarak `Authorization` / `Proxy-Authorization` başlığını üretir.

#### 3.7.2. [`SipTransport.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sip/SipTransport.kt) (Satır 1–300)
- SIP over TLS (RFC 3261 §18, varsayılan port 5061) için `SSLSocket` tabanlı akış (stream) çerçeveleyicisidir.
- **Android 9 `setsockoptTimeval` Hatası Çözümü (`connectTcp` — Satır 234–299):**
  - Android 9 (API 28) üzerinde `Socket.connect(endpoint, timeout)` çağrıldığında Conscrypt/libc içinde `NoSuchMethodError: Os.setsockoptTimeval` hatası oluşabilmektedir. Bu yüzden `Throwable` yakalanarak bloklayan `connect(endpoint)` + arka plan zamanlayıcı soket kapatıcı ile güvenli bağlantı kurulur.
- `SNIHostName` ve `endpointIdentificationAlgorithm = "HTTPS"` ile sunucu sertifikasını sistem CA deposuna karşı doğrular.
- Gelen bayt akışında `\r\n\r\n` ile başlık sonunu bulur, `Content-Length` (veya kısa biçimi `l`) değerini okur ve tam `Content-Length` kadar UTF-8 gövde baytını okuyarak tam bir SIP mesajı (`readMessage()`) döner; TCP akışındaki boş `\r\n` keepalive baytlarını başlangıçta otomatik atlar.

#### 3.7.3. [`SipMessage.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sip/SipMessage.kt) (Satır 1–671)
- **`SipMessage`:** İstek (`isRequest`, `method`, `requestUri`) ve yanıt (`isResponse`, `statusCode`, `reasonPhrase`) ayrıştırıcısı.
  - RFC 3261 §7.3.3 kısa (compact) başlık adlarını tam adlarına eşler (`v`->`via`, `f`->`from`, `t`->`to`, `i`->`call-id`, `m`->`contact`, `l`->`content-length`, `c`->`content-type`).
  - `parseSdp()`: SDP gövdesindeki `c=IN IP4 <ip>`, `m=audio <port>`, `a=rtpmap:<pt> <codec>/<clock>`, `a=crypto:<tag> <suite> inline:<key>` ve `a=sendonly`/`inactive` bilgilerini `SdpInfo` nesnesine çıkarır.
- **`SipBuilder`:**
  - `register`, `invite`, `ack`, `bye`, `cancel`, `message`, `response`, `buildSdp` oluşturucularını içerir.
  - SDP oluşturulurken seçilen `CodecMode` (`G722_ONLY`, `BOTH`, `ALAW_ONLY`) ve varsa `SrtpOffer` (`a=crypto:1 AEAD_AES_256_GCM ...`, `a=crypto:2 AEAD_AES_128_GCM ...`, `a=crypto:3 AES_CM_128_HMAC_SHA1_80 ...`) satırlarını ekler.

#### 3.7.4. [`SipCall.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sip/SipCall.kt) (Satır 1–486)
- Tek bir SIP çağrı diyaloğunu (Dialog) temsil eder (`callId`, `localTag`, `remoteTag`, `cseq`, `remoteSdp`, `negotiatedCodec`, `txSrtp`, `rxSrtp`).
- **Giden SIP Çağrısı (`startOutgoingInvite`):**
  - `INVITE` gönderir; UDP üzerinde RFC 3261 Timer A (`500ms -> 1000ms -> 2000ms -> 4000ms`) ile yeniden gönderim (retransmission) yapar.
  - `401 Unauthorized` / `407 Proxy Authentication Required` gelirse `ACK` gönderip `SipAuth` ile imzalı yeni `INVITE` (`cseq + 1`) gönderir.
  - `100 Trying` / `180 Ringing` / `183 Session Progress` geldiğinde yeniden gönderimi durdurur (`inviteAcknowledged = true`).
  - `200 OK` geldiğinde `Record-Route` ve `Contact` başlıklarını kaydeder, SDP'den uzak RTP IP:port, kodek ve SRTP anahtarını çıkarır, `ACK` gönderir ve `onAnswered` callback'ini tetikler.
- **Gelen SIP Çağrısı (`handleIncomingInvite`, `acceptIncoming`, `rejectIncoming`):**
  - `INVITE` geldiğinde hemen `100 Trying` ve ardından SDP'siz `180 Ringing` döner.
  - GSM çağrısı açıldığında `acceptIncoming()` çağrılır: Gelen `INVITE` yeniden gönderimlerine karşı `last200Ok` mesajını önbelleğe alır ve `ACK` gelene kadar UDP üzerinde `200 OK` mesajını periyodik olarak yeniden gönderir (Timer G).

#### 3.7.5. [`SipClient.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sip/SipClient.kt) (Satır 1–993)
- Hem UDP (`DatagramSocket`) hem TLS (`SipTransport`) üzerinden çalışabilen ana SIP istemcisidir.
- **Zamanlayıcılar ve Sabitler (Satır 868–954):**
  - `REGISTER_TIMEOUT_SEC = 10L`
  - `MESSAGE_TIMEOUT_SEC = 10L`
  - `REREGISTER_INTERVAL_MS = 30 * 60 * 1000L` (30 dakika — sunucunun verdiği `Expires: 3600` süresinin yarısı).
  - `NAT_KEEPALIVE_INTERVAL_MS = 25_000L` (25 saniye — UDP modunda NAT tablosunun düşmemesi için RFC 5626 §3.5.1 uyumlu çıplak `\r\n\r\n` gönderir; **asla kimlik doğrulamasız `OPTIONS` göndermez**, çünkü sunucudaki `fail2ban` 401 yanıtlarını sayıp cihazın IP'sini yasaklamaktadır!).
  - `POLL_INTERVAL_MS = 5_000L` (5 saniye).
  - **`RegisterBackoff` (Companion Object — Satır 942–991):** `SipClient` yeniden oluşturulsa (`reconnect()`) bile sıfırlanmayan statik üstel geri çekilme (exponential backoff: `15s` -> `30s` -> `60s` -> ... -> maks `5 dakika`, ±%20 jitter ile). Böylece sunucu erişilemez olduğunda `REGISTER` fırtınası ve `fail2ban` engeli oluşmaz. Kullanıcı UI'da ayarları kaydettiğinde `SipClient.resetBackoff()` ile sıfırlanır.
- **Gelen İstek İşleme (`handleIncomingMessage` — Satır 552–746):**
  - `OPTIONS`: Anında `200 OK` döner (Asterisk `qualify=yes` yoklamalarını yanıtlar).
  - `NOTIFY`: Anında `200 OK` döner.
  - `MESSAGE`: Gelen SIP `MESSAGE` isteğine hemen `200 OK` döner ve `onIncomingMessage` üzerinden `GatewayService`'e (GSM SMS gönderimi için) iletir.
  - `INVITE`: Aktif çağrının yeniden gönderimi (retransmission) veya re-INVITE ise mevcut `activeCall`'a iletir; yeni çağrı ise `SipCall` oluşturup `onIncomingCall` tetikler.
  - `INFO`: `SipInfoDtmf.parse()` ile DTMF tuşunu çıkarıp `onDtmf` (`GsmCallManager.dtmfRelay.pulse`) tetikler ve `200 OK` döner.
  - `BYE` / `CANCEL`: `200 OK` döner ve aktif çağrıyı sonlandırır.

---

### 3.8. SMS Köprüsü Paketi (`com.callagent.gateway.sms`)

#### 3.8.1. [`SmsReceiver.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sms/SmsReceiver.kt) (Satır 1–117)
- `android.provider.Telephony.SMS_RECEIVED` (`priority="999"`, `directBootAware="true"`) broadcast alıcısıdır.
- `Telephony.Sms.Intents.getMessagesFromIntent(intent)` ile PDU parçalarını okur, aynı göndericiden gelen multipart parçaları tek metinde birleştirir.
- Hangi SIM'den geldiğini (`subscription` / `android.telephony.extra.SUBSCRIPTION_INDEX` / `slot`) tespit eder.
- Mesajı önce diske (`SmsStore.add` ve `CallLogStore.addSms`) yazar, ardından `GatewayService.notifyNewSms(context)` çağırır.

#### 3.8.2. [`SmsStore.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sms/SmsStore.kt) (Satır 1–114)
- **Gelen (GSM -> SIP) SMS kuyruğu:** `sms_queue` (`SharedPreferences`) içinde `pending` anahtarıyla en fazla **200** (`MAX_PENDING = 200`) adet `PendingSms` tutar.
- Her mesaja oluşturulduğu anda bir UUID (`id`) atanır ve SIP `MESSAGE` isteğinde `X-SMS-Id` başlığıyla gönderilir (sunucu tarafında idempotency/tekillik anahtarı). Sunucudan `2xx` yanıtı alınana kadar kuyruktan silinmez (`SmsStore.remove`).

#### 3.8.3. [`SmsOutbox.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sms/SmsOutbox.kt) (Satır 1–154)
- **Giden (SIP -> GSM) SMS kuyruğu ve durum makinesi:** `sms_outbox` (`SharedPreferences`) içinde `messages` anahtarıyla en fazla **200** (`MAX_ENTRIES = 200`) adet `OutboundSms` tutar.
- Sunucu aynı `X-SMS-Id` ile tekrar SIP `MESSAGE` gönderirse `enqueue()` mevcut kaydı döner (`duplicate = true`), böylece aynı SMS modeme iki kez gönderilmez.
- İki aşamalı raporlama bayrakları:
  - `readyToReportSend()`: Tüm parçalar (`parts`) modem tarafından gönderildiğinde (`sentOk + sentFailed >= parts`) ve henüz `sendReported` olmadığında `true` döner -> Sunucuya `X-SMS-Report: send` (`status=sent` veya `status=failed`) SIP `MESSAGE` raporu gönderilir.
  - `readyToReportDelivery()`: Tüm parçalar için SMSC teslimat raporu geldiğinde (`deliveredOk + deliveredFailed >= parts`) ve henüz `deliveryReported` olmadığında `true` döner -> Sunucuya `X-SMS-Report: delivery` (`status=delivered` veya `status=failed`) SIP `MESSAGE` raporu gönderilir.

#### 3.8.4. [`SmsSender.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sms/SmsSender.kt) (Satır 1–174) ve [`SmsSendReceiver.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sms/SmsSendReceiver.kt) (Satır 1–129)
- `SmsSender.dispatch(context, sms)`:
  - `smsManagerFor(context, sms.subId)` ile hedef SIM'e bağlı `SmsManager` nesnesini alır (API 31+ `createForSubscriptionId`, API 22–30 `SmsManager.getSmsManagerForSubscriptionId`).
  - `divideMessage` ve `sendMultipartTextMessage` kullanır.
  - **Kritik `PendingIntent.FLAG_MUTABLE` Kuralı (Satır 80–105):** Android Telephony çerçevesi SMS gönderim hatasını (`errorCode`) ve SMSC teslimat PDU'sunu (`pdu`, `format`) `PendingIntent` içine sonradan ekler. `FLAG_IMMUTABLE` kullanılırsa bu ekstra alanlar sessizce düşürülür! Bu yüzden kendi paketimize ve `SmsSendReceiver` sınıfımıza hedeflenmiş açık (explicit) `Intent` ile `FLAG_MUTABLE` kullanılır.
  - `sentResultName(resultCode)` (RIL 100–120 kodları dahil) ve `networkCauseName(cause)` (GSM 04.11 ağ hata kodları: `unassigned_number`, `operator_determined_barring`, `sms_transfer_rejected` vb.) okunabilir hata adları üretir.
- `SmsSendReceiver`:
  - `ACTION_SENT` ve `ACTION_DELIVERED` yayınlarını yakalar, teslimat PDU'sundan `serviceCenterAddress` (`smsc`) ve `status` (`< 32` başarılı, `>= 64` kalıcı hata) değerlerini okur, `SmsOutbox` ve `CallLogStore` kayıtlarını günceller ve `GatewayService.reportSmsProgress()` çağırır.

#### 3.8.5. [`Transliterate.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sms/Transliterate.kt) (Satır 1–116)
- Bazı modem ve SMSC'lerin GSM 7-bit alfabesindeki aksanlı karakterleri (`ä`, `ö`, `ü`, `ß`, `é` vb.) ASCII tablosuyla yanlış çözüp alıcıda `~`, `{`, `|` gibi bozuk karakterler göstermesini engeller.
- `toAsciiOrNull(text)`:
  - Metin tamamen Latin/aksanlı karakterlerden oluşuyorsa (`ä`->`ae`, `ö`->`oe`, `ü`->`ue`, `ß`->`ss`, `€`->`EUR` + Unicode NFD birleştirici işaret temizliği) düz ASCII metne dönüştürür (160 karakter/parça GSM7 sınırı korunur).
  - Metinde Kiril, Arapça, İbranice, Yunanca veya CJK gibi ASCII karşılığı olmayan tek bir karakter bile varsa **`null`** döner ve metin **olduğu gibi (değiştirilmeden)** bırakılır; çünkü bu diller Android'i zaten otomatik olarak UCS-2 (16-bit Unicode) kodlamasına zorlar ve UCS-2'de GSM 7-bit tablo karışıklığı yaşanmaz.

---

## 4. SIP Özel Başlıkları (Custom Headers) ve SMS/USSD Protokolü

| Yön | SIP Metodu | Başlık (Header) | Açıklama |
| :--- | :--- | :--- | :--- |
| **SIP -> GSM (Çağrı)** | `INVITE` | `X-GSM-Forward: <numara>` | Aranacak hedef GSM numarası (yoksa Request-URI user kısmı kullanılır). |
| **SIP -> GSM (Çağrı)** | `INVITE` | `X-Call-Ed: <numara>` | Çift SIM cihazda hangi SIM'in (`own_number_slot_<N>`) kullanılacağını seçmek için eşleştirilen numara. |
| **GSM -> SIP (SMS)** | `MESSAGE` | `X-SMS-Id: <uuid>` | Gelen SMS'in benzersiz tekillik (idempotency) anahtarı. |
| **GSM -> SIP (SMS)** | `MESSAGE` | `X-SMS-From: <numara>` | SMS'i gönderen GSM numarası. |
| **GSM -> SIP (SMS)** | `MESSAGE` | `X-SMS-To: <numara>` | SMS'i alan SIM kartın numarası (`own_number_slot_<N>`). |
| **GSM -> SIP (SMS)** | `MESSAGE` | `X-SMS-Slot: <1\|2>` | SMS'in geldiği SIM yuvası (1-tabanlı). |
| **GSM -> SIP (SMS)** | `MESSAGE` | `X-SMS-Carrier: <ad>` | SIM operatör adı. |
| **GSM -> SIP (SMS)** | `MESSAGE` | `X-SMS-Timestamp: <ms>` | SMS'in alındığı epoch milisaniye zaman damgası. |
| **GSM -> SIP (SMS)** | `MESSAGE` | `X-SMS-Parts: <sayı>` | Multipart SMS parça sayısı. |
| **SIP -> GSM (SMS)** | `MESSAGE` | `X-SMS-To` / `X-GSM-Forward` | Gönderilecek hedef GSM numarası. |
| **SIP -> GSM (SMS)** | `MESSAGE` | `X-SMS-Id: <id>` | Sunucunun verdiği SMS kimliği (varsa teslimat raporlarında geri gönderilir). |
| **GSM -> SIP (SMS Raporu)** | `MESSAGE` | `X-SMS-Report: send\|delivery` | Gönderim (`send`) veya SMSC teslimat (`delivery`) durum raporu (`X-SMS-Status`, `X-SMS-Error`, `X-SMS-SMSC`). |

---

## 5. Magisk Modülü (`magisk/`) ve Go `tinymix` Aracı (`tools/tinymix/`)

### 5.1. Magisk Modülü Detayları
- **[`magisk/install.sh`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/magisk/install.sh) (Satır 1–148):**
  - `SKIPMOUNT=false`, `PROPFILE=false`, `POSTFSDATA=false`, `LATESTARTSERVICE=false` değişkenlerini açıkça tanımlar (Magisk'te `SKIPMOUNT` tanımsız bırakılırsa boş komut `0` döndüğü için `$SKIPMOUNT && touch skip_mount` çalışıp modülü sessizce devre dışı bırakır!).
  - APK'yı `/system/priv-app/Gateway/Gateway.apk` konumuna yerleştirir.
  - Cihazın ABI'sine (`getprop ro.product.cpu.abi`) göre 64-bit ise `tinymix`, 32-bit (`armeabi-v7a`) ise `tinymix32` ikilisini `/system/bin/tinymix` olarak kurar.
  - **Soğuk Açılış (Cold Boot) Ses Kaydı Çözümü:** `/system/priv-app/PermissionController` (ve `product`/`system_ext`/`GooglePermissionController` varyantları) altına `.replace` dosyası koyarak `PermissionController` uygulamasını dosya sistemi düzeyinde gizler. Böylece ekran kilitliyken başlayan servisin `RECORD_AUDIO` izni `MODE_FOREGROUND` ile engellenmez.
- **[`magisk/service.sh`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/magisk/service.sh) (Satır 1–307):**
  - `adb install -r` ile `/data/app/` altına güncellenmiş APK varsa bunu Magisk overlay içindeki `/system/priv-app/Gateway/Gateway.apk` ile senkronize eder.
  - `wait_for_pm`: `sys.boot_completed=1` ve `pm path android` yanıt verene kadar bekler.
  - `seed_su_policy`: Uygulamanın UID'sini bulur ve `magisk --sqlite` ile `policies` tablosuna `policy=2, until=0` yazar (Magisk root izninin asla düşmemesini garanti eder).
  - Tüm çalışma zamanı izinlerini (`pm grant`) verir, `appops set --uid com.callagent.gateway RECORD_AUDIO allow` uygular.
  - `settings put global sms_outgoing_check_max_count 1000000` ile Android'in 30 dakikada 30 SMS gönderim sınırını kaldırır.
  - Varsayılan SMS uygulamasının (`com.google.android.apps.messaging` / `com.android.messaging`) bildirim iznini (`POST_NOTIFICATIONS` / `POST_NOTIFICATION`) kapatır.
  - `tinymix` ve `tinycap` ikililerini SELinux kısıtlamalarından kaçınmak için `/data/local/tmp/` altına kopyalar (`chmod 755`).

### 5.2. Go `tinymix` Aracı ([`tools/tinymix/`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/tools/tinymix/main.go))
- Harici C kütüphanesi (`libtinyalsa`) gerektirmez; `CGO_ENABLED=0` ile tamamen statik derlenir.
- **32-bit vs 64-bit Kernel ABI Farkı ([`abi32.go`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/tools/tinymix/abi32.go) & [`abi64.go`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/tools/tinymix/abi64.go)):**
  - Linux ALSA kontrol ioctl numaraları (`_IOWR('U', ...)`) yapının bayt boyutunu (`sizeof`) ioctl komut numarasının içine kodlar:
    - **64-bit (`arm64`):** `snd_ctl_elem_list` = 80 bayt (`0xC0505510`), `snd_ctl_elem_value` = 1224 bayt (`READ=0xC4C85512`, `WRITE=0xC4C85513`), `longSize = 8`.
    - **32-bit (`arm`):** `snd_ctl_elem_list` = 72 bayt (`0xC0485510`), `snd_ctl_elem_value` = 712 bayt (`READ=0xC2C85512`, `WRITE=0xC2C85513`), `longSize = 4`.
  - Her iki dosyada da derleme zamanı boyut doğrulaması (`unsafe.Sizeof`) bulunur.

---

## 6. SharedPreferences Anahtarları Özeti

### 6.1. `gateway_prefs` (Device-Protected Storage)
| Anahtar | Tip | Varsayılan | Açıklama |
| :--- | :--- | :--- | :--- |
| `sip_server` | `String` | `""` | SIP sunucu adresi (domain veya IP) |
| `sip_port` | `Int` | `5060` | SIP sunucu portu (TLS açıldığında 5061) |
| `sip_user` | `String` | `""` | SIP kullanıcı adı / dahili (extension) |
| `sip_password` | `String` | `""` | SIP şifresi |
| `outbound_cid` | `String` | `""` | Giden çağrı eşleştirmesi için CID |
| `own_number` | `String` | `""` | Tek SIM veya yedek SIM telefon numarası |
| `own_number_slot_0` | `String` | `""` | SIM 1 telefon numarası (gelen GSM aramalarında hedef SIP uzantısı) |
| `own_number_slot_1` | `String` | `""` | SIM 2 telefon numarası |
| `codec` | `String` | `"g722"` | `"g722"`, `"both"` veya `"g711"` |
| `sip_tls` | `Boolean` | `false` | SIP over TLS (RFC 3261) aktif mi |
| `srtp` | `Boolean` | `false` | SRTP medya şifrelemesi aktif mi |
| `agent_volume_step` | `Int` | `0` | Ajan ses seviyesi kademesi (`-3` .. `+3`, `0` = `1.0x`) |
| `call_monitoring` | `Boolean` | `false` | Her çağrıda Snoop (hoparlörden dinleme) varsayılan açık mı |
| `ascii_sms` / `translit_ascii` | `Boolean` | `false` | Giden SMS'lerde GSM 7-bit ASCII transliterasyon (`Transliterate.toAsciiOrNull`) |
| `theme_mode` | `String` | `"system"` | `"system"` (varsayılan, sistem tercihi), `"light"` veya `"dark"` (`tnoktasi.com.tr` renk paleti) |
| `local_send_enabled` | `Boolean` | `false` | **Local Send** modu: Hotspot üzerinden internetsiz yerel SIP sunucusu (`:5060`) |
| `critical_battery_threshold` | `Int` | `15` | Kritik batarya eşiği (`%`): Şarjda değilken bu eşikte ve altında her `%1` düşüşte Zoiper `0000` kanalına sahte SMS gönderir |
| `custom_commands` | `String` | `"!uptime = ..."` | Zoiper `0000` sahte SMS komut kanalında çalıştırılacak özel `!komut = bash_komutu` tanımları |

### 6.2. Diğer SharedPreferences Depoları
- `call_history` (`entries`): Son 50 çağrı ve SMS kaydı ([`CallLogStore.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/service/CallLogStore.kt)).
- `sms_queue` (`pending`): Sunucuya iletilmeyi bekleyen gelen GSM SMS kuyruğu, maks 200 ([`SmsStore.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sms/SmsStore.kt)).
- `sms_outbox` (`messages`): Modem üzerinden gönderilen ve sunucuya `send`/`delivery` raporu iletilecek olan giden SMS kuyruğu, maks 200 ([`SmsOutbox.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/sms/SmsOutbox.kt)).

---

## 7. Local Send (Hotspot Üzerinden İnternetsiz Zoiper Köprüsü) ve `0000` Komut Kanalı (`com.callagent.gateway.localsend`)

- **[`HotspotHelper.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/localsend/HotspotHelper.kt):**
  - `detectHotspot(context)`: Cihazda mobil veri kapalı olsa bile aktif Wi-Fi Hotspot / SoftAP / USB RNDIS arayüzünü (`ap+`, `softap+`, `swlan+`, `wlan+`, `rndis+`) ve yerel IPv4 adresini (örn. `192.168.43.1`) tespit eder.
  - `discoverConnectedClients(hotspotSubnetPrefix)`: `/proc/net/arp` ve `ip -4 neigh show` üzerinden Hotspot'a bağlı Zoiper istemcilerinin IP adreslerini bulur.
  - `toggleInternetSharing(context)`: `!internet` komutu geldiğinde `svc data enable` / `svc data disable` ile mobil veriyi açıp kapatır ve `sysctl -w net.ipv4.ip_forward=1` + `iptables -t nat -A POSTROUTING -o rmnet+ -j MASQUERADE` kurallarıyla Hotspot internet paylaşımını anında devreye alır veya kapatır.
- **[`LocalSendController.kt`](file:///c:/Users/Gebisis/Documents/gsm2sip-main/gsm2sip-main/app/src/main/java/com/callagent/gateway/localsend/LocalSendController.kt):**
  - **Sanal Zoiper Komut/Terminal Kanalı (`0000` / `T-Switch Terminal`):** Zoiper bağlandığında otomatik karşılama mesajı göndererek Zoiper içinde `0000` (`T-Switch Terminal`) sohbeti oluşturur.
  - **Kritik Batarya Sahte SMS Bildirimi:** `Intent.ACTION_BATTERY_CHANGED` dinleyicisi ile batarya kritik eşiğe (`critical_battery_threshold`, varsayılan `%15`) veya altına indiğinde **her `%1` düşüşte** operatör SMS'i harcamadan doğrudan SIP `MESSAGE` ile Zoiper'e acil uyarı gönderir.
  - **Dahili & Bash Komutları:** `!batarya`, `!internet`, `!geneldurum`, `!snoop`/`!dinle`, `!yardim`, kullanıcı tanımlı `!komut = bash` kısayolları ve doğrudan `$ <komut>` / `0000` kanalına yazılan root bash komutlarını çalıştırıp çıktısını anında sahte SMS olarak Zoiper'e döndürür.
