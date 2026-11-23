# T-Switch (GM 8 ↔ iPhone) Tam Hibrit Mimari ve %100 Stabilite Tasarım Dokümanı

**Tarih:** 2026-11-23  
**Kapsam:** `gsm2sip` / **T-Switch** Android uygulaması (`app/src/main`)  
**Hedef Cihaz:** General Mobile GM 8 (`msm8937`, root/Magisk, `minSdk 26`, Kiosk Launcher + GSM-SIP Gateway)

---

## 1. Amaç ve Neden (Intent & Why)

T-Switch, fiziksel SIM kartın takılı olduğu **General Mobile GM 8** cihazını, günlük kullanılan **iPhone (Linphone / CallKit)** için kesintisiz bir ikinci hat köprüsüne dönüştürürken, iPhone ulaşılabilir olmadığında GM 8’in tek başına hızlı ve güvenilir bir Kiosk telefon olarak çalışmasını sağlar.

Derin kod denetiminde **17 doğrulanmış P0/P1/P2 hata** tespit edildi (TLS UTF-8 bayt çerçeveleme kilitlenmesi, `407 Proxy-Authorization` eksikliği, `From:` domain/user uyumsuzlukları, normal Wi-Fi’ın Hotspot sanılıp PushKit’in atlanması, `PCMU` ses bozulması, kimlik doğrulamasız root komut yürütmesi ve `!`/`$` normal SMS yutulması, çift gönderilen operatör SMS’i, arama bekletmenin aktif görüşmeyi öldürmesi, GM 8 hoparlör ses sızıntısı ve Android 8.0/8.1 crash’leri).

Bu tasarım, bu 17 hatanın **tamamını kök nedeninden çözmeyi**, geleceğe dönük **özel SIP sunucusu + Apple PushKit ayrımını** kesin bir kurala bağlamayı ve tüm çekirdek mantığı **%100 TDD** ile güvenceye almayı hedefler.

---

## 2. Hedefler, Kapsam Dışı Olanlar ve Anti-Hedefler

### Hedefler (Goals)
1. **Kesin 2 Modlu Geleceğe Dönük Yönlendirme Kuralı (Kullanıcı Kararı):**
   - **Mod 1 — `HYBRID_LOCAL_IP` (Özel SIP Sunucusu BOŞSA veya yalnızca `sip.linphone.org` ise — VARSAYILAN):**
     - **UYARI / DEĞİŞMEZ KURAL:** Kullanıcı "Özel SIP Sunucu Adresi" alanını boş bıraktığı sürece sistem **kesinlikle** bu IP tabanlı hibrit modda kalır.
     - **Çift Yığın (Dual-Stack) Çalışma:** `GatewayService` aynı anda iki uyumlu yığını yönetir:
       1. **Yerel UDP `:5060` Registrar (`SipClient` `localSendMode = true`):** `192.168.1.x` Wi-Fi’da `wlan0` üzerinde ve gerçek Hotspot’ta `192.168.1.85` sabit yerel IP’si (ve `tswitch.local` mDNS) üzerinden hizmet verir.
       2. **Kalıcı Bulut TLS `:5061` İstemcisi (`sip.linphone.org` / `t-switch_pusher`):** `t-switch_receiver@sip.linphone.org` hesabından gelen komutları (`!ara`, `!sms`, tüm terminal komutları) dinler, gelen GSM SMS’leri için bulut yedeği sağlar ve `PushWakeController` (`wakeOnly = false`) ile gelen GSM çağrılarını bulut + Apple PushKit üzerinden tam ses köprüsüyle aktarır.
   - **Mod 2 — `CUSTOM_SIP_WITH_PUSH_WAKE` (YALNIZCA kullanıcı özel SIP sunucu adresi girdiğinde — örn. `sip.alanadin.com`):**
     - Yerel IP / `Local Send` devreden çıkar; tek bir `SipClient` doğrudan kullanıcının özel SIP sunucusuna (`TLS :5061` + SRTP veya yapılandırılan port) `REGISTER` olur ve **tüm ana faaliyetler** (gelen/giden arama aktarımı, ses RTP/SRTP köprüleme, SMS) bu özel SIP sunucusundan yürür.
     - `sip.linphone.org` (`PushWakeController`) bu modda **YALNIZCA Apple PushKit uyandırma sinyali (`wakeOnly = true`)** olarak çalışır; kendi üzerinden medya köprüsü kurmaz.
