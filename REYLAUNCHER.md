# REYLauncher

Xiaomi TV Box S (3. nesil) için kişisel ana ekran. [tvOS Launcher v2.0.5](https://github.com/techgenius1/tvOS-Launcher)
(techgenius, Apache 2.0) temel alınarak yapıldı; orijinal lisans ve teşekkürler `LICENSE` ve `NOTICE`
dosyalarında duruyor. Paket adı `com.reylauncher.tv`, yani orijinal uygulamayla çakışmaz.

> **Durum:** Kod derleniyor ama henüz gerçek bir Mi Box S 3. nesilde denenmedi. İlk kurulumda çıkan
> sorunları not alıp düzeltmek gerekecek.

## Orijinalden farkları

**Kaldırılanlar**

| Ne | Neden |
|---|---|
| GitHub'dan otomatik güncelleme | İnternetten APK indirip kendi kendine (ADB ile sessizce) kurabiliyordu. Güncellemeler artık elle kurulur. |
| Sunum modu (8080 portunda web sunucusu) | Evdeki ağa açık bir sunucuydu. |
| AirPlay koruması | Ağa düzenli mDNS sorgusu atıyordu. |
| Cihaz yöneticisi (device admin) | Uygulamanın kaldırılmasını engelliyordu (orijinal projedeki #4). Uyku/kapatma artık erişilebilirlik servisiyle yapılıyor. |
| Konum izni | Sadece Wi-Fi ağının adını göstermek içindi. |
| Üst raf önerileri ve haberler, TMDB, Stremio | Kod tamamen silindi; JustWatch/TMDB/Stremio/haber sitelerine istek gitmez. |
| Boşta tam ekran gösteri, profiller, gezinme sesleri, AdGuard DNS anahtarı | İstenmedi; kapatıldı ve ayarlardan gizlendi. |
| 13 dil | Sadece Türkçe (varsayılan) ve İngilizce seçilebilir. |

**Eklenenler / değişenler**

- **Bing günün fotoğrafı (4K):** "Özel 1…5" duvar kağıtları yerine tek bir "Bing günün fotoğrafı" sahnesi
  (Gece Sahneleri) ve Klasik görünüm arka planı. Fotoğraf 4K (3840×2160, ~3,5 MB) indirilir ve olduğu
  gibi saklanır; ekran hangi çözünürlükte çiziliyorsa o boyutta açılır (Ayarlar → Hakkında → Ekran'da
  görünür). 4K yoksa 1920×1080 indirilir. Kutuda tek dosya tutulur, her gün üzerine yazılır; Bing
  sahnesi ve arka planı kapatılınca silinir. Hazır sahneler aynen duruyor.
- **Depolama temizliği:** Film araması afiş önbelleği 7 günden eski dosyaları siler ve 20 MB'ı geçmez;
  "İzlemeye devam et" listesinden çıkan içeriklerin resimleri silinir.
- **Film/dizi araması Türkiye'ye göre** (JustWatch, Türkçe).
- **Türk uygulama logoları:** TOD, Exxen, tabii, puhutv, TV+, Tivibu GO, beIN CONNECT, D-Smart GO,
  S Sport Plus.
- **Kumanda tuş isimleri:** Tuş atayıcıda Mi Box kumandasının Netflix / YouTube / Prime Video /
  Xiaomi TV+ / Uygulamalar tuşları adıyla görünür; herhangi bir uygulamaya atanabilir.
- **CleanYourTV, Xiaomi TV Box S 3. nesil (MiTV-AFMU0) profili:** Orijinal projedeki, bu kutuda denenmiş
  profil kullanılır; uygulama modeli kendisi tanır. REYLauncher'da varsayılan olarak sadece telemetri,
  reklam satırları ve otomatik yüklemeler seçili gelir. Disney+, HBO Max, Xiaomi TV+, ekran yansıtma
  (Miracast), USB medya oynatıcıları, galeri ve kurulum sihirbazı (setupwraith) isteğe bağlıdır,
  işaretlenmeden kapatılmaz.
- **Güvenli ADB** (aşağıda).

## Güvenlik ve gizlilik

### İnternete giden istekler (kodda kalan hepsi bu)

| Özellik | Adres | Ne zaman | Ne gönderilir |
|---|---|---|---|
| Hava durumu | `api.open-meteo.com` (yedek: `api.met.no`, `wttr.in`) | Hava durumu açıkken, varsayılan 15 dakikada bir | Seçili şehrin enlem/boylamı |
| Şehir arama | `geocoding-api.open-meteo.com` | Ayarlarda şehir ararken | Yazdığın şehir adı |
| Otomatik konum | `ipapi.co`, `ipwho.is`, `get.geojs.io` | Sadece şehir seçilmemişse, haftada en fazla bir kez | Hiçbir veri gönderilmez (her istekte olduğu gibi IP adresin görünür). Ayarlardan şehir seçersen hiç kullanılmaz. |
| Film/dizi arama | `apis.justwatch.com`, `images.justwatch.com` | Arama ekranında bir şey yazınca | Yazdığın arama metni |
| Bing fotoğrafı | `www.bing.com` | Bing sahnesi ya da arka planı açıksa, günde en fazla bir kez (~3,5 MB) | Hiçbir veri gönderilmez |

Analitik, reklam, hata raporu gönderme ya da "eve haber verme" kodu yok. Yüklü uygulama listesi,
izlediklerin, dosyaların hiçbir yere gönderilmez.

### İzinler ve neden gerekli oldukları

| İzin | Ne için |
|---|---|
| İnternet, ağ durumu | Yukarıdaki istekler, internet göstergesi |
| Tüm uygulamaları görme (`QUERY_ALL_PACKAGES`) | Ana ekranda uygulamaları listelemek (her başlatıcı için gerekli) |
| Kullanım istatistikleri | Son uygulamalar listesi |
| Bildirim erişimi | "İzlemeye devam et": oynatılan medyanın adı ve resmi, sadece kutuda saklanır |
| Erişilebilirlik servisi | Tuş atayıcı, Kontrol Merkezi, Home/ses tuşu düzeltmeleri, uyku. Tuş basışlarını ve hangi uygulamanın açıldığını görür; ekran içeriğini sadece "Son uygulamalar"da bir uygulamayı kapatırken Ayarlar'daki "Durmaya zorla" düğmesini bulmak için okur. |
| Dosyalara erişim | Uygulama simgesi değiştirirken seçtiğin resmi okumak |
| Mikrofon | Sadece arama ekranında mikrofona basınca (ilk kullanımda sorulur) |
| `WRITE_SECURE_SETTINGS` | Kurulumda bir kez verilir. TV erişilebilirlik iznini kendiliğinden kapatırsa REYLauncher'ın sadece **kendi** servisini yeniden açması ve ADB'yi kapatabilmesi için. |
| Arka plan işlemlerini sonlandırma | "Son uygulamalar → Tümünü kapat" |

### ADB nasıl kullanılıyor?

Orijinal uygulama ADB'nin (5555 portu) **sürekli açık** kalmasını istiyor, kendi ADB anahtarını saklıyor
ve tuş girişlerini ham olarak okuyordu (orijinal projedeki #1). REYLauncher'da:

- ADB sadece **kurulumda** ve **CleanYourTV** işlemlerinde kullanılır, sadece kutunun kendi adresine
  (`127.0.0.1`) bağlanılır.
- Kurulum bitince ve son kullanımdan **3 dakika sonra ADB otomatik kapatılır**; REYLauncher'ın ADB anahtarı
  silinir. Bir sonraki ADB işinde TV yeniden "Hata ayıklamaya izin verilsin mi?" diye sorar.
- "Her zaman izin ver" kutusunu işaretlemek gerekmez. **Senin başlatmadığın bir izin sorusunu asla onaylama.**
- Ayarlar → Gelişmiş: "ADB'yi şimdi kapat" (5555 portunun kapandığını kontrol eder) ve
  "İşlem bitince ADB'yi otomatik kapat".
- Ham tuş okuma (`getevent`) kaldırıldı; ses tuşu düzeltmesi erişilebilirlik servisinin zaten aldığı
  tuş olaylarıyla çalışır.
- ADB bir işlem sürerken ya da Ayarlar ekranı açıkken kapatılmaz; Ayarlar'dan çıktıktan 3 dakika sonra
  ve TV uykuya geçince kapatılır. Kapatmak için yeni bir ADB bağlantısı açılmaz (beklenmedik
  "izin verilsin mi?" sorusu çıkmaz).
- Erişilebilirlik otomatik onarımı sadece kutu açıldıktan sonraki ilk 10 dakikada çalışır; servisi
  sonradan kendin kapatırsan dokunmaz.
- **Bilinmeyen:** Bazı Google TV 14 kutularında ADB kapatılınca 5555 portu, Geliştirici seçeneklerinden
  USB hata ayıklamayı yeniden açmakla geri gelmeyebilir (bilgisayardan `adb tcpip 5555` gerekebilir).
  Mi Box S 2. nesilde sadece USB hata ayıklamayı açmak yetiyordu; 3. nesilde henüz denenmedi. Sorun
  olursa Ayarlar → Gelişmiş → "İşlem bitince ADB'yi otomatik kapat"ı kapatabilirsin.

### Kaldırma

Cihaz yöneticisi olmadığı için normal yoldan kaldırılır: Ayarlar → Uygulamalar → REYLauncher → Kaldır.
İstersen önce Ayarlar → Gelişmiş → "Tüm izinleri kaldır".

## Kurulum

1. APK'yı kutuya at (ör. iPhone'dan AirTransfer / "Send Files to TV", ya da Downloader uygulaması).
2. Kurulum sihirbazında **Kolay Kurulum (ADB)**: Geliştirici seçenekleri → USB hata ayıklama açılır,
   izinler verilir, sonra REYLauncher ADB'yi kendisi kapatır. İstersen **Manuel Kurulum** ile izinleri
   tek tek verirsin.
3. Google TV'de Home tuşu varsayılan olarak Google'ın ana ekranını açar: Ayarlar → Düğme eşleyici →
   "Ana Ekran tuşu düzeltmesi"ni aç (ya da Gelişmiş → CleanYourTV → "Sistem başlatıcılarını devre dışı
   bırak").

## Derleme

- **Resmi yol (yayımlanan APK'lar böyle derlenir):** Android SDK build-tools 34.0.0 + platform android-34,
  **JDK 17** ile `./build.sh` (aapt2 + d8 + apksigner). Çıktı: `build/REYLauncher.apk`.
  JDK 21 ile derlenen sınıflarda d8 8.2 hata veriyor (orijinal kodda da aynı); JDK 17 kullanın.
  Release imzası için: `RELEASE_KEYSTORE`, `RELEASE_KEYSTORE_PASS`, `RELEASE_KEY_ALIAS` ortam değişkenleri.
- Android SDK indirilemeyen ortamlar için yedek yol: `tools/build-local.sh` (Ubuntu paketleri + Maven
  Central; ayrıntılar dosyanın başında).
- İmza anahtarı bu repoda **yok** (`*.p12`, `*.pass` `.gitignore`'da).
