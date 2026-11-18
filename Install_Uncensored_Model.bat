@echo off
setlocal
 echo ================================================
 echo  Stomake - Install Qwen2.5 3B Abliterated
 echo ================================================
 echo.
where ollama >nul 2>nul
if errorlevel 1 (
  echo Ollama belum ditemukan di PATH.
  echo Install Ollama terlebih dahulu dari https://ollama.com/download
  echo Setelah selesai, tutup dan buka CMD baru lalu jalankan file ini lagi.
  pause
  exit /b 1
)
echo Model uncensored abliterated sekitar 1.9 GB akan diunduh.
echo Pastikan koneksi internet tersedia dan ruang disk cukup.
echo.
ollama pull richardyoung/qwen2.5-3b-instruct-abliterated
if errorlevel 1 (
  echo.
  echo Unduhan gagal. Pastikan Ollama berjalan dan coba lagi.
  pause
  exit /b 1
)
echo.
echo Model berhasil diunduh.
echo Jalankan Stomake PC, tekan Refresh model, lalu pilih Qwen2.5 3B Abliterated.
echo.
pause