2. **Dışarıdayken Buluttan Giden Arama (iPhone-First Callback) ve Dışarı SMS (Kullanıcı Kararı):**
   - `!ara <numara>` (veya eşli hesaptan gelen yalın telefon numarası): GM 8 **önce iPhone’u** (`t-switch_receiver@sip.linphone.org`) çaldırır; iPhone `200 OK` ile açınca RTP/ringback başlar ve GM 8 hedef numarayı SIM karttan arayıp iki sesi köprüler.
   - `!sms <numara> <mesaj>`: GM 8 mesajı SIM karttan tek seferlik (`SmsOutbox.claimForDispatch`) SMS olarak gönderir.
3. **Eşli Hesap Doğrulamalı Tam Terminal Yetkisi (Kullanıcı Kararı):**
   - Tüm terminal komutları (`!internet`, `!kiosk`, `!ekran`, `!debloat`, `!gb`, `!snoop`, `!batarya`, `!geneldurum`, `!yardim`, `custom_commands`, `$` bash) hem yerel ağda hem bulutta aktiftir; **ANCAK** yalnızca gönderenin **eşli cihazdaki yetkili hesap olduğu doğrulandığında** çalıştırılır:
     - Yerel ağda: kaynak IP:port aktif kayıtlı `registeredPeer` olmalı.
     - Bulut / Özel SIP’te: SIP `From:` kullanıcısı birebir eşli alıcı hesabıyla (`PushWakeController.getReceiverUser()`, örn. `t-switch_receiver`) ve yetkili domain (`pushDomain` veya `customSipServer`) ile eşleşmeli; yetkisiz göndericiler `403 Forbidden` ile reddedilir.
     - **Normal SMS Koruması:** Alıcı hedefi gerçek bir telefon numarasıysa (`+905...`, `05...`), gövde `!` veya `$` ile başlasa bile **asla** terminal komutu sayılmaz; doğrudan GSM SMS olarak gönderilir.
4. **Sıfır Protokol, Ses, SMS ve Kiosk Hatası:** Tüm 17 P0/P1/P2 hatasının kök nedenden giderilmesi ve **%100 TDD** birim test güvencesi.

### Kapsam Dışı (Non-Goals)
- VPS üzerinde Asterisk kurulumu (yalnızca GM 8 Android uygulaması).
- iOS Linphone uygulamasında değişiklik yapmak.
- Yerel `192.168.1.85` için self-signed TLS veya port `53` DNS sunucusu kurmak.

### Anti-Hedefler (Anti-Goals)
- Özel SIP sunucusu boşken sistemin IP (`192.168.1.85` / yerel) davranışını kaybetmesi.
- Özel SIP sunucusu doluyken `sip.linphone.org` PushKit bacağının ana çağrıyı ele geçirmesi veya çift çağrı yaratması.
- Aynı çağrıda iPhone CallKit ekranının hem yerel hem bulut hattından **çift çalması**.
- GM 8 hoparlöründen/zilinden SIP-köprülü aramada dışarı ses sızması.
- Yetkisiz herhangi bir SIP hesabının `root` komutu çalıştırabilmesi veya normal SMS’in operatörden 2 kez gönderilmesi.

---

## 3. Değerlendirilen Yaklaşımlar (Approaches)

