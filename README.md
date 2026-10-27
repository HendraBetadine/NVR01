# STB NVR — Perekam CCTV 24/7 untuk Formuler Z+ (Android 4.4)

Aplikasi sederhana: rekam kamera RTSP terus-menerus ke HDD/flashdisk USB, potong per 10 menit,
jalan otomatis saat box menyala, dan hapus rekaman terlama saat disk hampir penuh.

## A. Bikin APK (cara gampang, tanpa install apa-apa di PC)

1. Buat akun / login di **github.com** → klik **New repository** → beri nama `stbnvr` → **Create**.
2. Klik **uploading an existing file**, lalu drag **semua isi folder ini** (termasuk folder `.github`)
   → **Commit changes**.
   > Folder `.github` kadang tidak ikut kalau di-drag. Jika tab **Actions** kosong, buat file baru
   > lewat **Add file → Create new file** dengan nama `.github/workflows/build.yml`,
   > lalu paste isi file `build.yml` dari folder ini.
3. Buka tab **Actions** → tunggu ±10–15 menit sampai centang hijau (build pertama lama karena compile ffmpeg).
4. Klik build-nya → bagian **Artifacts** → download **STBNVR-apk** → ekstrak, dapat `app-release.apk`.

### Kunci tanda tangan (wajib supaya update tidak perlu uninstall)
Di repo GitHub: **Settings → Secrets and variables → Actions → New repository secret**, buat dua secret:
- `KEYSTORE_BASE64` — isi file `KEYSTORE_BASE64.txt`
- `KEYSTORE_PASSWORD` — isi file `KEYSTORE_PASSWORD.txt`

Simpan file `stbnvr.jks` + password di tempat aman. Jangan di-commit ke repo (repo ini publik).
Kalau kunci hilang, versi berikutnya harus uninstall dulu.

Setelah secret terpasang, setiap build menerbitkan APK di
`https://github.com/HendraBetadine/NVR01/releases/latest/download/STBNVR.apk`.

## B. Install ke Formuler Z+

1. Di box: **Settings → Security → Unknown sources** → aktifkan.
2. Copy `app-release.apk` ke flashdisk → colok ke box → buka pakai file manager → **Install**.
   (Atau via ADB: `adb connect IP_BOX` lalu `adb install app-release.apk`.)

## C. Pakai

1. Colok **HDD/SSD USB** (jangan rekam ke memori internal 8 GB).
2. Buka **STB NVR**, isi kamera satu per baris:
   ```
   depan,rtsp://admin:12345@192.168.1.64:554/Streaming/Channels/102
   gudang,rtsp://admin:12345@192.168.1.65:554/cam/realmonitor?channel=1&subtype=1
   ```
3. **Cari storage** → pilih HDD USB.
4. Tekan **SIMPAN & MULAI REKAM**. Status tiap kamera tampil di bawah.

Rekaman tersimpan di `<folder>/<nama kamera>/nama_YYYYMMDD_HHMMSS.mkv`. Putar dengan VLC.
Kalau kamera gagal tersambung, lihat `ffmpeg.log` di folder kamera tersebut.

## Live view (seperti NVR)

**Di TV (HDMI):** tekan **📺 LIVE VIEW** di aplikasi, atau centang *Tampilkan live view saat box menyala*.
Remote: panah = pilih kamera · OK = perbesar/kembali · tombol 1/4/9/6 = layout 1/4/9/16 ·
CH+/CH− = halaman berikutnya · Back = keluar.

**Di browser:** tab **Live** — layout 1/4/9/16, klik dua kali kamera untuk memperbesar, tombol layar penuh.

Catatan: live view memakai stream yang sama dengan perekaman (1 koneksi per kamera), jadi
perekaman harus aktif. Jeda ±4–6 detik. Formuler Z+ realistis 4 kamera sekaligus di TV
(keterbatasan hardware decoder); di browser tergantung PC/HP.

## D. Web server (upload / download file dari PC atau HP)

Aktif otomatis. Alamatnya tampil di bagian atas layar aplikasi, misalnya `http://192.168.1.50:8080`.

- Login: user **admin**, password default **admin** — ganti di layar aplikasi lalu tekan **Terapkan**.
- Pilih lokasi (Rekaman / Internal / USB), lalu upload (tombol atau drag & drop), download, buat folder, hapus.
- Upload ditulis langsung ke disk, jadi file besar (GB) tetap aman. Download mendukung resume.
- Status perekaman kamera juga tampil di halaman ini.
- Hanya untuk jaringan lokal. **Jangan** di-port-forward ke internet tanpa VPN.
- Jangan simpan file `.mkv` pribadi di dalam folder kamera — folder itu ikut dibersihkan otomatis.

## E. HTTPS langsung di STB (tanpa proxy)

1. Ganti password web (minimal 8 karakter, bukan `admin`).
2. Pengaturan → **HTTPS**: centang aktif, isi domain (mis. `home.58s.my.id`), Simpan & terapkan.
3. Di router, DNAT IP publik:
   - port **80 → IP-STB:8080** (validasi & perpanjangan sertifikat Let's Encrypt)
   - port **443 → IP-STB:8443** (HTTPS)
4. Sertifikat diminta otomatis (±30 detik) dan diperpanjang sendiri 30 hari sebelum habis.
   Akses `http://` dari internet otomatis dialihkan ke `https://`. Akses LAN tetap `http://IP-STB:8080`.

### URL RTSP umum (pakai substream supaya ringan)
| Merek | Substream |
|---|---|
| Hikvision / Ezviz | `rtsp://user:pass@IP:554/Streaming/Channels/102` |
| Dahua / Imou | `rtsp://user:pass@IP:554/cam/realmonitor?channel=1&subtype=1` |
| Xiongmai (XMEye) | `rtsp://IP:554/user=admin&password=&channel=1&stream=1.sdp` |
| TP-Link Tapo | `rtsp://user:pass@IP:554/stream2` |

## Catatan
- Formuler Z+: CPU A7 + RAM 1 GB → realistis **2–4 kamera**. Rekaman tidak di-encode ulang, jadi ringan.
- Kebutuhan disk ≈ bitrate × 10,8 GB/hari per Mbps. Substream 512 kbps ≈ 5,4 GB/hari/kamera.
- Mati listrik: aplikasi otomatis jalan lagi setelah box menyala (format MKV tetap terbaca walau terpotong).
- Set IP kamera **statis** (atau DHCP reservation di router) supaya URL tidak berubah.

## Build manual (opsional)
- Butuh Ubuntu + Android NDK **r25c** (r26+ tidak support Android 4.4).
- `ANDROID_NDK_HOME=~/android-ndk-r25c ./scripts/build-ffmpeg.sh`
- Lalu buka folder ini di Android Studio → **Build → Build APK**.
