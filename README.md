# T-Switch (T-Noktası GSM ↔ SIP Call Switcher)

**T-Switch**, root'lu bir Android telefonu 7/24 kesintisiz çalışan bir **GSM ↔ SIP köprüsüne** dönüştüren açık kaynaklı bir Android uygulaması ve sistem katmanıdır.

Temel amacı; SIM kartınızın takılı olduğu yedek bir Android cihazı (öncelikli hedef donanım: **General Mobile GM 8 / Qualcomm MSM8937**) sabit veya taşınabilir bir GSM geçidi olarak kullanıp, gelen/giden tüm **GSM aramalarını ve SMS'lerini** internet veya yerel ağ üzerinden **iPhone (Linphone / Apple PushKit)** ya da kendi SIP sunucunuza **tamamen dijital ses kalitesiyle** aktarmaktır.

---

## Neden T-Switch?

- **İkinci SIM / Yedek Hattı Tek Telefonda Taşıma:** Fiziksel SIM kart Android cihazda kalır; aramaları ve SMS'leri ana telefonunuzdaki (ör. iPhone) Linphone uygulaması üzerinden doğrudan karşılar ve başlatırsınız.
- **Ekran Kilitliyken Bile Ulaşılabilirlik (Apple PushKit):** iOS arka plan kısıtlamalarını aşmak için TLS (`5061`) üzerinden Apple PushKit uyandırma sinyali gönderir; iPhone uyku modundayken bile gelen GSM araması anında çalar.
- **Tamamen Dijital, Yankısız Ses Köprüsü:** Hoparlör-mikrofon arası akustik aktarım yapmaz. Doğrudan modem downlink/uplink ses yollarını (ALSA / `Incall_Music` / `VOICE_DOWNLINK`) kullanarak cihazın fiziksel mikrofonunu ve hoparlörünü sessiz tutar.
- **Uzaktan Yönetim Terminali:** SIP mesajlaşma kanalı üzerinden telefonun bataryasını, internet paylaşımını, ekranını, kiosk modunu kontrol edebilir; rehberden isimle arama veya SMS başlatabilirsiniz.

---

## Sistem Mimarisi

```text
                       ┌───────────────────────────────────────────┐
                       │         T-Switch (Root'lu Android)        │
  GSM Şebekesi         │                                           │        Bulut / Yerel Ağ
 ┌────────────┐  SIM   │  GsmCallService ──▶ CallOrchestrator      │  TLS   ┌──────────────────┐
 │  Arayan /  │◄──────►│        │                  │               │◄──────►│  SIP Sunucusu    │
 │  SMS Ağı   │  Modem │  VOICE_DOWNLINK     SipClient / RTP       │  5061  │  (Linphone vb.)  │
 └────────────┘        │  libalsaplay.so     PushWakeController    │  UDP   └────────┬─────────┘
                       │  (ALSA Incall_Music & CVP TX Mute)        │  5060           │ PushKit /
                       │        │                  │               │                 │ SIP / RTP
                       │  SmsReceiver/Outbox  LocalSendController  │        ┌────────▼─────────┐
                       └───────────────────────────────────────────┘        │  iPhone / SIP    │
                                                                            │  İstemcisi       │
                                                                            │  (Linphone vb.)  │
                                                                            └──────────────────┘
```

---

## Çalışma Modları

T-Switch, Ayarlar ekranındaki yapılandırmaya göre üç farklı yönlendirme modunda çalışabilir:

| Mod | Açıklama | Tipik Kullanım |
| --- | --- | --- |
| **Bulut Modu** *(Varsayılan)* (`CLOUD_ONLY_LINPHONE`) | `sip.linphone.org:5061` (TLS) + Apple PushKit ile tek hesap üzerinden arama, SMS ve uzaktan komut köprüleme. | Farklı ağlarda / mobil veride 7/24 iPhone kullanımı |
| **Hibrit Yerel Mod** (`HYBRID_LOCAL_IP`) | Hotspot veya Wi-Fi üzerinde sabit yerel IP (`192.168.1.85:5060`) ve `tswitch.local` (mDNS) ile yerel SIP + gerekirse Bulut PushKit uyandırma. | Aynı yerel ağda / hotspot üzerinde düşük gecikmeli çalışma |
| **Özel SIP Sunucu Modu** (`CUSTOM_SIP_WITH_PUSH_WAKE`) | Kendi Asterisk, FreePBX veya özel SIP sunucunuza UDP/TLS (+ isteğe bağlı SDES SRTP) ile kayıt. | Kurumsal santral veya yapay zeka sesli asistan entegrasyonu |

---

## Temel Özellikler