1. **Yaklaşım 1 — Yalnızca Bulut (`sip.linphone.org`):** Ev/Hotspot’ta doğrudan yerel IP tuşlamasını ve internetsiz yerel modu kaybeder.
2. **Yaklaşım 2 — Yalnızca Sabit Yerel IP (`192.168.1.85`):** Dışarıdayken iPhone’dan arama/SMS yapılamaz ve gelecekteki özel SIP sunucusu için mimari eksiktir.
3. **Yaklaşım 3 — Kesin 2 Modlu Tam Hibrit + Açık Özel SIP Anahtarı *(Seçilen Yaklaşım)*:**
   - Eski kafa karıştıran `local_send_enabled` anahtarı yerine deterministik `RoutingPolicy` kullanılır: `customSipServer` boş → `HYBRID_LOCAL_IP` (çift yığın: yerel IP `192.168.1.85` + bulut `sip.linphone.org` TLS); `customSipServer` dolu → `CUSTOM_SIP_WITH_PUSH_WAKE` (özel SIP sunucusu + yalnızca PushKit uyandırıcı).

---

## 4. Sistem Tasarımı ve Modül Bazlı Çözüm Şartnamesi

### 4.1. Yönlendirme Politikası, Sabit Yerel IP ve Failover (`RoutingPolicy`, `LocalIpPinController`, `CallOrchestrator`)
- **`RoutingPolicy.kt` (Saf Kotlin — JVM’de test edilebilir):**
  - Girdiler: `customSipServer: String`, `customSipPort: Int`, `customSipTls: Boolean`, `pinnedLocalIp: String` (varsayılan `"192.168.1.85"`), `pushEnabled: Boolean`, `pushPasswordSet: Boolean`.
  - Çıktı:
    - `customSipServer.trim()` boşsa veya `sip.linphone.org` ise → `RoutingMode.HYBRID_LOCAL_IP`.
    - Aksi halde → `RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE` (`effectiveTls = customSipTls || customSipPort == 5061`).
- **Sabit Yerel IP (`LocalIpPinController.kt` — `HYBRID_LOCAL_IP` modunda):**
  - `192.168.1.x` client Wi-Fi’da (`wlan0` alt ağı `192.168.1.0/24` ise): idempotent `ip addr replace 192.168.1.85/24 dev wlan0`. Farklı subnet’lerde (`192.168.0.x`, `10.x.x.x`) dokunmaz (ağı bozmaz).
  - Gerçek Hotspot aktifken (`ap0`/`swlan0`/`wlan0` AP, örn. `192.168.43.1`): idempotent `ip addr replace 192.168.1.85/32 dev <iface>` + `iptables -t nat -C PREROUTING -i <iface> -d 192.168.1.85 -j DNAT --to-destination <apIp> || -A ...`; Hotspot kapanınca temizlenir.
- **`HYBRID_LOCAL_IP` Gelen Çağrı & SMS Failover Kuralı *(Mühendislik Kararı — Çift Çalmayı Önler)*:**
  - `SipClient` yerel peer için `lastPeerSeenMs` TTL’i (`65_000L` ms = `Expires: 60` + 5 sn tolerans) tutar.
  - **Gelen GSM Çağrısı:**
    - Yerel peer taze (`registeredPeer != null && age <= 65s`) ise: önce yerel UDP `INVITE` gönderilir ve **1.5 saniye** yanıt penceresi beklenir (uyanık yerel iPhone `<100ms` içinde `100/180` döner).
    - 1.5 sn içinde yerel yanıt yoksa veya yerel peer bayat/yoksa: **sıralı failover** ile yerel `INVITE` tekrarları ve `resendPendingOutboundInvites()` bu çağrı nesli (`generation`) için **bastırılır** ve `PushWakeController` (`wakeOnly = false`) tek medya bacağı olarak devreye girer. Böylece iPhone uyanıp yerel ağa tekrar kayıt olsa bile CallKit **asla çift çalmaz**.
  - **Gelen GSM SMS:** Yerel peer tazeyse önce yerel UDP `MESSAGE` denenir; yanıt `0` veya `>= 400` ise otomatik olarak bulut `sip.linphone.org` TLS hattından `t-switch_receiver@sip.linphone.org`’a düşer (`From: "<gönderen>" <sip:t-switch_pusher@sip.linphone.org>`).
