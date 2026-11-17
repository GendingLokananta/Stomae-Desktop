# Stomake PC

Stomake PC adalah aplikasi desktop Windows berbasis Python + PySide6 + SQLite. Versi ini melanjutkan fondasi desktop Stomake/NopAI lama dan dirancang untuk bertukar cerita dengan Stomake Android melalui file `.stomake`.

## Fitur yang tersedia

- Banyak proyek cerita, bab, autosave, rich-text editor, shortcut Bold/Italic/Underline/Strikethrough, heading, alignment, pilihan font, dan sisipkan gambar.
- Story Bible / World Bible, kategori custom, profil karakter/role, koneksi karakter, relasi dengan deskripsi, tautan entri ke bab.
- Timeline, statistik dan target kata, riwayat versi/snapshot.
- Mode Baca untuk sinopsis, bab, Bible, relasi, dan timeline.
- Sampul dan sinopsis proyek; gambar utama dan galeri pada entri Bible; crop sebelum memilih gambar.
- Empat tema: Dark, Light, Warm Light, Slate. Tema disimpan di pengaturan Windows.
- Panel navigasi kiri dan AI kanan dapat diminimize secara terpisah.
- Mode baca non-modal di jendela terpisah, mendukung fullscreen dan multitasking.
- AI Assistant lokal melalui Ollama, pemilih model lokal dan refresh daftar model, konteks proyek, riwayat percakapan yang tersimpan.
- Backup/Restore database lokal.
- Export/Import `.stomake` kompatibel dengan format single-story Stomake Android (JSON dengan ekstensi `.stomake`).
- Export Word `.docx` dan opsi menyiapkan file untuk Google Docs.

## Build Windows EXE dengan GitHub Actions

1. Buat repository GitHub baru, misalnya `Stomake-PC`.
2. Upload seluruh isi folder ini ke branch `main`.
3. Buka tab **Actions**. Workflow **Build Stomake PC EXE** akan berjalan otomatis.
4. Setelah selesai, buka run terakhir lalu unduh artifact **Stomake-PC-Windows**.
5. Extract ZIP dan jalankan `Stomake.exe`.

Bisa juga build lokal di Windows dengan Python 3.12: klik dua kali `Build_Stomake_PC.bat`. Hasilnya ada di `dist\Stomake.exe`.

## Ollama lokal

Pasang Ollama dari https://ollama.com/download, buka Ollama, lalu unduh model contoh:

```powershell
ollama pull qwen2.5:3b
```

Stomake PC menghubungi `http://localhost:11434`. Di panel AI, pilih model yang sudah diunduh atau tekan tombol refresh untuk membaca daftar model Ollama. AI lokal membutuhkan Ollama berjalan; fitur menulis, Bible, backup, dan import/export tetap dapat digunakan tanpa Ollama.

## Transfer Android ↔ PC

- Dari Android: buka menu cerita → Export Cerita, simpan atau bagikan file `.stomake`.
- Di PC: klik **Import .stomake** di bagian atas, lalu pilih file tersebut.
- Dari PC: klik **Export Cerita** → **Export Stomake (.stomake)**.
- Di Android: pilih Import Cerita dan pilih file `.stomake` dari PC.

Import membuat salinan proyek baru; proyek yang sudah ada tidak ditimpa. Jika nama sama, aplikasi menambahkan akhiran angka.

**Catatan kompatibilitas:** teks bab, sampul, sinopsis, entri Bible, role, data gambar, timeline, dan relasi dipetakan ke struktur yang didukung format Android. Format paragraf/rich-text khusus PC dapat disederhanakan menjadi teks biasa saat transfer lintas platform karena format ekspor Android saat ini berisi field `content` biasa; file database PC lokal tetap menyimpan HTML rich-text penuh.

## Data dan backup

Database disimpan di folder `data\Stomake.db` di samping EXE. Gunakan tombol **Backup / Restore Data** sebelum memindahkan PC atau menginstal ulang. File backup `.db` berisi seluruh data lokal, termasuk gambar yang telah disimpan sebagai data internal.

Jangan mengunggah database atau file backup yang berisi naskah pribadi ke repository publik.

Lihat `FEATURES.md` untuk checklist fitur Android yang dipetakan ke PC dan catatan kompatibilitas lintas platform.


## Stomake PC 1.0.1 — perbaikan import PySide6

Perbaikan error `cannot import name QGraphicsView from PySide6.QtGui`: kelas `QGraphicsView`, `QGraphicsScene`, dan `QGraphicsRectItem` sekarang diimpor dari `PySide6.QtWidgets`, sedangkan `QPainter` dan `QPixmap` tetap dari `PySide6.QtGui`. GitHub Actions juga menjalankan pemeriksaan import grafis sebelum PyInstaller.


## Stomake PC 1.0.4 — Medium-inspired writing editor
- Formatting toolbar is now split across two rows so Font, Ukuran, and Paragraf/Heading controls remain visible without a horizontal scroll indicator.
- Toolbar labels use full wording; font family, size, and paragraph style dropdowns have usable widths.
- Writing column is centered and limited to a comfortable reading width, inspired by Medium.
- Paragraph / H1 / H2 / H3 applies paragraph block styles and the active style follows the caret.
- Changing font family or size updates the editor default font as well as the typing format, including the empty-editor placeholder.


## Stomake PC 1.0.5 — clean home and cohesive UI
- Beranda now hides the editor toolbar, left Bible/navigation sidebar, and right AI panel. Opening a story restores the workbench panels to their previous visibility.
- Home includes a clean Stomake heading and a small settings menu for theme and backup/restore.
- Editor header actions are shortened and import/export/cover actions are grouped in a Lainnya menu, preventing clipped labels.
- Story cards have more inner spacing and transparent title/synopsis labels; the global label style is transparent so text no longer appears on awkward rectangular blocks.
- AI heading blends with the right panel without a separate label background.


## Stomake PC 1.0.5 — home library polish
- The opening screen is now a clean library showing saved works, covers, titles, synopses, and Read/Edit actions. It hides the Bible/sidebar, editing toolbar, and AI panel while on Home.
- Opening a story restores the left and right workbench panels to their previous visibility.
- Home settings menu retains theme switching and backup/restore access.
- Story card title/synopsis text has transparent backgrounds and more breathing room beside the cover.
- The editor header has shorter controls; import, export, and cover/synopsis actions are grouped under “Lainnya” to prevent clipping.
