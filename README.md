# Stomake Desktop 2.1.1 — JavaFX

Stomake 2.1.1 adalah aplikasi desktop untuk menulis cerita, mengatur worldbuilding, dan roleplay karakter AI. UI desktop menggunakan **Java 21 + JavaFX**; data cerita dan percakapan disimpan di SQLite. AI berjalan melalui **Ollama lokal**.

## Fitur JavaFX

### Proyek dan penulisan
- Beranda proyek, buat/ganti nama/hapus cerita, sinopsis, sampul, dan import cerita.
- Editor bab berbasis JavaFX WebView dengan bold, italic, underline, strikethrough, heading, font family, ukuran font yang terlihat, alignment, daftar, dan autosave.
- Sisipkan gambar ke bab dengan dialog crop.
- Riwayat versi bab: simpan snapshot saat isi berubah, lihat versi sebelumnya, dan pulihkan versi.
- Mode baca terpisah dengan daftar isi yang bisa disembunyikan dan fullscreen.
- Statistik kata, bab, jumlah entri Story Bible, aktivitas menulis, dan target kata harian.

### Story Bible dan gambar
- Entri karakter, lokasi, faksi, benda, lore, dan kategori custom.
- Trait custom yang bisa ditambahkan dan dipakai ulang.
- Gambar utama dan galeri multi-gambar untuk entri Bible; crop, atur lebar, pilih tata letak kiri/tengah/kanan, dan hapus gambar.
- Sampul cerita bisa diganti/crop dan sinopsis dapat diedit.
- Peta relasi karakter: buat relasi, beri label/catatan, lihat diagram, dan hapus relasi.

### Studio Karakter dan AI
- Karakter mandiri yang tidak harus terikat ke proyek cerita.
- Persona mencakup personality, traits, background, appearance, speaking style, motivations, flaws, secrets, relationship to user, scenario, greeting, extra instructions, dan memory notes.
- Chat roleplay tersimpan per karakter; model bisa dipilih per karakter, chat bisa direset, dan profil bisa dimasukkan ke Story Bible.
- Prompt roleplay menyertakan profil karakter dan riwayat chat terbaru. Tombol ringkas memori membantu menyimpan fakta kontinuitas saat riwayat percakapan panjang.
- AI Penulis terpisah dari model roleplay; tampil sebagai chat dengan riwayat percakapan tersimpan per proyek, pemilih model dan refresh daftar model Ollama.
- Pengaturan server Ollama, model, koneksi, dan unduh model.

## Model AI roleplay

Default roleplay diarahkan ke fine-tune komunitas Gemma 3 4B Heretic:

```powershell
ollama pull R4C3R/gemma-3-4b-it-heretic:q4_k_m
```

Alternatif yang lebih ringan:

```powershell
ollama pull richardyoung/qwen2.5-3b-instruct-abliterated:latest
```

Alternatif roleplay lebih besar:

```powershell
ollama pull R4C3R/qwen3-8b-heretic:q4_k_m
```

Model uncensored mengurangi kecenderungan menolak, tetapi tidak menjamin penolakan nol atau konsistensi sempurna. Model 8B lebih berat; untuk laptop dengan RAM 8 GB, mulai dari model 3B/4B dan tutup aplikasi berat. AI lokal membutuhkan Ollama yang aktif dan model sudah terunduh.

## Build otomatis menjadi aplikasi Windows lewat GitHub Actions

**Kamu tidak perlu memasang JDK atau Maven di laptop hanya untuk menghasilkan build Windows.** Workflow `.github/workflows/build-windows.yml` sudah disertakan. GitHub Actions akan menggunakan runner Windows untuk memasang JDK 21 dan Maven, kemudian menjalankan `Build-Windows.ps1` dan mengunggah hasilnya sebagai artifact.

### Cara memasukkan source ke GitHub

1. Buat repository baru di GitHub, misalnya `Stomake-Desktop`. Pilih **Public** atau **Private** sesuai kebutuhan, dan biarkan repository benar-benar kosong (jangan centang Add a README, license, atau `.gitignore`).
2. Ekstrak ZIP source Stomake, lalu buka terminal di folder `Stomake-2.1.1` (folder yang berisi `pom.xml`, `Build-Windows.ps1`, dan `.github`).
3. Pastikan `.github/workflows/build-windows.yml` ikut terunggah. Jangan unggah database pribadi atau backup berisi naskah.
4. Jalankan perintah berikut setelah mengganti URL repository dengan milikmu:

```powershell
git init -b main
git add .
git commit -m "Stomake 2.1.1 JavaFX"
git remote add origin https://github.com/USERNAME/Stomake-Desktop.git
git push -u origin main
```

Kalau repository sudah memiliki remote dan branch, cukup salin file source ke folder repository, lalu jalankan `git add .`, `git commit`, dan `git push`.

### Mengambil EXE hasil build

1. Buka repository GitHub, lalu pilih tab **Actions**.
2. Pilih workflow **Build Stomake Windows**. Workflow berjalan otomatis saat ada push ke branch repository mana pun; kamu juga bisa memilih **Run workflow** untuk menjalankannya manual.
3. Buka hasil run terbaru dan cari bagian **Artifacts**.
4. Unduh artifact **Stomake-Windows-x64**. Di dalamnya ada ZIP aplikasi Windows dan file checksum.
5. Ekstrak `Stomake-2.1.1-Windows-x64.zip` secara utuh, lalu jalankan `Stomake.exe` di dalam folder `Stomake`.

### Apa yang dihasilkan?

GitHub Actions membuat **portable app-image**: folder aplikasi yang berisi `Stomake.exe`, library JavaFX, dependency, dan runtime Java privat. Jadi pengguna Windows tidak perlu memasang JDK/Maven. Jangan memindahkan `Stomake.exe` keluar dari foldernya.

Ini bukan satu file EXE installer yang berdiri sendiri. Aplikasi perlu seluruh folder `Stomake`. Proses build lokal juga tersedia melalui `Build-Windows.ps1`, tetapi cara GitHub Actions di atas tidak memerlukan toolchain build di laptop.

## Yang tetap terpisah: Ollama

EXE Stomake memuat runtime Java dan UI, tetapi **Ollama dan file model AI tidak dimasukkan ke dalam EXE**. Ollama adalah layanan inferensi lokal yang harus dipasang dan dijalankan terpisah. Setelah Ollama aktif dan model diunduh, Stomake mengaksesnya melalui alamat lokal `http://localhost:11434` secara default. Fitur editor, Story Bible, timeline, dan pengelolaan cerita tetap bisa digunakan tanpa Ollama.

## Build manual (opsional)

Jika ingin menjalankan source atau build di komputer sendiri:

- JDK 21
- Maven 3.9+
- Windows x64 untuk menghasilkan Windows app-image

```powershell
mvn javafx:run
```

Untuk membuat portable Windows app-image:

```powershell
./Build-Windows.ps1
```

Hasilnya ada di `dist\Stomake\Stomake.exe` dan folder `dist\Stomake`.

## Database dan migrasi Stomake 1.x

Lokasi database baru:

- Windows: `%APPDATA%\Stomake\data\Stomake.db`
- Sistem lain: `~/.stomake/data/Stomake.db`

Saat pertama dijalankan, aplikasi mencoba menyalin database lama dari `data\Stomake.db` atau `data\nopai.db` pada folder kerja. Bila database lama berada di tempat lain, tutup aplikasi lama, buka **Pengaturan & Model AI → Impor database Stomake 1.x**, pilih database, lalu restart aplikasi.

Migrasi skema bersifat additive: tabel dan kolom lama dibiarkan, sedangkan tabel/kolom baru dibuat bila belum ada. Fitur impor/ekspor `.stomake` mempertahankan metadata cerita, bab, cover, gambar/galeri, Story Bible, timeline, dan relasi yang tersedia dalam format tersebut. Ekspor Word menyertakan teks cerita, sinopsis, Story Bible, timeline, dan relasi; pemformatan rich-text serta gambar tidak seluruhnya direproduksi di dokumen Word.

**Selalu backup database sebelum migrasi, restore, atau penghapusan proyek.** Aplikasi menyimpan data lokal; jangan commit file database ke repository publik.

## Pengujian dan batasan build

Source JavaFX dan workflow build Windows disertakan. Build Windows yang sebenarnya dijalankan di runner GitHub Actions; lingkungan pengembangan ini tidak menyediakan Maven dan dependensi JavaFX untuk menjalankan full build. Karena itu, keberhasilan pembuatan `Stomake.exe` perlu dikonfirmasi dari hasil run GitHub Actions pertama.