- **`CUSTOM_SIP_WITH_PUSH_WAKE` Uyandırma ve `480`/`404` Yeniden Deneme Kuralı *(Mühendislik Kararı)*:**
  - Gelen GSM çağrısında `SipClient.makeCall()` doğrudan özel SIP sunucusuna gider.
  - Özel SIP sunucusu **1.2 sn** içinde `180/183/200` dönmezse **VEYA** anında `480 Temporarily Unavailable` / `404 Not Found` dönerse (iPhone uykuda olduğu için Asterisk hemen reddeder):
    - `PushWakeController` **`wakeOnly = true`** modunda ateşlenir (iPhone’u Apple PushKit ile uyandırır; `200 OK` alsa dahi medya köprülemez, temizçe `BYE` atar).
    - Uyandırma penceresi (en fazla **20 sn**) boyunca `480`/`404` yanıtları kalıcı hata sayılmaz; `CallOrchestrator` her **2.0 saniyede** bir özel SIP sunucusuna `SipClient.makeCall()` tekrar dener.
    - iPhone uyanıp özel SIP sunucusuna kayıt olup `180`/`183`/`200` döndüğü anda `PushWakeController.cancelWake()` anında çağrılır ve görüşme tamamen özel SIP sunucusu (TLS/SRTP) üzerinden yürür.
    - iPhone çağrıyı bilinçli reddederse (`486`/`603`), yeniden denenmez ve GM 8 yerel çalmaya düşürülmez.
- **iPhone-First Bulut Callback (`!ara <numara>` / yalın numara):**
  1. Kalıcı bulut TLS istemcisi (`t-switch_pusher@sip.linphone.org`) eşli `t-switch_receiver` hesabından `!ara <numara>` veya yalın telefon numarası (`+905...`, `05...`) aldığında `200 OK` döner ve `CallOrchestrator.startCloudCallback(destNumber)` çağırır.
  2. `CallOrchestrator` önce iPhone’a (`t-switch_receiver@sip.linphone.org`) bulut SIP `INVITE` atar (`bridgeState = CALLBACK_CALLING_SIP`).
  3. iPhone `200 OK` ile cevapladığında GM 8 RTP oturumunu başlatır (ringback/sessizlik akışı hazır) ve `GsmCallManager.makeCall(context, destNumber, headless = true)` ile hedef GSM numarasını arar (`bridgeState = GSM_DIALING` → `BRIDGED`).
  4. iPhone açmadan vazgeçerse (`486`/`603`/zaman aşımı) GSM araması hiç başlatılmaz.

### 4.2. SIP / TLS / Digest Protokol Düzeltmeleri (P0)
1. **`TlsSipTransport` (`SipTransport.kt`):** Ham bayt tamponu (`ByteArray` / `ByteArrayOutputStream`) ile `\r\n\r\n` ve `Content-Length` **UTF-8 bayt uzunluğu** üzerinden çerçeveleme; Türkçe karakter ve parçalı TCP okumalarında sıfır kilitlenme/bozulma.
2. **`SipMessage` (`SipMessage.kt`):** Çoklu başlık saklama (`headers: Map<String, List<String>>`), tüm `Via:` başlıklarının yanıtlarla korunması, çok satırlı ve virgülle ayrılmış `Record-Route:` ayrıştırması; `SipBuilder.register()` içinde `From: <sip:$username@$domain>`.
3. **`SipAuth` (`SipAuth.kt`):** `AuthParams(realm, nonce, opaque, qop, isProxy, algorithm)`; `407` için `Proxy-Authorization:`, `401` için `Authorization:`; `MD5` ve `SHA-256` desteği.
4. **`SipClient` & `SipCall` (`SipClient.kt`, `SipCall.kt`):**
   - `REGISTER` `401/407` başına en fazla 1 auth tekrarı (sonsuz döngü/IP ban koruması).
   - `SipCall` `401/407` `INVITE` challenge’ını `ACK` ettikten sonra auth’lu `INVITE` gönderir; dialog `Record-Route` → ters `Route:` başlıklarını `ACK`/`BYE` içine ekler; TLS’te `localPort` (5062) yerine `advertisedPort` (ephemeral TLS portu) kullanır; TLS’te UDP Timer-A tekrarı yapmaz.
   - `makeCall()` / `resendInviteWithAuth()` içinde `call.fromHeader` ile wire `From:` birebir eşleşir; `sip.linphone.org` için `From` user = `username` ve arayan numara = `Display Name`.
   - `handleIncomingInvite()` `180 Ringing` paketini hemen atmaz (sadece `100 Trying`); `CallOrchestrator.handleOutboundFlow()` onaylayınca `180 Ringing` gönderir.
   - `sendSipMessageToAddress()` `407` yanıtında `Proxy-Authorization:` ile yeniden dener.