### 1. Çift Yönlü Dijital Ses Köprüsü
- **Kodek Desteği:** G.722 (16 kHz wideband — varsayılan) ve G.711 (PCMA / PCMU, 8 kHz), ayrıca RFC 4733 & SIP INFO DTMF aktarımı.
- **Donanım Profili & Root ALSA Enjeksiyonu:**
  - **General Mobile GM 8 (`msm8937`):** Dahili C aracı `libalsaplay.so` + atomik `libtinyalsa.so` kontrolü ile doğrudan `/dev/snd/pcmC0D12p` (`Incall_Music`) cihazına 16 kHz mono PCM enjeksiyonu; aynı anda CVP TX yolu sessize alınarak cihaz mikrofonundan ortam sesi/yankı gitmesi engellenir.
  - **Poco X3 NFC (`sm6150`)** ve **Galaxy S4 Mini (`msm8930`)** profilleri de `DeviceProfile` içinde tanımlıdır.
- **Snoop (Canlı Dinleme) & Sessize Alma:** İsteğe bağlı olarak aktif çağrıyı cihaz hoparlöründen tek dokunuşla veya `!snoop` komutuyla dinleyebilirsiniz (mikrofon sessiz kalır).

### 2. T-Switch Terminal (SIP Mesaj Komut Kanalı)
Eşleşmiş SIP hesabınızdan (veya yerel modda `0000` kanalından) mesaj göndererek köprüyü uzaktan yönetebilirsiniz:

| Komut | İşlev |
| --- | --- |
| `!ara <numara veya kişi adı>` | Önce iPhone'u çaldırır, açtığınızda GSM hattından hedefi arar (rehberde isim eşleştirme destekli). |
| `!sms <numara veya kişi adı> <mesaj>` | Belirtilen numara veya rehberdeki kişiye GSM üzerinden SMS gönderir. |
| `!geneldurum` / `!durum` | Batarya, sıcaklık, şebeke, IP, SIP ve PushKit durum özetini döner. |
| `!batarya` | Pil yüzdesi, şarj durumu, voltaj ve sıcaklık bilgisini gösterir. |
| `!ekran <ac\|kapat>` | Cihaz ekranını uzaktan açar veya kapatır. |
| `!kiosk <ac\|kapat>` | Kiosk / launcher güç tasarrufu modunu yönetir. |
| `!internet` | Mobil veri / hotspot internet paylaşımını açıp kapatır. |
| `!gb` | Operatör kalan internet/paket sorgusu (SMS) tetikler. |
| `!snoop` | Aktif çağrıda cihaz hoparlör dinleme modunu açar/kapatır. |
| `!push` / `!push test` / `!push ac\|kapat` | Apple PushKit durumunu görüntüler, test uyandırması gönderir veya açıp kapatır. |
| `!yardim` | Tüm yerleşik ve kullanıcı tanımlı özel komutların listesini döner. |
| `$ <komut>` | Yalnızca yetkili gönderen için root shell komutu çalıştırır. |

### 3. SMS Köprüsü & Kuyruk Yönetimi
- Gelen GSM SMS'leri bağlantı kopuksa diskte kuyruklanır (`SmsStore`) ve SIP kaydı sağlandığında sırayla iletilir.
- Giden SMS'ler parça sayısı ve kodlama (GSM-7 / UCS-2) ölçümü ile `SmsOutbox` üzerinden gönderilir; isteğe bağlı ASCII dönüştürme (transliteration) desteklenir.
- Şarjda değilken pil belirlenen kritik eşiğin (varsayılan `%15`) altına düştüğünde her `%1` düşüşte SIP üzerinden bildirim gönderilir.

### 4. Kiosk, Dayanıklılık ve Yerel Yedek Arayüz
- **Açılışta Otomatik Başlatma:** `BootReceiver` cihaz açıldığında `RECORD_AUDIO` appops iznini root ile yeniler ve servisi otomatik başlatır.
- **Yerel Fallback:** iPhone'a veya SIP istemcisine ulaşılamadığında gelen arama Android cihazın kendi ekranında çalarak doğrudan cevaplanabilir; ayrıca uygulama içinde dahili tuş takımı ve rehber bulunur.

---

## Gereksinimler

- **Android Cihaz:** Root erişimli (Magisk önerilir) Android 8.0+ (API 26+) Qualcomm tabanlı cihaz (öncelikli test cihazı: **General Mobile GM 8 / MSM8937**).
- **Ayrıcalıklı İzinler:** `CAPTURE_AUDIO_OUTPUT` (Magisk modülü / `priv-app` kurulumu ile), `RECORD_AUDIO`, `READ_PHONE_STATE`, `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `RECEIVE_SMS`, `SEND_SMS`, `READ_CONTACTS`.
- **Varsayılan Arama Uygulaması (Default Dialer):** Android `InCallService` bağlanabilmesi için T-Switch varsayılan telefon uygulaması olarak seçilmelidir.
- **Derleme Ortamı (Kaynak Koddan):** JDK 17, Android SDK (API 34) ve Android NDK (`alsaplay.c` native derlemesi için).

---

## Derleme ve Kurulum

### 1. Birim Testleri ve APK Derleme

```powershell
# Birim testlerini çalıştır
.\gradlew.bat testDebugUnitTest --no-daemon

