# T-Switch Tek Hesap Varsayılan Modu ve Akıllı Rehberli `!ara` / `!sms` Tasarım Dokümanı

**Tarih:** 2026-11-24  
**Kapsam:** `RoutingPolicy`, `CommandAuthorization`, yeni `ContactCommandResolver`, `GatewayService`, `CallOrchestrator`, `LocalSendController`, `MainActivity` + `activity_main.xml`

---

## 1. Amaç ve Neden
Kullanıcı iPhone’da iki ayrı SIP hesabı yönetmekten yorulduğu için **tek hesap (`t-switch_receiver@sip.linphone.org`)** deneyimini varsayılan yapmak, yerel SIP (`192.168.1.85:5060`) mekanizmasını kaybetmeden **ayrı bir mod seçeneğine** taşımak ve terminal sohbetinden hem numara hem rehberdeki isimle (yakın eşleşme + etkileşimli onay ile) arama (`!ara` / `! ara`) ve SMS (`!sms` / `! sms`) başlatabilmek istiyor.

---

## 2. Yaklaşımlar ve Seçilen Tasarım
- **Yaklaşım A (Mevcut 2 Modlu Yapıyı Zorlamak):** İki hesabı zorunlu kıldığı için kullanıcı talebiyle çelişir.
- **Yaklaşım B (3 Açık Mod + Etkileşimli Rehber Çözümleyici — *Seçilen*):**
  1. **`RoutingMode.CLOUD_ONLY_LINPHONE` (Yeni Varsayılan):** Özel SIP sunucusu boş ve "İkincil Yerel SIP Modu" kapalıyken aktif olur. Yalnızca tek kalıcı `sip.linphone.org:5061` TLS istemcisi (`t-switch_pusher`) çalışır; yerel UDP `:5060` ve `192.168.1.85` sabitlemesi açılmaz. Gelen aramalar doğrudan `PushWakeController` (`wakeOnly = false`), gelen/giden SMS ve tüm terminal komutları bu tek TLS hattından yürür.
  2. **`RoutingMode.HYBRID_LOCAL_IP` (Ayrı Mod Seçeneği):** Kullanıcı Ayarlar’dan **"İkincil Yerel SIP Modu (`192.168.1.85`)"** (`local_sip_mode_enabled = true`) seçeneğini açtığında mevcut çift yığınlı yerel IP + bulut hibrit mekanizması aynen devreye girer.
  3. **`RoutingMode.CUSTOM_SIP_WITH_PUSH_WAKE` (Değişmez Kural Korunur):** Kullanıcı özel bir SIP sunucu adresi girdiğinde tüm ana faaliyetler o sunucudan, Linphone ise yalnızca `wakeOnly = true` PushKit uyandırıcı olarak çalışır.

---

## 3. Akıllı Rehberli `!ara` / `!sms` Davranışı (`ContactCommandResolver`)
- **Boşluk Toleransı:** `!ara`, `! ara`, `!sms`, `! sms` (büyük/küçük harf duyarsız) hepsi kabul edilir.
- **Numara vs İsim Ayrımı (`CommandAuthorization`):**
  - `!ara <hedef>`: `<hedef>` telefon numarasıysa → `CallbackCall(numara)`; isimse → `ContactCallQuery(isim)`.
  - `!sms <hedef> <mesaj>`: `<hedef>` telefon numarasıysa → `OutboundSmsCommand(numara, mesaj)`; isimse → `ContactSmsQuery(isim, mesaj)`. *(Örn. `!sms Ahmet yoldayım` veya `!sms "Ahmet Yılmaz" yoldayım` desteklenir.)*
- **Türkçe Normalizasyon & Bulanık (Fuzzy) Eşleştirme:**
  - `İ/I/ı→i`, `ş→s`, `ğ→g`, `ç→c`, `ö→o`, `ü→u`, küçük harf, boşluk normalizasyonu.
  - **Kesin tek eşleşme** (tam isim veya tekil, yazım hatasız kelime/alt-dize eşleşmesi): **hemen çalıştırır** (arama başlatır veya SMS gönderir) ve bilgi mesajı döner (`"📞 Aranıyor: Ahmet Yılmaz (+90532...)"` / `"✉️ SMS gönderiliyor: Ahmet Yılmaz (+90532...)"`).
  - **Tek yakın/bulanık eşleşme** (yazım hatası, ör. `"Ahmt"` → `"Ahmet Yılmaz"`, Levenshtein mesafesi ≤ 2): onay sorar (`"❓ Bunu mu demek istediniz?\n1) Ahmet Yılmaz (+90532...)\nOnaylamak için 'evet' veya '1', vazgeçmek için 'iptal' yazın."`).
  - **Birden fazla eşleşme** (en iyi 5 sonuç): numaralı liste ile sorar (`"1-5"` veya `"iptal"`).
  - **Eşleşme yoksa:** `"❌ Rehberde '<sorgu>' için eşleşme bulunamadı."` döner.
- **Etkileşimli Onay Oturumu:** 60 sn geçerlidir (`evet`/`e`/`1`..`5` onaylar, `iptal`/`h`/`0` iptal eder). Yeni bir `!ara`/`!sms` önceki bekleyen onayı temizler. Tüm eşleştirme ve onay mantığı saf Kotlin + JUnit 4 TDD testleriyle doğrulanır.

---

## 4. Kısıtlar ve Uygulayıcıya Bırakılanlar
- `minSdk = 26`, mevcut 79 birim testinde sıfır gerileme, tüm güvenlik sınırları (eşli hesap doğrulaması, `!`/`$` normal SMS koruması, çift SMS önleme) aynen korunur.
- Rehber okuma (`ContactsContract`) arka planda/önbellekten çalışır; UI thread bloklanmaz.