### 4.3. `PushWakeController` & `RtpSession` Düzeltmeleri (P0/P1)
1. **`PushWakeController.kt`:**
   - `wakeOnly: Boolean` parametresi eklenir (`CUSTOM_SIP_WITH_PUSH_WAKE` → `true`, `HYBRID_LOCAL_IP` → `false`).
   - `cancelWake()` aktif bekleme/soketi `<100ms` içinde keser; `CANCEL` yalnızca `1xx` alındıktan sonra gönderilir ve `487` `ACK` edilir; in-dialog `BYE` için `401/407` auth challenge yanıtlanır.
   - Tüm yanıtlar explicit `transport=tls` kullanır (global `SipBuilder.transport`’a bağımlı değil).
   - STUN keşfi için `RtpSession` ile port çakışmasını ve CGNAT pinhole kaybını önleyen tek port/hızlı STUN (en fazla 2.5 sn toplam süre, başarısızsa anında yerel IP) + `re-INVITE` geldiğinde aktif `RtpSession` uzak RTP hedefinin canlı güncellenmesi (`updateRemoteEndpoint`).
   - `detectLocalIpv4()` hücresel/VPN/dummy arayüzleri dışlar.
2. **`PcmuCodec.kt` + `RtpSession.kt`:**
   - Gerçek ITU-T G.711 µ-law (`PcmuCodec`) kodlayıcı/çözücü eklenir; `PT_PCMU` (0) `captureLoop`, `playbackLoop` ve `sendSilencePackets` yollarında bağlanır.
   - `playbackLoop()` içinde `track.play()` çağrısı `enableIncallMusicViaMixer()` tamamlandıktan sonra yapılır (GM 8 hoparlör başlangıç ses patlamasını önler).

### 4.4. Güvenlik, SMS ve Komut Yetkilendirmesi (P0/P1)
1. **Yetkilendirme Doğrulayıcısı (`CommandAuthorization.kt` — Saf Kotlin):**
   - Gelen SIP `MESSAGE` için `(routingMode, target, fromUri, sourceAddr, registeredPeer, expectedReceiverUser, expectedDomains, body)` girdilerini değerlendirip tek bir karar döner: `TERMINAL_COMMAND`, `CALLBACK_CALL`, `OUTBOUND_SMS_COMMAND` (`!sms`), `DIRECT_GSM_SMS`, `REJECT_FORBIDDEN (403)`, veya `REJECT_BAD_REQUEST (400)`.
   - Hedef geçerli bir telefon numarasıysa (`+905...`, `05...`, en az 7 rakam) gövde `!` veya `$` ile başlasa bile `DIRECT_GSM_SMS` döner (asla komut çalışmaz).
   - Hedef terminal/gateway hesabıysa (`0000`, `tswitch`, `t-switch`, `terminal`, `t-switch_pusher`):
     - Yerel modda kaynak `registeredPeer` ile eşleşmeli;
     - Bulut / Özel SIP’te `From` kullanıcısı `expectedReceiverUser` (`t-switch_receiver`) ve domain yetkili domain ile eşleşmeli; aksi halde `REJECT_FORBIDDEN (403)`.
     - Yetkiliyse: `!ara <num>` / yalın numara → `CALLBACK_CALL`; `!sms <num> <metin>` → `OUTBOUND_SMS_COMMAND`; diğer tüm `!komut` / `$` / `custom_commands` → `TERMINAL_COMMAND` (tüm komutlar aktif!).
2. **Çift SMS Önleme:** `MainActivity.showQuickSmsDialog()` ve `LocalSendController.queryTurkcellRemainingGb()` gönderim öncesi `SmsOutbox.claimForDispatch(id)` kullanır (veya `GatewayService.dispatchOutbox()`’a bırakır).