# Debug APK oluştur
.\gradlew.bat assembleDebug --no-daemon
```

Oluşan APK dosyası `app/build/outputs/apk/debug/app-debug.apk` konumunda yer alır.

### 2. Cihaza Kurulum
- Root'lu cihazda `CAPTURE_AUDIO_OUTPUT` izninin etkin olması için `magisk-module/` dizinindeki yapılandırmayı kullanarak uygulamayı ayrıcalıklı sistem uygulaması (`priv-app`) olarak kurmanız önerilir.
- Güncelleme sırasında hızlı kurulum için:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### 3. İlk Yapılandırma (Uygulama İçi)

> **Not:** Güvenlik ve gizlilik gereği kaynak kodda herhangi bir kişisel SIP hesabı, şifre veya telefon numarası gömülü **değildir**. Tüm hesap bilgilerini ilk kurulumda uygulama içinden girmeniz gerekir.

1. İki adet SIP hesabı oluşturun (ör. ücretsiz `sip.linphone.org` üzerinde bir **Gateway / Gönderici** hesabı ve bir **iPhone / Alıcı** hesabı).
2. iPhone'da **Linphone** uygulamasını açın, **Alıcı** hesabıyla oturum açın ve Push bildirimlerinin etkin olduğundan emin olun.
3. Android cihazda **T-Switch → Ayarlar → Linphone Hibrit PushKit** kartında:
   - **Linphone SIP Domain:** `sip.linphone.org` (veya kendi sunucunuz)
   - **Gateway Gönderici Hesabı (Pusher):** kendi gönderici kullanıcı adınız
   - **Gönderici SIP Şifresi:** gönderici hesabınızın şifresi
   - **iPhone Alıcı Hesabı (Receiver):** iPhone'da açık olan alıcı kullanıcı adınız
4. **Push Kaydet** butonuna basın ve **PushKit Test** ile iPhone'un uyandığını doğrulayın.
5. (İsteğe bağlı) Yerel ağ/hotspot üzerinden sabit IP ile çalışmak için **İkincil Yerel SIP Modu** seçeneğini veya kendi santraliniz için **Özel SIP Sunucusu** alanını yapılandırın.

---

## Güvenlik ve Gizlilik

- **Yetkili Gönderen Doğrulaması (`CommandAuthorization`):** Bulut üzerinden gelen `!ara`, `!sms`, terminal ve `$ <bash>` komutları yalnızca yapılandırılmış eşleşmiş alıcı hesabından (`Receiver`) ve izin verilen SIP alan adından geliyorsa kabul edilir; diğer tüm kaynaklara `403 Forbidden` dönülür.
- **Şifreli İletişim:** Bulut modunda SIP sinyalleşmesi TLS (`5061`) üzerinden taşınır; Özel SIP Sunucu modunda TLS + SDES SRTP (RFC 3711) desteklenir.
- **Sıfır Gömülü Kimlik Bilgisi:** Kişisel SIP kullanıcı adları, şifreler ve telefon numaraları yalnızca cihaz üzerindeki `SharedPreferences` alanında saklanır; depoda yer almaz.

---

## Proje Yapısı

```text
T-Switch/
├── app/
│   └── src/
│       ├── main/
│       │   ├── cpp/alsaplay.c                  # Root ALSA PCM uplink enjeksiyonu (libalsaplay.so)
│       │   ├── java/com/callagent/gateway/
│       │   │   ├── MainActivity.kt             # Ana panel, ayarlar, tuş takımı, rehber, yerel arama ekranı
│       │   │   ├── audio/                      # AlsaPcmPlayback, DeviceProfile, G711, G722, RtpSession
│       │   │   ├── gsm/                        # GsmCallService, GsmCallManager, CallOrchestrator
│       │   │   ├── localsend/                  # PushWakeController, LocalSendController, CommandAuthorization,
│       │   │   │                               # KioskController, HotspotHelper, LocalIpPinController, mDNS
│       │   │   ├── service/                    # GatewayService, BootReceiver, RoutingPolicy
│       │   │   ├── sip/                        # SipClient, TlsSipTransport, SipAuth, SipBuilder, SipMessage
│       │   │   └── sms/                        # SmsReceiver, SmsSender, SmsStore, SmsOutbox
│       │   └── res/                            # Arayüz yerleşimleri ve kaynaklar
│       └── test/                               # JVM birim testleri
├── magisk-module/                              # Ayrıcalıklı izinler (priv-app) ve boot desteği için Magisk modülü
└── README.md
```

---

## Temel Proje ve Lisans

Bu proje, açık kaynaklı **gsm2sip** mimarisi üzerine inşa edilerek General Mobile GM 8 (`msm8937`) root ALSA ses enjeksiyonu, Apple PushKit hibrit bulut/yerel yönlendirme, Kiosk otomasyonu ve T-Switch uzaktan komut terminali ile genişletilmiştir.

Lisans bilgileri için [LICENSE](LICENSE) dosyasına bakın.