### 4.5. Ağ, Telekom, Kiosk, Crash ve Bellek Düzeltmeleri (P0/P1/P2)
- **`HotspotHelper.kt`:** Normal client Wi-Fi (`wlan0`, AP kapalı, tether IP’si değil) `active = false` döner (client Wi-Fi IP’si ayrı `wifiIp` alanında taşınır). `toggleInternetSharing()` `ip rule` eklemesini idempotent yapar ve kapatırken siler.
- **`LocalMdnsResponder.kt`:** `rmnet*`/`tun*`/`dummy*`/`p2p*` arayüzlerini filtreler; arayüz değişimini sadece `SocketTimeoutException`’da değil periyodik zaman damgasıyla her 5 sn’de bir kontrol eder.
- **`GsmCallManager.kt`:**
  - İkinci gelen çağrı (`onCallAdded`) aktif çağrıyı (`activeCall != null && activeCallState != STATE_DISCONNECTED`) ezmez; `onCallRemoved` yalnızca `call === activeCall` ise `restoreAudio()` çağırır.
  - `onCallStateChanged(STATE_RINGING)` zil sesini susturur; `STATE_DIALING`/`STATE_CONNECTING` içinde `markNextOutgoingAsLocal` işlenir; `CallOrchestrator.onGsmCallStateChanged()` `isLocalFallbackCall(call)` koruması içerir.
  - `clearLocalFallback()` callback’i her zaman `MainLooper` üzerinde çalıştırır, tutulan `WakeLock`’u serbest bırakır, `am.mode = AudioManager.MODE_NORMAL` yapar ve Kiosk ekran kapatma zamanlayıcısını yalnızca **gelen fallback çağrısıysa** ve 1.5 sn sonunda aktif/çalan çağrı yoksa çalıştırır.
- **`MainActivity.kt` & `GatewayService.kt` & `CallLogStore.kt` & `KioskController.kt`:**
  - `MainActivity`: API 28+ (`getSignalStrength`, `mccString`/`mncString`) `Build.VERSION.SDK_INT >= 28` koruması; `onDestroy`’da `onLocalFallbackStateChanged = null`; `onPause`’da `localTimerHandler` iptali; ölü `uptimeRunnable` temizliği; rehber aramasına `200ms` debounce; `btnLocalReject`/`btnLocalHangup` çağrıyı sonlandırıp logladıktan sonra `clearLocalFallback()`; `STATE_HOLDING` için `"Bekletiliyor"` etiketi (sayaç sıfırlanmaz); Ayarlar’da "Özel SIP Sunucusu" alanı + doğru şifre/port/TLS senkronizasyonu + doğru çağrı yönü ve TLS/SRTP rozetleri.
  - `GatewayService`: `startForeground()` tüm erken `stopSelf()` dönüşlerinden önce; `EXTRA_PORT` default `-1` (kayıtlı portu ezmez); `applyConfigChange()` codec + agent volume yeniler; `SimpleDateFormat` thread-safe (`ThreadLocal`); `onDestroy()` `rescueHandler` iptali.
  - `CallLogStore`: `getEntries()` ve `clear()` `@Synchronized`.
  - `KioskController`: `debloatAndBoost()` gerçek sayıyı döner (sahte `.coerceAtLeast(39)` yok); `applyKioskMode(false)` `screen_off_timeout` (`60000`) ve animasyon ölçeklerini (`1.0`) geri yükler.

---

## 5. Uygulayıcıya (Builder) Bırakılan Detaylar

- JUnit4 birim testlerinin dosya yerleşimi (`app/src/test/java/com/callagent/gateway/...`) ve sahte soket/clock test yardımcıları.
- Ayarlar ekranındaki "Özel SIP Sunucusu" ve "Sabit Yerel IP (`192.168.1.85`)" kartlarının mevcut soft-rounded XML temasına uygun yerleştirilmesi.
- `CallConnectionService` kullanılmayan stub’ının temizlenip temizlenmemesi (manifest riski yoksa korunabilir).
