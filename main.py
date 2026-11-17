import sys
import sqlite3
import json
import base64
import mimetypes
import uuid
from pathlib import Path

import requests

from PySide6.QtCore import Qt, QTimer, QThread, Signal, QUrl, QSettings, QByteArray, QBuffer, QIODevice
from PySide6.QtGui import QDesktopServices, QTextCharFormat, QTextListFormat, QTextBlockFormat, QFont, QShortcut, QKeySequence, QColor, QPixmap, QTextImageFormat, QTextDocument, QPen, QPainter
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QFrame, QLabel,
    QPushButton, QTextEdit, QVBoxLayout, QHBoxLayout,
    QSplitter, QMessageBox, QInputDialog, QListWidget,
    QListWidgetItem, QComboBox, QDialog, QFormLayout,
    QLineEdit, QDialogButtonBox, QMenu, QFileDialog, QStackedWidget,
    QTabWidget, QCheckBox, QTableWidget, QTableWidgetItem, QHeaderView,
    QDateEdit, QSpinBox, QGroupBox, QScrollArea, QMenuBar, QToolButton, QGraphicsView, QGraphicsScene, QGraphicsRectItem
)

# =========================
# CONFIGURATION
# =========================

APP_NAME = "Stomake"
MODEL_NAME = "qwen2.5:3b"

BASE_DIR = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(exist_ok=True)
DB_PATH = DATA_DIR / "Stomake.db"
LEGACY_DB_PATH = DATA_DIR / "nopai.db"

# Preserve existing data by renaming the legacy database on first upgrade.
# If Stomake.db already exists, leave both files untouched.
if LEGACY_DB_PATH.exists() and not DB_PATH.exists():
    LEGACY_DB_PATH.replace(DB_PATH)


# =========================
# DATABASE
# =========================

def db():
    return sqlite3.connect(DB_PATH)


def setup_database():
    with db() as conn:
        conn.execute("""
            CREATE TABLE IF NOT EXISTS projects (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS chapters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                position INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY(project_id) REFERENCES projects(id)
            )
        """)
        # Rich-text HTML is stored separately so formatting survives reopening,
        # while the plain-text content column remains compatible with AI features.
        chapter_columns = {row[1] for row in conn.execute("PRAGMA table_info(chapters)").fetchall()}
        if "content_html" not in chapter_columns:
            conn.execute("ALTER TABLE chapters ADD COLUMN content_html TEXT NOT NULL DEFAULT ''")

        conn.execute("""
            CREATE TABLE IF NOT EXISTS characters (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                role TEXT NOT NULL DEFAULT 'Karakter Pendukung',
                details TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(project_id) REFERENCES projects(id)
            )
        """)
        # Upgrade databases created by older Stomake versions without losing data.
        character_columns = {row[1] for row in conn.execute("PRAGMA table_info(characters)").fetchall()}
        if "role" not in character_columns:
            conn.execute("ALTER TABLE characters ADD COLUMN role TEXT NOT NULL DEFAULT 'Karakter Pendukung'")

        conn.execute("""
            CREATE TABLE IF NOT EXISTS worldbuilding (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT 'Lore',
                details TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(project_id) REFERENCES projects(id)
            )
        """)
        # Keep compatibility with the first Stomake version.
        conn.execute("""
            CREATE TABLE IF NOT EXISTS manuscript (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS custom_categories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                section TEXT NOT NULL,
                name TEXT NOT NULL,
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(section, name)
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS custom_options (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL DEFAULT 0,
                field_key TEXT NOT NULL,
                value TEXT NOT NULL,
                UNIQUE(project_id, field_key, value)
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS story_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                details TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(project_id) REFERENCES projects(id)
            )
        """)
        # Extensible profile fields and connections for Story/World Bible.
        story_record_columns = {row[1] for row in conn.execute("PRAGMA table_info(story_records)").fetchall()}
        if "metadata" not in story_record_columns:
            conn.execute("ALTER TABLE story_records ADD COLUMN metadata TEXT NOT NULL DEFAULT '{}' ")
        # Cross-platform fields: Android .stomake imports retain covers and inline/library images.
        for table, columns in {
            "projects": {
                "cover_image": "TEXT NOT NULL DEFAULT ''",
                "synopsis": "TEXT NOT NULL DEFAULT ''",
            },
            "chapters": {
                "subtitle": "TEXT NOT NULL DEFAULT ''",
                "image_data": "TEXT NOT NULL DEFAULT ''",
                "gallery_data": "TEXT NOT NULL DEFAULT '[]'",
                "text_align": "TEXT NOT NULL DEFAULT 'left'",
            },
            "story_records": {
                "role": "TEXT NOT NULL DEFAULT ''",
                "image_data": "TEXT NOT NULL DEFAULT ''",
                "image_width": "REAL NOT NULL DEFAULT 1.0",
                "gallery_data": "TEXT NOT NULL DEFAULT '[]'",
            },
        }.items():
            existing_columns = {row[1] for row in conn.execute(f"PRAGMA table_info({table})").fetchall()}
            for column, definition in columns.items():
                if column not in existing_columns:
                    conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
        conn.execute("""
            CREATE TABLE IF NOT EXISTS story_record_chapters (
                record_id INTEGER NOT NULL, chapter_id INTEGER NOT NULL,
                PRIMARY KEY(record_id, chapter_id),
                FOREIGN KEY(record_id) REFERENCES story_records(id) ON DELETE CASCADE,
                FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE CASCADE
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS character_relationships (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL, source_id INTEGER NOT NULL,
                target_id INTEGER NOT NULL, relation TEXT NOT NULL DEFAULT 'Terhubung',
                notes TEXT NOT NULL DEFAULT '',
                UNIQUE(source_id, target_id, relation),
                FOREIGN KEY(project_id) REFERENCES projects(id),
                FOREIGN KEY(source_id) REFERENCES story_records(id) ON DELETE CASCADE,
                FOREIGN KEY(target_id) REFERENCES story_records(id) ON DELETE CASCADE
            )
        """)

        conn.execute("""
            CREATE TABLE IF NOT EXISTS timeline_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                event_date TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                chapter_id INTEGER,
                character_names TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(project_id) REFERENCES projects(id),
                FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE SET NULL
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS chapter_versions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                chapter_id INTEGER NOT NULL,
                title TEXT NOT NULL,
                content TEXT NOT NULL DEFAULT '',
                content_html TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY(project_id) REFERENCES projects(id),
                FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE CASCADE
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS writing_goals (
                project_id INTEGER PRIMARY KEY,
                daily_word_goal INTEGER NOT NULL DEFAULT 500,
                FOREIGN KEY(project_id) REFERENCES projects(id)
            )
        """)
        conn.execute("""
            CREATE TABLE IF NOT EXISTS writing_activity (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                activity_date TEXT NOT NULL,
                words_written INTEGER NOT NULL DEFAULT 0,
                UNIQUE(project_id, activity_date),
                FOREIGN KEY(project_id) REFERENCES projects(id)
            )
        """)
        conn.execute("""CREATE TABLE IF NOT EXISTS ai_conversations (
            id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL,
            title TEXT NOT NULL, messages TEXT NOT NULL DEFAULT '[]',
            updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        )""")

        count = conn.execute(
            "SELECT COUNT(*) FROM projects"
        ).fetchone()[0]

        if count == 0:
            old = conn.execute(
                "SELECT title, content FROM manuscript WHERE id = 1"
            ).fetchone()
            title = old[0] if old else "Cerita Pertamaku"
            content = old[1] if old else ""
            cur = conn.execute(
                "INSERT INTO projects (title) VALUES (?)", (title,)
            )
            project_id = cur.lastrowid
            conn.execute("""
                INSERT INTO chapters
                (project_id, title, content, position)
                VALUES (?, 'Bab 1', ?, 1)
            """, (project_id, content))

        # Ensure projects created by earlier versions have at least one chapter.
        projects = conn.execute("SELECT id FROM projects").fetchall()
        for (project_id,) in projects:
            chapter_count = conn.execute(
                "SELECT COUNT(*) FROM chapters WHERE project_id = ?",
                (project_id,)
            ).fetchone()[0]
            if chapter_count == 0:
                conn.execute("""
                    INSERT INTO chapters
                    (project_id, title, content, position)
                    VALUES (?, 'Bab 1', '', 1)
                """, (project_id,))


def get_projects():
    with db() as conn:
        return conn.execute(
            "SELECT id, title FROM projects ORDER BY id"
        ).fetchall()


def create_project(title):
    with db() as conn:
        cur = conn.execute(
            "INSERT INTO projects (title) VALUES (?)", (title,)
        )
        project_id = cur.lastrowid
        conn.execute("""
            INSERT INTO chapters
            (project_id, title, content, position)
            VALUES (?, 'Bab 1', '', 1)
        """, (project_id,))
        return project_id


def get_chapters(project_id):
    with db() as conn:
        return conn.execute("""
            SELECT id, title, content, position
            FROM chapters
            WHERE project_id = ?
            ORDER BY position, id
        """, (project_id,)).fetchall()


def create_chapter(project_id, title):
    with db() as conn:
        position = conn.execute("""
            SELECT COALESCE(MAX(position), 0) + 1
            FROM chapters WHERE project_id = ?
        """, (project_id,)).fetchone()[0]
        cur = conn.execute("""
            INSERT INTO chapters
            (project_id, title, content, position)
            VALUES (?, ?, '', ?)
        """, (project_id, title, position))
        return cur.lastrowid


def save_chapter(chapter_id, title, content, content_html=None):
    with db() as conn:
        if content_html is None:
            conn.execute("""
                UPDATE chapters SET title = ?, content = ?
                WHERE id = ?
            """, (title, content, chapter_id))
        else:
            conn.execute("""
                UPDATE chapters SET title = ?, content = ?, content_html = ?
                WHERE id = ?
            """, (title, content, content_html, chapter_id))


# =========================
# AI WORKER
# =========================

class AIWorker(QThread):
    success = Signal(str)
    failure = Signal(str)

    def __init__(self, prompt, context, model=MODEL_NAME):
        super().__init__()
        self.prompt = prompt
        self.context = context
        self.model = model

    def run(self):
        try:
            response = requests.post(
                "http://localhost:11434/api/chat",
                json={
                    "model": self.model,
                    "stream": False,
                    "messages": [
                        {
                            "role": "system",
                            "content": (
                                "Kamu adalah asisten penulis kreatif Stomake. Bantu pengguna "
                                "mengembangkan cerita fiksi, karakter, dialog, konflik, organisasi, "
                                "worldbuilding, plot, dan prosa. Perlakukan permintaan pengguna dalam "
                                "konteks penulisan cerita kecuali pengguna dengan jelas meminta tindakan "
                                "nyata. Tema fiksi seperti rezim, perlawanan, konflik politik, perang, "
                                "organisasi, atau perjuangan karakter boleh dibahas sebagai elemen cerita. "
                                "Gunakan konteks proyek untuk menjaga konsistensi. Jangan mengarang fakta "
                                "yang bertentangan dengan konteks; jika informasi belum ada, tawarkan ide "
                                "sebagai kemungkinan atau usulan. Jawab dengan bahasa yang digunakan pengguna.\n\n"
                                "KONTEKS PROYEK:\n" + self.context
                            )
                        },
                        {"role": "user", "content": self.prompt}
                    ],
                    "options": {"num_ctx": 4096}
                },
                timeout=300
            )
            response.raise_for_status()
            answer = response.json().get("message", {}).get("content", "")
            self.success.emit(answer or "AI tidak memberikan jawaban.")
        except requests.exceptions.ConnectionError:
            self.failure.emit(
                f"Tidak dapat terhubung ke Ollama. Pastikan Ollama berjalan "
                f"dan model '{self.model}' sudah diunduh. Jalankan: ollama pull {self.model}"
            )
        except requests.exceptions.Timeout:
            self.failure.emit(
                "AI terlalu lama merespons. Coba prompt yang lebih pendek."
            )
        except Exception as error:
            self.failure.emit(str(error))


# =========================
# RECORD EDITOR DIALOG
# =========================

# =========================
# IMAGE CROPPER
# =========================

class CropView(QGraphicsView):
    def __init__(self, pixmap, parent=None):
        self.scene_ref = QGraphicsScene()
        super().__init__(self.scene_ref, parent)
        self.pixmap_item = self.scene_ref.addPixmap(pixmap)
        self.crop_item = None
        self.origin = None
        self.setDragMode(QGraphicsView.DragMode.NoDrag)
        self.setRenderHint(QPainter.RenderHint.Antialiasing)
        self.setSceneRect(self.pixmap_item.boundingRect())
        self.setMinimumSize(520, 360)
        self._fit_pending = True
        self.setTransformationAnchor(QGraphicsView.ViewportAnchor.AnchorUnderMouse)
        self.setResizeAnchor(QGraphicsView.ViewportAnchor.AnchorViewCenter)

    def showEvent(self, event):
        super().showEvent(event)
        if self._fit_pending:
            self.fitInView(self.pixmap_item.boundingRect(), Qt.AspectRatioMode.KeepAspectRatio)
            self._fit_pending = False

    def resizeEvent(self, event):
        super().resizeEvent(event)
        if self._fit_pending and self.width() > 0 and self.height() > 0:
            self.fitInView(self.pixmap_item.boundingRect(), Qt.AspectRatioMode.KeepAspectRatio)
            self._fit_pending = False

    def mousePressEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.origin = self.mapToScene(event.position().toPoint())
            if self.crop_item:
                self.scene_ref.removeItem(self.crop_item)
            self.crop_item = self.scene_ref.addRect(0, 0, 1, 1, QPen(Qt.GlobalColor.cyan, 2, Qt.PenStyle.DashLine))
            event.accept(); return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event):
        if self.origin is not None and self.crop_item is not None:
            current = self.mapToScene(event.position().toPoint())
            bounds = self.pixmap_item.boundingRect()
            from PySide6.QtCore import QRectF
            rect = QRectF(self.origin, current).normalized().intersected(bounds)
            self.crop_item.setRect(rect)
            event.accept(); return
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event):
        if event.button() == Qt.MouseButton.LeftButton:
            self.origin = None
            event.accept(); return
        super().mouseReleaseEvent(event)

    def crop_rect(self):
        if self.crop_item is None:
            return None
        rect = self.crop_item.rect().normalized().intersected(self.pixmap_item.boundingRect())
        return rect if rect.width() > 8 and rect.height() > 8 else None


def crop_image_file(parent, title="Edit Gambar"):
    path, _ = QFileDialog.getOpenFileName(parent, title, "", "Gambar (*.png *.jpg *.jpeg *.webp *.bmp)")
    if not path:
        return None
    pixmap = QPixmap(path)
    if pixmap.isNull():
        QMessageBox.warning(parent, "Gambar", "File gambar tidak dapat dibuka.")
        return None
    dialog = QDialog(parent)
    dialog.setWindowTitle(title + " — Crop")
    dialog.resize(760, 620)
    layout = QVBoxLayout(dialog)
    layout.addWidget(QLabel("Tarik mouse pada gambar untuk memilih area crop. Tanpa seleksi, gambar asli akan digunakan."))
    view = CropView(pixmap)
    layout.addWidget(view, 1)
    preview = QLabel("Preview crop akan muncul setelah area dipilih.")
    preview.setAlignment(Qt.AlignmentFlag.AlignCenter)
    preview.setMinimumHeight(110)
    layout.addWidget(preview)
    def show_preview():
        rect = view.crop_rect()
        if rect is None:
            preview.setText("Belum ada area crop yang dipilih."); preview.setPixmap(QPixmap()); return
        crop = pixmap.copy(int(rect.x()), int(rect.y()), int(rect.width()), int(rect.height()))
        preview.setPixmap(crop.scaled(320, 100, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
    preview_button = QPushButton("Preview Crop")
    preview_button.clicked.connect(show_preview)
    layout.addWidget(preview_button)
    buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel | QDialogButtonBox.StandardButton.Save)
    buttons.button(QDialogButtonBox.StandardButton.Save).setText("Pakai Gambar")
    buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject)
    layout.addWidget(buttons)
    if dialog.exec() != QDialog.DialogCode.Accepted:
        return None
    rect = view.crop_rect()
    result = pixmap.copy(int(rect.x()), int(rect.y()), int(rect.width()), int(rect.height())) if rect is not None else pixmap
    buffer = QBuffer(); buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    result.save(buffer, "PNG")
    return base64.b64encode(bytes(buffer.data())).decode("ascii")


# =========================
# MAIN APPLICATION
# =========================

class Stomake(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Stomake PC 1.0.6 — Ruang Cerita Kreatif")
        self.resize(1450, 880)
        self.setMinimumSize(1000, 650)

        self.project_id = None
        self.project_title = ""
        self.chapter_id = None
        self.chapter_title = ""
        self.last_answer = ""
        self.worker = None
        self.loading = False
        self.active_records_kind = None
        self.active_record_id = None
        self.reference_record_kind = None
        self.reference_record_id = None
        self.record_windows = {}
        self.ai_messages = []
        self.ai_conversation_id = None

        setup_database()
        self.build_ui()
        self.apply_theme()
        self.load_projects()
        self.show_home()

        self.autosave_timer = QTimer(self)
        self.autosave_timer.timeout.connect(self.save_current_chapter)
        self.autosave_timer.start(1500)

    # -------------------------
    # UI
    # -------------------------

    def build_ui(self):
        root = QWidget()
        root_layout = QHBoxLayout(root)
        root_layout.setContentsMargins(0, 0, 0, 0)
        root_layout.setSpacing(0)

        # Left navigation / project tree
        sidebar = QFrame()
        self.sidebar = sidebar
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(250)
        side = QVBoxLayout(sidebar)
        side.setContentsMargins(16, 20, 16, 16)
        side.setSpacing(9)

        logo = QLabel("✦  Stomake")
        logo.setObjectName("logo")
        side.addWidget(logo)
        tagline = QLabel("Ruang Cerita Kreatif")
        tagline.setObjectName("muted")
        side.addWidget(tagline)
        theme_row = QHBoxLayout()
        theme_row.addWidget(QLabel("Tema"))
        self.theme_combo = QComboBox()
        self.theme_combo.addItems(["Dark", "Light", "Warm Light", "Slate"])
        self.theme_combo.currentTextChanged.connect(self.set_theme)
        theme_row.addWidget(self.theme_combo, 1)
        side.addLayout(theme_row)
        backup_button = QPushButton("Backup / Restore Data")
        backup_button.clicked.connect(self.backup_restore_menu)
        side.addWidget(backup_button)
        side.addSpacing(12)

        side.addSpacing(12)
        side.addWidget(QLabel("CERITA SAYA"))
        project_row = QHBoxLayout()
        self.project_combo = QComboBox()
        self.project_combo.currentIndexChanged.connect(self.on_project_changed)
        self.project_combo.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.project_combo.customContextMenuRequested.connect(self.show_project_menu)
        project_row.addWidget(self.project_combo, 1)
        add_project = QPushButton("＋")
        add_project.setFixedWidth(34)
        add_project.clicked.connect(self.new_project)
        project_row.addWidget(add_project)
        side.addLayout(project_row)

        side.addSpacing(8)
        side.addWidget(QLabel("MANUSKRIP"))
        self.chapter_list = QListWidget()
        self.chapter_list.currentItemChanged.connect(self.on_chapter_changed)
        self.chapter_list.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.chapter_list.customContextMenuRequested.connect(self.show_chapter_menu)
        self.chapter_list.setMaximumHeight(145)
        side.addWidget(self.chapter_list)
        add_chapter = QPushButton("＋ Tambah Bab")
        add_chapter.clicked.connect(self.new_chapter)
        side.addWidget(add_chapter)

        # Bible dibuat sebagai satu area scroll terpisah. Judul aplikasi,
        # pemilih "Cerita Saya", dan daftar "Manuskrip" tetap di tempatnya.
        self.nav_buttons = {}
        self.nav_section_layouts = {}
        self.nav_section_widgets = {}
        bible_scroll = QScrollArea()
        bible_scroll.setObjectName("bibleScroll")
        bible_scroll.setWidgetResizable(True)
        bible_scroll.setFrameShape(QFrame.Shape.NoFrame)
        bible_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        bible_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        bible_content = QWidget()
        bible_layout = QVBoxLayout(bible_content)
        bible_layout.setContentsMargins(0, 4, 2, 4)
        bible_layout.setSpacing(5)
        for section_key, section_title in (("story", "STORY BIBLE"), ("world", "WORLD BIBLE")):
            section = QLabel(section_title)
            section.setObjectName("sectionLabel")
            bible_layout.addWidget(section)
            self.nav_section_widgets[section_key] = section
            self.nav_section_layouts[section_key] = bible_layout
            if section_key == "world":
                self.add_nav_entry("characters", "♙  Karakter", section_key)
            add_category = QPushButton("＋ Tambah Kategori")
            add_category.setObjectName("navButton")
            add_category.clicked.connect(lambda checked=False, sec=section_key: self.add_custom_category(sec))
            bible_layout.addWidget(add_category)
            self.nav_section_widgets[section_key + "_add"] = add_category
        self.load_custom_category_navigation(bible_layout)
        bible_layout.addSpacing(8)
        tools_label = QLabel("ALAT PENULIS")
        tools_label.setObjectName("sectionLabel")
        bible_layout.addWidget(tools_label)
        self.add_nav_entry("connected_db", "◈  Koneksi Cerita", "world")
        self.add_nav_entry("timeline", "◷  Timeline", "world")
        self.add_nav_entry("statistics", "▥  Statistik", "story")
        self.add_nav_entry("versions", "↶  Riwayat Versi", "story")
        bible_layout.addStretch(1)
        bible_scroll.setWidget(bible_content)
        side.addWidget(bible_scroll, 1)
        self.bible_scroll = bible_scroll
        self.save_label = QLabel("● Siap")
        self.save_label.setObjectName("muted")
        side.addWidget(self.save_label)

        # Center editor
        center = QFrame()
        center_layout = QVBoxLayout(center)
        center_layout.setContentsMargins(18, 16, 18, 14)
        center_layout.setSpacing(12)
        self.editor_header = QWidget()
        header = QHBoxLayout(self.editor_header)
        header.setContentsMargins(0, 0, 0, 0)
        header.setSpacing(8)
        self.sidebar_toggle_button = QPushButton("☰")
        self.sidebar_toggle_button.setObjectName("iconButton")
        self.sidebar_toggle_button.setFixedSize(36, 36)
        self.sidebar_toggle_button.setToolTip("Tampilkan / sembunyikan panel Story Bible dan navigasi")
        self.sidebar_toggle_button.clicked.connect(self.toggle_sidebar)
        header.addWidget(self.sidebar_toggle_button)
        self.chapter_heading = QLabel("Editor Cerita")
        self.chapter_heading.setObjectName("heading")
        header.addWidget(self.chapter_heading)
        self.home_button = QPushButton("⌂ Beranda")
        self.home_button.setToolTip("Kembali ke daftar karya")
        self.home_button.clicked.connect(self.show_home)
        header.addWidget(self.home_button)
        header.addStretch(1)
        self.focus_button = QPushButton("◉ Fokus")
        self.focus_button.setToolTip("Aktif/nonaktifkan Focus Writing (Ctrl+Shift+F)")
        self.focus_button.clicked.connect(self.toggle_focus_mode)
        header.addWidget(self.focus_button)
        self.ai_toggle_button = QPushButton("AI")
        self.ai_toggle_button.setToolTip("Tampilkan atau sembunyikan panel AI (Ctrl+Shift+A)")
        self.ai_toggle_button.clicked.connect(self.toggle_ai_panel)
        header.addWidget(self.ai_toggle_button)
        more_button = QToolButton()
        more_button.setText("Lainnya  ▾")
        more_button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        more_menu = QMenu(more_button)
        more_menu.addAction("Import .stomake", self.import_stomake)
        more_menu.addAction("Export Cerita", self.show_export_menu)
        more_menu.addSeparator()
        more_menu.addAction("Sampul / Sinopsis", self.edit_project_details)
        more_button.setMenu(more_menu)
        header.addWidget(more_button)
        center_layout.addWidget(self.editor_header)

        # Area tengah memakai halaman bertumpuk: cerita atau detail karakter/latar.
        self.center_stack = QStackedWidget()
        center_layout.addWidget(self.center_stack, 1)

        # Beranda menampilkan semua karya seperti halaman utama Android.
        self.home_page = QWidget()
        home_layout = QVBoxLayout(self.home_page)
        home_layout.setContentsMargins(26, 20, 26, 22)
        home_layout.setSpacing(14)
        home_topbar = QHBoxLayout()
        home_brand = QLabel("✦  Stomake")
        home_brand.setObjectName("logo")
        home_topbar.addWidget(home_brand)
        home_topbar.addStretch(1)
        home_settings_button = QToolButton()
        home_settings_button.setText("⚙")
        home_settings_button.setToolTip("Tema dan pengaturan data")
        home_settings_button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        home_settings_menu = QMenu(home_settings_button)
        theme_menu = home_settings_menu.addMenu("Tema aplikasi")
        for theme_name in ("Dark", "Light", "Warm Light", "Slate"):
            theme_action = theme_menu.addAction(theme_name)
            theme_action.triggered.connect(lambda checked=False, value=theme_name: self.set_theme(value))
        home_settings_menu.addSeparator()
        home_settings_menu.addAction("Backup / Restore Data", self.backup_restore_menu)
        home_settings_button.setMenu(home_settings_menu)
        home_topbar.addWidget(home_settings_button)
        home_layout.addLayout(home_topbar)
        home_heading = QLabel("Cerita Saya")
        home_heading.setObjectName("heading")
        home_heading.setStyleSheet("font-size: 27px; font-weight: 600;")
        home_layout.addWidget(home_heading)
        self.home_count_label = QLabel("Karya tersimpan")
        self.home_count_label.setObjectName("muted")
        home_layout.addWidget(self.home_count_label)
        home_actions = QHBoxLayout()
        create_story_button = QPushButton("＋ Cerita Baru")
        create_story_button.setObjectName("primary")
        create_story_button.clicked.connect(self.new_project)
        home_actions.addWidget(create_story_button)
        import_story_button = QPushButton("⇧ Import Cerita")
        import_story_button.clicked.connect(self.import_stomake)
        home_actions.addWidget(import_story_button)
        home_actions.addStretch(1)
        home_layout.addLayout(home_actions)
        self.home_scroll = QScrollArea()
        self.home_scroll.setWidgetResizable(True)
        self.home_scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.home_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.home_cards_host = QWidget()
        self.home_cards_layout = QVBoxLayout(self.home_cards_host)
        self.home_cards_layout.setContentsMargins(0, 4, 8, 8)
        self.home_cards_layout.setSpacing(14)
        self.home_cards_layout.addStretch(1)
        self.home_scroll.setWidget(self.home_cards_host)
        home_layout.addWidget(self.home_scroll, 1)
        self.center_stack.addWidget(self.home_page)

        self.chapter_page = QWidget()
        chapter_page_layout = QVBoxLayout(self.chapter_page)
        chapter_page_layout.setContentsMargins(0, 0, 0, 0)
        chapter_page_layout.setSpacing(8)

        # Medium-inspired editor toolbar: split into two clear rows so the
        # Font / Ukuran / Paragraf controls are never squeezed or clipped.
        toolbar_host = QWidget()
        toolbar_host.setObjectName("mediumToolbar")
        toolbar_host_layout = QVBoxLayout(toolbar_host)
        toolbar_host_layout.setContentsMargins(0, 0, 0, 2)
        toolbar_host_layout.setSpacing(6)

        format_row = QHBoxLayout()
        format_row.setContentsMargins(0, 0, 0, 0)
        format_row.setSpacing(5)
        self.format_buttons = {}
        for key, label, tooltip in [
            ("bold", "B", "Tebal (Ctrl+B)"),
            ("italic", "I", "Miring (Ctrl+I)"),
            ("underline", "U", "Garis bawah (Ctrl+U)"),
            ("strike", "S̶", "Coret"),
            ("bullet", "•", "Daftar bullet"),
            ("number", "1.", "Daftar bernomor"),
        ]:
            button = QPushButton(label)
            button.setToolTip(tooltip)
            button.setCheckable(True)
            button.setFixedSize(34, 32)
            button.setProperty("formatKey", key)
            button.clicked.connect(lambda checked=False, k=key: self.apply_format(k))
            self.format_buttons[key] = button
            format_row.addWidget(button)

        format_row.addSpacing(8)
        self.alignment_buttons = {}
        for key, label, tooltip in [
            ("left", "☰", "Rata kiri"),
            ("center", "≡", "Rata tengah"),
            ("right", "☷", "Rata kanan"),
            ("justify", "▤", "Rata kiri-kanan"),
        ]:
            button = QPushButton(label)
            button.setToolTip(tooltip)
            button.setCheckable(True)
            button.setFixedSize(34, 32)
            button.clicked.connect(lambda checked=False, k=key: self.apply_alignment(k))
            self.alignment_buttons[key] = button
            format_row.addWidget(button)
        format_row.addStretch(1)

        controls_row = QHBoxLayout()
        controls_row.setContentsMargins(0, 0, 0, 0)
        controls_row.setSpacing(8)

        font_label = QLabel("Font")
        font_label.setObjectName("toolbarLabel")
        controls_row.addWidget(font_label)
        self.font_family_combo = QComboBox()
        self.font_family_combo.addItems(["Garamond", "Times New Roman", "Courier New"])
        self.font_family_combo.setCurrentText("Garamond")
        self.font_family_combo.setMinimumWidth(150)
        self.font_family_combo.setMaximumWidth(190)
        self.font_family_combo.setFixedHeight(36)
        self.font_family_combo.setToolTip("Pilih jenis huruf")
        self.font_family_combo.currentTextChanged.connect(self.apply_font_family)
        controls_row.addWidget(self.font_family_combo)

        size_label = QLabel("Ukuran")
        size_label.setObjectName("toolbarLabel")
        controls_row.addWidget(size_label)
        self.font_size_combo = QComboBox()
        self.font_size_combo.setEditable(True)
        self.font_size_combo.setMinimumWidth(76)
        self.font_size_combo.setMaximumWidth(88)
        self.font_size_combo.setFixedHeight(36)
        self.font_size_combo.addItems(["8", "9", "10", "11", "12", "14", "16", "18", "20", "22", "24", "26", "28", "36", "48", "72"])
        self.font_size_combo.setCurrentText("12")
        self.font_size_combo.setToolTip("Ukuran font (pt). Pilih atau ketik ukuran lalu tekan Enter")
        self.font_size_combo.activated.connect(self.apply_font_size)
        self.font_size_combo.lineEdit().returnPressed.connect(self.apply_font_size)
        controls_row.addWidget(self.font_size_combo)

        paragraph_label = QLabel("Gaya")
        paragraph_label.setObjectName("toolbarLabel")
        controls_row.addWidget(paragraph_label)
        self.heading_combo = QComboBox()
        self.heading_combo.addItems(["Paragraf", "H1", "H2", "H3"])
        self.heading_combo.setMinimumWidth(126)
        self.heading_combo.setMaximumWidth(150)
        self.heading_combo.setFixedHeight(36)
        self.heading_combo.setToolTip("Pilih gaya paragraf seperti Medium")
        self.heading_combo.activated.connect(self.apply_heading_level)
        controls_row.addWidget(self.heading_combo)

        insert_image_button = QPushButton("＋ Gambar")
        insert_image_button.setFixedHeight(36)
        insert_image_button.setToolTip("Sisipkan gambar ke posisi kursor")
        insert_image_button.clicked.connect(self.insert_chapter_image)
        controls_row.addWidget(insert_image_button)
        reader_button = QPushButton("Baca ↗")
        reader_button.setFixedHeight(36)
        reader_button.clicked.connect(self.open_reading_mode)
        controls_row.addWidget(reader_button)
        controls_row.addStretch(1)

        toolbar_host_layout.addLayout(format_row)
        toolbar_host_layout.addLayout(controls_row)
        chapter_page_layout.addWidget(toolbar_host)

        self.editor = QTextEdit()
        self.editor.setAcceptRichText(True)
        self.editor.setAlignment(Qt.AlignmentFlag.AlignLeft)
        self.editor.setObjectName("mediumEditor")
        initial_editor_font = QFont(self.font_family_combo.currentText() or "Garamond", 12)
        self.editor.setFont(initial_editor_font)
        self.editor.document().setDefaultFont(initial_editor_font)
        self.editor.document().setDocumentMargin(0)
        self.editor.setPlaceholderText("Mulai tulis ceritamu di sini...")
        self.editor.setAlignment(Qt.AlignmentFlag.AlignLeft)
        initial_cursor = self.editor.textCursor()
        initial_block = QTextBlockFormat()
        initial_block.setAlignment(Qt.AlignmentFlag.AlignLeft)
        initial_cursor.setBlockFormat(initial_block)
        self.editor.setTextCursor(initial_cursor)
        initial_char_format = QTextCharFormat()
        initial_char_format.setFont(initial_editor_font)
        self.editor.setCurrentCharFormat(initial_char_format)
        self.editor.setLineWrapMode(QTextEdit.LineWrapMode.WidgetWidth)
        self.editor.setMinimumWidth(460)
        self.editor.setMaximumWidth(860)

        editor_canvas = QWidget()
        editor_canvas.setObjectName("editorCanvas")
        editor_canvas_layout = QHBoxLayout(editor_canvas)
        editor_canvas_layout.setContentsMargins(12, 0, 12, 0)
        editor_canvas_layout.setSpacing(0)
        editor_canvas_layout.addStretch(1)
        editor_canvas_layout.addWidget(self.editor, 0)
        editor_canvas_layout.addStretch(1)

        self.editor.textChanged.connect(self.update_word_count)
        self.editor.cursorPositionChanged.connect(self.update_format_indicators)
        self.editor.selectionChanged.connect(self.update_format_indicators)
        self.editor.setTabChangesFocus(False)

        # Panel referensi ini muncul saat halaman karakter/latar diminimize.
        # Editor cerita tetap aktif sehingga pengguna bisa menulis sambil membaca referensi.
        self.editor_splitter = QSplitter(Qt.Orientation.Horizontal)
        self.editor_splitter.setChildrenCollapsible(False)
        self.editor_splitter.addWidget(editor_canvas)

        self.reference_panel = QFrame()
        self.reference_panel.setObjectName("panel")
        self.reference_panel.setMinimumWidth(190)
        reference_layout = QVBoxLayout(self.reference_panel)
        reference_layout.setContentsMargins(10, 10, 10, 10)
        reference_layout.setSpacing(7)
        reference_header = QHBoxLayout()
        self.reference_heading = QLabel("Referensi")
        self.reference_heading.setObjectName("heading")
        reference_header.addWidget(self.reference_heading, 1)
        self.reference_close_button = QPushButton("×")
        self.reference_close_button.setFixedSize(28, 28)
        self.reference_close_button.setToolTip("Tutup panel referensi")
        self.reference_close_button.clicked.connect(self.hide_reference_panel)
        reference_header.addWidget(self.reference_close_button)
        reference_layout.addLayout(reference_header)
        self.reference_details = QTextEdit()
        self.reference_details.setReadOnly(True)
        self.reference_details.setAcceptRichText(False)
        self.reference_details.setPlaceholderText("Minimize halaman karakter atau latar untuk menampilkan referensi di sini.")
        reference_layout.addWidget(self.reference_details, 1)
        self.reference_open_button = QPushButton("Buka halaman untuk edit")
        self.reference_open_button.clicked.connect(self.restore_reference_page)
        reference_layout.addWidget(self.reference_open_button)
        self.reference_panel.hide()
        self.editor_splitter.addWidget(self.reference_panel)
        self.editor_splitter.setSizes([700, 0])
        chapter_page_layout.addWidget(self.editor_splitter, 1)

        # Familiar Word shortcuts for paragraph alignment.
        QShortcut(QKeySequence("Ctrl+L"), self.editor, activated=lambda: self.apply_alignment("left"))
        QShortcut(QKeySequence("Ctrl+E"), self.editor, activated=lambda: self.apply_alignment("center"))
        QShortcut(QKeySequence("Ctrl+R"), self.editor, activated=lambda: self.apply_alignment("right"))
        QShortcut(QKeySequence("Ctrl+J"), self.editor, activated=lambda: self.apply_alignment("justify"))
        # Rich-text shortcuts must be attached to the editor and work while it has focus.
        for sequence, key in (("Ctrl+B", "bold"), ("Ctrl+I", "italic"), ("Ctrl+U", "underline"), ("Ctrl+Shift+X", "strike")):
            shortcut = QShortcut(QKeySequence(sequence), self.editor)
            shortcut.setContext(Qt.ShortcutContext.WidgetWithChildrenShortcut)
            shortcut.activated.connect(lambda k=key: self.toggle_format_shortcut(k))
        footer = QHBoxLayout()
        self.word_label = QLabel("0 kata")
        self.word_label.setObjectName("muted")
        footer.addWidget(self.word_label)
        footer.addStretch()
        footer.addWidget(QLabel("Disimpan lokal • Markdown"))
        chapter_page_layout.addLayout(footer)
        self.center_stack.addWidget(self.chapter_page)

        # Halaman karakter/latar di area tengah, bukan dialog pop-up.
        self.record_page = QWidget()
        record_layout = QVBoxLayout(self.record_page)
        record_layout.setContentsMargins(8, 8, 8, 8)
        record_layout.setSpacing(10)
        self.record_page_heading = QLabel("Detail Karakter / Latar")
        self.record_page_heading.setObjectName("heading")
        record_layout.addWidget(self.record_page_heading)
        record_layout.addWidget(QLabel("Nama"))
        self.record_name_input = QLineEdit()
        self.record_name_input.setPlaceholderText("Masukkan nama karakter atau latar")
        record_layout.addWidget(self.record_name_input)
        self.record_role_label = QLabel("Role Karakter")
        record_layout.addWidget(self.record_role_label)
        self.record_role_input = QComboBox()
        self.record_role_input.setEditable(True)
        self.record_role_input.addItems([
            "Protagonis", "Antagonis", "Deuteragonis", "Tritagonis",
            "Karakter Pendukung", "Mentor", "Love Interest", "Sidekick",
            "Figuran", "Lainnya"
        ])
        self.record_role_input.setToolTip("Pilih role karakter atau ketik role buatan sendiri")
        record_layout.addWidget(self.record_role_input)
        self.record_category_label = QLabel("Kategori")
        record_layout.addWidget(self.record_category_label)
        self.record_category_input = QComboBox()
        self.record_category_input.setEditable(True)
        self.record_category_input.addItems(["Lore", "Lokasi", "Faksi", "Sistem Kekuatan", "Benda", "Sejarah", "Lainnya"])
        record_layout.addWidget(self.record_category_input)
        record_layout.addWidget(QLabel("Detail / Deskripsi"))
        self.record_details_input = QTextEdit()
        self.record_details_input.setPlaceholderText("Tulis detail karakter atau latar di sini...")
        record_layout.addWidget(self.record_details_input, 1)
        record_buttons = QHBoxLayout()
        record_buttons.addStretch()
        self.record_back_button = QPushButton("Kembali ke Cerita")
        self.record_back_button.clicked.connect(self.minimize_record_page)
        record_buttons.addWidget(self.record_back_button)
        self.record_save_button = QPushButton("Simpan")
        self.record_save_button.setObjectName("primary")
        self.record_save_button.clicked.connect(self.save_record_form)
        record_buttons.addWidget(self.record_save_button)
        record_layout.addLayout(record_buttons)
        self.center_stack.addWidget(self.record_page)

        # Data-driven pages for the story bible. Each section has persistent CRUD.
        self.data_pages = {}
        self.data_lists = {}
        self.data_searches = {}
        self.data_titles = {
            "characters": ("Karakter", "Karakter", "Profil karakter, peran, dan trait.")
        }
        with db() as conn:
            custom_rows = conn.execute("SELECT id, section, name FROM custom_categories ORDER BY id").fetchall()
        for category_id, section, name in custom_rows:
            key = f"custom_{category_id}"
            self.data_titles[key] = (name, name, f"Kategori cerita buatanmu: {name}.")
        for key, (title, category, description) in self.data_titles.items():
            self.create_data_page(key, title, category, description)

        # AI panel
        ai_panel = QFrame()
        self.ai_panel = ai_panel
        ai_panel.setObjectName("panel")
        ai_panel.setMinimumWidth(280)
        ai_layout = QVBoxLayout(ai_panel)
        ai_layout.setContentsMargins(12, 12, 12, 12)
        ai_layout.setSpacing(8)
        ai_header = QHBoxLayout()
        ai_title = QLabel("✦ AI")
        ai_title.setObjectName("heading")
        ai_header.addWidget(ai_title, 1)
        self.ai_model_combo = QComboBox()
        self.ai_model_combo.setMinimumWidth(150)
        self.ai_model_combo.addItems(["llama3.2:3b", "qwen2.5:3b"])
        stored_model = QSettings("GendingLokananta", "StomakePC").value("ollama_model", MODEL_NAME)
        self.ai_model_combo.setCurrentText(stored_model)
        self.ai_model_combo.currentTextChanged.connect(lambda value: QSettings("GendingLokananta", "StomakePC").setValue("ollama_model", value))
        ai_header.addWidget(self.ai_model_combo)
        refresh_models = QPushButton("↻")
        refresh_models.setToolTip("Muat model yang tersedia dari Ollama")
        refresh_models.setFixedWidth(30)
        refresh_models.clicked.connect(self.refresh_ollama_models)
        ai_header.addWidget(refresh_models)
        ai_hide_button = QPushButton("×")
        ai_hide_button.setObjectName("iconButton")
        ai_hide_button.setFixedSize(28, 28)
        ai_hide_button.setToolTip("Sembunyikan AI Assistant")
        ai_hide_button.clicked.connect(self.toggle_ai_panel)
        ai_header.addWidget(ai_hide_button)
        ai_layout.addLayout(ai_header)
        self.chat = QTextEdit()
        self.chat.setReadOnly(True)
        self.chat.setPlaceholderText("Hai, aku asistenmu. Aku bisa membantu melanjutkan cerita, memberi saran, dan memeriksa konsistensi.")
        ai_layout.addWidget(self.chat, 1)
        ai_actions = QHBoxLayout()
        clear_chat_button = QPushButton("Chat Baru")
        clear_chat_button.clicked.connect(self.clear_chat)
        history_chat_button = QPushButton("Riwayat")
        history_chat_button.clicked.connect(self.show_ai_history)
        ai_actions.addWidget(clear_chat_button)
        ai_actions.addWidget(history_chat_button)
        ai_layout.addLayout(ai_actions)
        for label, prompt in [
            ("Lanjutkan adegan ini", "Lanjutkan adegan dari bagian akhir bab aktif. Jaga gaya dan konsistensi cerita."),
            ("Ubah gaya tulisan", "Berikan versi revisi dari bagian akhir naskah dengan gaya yang lebih kuat, tanpa mengubah fakta cerita."),
            ("Periksa konsistensi", "Periksa kemungkinan inkonsistensi berdasarkan konteks cerita yang tersedia."),
            ("Tanya tentang cerita", "Ringkas informasi penting yang relevan dari konteks cerita.")
        ]:
            quick = QPushButton(label)
            quick.clicked.connect(lambda checked=False, p=prompt: self.run_quick_prompt(p))
            ai_layout.addWidget(quick)
        self.prompt = QTextEdit()
        self.prompt.setPlaceholderText("Tanya asisten tentang ceritamu...")
        self.prompt.setMaximumHeight(105)
        ai_layout.addWidget(self.prompt)
        self.send_button = QPushButton("Kirim ke AI ➤")
        self.send_button.setObjectName("primary")
        self.send_button.clicked.connect(self.ask_ai)
        ai_layout.addWidget(self.send_button)
        insert_button = QPushButton("Masukkan jawaban ke naskah")
        insert_button.clicked.connect(self.insert_answer)
        ai_layout.addWidget(insert_button)

        splitter = QSplitter(Qt.Orientation.Horizontal)
        self.main_splitter = splitter
        splitter.setObjectName("mainSplitter")
        splitter.setChildrenCollapsible(False)
        splitter.addWidget(center)
        splitter.addWidget(ai_panel)
        splitter.setSizes([900, 360])

        root_layout.addWidget(sidebar)
        root_layout.addWidget(splitter, 1)
        self.setCentralWidget(root)
        self._last_splitter_sizes = [900, 360]
        self._home_mode = False
        self._home_sidebar_was_visible = True
        self._home_ai_was_visible = True
        self._focus_mode = False
        self._focus_ai_was_visible = True
        QShortcut(QKeySequence("Ctrl+Shift+A"), self, activated=self.toggle_ai_panel)
        QShortcut(QKeySequence("Ctrl+Shift+F"), self, activated=self.toggle_focus_mode)

    def show_home(self):
        """Tampilkan beranda bersih seperti library Android; editor panels disembunyikan."""
        if self.chapter_id is not None:
            self.save_current_chapter()
        if not self._home_mode:
            # isVisible() is false before the main window is first shown; use
            # isHidden() to preserve each widget's explicit visibility state.
            self._home_sidebar_was_visible = not self.sidebar.isHidden()
            self._home_ai_was_visible = not self.ai_panel.isHidden()
        self._home_mode = True
        self.sidebar.hide()
        self.ai_panel.hide()
        self.editor_header.hide()
        self.refresh_home_cards()
        self.center_stack.setCurrentWidget(self.home_page)
        self.chapter_heading.setText("Beranda")

    def refresh_home_cards(self):
        """Bangun ulang kartu karya dari database lokal."""
        while self.home_cards_layout.count():
            item = self.home_cards_layout.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()
        with db() as conn:
            projects = conn.execute(
                "SELECT id,title,cover_image,synopsis FROM projects ORDER BY id DESC"
            ).fetchall()
        self.home_count_label.setText(f"{len(projects)} cerita tersimpan")
        for project_id, title, cover_data, synopsis in projects:
            card = QFrame()
            card.setObjectName("storyCard")
            card.setMinimumHeight(220)
            card_layout = QHBoxLayout(card)
            card_layout.setContentsMargins(0, 0, 18, 0)
            card_layout.setSpacing(0)
            cover = QLabel()
            cover.setFixedWidth(168)
            cover.setMinimumHeight(220)
            cover.setAlignment(Qt.AlignmentFlag.AlignCenter)
            cover.setStyleSheet("background: rgba(140,130,150,0.12); border-top-left-radius: 10px; border-bottom-left-radius: 10px;")
            pixmap = QPixmap()
            if cover_data:
                try:
                    pixmap.loadFromData(base64.b64decode(cover_data))
                except Exception:
                    pass
            if not pixmap.isNull():
                cover.setPixmap(pixmap.scaled(168, 220, Qt.AspectRatioMode.KeepAspectRatioByExpanding, Qt.TransformationMode.SmoothTransformation))
            else:
                cover.setText("▤\nSampul")
                cover.setStyleSheet(cover.styleSheet() + " font-size: 22px; color: palette(mid); ")
            card_layout.addWidget(cover)
            info = QVBoxLayout()
            info.setContentsMargins(28, 18, 10, 18)
            info.setSpacing(12)
            title_label = QLabel(title)
            title_label.setObjectName("heading")
            title_label.setStyleSheet("background: transparent; font-size: 20px; font-weight: 650; padding: 0;")
            info.addWidget(title_label)
            synopsis_label = QLabel((synopsis or "Belum ada sinopsis.").strip())
            synopsis_label.setWordWrap(True)
            synopsis_label.setObjectName("muted")
            synopsis_label.setMaximumHeight(76)
            synopsis_label.setStyleSheet("background: transparent; padding: 0; line-height: 1.45;")
            info.addWidget(synopsis_label, 1)
            buttons = QHBoxLayout()
            read_button = QPushButton("▤  Baca")
            read_button.setObjectName("primary")
            read_button.clicked.connect(lambda checked=False, pid=project_id: self.open_home_story(pid, read=True))
            edit_button = QPushButton("✎  Edit")
            edit_button.clicked.connect(lambda checked=False, pid=project_id: self.open_home_story(pid, read=False))
            more_button = QPushButton("⋮")
            more_button.setFixedWidth(38)
            more_button.setToolTip("Ubah judul, sampul/sinopsis, export, atau hapus cerita")
            more_button.clicked.connect(lambda checked=False, pid=project_id, anchor=more_button: self.home_project_menu(pid, anchor))
            buttons.addWidget(read_button)
            buttons.addWidget(edit_button)
            buttons.addWidget(more_button)
            info.addLayout(buttons)
            card_layout.addLayout(info, 1)
            self.home_cards_layout.addWidget(card)
        self.home_cards_layout.addStretch(1)
        if not projects:
            empty = QLabel("Belum ada cerita. Klik ‘Cerita Baru’ atau import cerita lama untuk mulai.")
            empty.setObjectName("muted")
            empty.setAlignment(Qt.AlignmentFlag.AlignCenter)
            empty.setMinimumHeight(180)
            self.home_cards_layout.insertWidget(0, empty)

    def open_home_story(self, project_id, read=False):
        index = self.project_combo.findData(project_id)
        if index >= 0:
            self.loading = True
            self.project_combo.setCurrentIndex(index)
            self.loading = False
        self.open_project(project_id)
        if read:
            self._reader_return_home_on_close = True
            self.open_reading_mode()

    def home_project_menu(self, project_id, anchor):
        index = self.project_combo.findData(project_id)
        if index >= 0:
            self.loading = True
            self.project_combo.setCurrentIndex(index)
            self.loading = False
        self.open_project(project_id)
        menu = QMenu(self)
        rename_action = menu.addAction("Ubah Judul")
        media_action = menu.addAction("Atur Sampul / Sinopsis")
        export_action = menu.addAction("Export Cerita")
        menu.addSeparator()
        delete_action = menu.addAction("Hapus Cerita")
        chosen = menu.exec(anchor.mapToGlobal(anchor.rect().bottomLeft()))
        if chosen == rename_action:
            self.rename_project()
        elif chosen == media_action:
            self.edit_project_details()
        elif chosen == export_action:
            self.export_stomake()
        elif chosen == delete_action:
            self.delete_project()
        self.show_home()

    def toggle_sidebar(self):
        """Minimize atau tampilkan navigasi Story Bible di sisi kiri."""
        if getattr(self, "_home_mode", False):
            return
        if not hasattr(self, "sidebar"):
            return
        self.sidebar.setVisible(not self.sidebar.isVisible())
        self.sidebar_toggle_button.setToolTip(
            "Sembunyikan panel navigasi" if self.sidebar.isVisible() else "Tampilkan panel navigasi"
        )

    def toggle_ai_panel(self):
        """Tampilkan/sembunyikan AI tanpa mengubah panel lain."""
        if getattr(self, "_home_mode", False):
            return
        if not hasattr(self, "ai_panel"):
            return
        if self.ai_panel.isVisible():
            sizes = self.main_splitter.sizes()
            if len(sizes) == 2 and sizes[1] > 0:
                self._last_splitter_sizes = sizes
            self.ai_panel.hide()
            self.ai_toggle_button.setText("Tampilkan AI")
            self.ai_toggle_button.setToolTip("Tampilkan AI Assistant (Ctrl+Shift+A)")
            visible_sizes = self.main_splitter.sizes()
            if len(visible_sizes) == 2:
                self.main_splitter.setSizes([visible_sizes[0] + visible_sizes[1], 0])
        else:
            self.ai_panel.show()
            self.ai_toggle_button.setText("Sembunyikan AI")
            self.ai_toggle_button.setToolTip("Sembunyikan AI Assistant (Ctrl+Shift+A)")
            sizes = self._last_splitter_sizes or [900, 360]
            self.main_splitter.setSizes(sizes)

    def toggle_focus_mode(self):
        """Mode menulis fokus; tombol header tetap tersedia untuk keluar."""
        if not hasattr(self, "main_splitter"):
            return
        if not self._focus_mode:
            sizes = self.main_splitter.sizes()
            if len(sizes) == 2:
                self._last_splitter_sizes = sizes
            self._focus_ai_was_visible = self.ai_panel.isVisible()
            self._focus_mode = True
            self.sidebar.hide()
            self.ai_panel.hide()
            self.focus_button.setText("Keluar Fokus")
            self.ai_toggle_button.setText("Tampilkan AI")
            self.main_splitter.setSizes([1, 0])
        else:
            self._focus_mode = False
            self.sidebar.show()
            if self._focus_ai_was_visible:
                self.ai_panel.show()
                self.ai_toggle_button.setText("Sembunyikan AI")
            else:
                self.ai_panel.hide()
                self.ai_toggle_button.setText("Tampilkan AI")
            self.main_splitter.setSizes(self._last_splitter_sizes or [900, 360])
            self.focus_button.setText("◉ Fokus")

    def add_nav_entry(self, key, label, section_key):
        button = QPushButton(label)
        button.setObjectName("navButton")
        button.setCursor(Qt.CursorShape.PointingHandCursor)
        button.setCheckable(True)
        button.setAutoExclusive(False)
        button.clicked.connect(lambda checked=False, k=key: self.open_navigation_page(k))
        self.nav_buttons[key] = button
        add_button = self.nav_section_widgets.get(section_key + "_add")
        layout = self.nav_section_layouts[section_key]
        if add_button is not None:
            layout.insertWidget(layout.indexOf(add_button), button)
        else:
            layout.addWidget(button)
        return button

    def load_custom_category_navigation(self, side):
        with db() as conn:
            rows = conn.execute("SELECT id, section, name FROM custom_categories ORDER BY id").fetchall()
        for category_id, section, name in rows:
            key = f"custom_{category_id}"
            self.add_nav_entry(key, "  " + name, section)

    def add_custom_category(self, section):
        section_label = "Story Bible" if section == "story" else "World Bible"
        # Offer useful built-in category suggestions while still allowing custom names.
        defaults = (["Karakter", "Hubungan", "Konflik", "Alur Cerita", "Tema", "Lore", "Catatan"]
                    if section == "story" else
                    ["Lokasi", "Kelompok", "Item", "Konsep", "Sistem Kekuatan", "Aturan", "Sejarah", "Budaya", "Organisasi", "Makhluk", "Lore", "Lainnya"])
        dialog = QDialog(self)
        dialog.setWindowTitle("Tambah Kategori")
        dialog.setMinimumWidth(360)
        form = QVBoxLayout(dialog)
        form.addWidget(QLabel(f"Pilih kategori bawaan atau ketik kategori baru untuk {section_label}:"))
        category_combo = QComboBox()
        category_combo.setEditable(True)
        category_combo.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        category_combo.addItems(defaults)
        category_combo.setCurrentText("")
        category_combo.setPlaceholderText("Pilih atau ketik nama kategori")
        form.addWidget(category_combo)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        form.addWidget(buttons)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        name = category_combo.currentText().strip()
        if not name:
            return
        try:
            with db() as conn:
                cur = conn.execute("INSERT INTO custom_categories (section, name) VALUES (?, ?)", (section, name))
                category_id = cur.lastrowid
        except sqlite3.IntegrityError:
            QMessageBox.information(self, "Kategori sudah ada", "Kategori dengan nama itu sudah ada di bagian ini.")
            return
        key = f"custom_{category_id}"
        self.data_titles[key] = (name, name, f"Kategori cerita buatanmu: {name}.")
        self.create_data_page(key, name, name, self.data_titles[key][2])
        self.add_nav_entry(key, "  " + name, section)
        self.open_navigation_page(key)

    def create_data_page(self, key, title, category, description):
        # Bible opens as a movable, non-modal window so the manuscript editor
        # remains visible and editable at the same time.
        page = QDialog(self, Qt.WindowType.Window)
        page.setWindowTitle(f"{title} — Stomake")
        page.resize(620, 680)
        page.setMinimumSize(480, 420)
        layout = QVBoxLayout(page)
        layout.setContentsMargins(14, 14, 14, 14)
        layout.setSpacing(12)
        page_header = QHBoxLayout()
        heading = QLabel(title)
        heading.setObjectName("heading")
        page_header.addWidget(heading)
        page_header.addStretch()
        add_btn = QPushButton("＋ Tambah " + category)
        add_btn.setObjectName("primary")
        add_btn.clicked.connect(lambda checked=False, k=key: self.create_story_record(k))
        page_header.addWidget(add_btn)
        layout.addLayout(page_header)
        desc = QLabel(description)
        desc.setObjectName("muted")
        desc.setWordWrap(True)
        layout.addWidget(desc)
        search = QLineEdit()
        search.setPlaceholderText("Cari " + title.lower() + "...")
        search.textChanged.connect(lambda text, k=key: self.refresh_story_records(k))
        layout.addWidget(search)
        record_list = QListWidget()
        record_list.itemDoubleClicked.connect(lambda item, k=key: self.edit_story_record(k, item))
        record_list.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        record_list.customContextMenuRequested.connect(lambda pos, k=key: self.story_record_menu(k, pos))
        layout.addWidget(record_list, 1)
        actions = QHBoxLayout()
        edit_btn = QPushButton("Edit")
        edit_btn.clicked.connect(lambda checked=False, k=key: self.edit_selected_story_record(k))
        delete_btn = QPushButton("Hapus")
        delete_btn.clicked.connect(lambda checked=False, k=key: self.delete_selected_story_record(k))
        actions.addWidget(edit_btn)
        actions.addWidget(delete_btn)
        actions.addStretch()
        layout.addLayout(actions)
        self.data_pages[key] = page
        self.data_lists[key] = record_list
        self.data_searches[key] = search

    # -------------------------
    # STORY BIBLE NAVIGATION / CRUD
    # -------------------------

    def open_navigation_page(self, key):
        special_pages = {
            "connected_db": self.show_connected_database,
            "timeline": self.show_timeline,
            "statistics": self.show_writing_statistics,
            "versions": self.show_version_history,
        }
        if key in special_pages:
            self.save_current_chapter()
            special_pages[key]()
        if key in self.data_pages:
            self.save_current_chapter()
            self.refresh_story_records(key)
            page = self.data_pages[key]
            page.show()
            page.raise_()
            page.activateWindow()
            self.chapter_heading.setText(self.chapter_title or "Editor Cerita")
        for name, button in self.nav_buttons.items():
            button.setChecked(name == key)

    # -------------------------
    # STORY TOOLS: CONNECTIONS, TIMELINE, STATS, VERSION HISTORY
    # -------------------------

    def _tool_dialog(self, title, width=900, height=620):
        dialog = QDialog(self)
        dialog.setWindowTitle(f"{title} — Stomake")
        dialog.resize(width, height)
        dialog.setMinimumSize(min(width, 620), min(height, 420))
        # Explicit palette prevents Qt native controls from reverting to white in dark mode.
        layout = QVBoxLayout(dialog)
        layout.setContentsMargins(16, 16, 16, 16)
        layout.setSpacing(10)
        return dialog, layout

    def show_connected_database(self):
        if self.project_id is None:
            return
        dialog, layout = self._tool_dialog("Koneksi Cerita", 900, 650)
        layout.addWidget(QLabel("Ringkasan hubungan karakter, entri Bible, dan bab yang ditautkan."))
        tabs = QTabWidget()
        relationships = QTableWidget(0, 4)
        relationships.setHorizontalHeaderLabels(["Karakter / Entri A", "Hubungan", "Karakter / Entri B", "Catatan"])
        links = QTableWidget(0, 3)
        links.setHorizontalHeaderLabels(["Entri", "Kategori", "Bab terkait"])
        mentions = QTableWidget(0, 3)
        mentions.setHorizontalHeaderLabels(["Bab", "Nama entri yang cocok", "Jenis data"])
        for table in (relationships, links, mentions):
            table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
            table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
            table.setAlternatingRowColors(True)
        with db() as conn:
            rel_rows = conn.execute("""
                SELECT a.name, r.relation, b.name, r.notes
                FROM character_relationships r
                JOIN story_records a ON a.id=r.source_id
                JOIN story_records b ON b.id=r.target_id
                WHERE r.project_id=? ORDER BY a.name, b.name
            """, (self.project_id,)).fetchall()
            link_rows = conn.execute("""
                SELECT sr.name, sr.category, GROUP_CONCAT(ch.title, ', ')
                FROM story_records sr LEFT JOIN story_record_chapters rc ON rc.record_id=sr.id
                LEFT JOIN chapters ch ON ch.id=rc.chapter_id
                WHERE sr.project_id=? GROUP BY sr.id ORDER BY sr.name
            """, (self.project_id,)).fetchall()
            chapters = conn.execute("SELECT id, title, content FROM chapters WHERE project_id=? ORDER BY position", (self.project_id,)).fetchall()
            records = conn.execute("SELECT name, kind FROM story_records WHERE project_id=?", (self.project_id,)).fetchall()
            records += conn.execute("SELECT name, 'Karakter' FROM characters WHERE project_id=?", (self.project_id,)).fetchall()
            records += conn.execute("SELECT name, category FROM worldbuilding WHERE project_id=?", (self.project_id,)).fetchall()
        def fill(table, rows):
            table.setRowCount(len(rows))
            for ri, row in enumerate(rows):
                for ci, value in enumerate(row):
                    table.setItem(ri, ci, QTableWidgetItem(str(value or "—")))
        fill(relationships, rel_rows)
        fill(links, link_rows)
        found = []
        for _cid, title, content in chapters:
            names = sorted({name for name, _kind in records if name and name.casefold() in (content or "").casefold()})
            for name in names:
                kinds = next((kind for rname, kind in records if rname == name), "Entri")
                found.append((title, name, kinds))
        fill(mentions, found)
        tabs.addTab(relationships, "Hubungan")
        tabs.addTab(links, "Tautan Bab")
        tabs.addTab(mentions, "Kemunculan dalam Naskah")
        layout.addWidget(tabs, 1)
        close_btn = QPushButton("Tutup")
        close_btn.clicked.connect(dialog.accept)
        layout.addWidget(close_btn, alignment=Qt.AlignmentFlag.AlignRight)
        dialog.exec()

    def show_timeline(self):
        if self.project_id is None:
            return
        dialog, layout = self._tool_dialog("Timeline", 900, 650)
        layout.addWidget(QLabel("Susun peristiwa berdasarkan tanggal atau urutan waktu cerita."))
        event_list = QListWidget()
        layout.addWidget(event_list, 1)
        def reload_events():
            event_list.clear()
            with db() as conn:
                rows = conn.execute("""
                    SELECT e.id, e.event_date, e.title, e.description, e.character_names, c.title
                    FROM timeline_events e LEFT JOIN chapters c ON c.id=e.chapter_id
                    WHERE e.project_id=? ORDER BY CASE WHEN e.event_date='' THEN 1 ELSE 0 END, e.event_date, e.id
                """, (self.project_id,)).fetchall()
            for event_id, event_date, title, description, characters, chapter in rows:
                label = f"{event_date or 'Tanpa tanggal'}  ·  {title}"
                if chapter: label += f"  [{chapter}]"
                if characters: label += f"  —  {characters}"
                item = QListWidgetItem(label)
                item.setData(Qt.ItemDataRole.UserRole, event_id)
                item.setToolTip(description or "Tidak ada deskripsi")
                event_list.addItem(item)
        def edit_event(existing_id=None):
            current = None
            if existing_id is not None:
                with db() as conn:
                    current = conn.execute("SELECT title,event_date,description,chapter_id,character_names FROM timeline_events WHERE id=? AND project_id=?", (existing_id,self.project_id)).fetchone()
            form_dialog = QDialog(dialog)
            form_dialog.setWindowTitle("Edit Peristiwa" if current else "Tambah Peristiwa")
            form = QFormLayout(form_dialog)
            title_input = QLineEdit(current[0] if current else "")
            date_input = QLineEdit(current[1] if current else "")
            date_input.setPlaceholderText("Contoh: Tahun 102 atau 2026-04-15")
            description_input = QTextEdit()
            description_input.setPlainText(current[2] if current else "")
            characters_input = QLineEdit(current[4] if current else "")
            chapter_combo = QComboBox(); chapter_combo.addItem("— Tidak ditautkan —", None)
            with db() as conn:
                chapter_rows = conn.execute("SELECT id,title FROM chapters WHERE project_id=? ORDER BY position", (self.project_id,)).fetchall()
            for cid, ctitle in chapter_rows: chapter_combo.addItem(ctitle, cid)
            if current and current[3] is not None:
                idx = chapter_combo.findData(current[3]); chapter_combo.setCurrentIndex(max(0,idx))
            form.addRow("Nama peristiwa", title_input); form.addRow("Tanggal / urutan", date_input)
            form.addRow("Bab terkait", chapter_combo); form.addRow("Karakter terkait", characters_input); form.addRow("Deskripsi", description_input)
            buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
            buttons.accepted.connect(form_dialog.accept); buttons.rejected.connect(form_dialog.reject); form.addRow(buttons)
            if form_dialog.exec() != QDialog.DialogCode.Accepted: return
            title = title_input.text().strip()
            if not title:
                QMessageBox.information(dialog, "Timeline", "Nama peristiwa wajib diisi."); return
            values = (title, date_input.text().strip(), description_input.toPlainText().strip(), chapter_combo.currentData(), characters_input.text().strip())
            with db() as conn:
                if existing_id is None:
                    conn.execute("INSERT INTO timeline_events(project_id,title,event_date,description,chapter_id,character_names) VALUES(?,?,?,?,?,?)", (self.project_id,*values))
                else:
                    conn.execute("UPDATE timeline_events SET title=?,event_date=?,description=?,chapter_id=?,character_names=? WHERE id=? AND project_id=?", (*values,existing_id,self.project_id))
            reload_events()
        def edit_selected():
            item = event_list.currentItem()
            if item: edit_event(item.data(Qt.ItemDataRole.UserRole))
        def delete_selected():
            item = event_list.currentItem()
            if not item: return
            if QMessageBox.question(dialog,"Hapus Peristiwa","Hapus peristiwa yang dipilih?",QMessageBox.StandardButton.Yes|QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes: return
            with db() as conn: conn.execute("DELETE FROM timeline_events WHERE id=? AND project_id=?",(item.data(Qt.ItemDataRole.UserRole),self.project_id))
            reload_events()
        row = QHBoxLayout()
        for label, handler in (("＋ Tambah", lambda: edit_event()), ("Edit", edit_selected), ("Hapus", delete_selected)):
            btn=QPushButton(label); btn.clicked.connect(handler); row.addWidget(btn)
        row.addStretch(); close_btn=QPushButton("Tutup"); close_btn.clicked.connect(dialog.accept); row.addWidget(close_btn)
        layout.addLayout(row); reload_events(); dialog.exec()

    def show_writing_statistics(self):
        if self.project_id is None: return
        dialog, layout = self._tool_dialog("Statistik Penulisan", 820, 600)
        with db() as conn:
            chapters = conn.execute("SELECT title,content FROM chapters WHERE project_id=? ORDER BY position", (self.project_id,)).fetchall()
            characters = conn.execute("SELECT COUNT(*) FROM characters WHERE project_id=?", (self.project_id,)).fetchone()[0]
            world_items = conn.execute("SELECT COUNT(*) FROM worldbuilding WHERE project_id=?", (self.project_id,)).fetchone()[0]
            bible_records = conn.execute("SELECT COUNT(*) FROM story_records WHERE project_id=?", (self.project_id,)).fetchone()[0]
            conn.execute("INSERT OR IGNORE INTO writing_goals(project_id,daily_word_goal) VALUES(?,500)", (self.project_id,))
            goal = conn.execute("SELECT daily_word_goal FROM writing_goals WHERE project_id=?", (self.project_id,)).fetchone()[0]
            from datetime import date
            today = date.today().isoformat()
            today_written = conn.execute("SELECT words_written FROM writing_activity WHERE project_id=? AND activity_date=?", (self.project_id,today)).fetchone()
            today_written = today_written[0] if today_written else 0
        total_words = sum(len((content or "").split()) for _title,content in chapters)
        total_chars = sum(len(content or "") for _title,content in chapters)
        summary = QLabel(f"<h2>{total_words:,} kata</h2><p>{len(chapters)} bab · {total_chars:,} karakter teks · {characters} karakter cerita · {world_items + bible_records} entri latar/Bible</p>")
        summary.setTextFormat(Qt.TextFormat.RichText); layout.addWidget(summary)
        goal_box = QGroupBox("Target kata harian"); goal_row = QHBoxLayout(goal_box)
        goal_spin = QSpinBox(); goal_spin.setRange(1,100000); goal_spin.setValue(goal)
        goal_row.addWidget(QLabel("Target:")); goal_row.addWidget(goal_spin); goal_row.addWidget(QLabel(f"Kata tercatat hari ini: {today_written:,}")); goal_row.addStretch()
        save_goal = QPushButton("Simpan Target")
        def persist_goal():
            with db() as conn: conn.execute("INSERT INTO writing_goals(project_id,daily_word_goal) VALUES(?,?) ON CONFLICT(project_id) DO UPDATE SET daily_word_goal=excluded.daily_word_goal",(self.project_id,goal_spin.value()))
            QMessageBox.information(dialog,"Target disimpan",f"Target harian diatur ke {goal_spin.value():,} kata.")
        save_goal.clicked.connect(persist_goal); goal_row.addWidget(save_goal); layout.addWidget(goal_box)
        table=QTableWidget(len(chapters),3); table.setHorizontalHeaderLabels(["Bab","Jumlah kata","Jumlah karakter"]); table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        for i,(title,content) in enumerate(chapters):
            table.setItem(i,0,QTableWidgetItem(title)); table.setItem(i,1,QTableWidgetItem(f"{len((content or '').split()):,}")); table.setItem(i,2,QTableWidgetItem(f"{len(content or ''):,}"))
        table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers); layout.addWidget(table,1)
        note=QLabel("Kata harian menghitung penambahan bersih jumlah kata saat naskah disimpan. Penghapusan atau penggantian kata tidak menambah progres harian.")
        note.setWordWrap(True); layout.addWidget(note)
        close_btn=QPushButton("Tutup"); close_btn.clicked.connect(dialog.accept); layout.addWidget(close_btn,alignment=Qt.AlignmentFlag.AlignRight)
        dialog.exec()

    def create_chapter_snapshot(self, chapter_id=None, announce=True):
        chapter_id = chapter_id or self.chapter_id
        if chapter_id is None or self.project_id is None: return
        if chapter_id == self.chapter_id:
            title = self.chapter_title
            content = self.editor.toPlainText()
            content_html = self.editor.toHtml()
        else:
            with db() as conn:
                row = conn.execute("SELECT title,content,content_html FROM chapters WHERE id=? AND project_id=?",(chapter_id,self.project_id)).fetchone()
            if not row: return
            title,content,content_html = row
        with db() as conn:
            latest = conn.execute("SELECT title,content,content_html FROM chapter_versions WHERE chapter_id=? ORDER BY id DESC LIMIT 1",(chapter_id,)).fetchone()
            if latest and latest == (title,content,content_html):
                if announce: QMessageBox.information(self,"Riwayat Versi","Isi bab belum berubah dari versi terakhir.")
                return
            conn.execute("INSERT INTO chapter_versions(project_id,chapter_id,title,content,content_html) VALUES(?,?,?,?,?)",(self.project_id,chapter_id,title,content,content_html or ""))
        if announce: QMessageBox.information(self,"Snapshot tersimpan",f"Versi baru untuk '{title}' berhasil disimpan.")

    def show_version_history(self):
        if self.project_id is None: return
        dialog, layout = self._tool_dialog("Riwayat Versi", 900, 650)
        chapter_combo=QComboBox()
        with db() as conn:
            chapters=conn.execute("SELECT id,title FROM chapters WHERE project_id=? ORDER BY position",(self.project_id,)).fetchall()
        for cid,title in chapters: chapter_combo.addItem(title,cid)
        if self.chapter_id is not None:
            idx=chapter_combo.findData(self.chapter_id)
            if idx>=0: chapter_combo.setCurrentIndex(idx)
        layout.addWidget(QLabel("Pilih bab untuk melihat snapshot dan memulihkan versi lama.")); layout.addWidget(chapter_combo)
        version_list=QListWidget(); layout.addWidget(version_list,1)
        preview=QTextEdit(); preview.setReadOnly(True); preview.setMaximumHeight(160); layout.addWidget(preview)
        def reload_versions():
            version_list.clear(); preview.clear(); cid=chapter_combo.currentData()
            with db() as conn: rows=conn.execute("SELECT id,title,created_at,content FROM chapter_versions WHERE project_id=? AND chapter_id=? ORDER BY id DESC",(self.project_id,cid)).fetchall()
            for vid,title,created,content in rows:
                item=QListWidgetItem(f"{created} · {title} · {len((content or '').split()):,} kata")
                item.setData(Qt.ItemDataRole.UserRole,vid); item.setData(Qt.ItemDataRole.UserRole+1,content); version_list.addItem(item)
        def preview_selected(item):
            if item: preview.setPlainText(item.data(Qt.ItemDataRole.UserRole+1) or "")
        def restore_version():
            item=version_list.currentItem()
            if not item: return
            if QMessageBox.question(dialog,"Pulihkan Versi","Ganti isi bab dengan versi yang dipilih? Isi saat ini akan disimpan sebagai snapshot terlebih dahulu.",QMessageBox.StandardButton.Yes|QMessageBox.StandardButton.No) != QMessageBox.StandardButton.Yes: return
            cid=chapter_combo.currentData()
            self.create_chapter_snapshot(cid,announce=False)
            with db() as conn:
                row=conn.execute("SELECT title,content,content_html FROM chapter_versions WHERE id=? AND project_id=?",(item.data(Qt.ItemDataRole.UserRole),self.project_id)).fetchone()
                if not row: return
                conn.execute("UPDATE chapters SET title=?,content=?,content_html=? WHERE id=? AND project_id=?",(*row,cid,self.project_id))
            if cid == self.chapter_id:
                self.chapter_title=row[0]; self.editor.setHtml(row[2] or row[1]); self.chapter_heading.setText(row[0]); self.save_current_chapter()
            self.load_chapters(select_id=cid); reload_versions()
            QMessageBox.information(dialog,"Versi dipulihkan","Versi terpilih berhasil dipulihkan.")
        version_list.currentItemChanged.connect(lambda current,previous: preview_selected(current))
        chapter_combo.currentIndexChanged.connect(lambda _index: reload_versions())
        row=QHBoxLayout(); snapshot=QPushButton("＋ Simpan Snapshot Bab Aktif"); snapshot.clicked.connect(lambda: (self.create_chapter_snapshot(announce=True),reload_versions()))
        restore=QPushButton("Pulihkan Versi Terpilih"); restore.clicked.connect(restore_version)
        close_btn=QPushButton("Tutup"); close_btn.clicked.connect(dialog.accept)
        row.addWidget(snapshot); row.addWidget(restore); row.addStretch(); row.addWidget(close_btn); layout.addLayout(row)
        reload_versions(); dialog.exec()

    def refresh_story_records(self, kind):
        if kind not in self.data_lists or self.project_id is None:
            return
        query = self.data_searches[kind].text().strip() if kind in self.data_searches else ""
        with db() as conn:
            rows = conn.execute(
                "SELECT id, name, category, details, status FROM story_records "
                "WHERE project_id = ? AND kind = ? AND (name LIKE ? OR details LIKE ? OR category LIKE ? OR status LIKE ? OR metadata LIKE ?) "
                "ORDER BY id DESC",
                (self.project_id, kind, f"%{query}%", f"%{query}%", f"%{query}%", f"%{query}%", f"%{query}%")
            ).fetchall()
        widget = self.data_lists[kind]
        widget.clear()
        for record_id, name, category, details, status in rows:
            subtitle = " · ".join(x for x in (category, status) if x)
            item = QListWidgetItem(name + ("\n" + subtitle if subtitle else ""))
            item.setData(Qt.ItemDataRole.UserRole, record_id)
            item.setToolTip(details[:1000])
            widget.addItem(item)
        if not rows:
            widget.addItem(QListWidgetItem("Belum ada data. Klik tombol Tambah untuk membuat entri pertama."))

    def create_story_record(self, kind, record_id=None):
        if self.project_id is None:
            QMessageBox.information(self, "Stomake", "Buat atau pilih cerita terlebih dahulu.")
            return
        existing = None
        metadata = {}
        linked_chapter_ids = set()
        existing_relationships = []
        if record_id is not None:
            with db() as conn:
                existing = conn.execute(
                    "SELECT name, category, details, status, metadata, image_data, image_width, gallery_data, role FROM story_records WHERE id=? AND project_id=? AND kind=?",
                    (record_id, self.project_id, kind)
                ).fetchone()
                linked_chapter_ids = {r[0] for r in conn.execute(
                    "SELECT chapter_id FROM story_record_chapters WHERE record_id=?", (record_id,)
                ).fetchall()}
                if kind == "characters":
                    existing_relationships = conn.execute(
                        "SELECT cr.id, cr.relation, cr.notes, other.name, other.id "
                        "FROM character_relationships cr JOIN story_records other ON "
                        "other.id = CASE WHEN cr.source_id=? THEN cr.target_id ELSE cr.source_id END "
                        "WHERE cr.project_id=? AND (cr.source_id=? OR cr.target_id=?) ORDER BY other.name",
                        (record_id, self.project_id, record_id, record_id)
                    ).fetchall()
            if not existing:
                return
            try:
                metadata = json.loads(existing[4] or "{}")
            except (TypeError, json.JSONDecodeError):
                metadata = {}
        image_data = existing[5] if existing and len(existing) > 5 else ""
        image_width = float(existing[6] if existing and len(existing) > 6 else 1.0)
        gallery_data = existing[7] if existing and len(existing) > 7 else "[]"
        try:
            gallery_items = json.loads(gallery_data or "[]")
            if not isinstance(gallery_items, list): gallery_items = []
        except (TypeError, json.JSONDecodeError):
            gallery_items = []

        title, category_default, _ = self.data_titles[kind]
        dialog = QDialog(self.data_pages.get(kind, self))
        dialog.setWindowTitle(("Edit " if existing else "Tambah ") + title)
        dialog.resize(720, 700)
        dialog.setMinimumSize(560, 500)
        outer = QVBoxLayout(dialog)
        tabs = QTabWidget()
        outer.addWidget(tabs, 1)

        basic_page = QWidget()
        basic_layout = QVBoxLayout(basic_page)
        form = QFormLayout()
        name_input = QLineEdit(existing[0] if existing else "")
        name_input.setPlaceholderText("Nama / judul entri")
        category_input = QComboBox()
        category_input.setEditable(True)
        category_input.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        category_defaults = ["Lokasi", "Kelompok", "Item", "Konsep", "Lore", "Aturan", "Sejarah", "Budaya", "Organisasi", "Lainnya"]
        if kind == "characters":
            category_defaults = ["Protagonis", "Antagonis", "Pendukung", "NPC", "Lainnya"]
        with db() as conn:
            saved_categories = [r[0] for r in conn.execute("SELECT DISTINCT category FROM story_records WHERE project_id=? AND kind=? AND category<>'' ORDER BY category", (self.project_id, kind)).fetchall()]
            saved_custom_categories = [r[0] for r in conn.execute("SELECT value FROM custom_options WHERE project_id IN (0, ?) AND field_key=? ORDER BY value", (self.project_id, f"category:{kind}")).fetchall()]
        for option in list(dict.fromkeys(category_defaults + saved_categories + saved_custom_categories)):
            category_input.addItem(option)
        category_input.setCurrentText(existing[1] if existing else category_default)
        category_input.setToolTip("Pilih kategori bawaan atau ketik kategori sendiri.")

        status_input = QComboBox()
        status_input.setEditable(True)
        status_input.setInsertPolicy(QComboBox.InsertPolicy.NoInsert)
        if kind == "characters":
            trait_defaults = ["Protagonis", "Antagonis", "Pendukung", "Manipulatif", "Ambisius", "Pemberani", "Pemalu", "Dingin", "Cerdas", "Setia", "Licik", "Humoris", "Introver", "Ekstrover"]
            status_input.setPlaceholderText("Pilih/ketik trait; pisahkan tiap trait dengan koma")
            status_label = "Trait karakter"
            option_key = "traits:characters"
        else:
            trait_defaults = ["Penting", "Rahasia", "Misterius", "Berbahaya", "Magis", "Terlarang", "Legendaris"]
            status_input.setPlaceholderText("Pilih/ketik tag; pisahkan dengan koma")
            status_label = "Tag / Status"
            option_key = f"tags:{kind}"
        with db() as conn:
            saved_statuses = [r[0] for r in conn.execute("SELECT DISTINCT status FROM story_records WHERE project_id=? AND kind=? AND status<>'' ORDER BY status", (self.project_id, kind)).fetchall()]
            custom_statuses = [r[0] for r in conn.execute("SELECT value FROM custom_options WHERE project_id IN (0, ?) AND field_key=? ORDER BY value", (self.project_id, option_key)).fetchall()]
        for option in list(dict.fromkeys(trait_defaults + saved_statuses + custom_statuses)):
            status_input.addItem(option)
        status_input.setCurrentText(existing[3] if existing else "")
        status_input.setToolTip("Bisa pilih saran atau ketik sendiri. Gunakan koma untuk beberapa trait/tag.")
        # When a comma is typed, remember everything before the current trait.
        # QComboBox replaces its edit text on activation, so restore the prefix ourselves.
        trait_state = {"prefix": "", "waiting": False}
        def show_next_trait_suggestions(text):
            if text.rstrip().endswith(","):
                trait_state["prefix"] = text.rstrip()[:-1].rstrip()
                trait_state["waiting"] = True
                QTimer.singleShot(0, status_input.showPopup)
            elif trait_state["waiting"] and text.strip():
                # The user started typing a custom next trait; stop treating it as a selection.
                trait_state["waiting"] = False
        status_input.lineEdit().textEdited.connect(show_next_trait_suggestions)
        def append_selected_trait(index):
            chosen = status_input.itemText(index).strip()
            if not chosen:
                return
            if trait_state["waiting"]:
                prefix = trait_state["prefix"]
                parts = [part.strip() for part in prefix.split(",") if part.strip()]
                if not parts or parts[-1].casefold() != chosen.casefold():
                    result = (prefix + ", " if prefix else "") + chosen + ", "
                else:
                    result = prefix + ", "
                status_input.setEditText(result)
                status_input.lineEdit().setCursorPosition(len(result))
                trait_state["waiting"] = True
                trait_state["prefix"] = result.rstrip()[:-1].rstrip()
                QTimer.singleShot(0, status_input.showPopup)
        status_input.activated.connect(append_selected_trait)
        def remember_custom_values(combo, field_key):
            raw = combo.currentText()
            values = [part.strip() for part in raw.split(",") if part.strip()]
            with db() as conn:
                for value in values:
                    try:
                        conn.execute("INSERT OR IGNORE INTO custom_options (project_id, field_key, value) VALUES (?, ?, ?)", (self.project_id, field_key, value))
                    except sqlite3.Error:
                        pass
        category_input.lineEdit().editingFinished.connect(lambda: remember_custom_values(category_input, f"category:{kind}"))
        status_input.lineEdit().editingFinished.connect(lambda: remember_custom_values(status_input, option_key))
        details_input = QTextEdit()
        details_input.setPlaceholderText("Deskripsi umum, catatan, rahasia, aturan, atau informasi lain...")
        details_input.setPlainText(existing[2] if existing else "")
        form.addRow("Nama / Judul", name_input)
        form.addRow("Kategori", category_input)
        form.addRow(status_label, status_input)
        basic_layout.addLayout(form)
        basic_layout.addWidget(QLabel("Deskripsi umum"))
        basic_layout.addWidget(details_input, 1)
        image_preview = QLabel("Belum ada gambar")
        image_preview.setAlignment(Qt.AlignmentFlag.AlignCenter)
        image_preview.setMinimumHeight(140)
        def refresh_record_image_preview():
            if not image_data:
                image_preview.setPixmap(QPixmap()); image_preview.setText("Belum ada gambar"); return
            try:
                pix=QPixmap(); pix.loadFromData(base64.b64decode(image_data))
                image_preview.setPixmap(pix.scaled(max(80,int(230*image_width)),max(80,int(160*image_width)),Qt.AspectRatioMode.KeepAspectRatio,Qt.TransformationMode.SmoothTransformation)); image_preview.setText("")
            except Exception: image_preview.setText("Preview gambar tidak tersedia")
        refresh_record_image_preview()
        image_row=QHBoxLayout()
        choose_record_image=QPushButton("Pilih / Ganti Gambar")
        remove_record_image=QPushButton("Hapus Gambar")
        size_record_image=QPushButton("Atur Lebar")
        def choose_record_image_file():
            nonlocal image_data
            chosen_image = crop_image_file(dialog, "Pilih Gambar Bible")
            if chosen_image:
                image_data = chosen_image
                refresh_record_image_preview()
        def clear_record_image():
            nonlocal image_data
            image_data=""; refresh_record_image_preview()
        def resize_record_image():
            nonlocal image_width
            value, ok = QInputDialog.getInt(dialog, "Lebar Gambar", "Lebar relatif (%)", int(image_width*100), 20, 100, 5)
            if ok:
                image_width = value / 100.0
                refresh_record_image_preview()
        choose_record_image.clicked.connect(choose_record_image_file); remove_record_image.clicked.connect(clear_record_image); size_record_image.clicked.connect(resize_record_image)
        image_row.addWidget(choose_record_image); image_row.addWidget(remove_record_image); image_row.addWidget(size_record_image)
        basic_layout.addWidget(QLabel("Gambar utama")); basic_layout.addWidget(image_preview); basic_layout.addLayout(image_row)
        basic_layout.addWidget(QLabel("Galeri gambar"))
        gallery_list = QListWidget()
        def refresh_gallery_list():
            gallery_list.clear()
            for index, item in enumerate(gallery_items, 1):
                gallery_list.addItem(f"Gambar {index} • lebar {int(float(item.get('width',1.0))*100)}% • {item.get('layout','center')}")
        refresh_gallery_list()
        gallery_actions = QHBoxLayout()
        add_gallery_button = QPushButton("Tambah Gambar")
        remove_gallery_button = QPushButton("Hapus Gambar")
        size_gallery_button = QPushButton("Atur Lebar")
        layout_gallery_button = QPushButton("Layout")
        def add_gallery_image():
            data = crop_image_file(dialog, "Tambah Gambar Bible")
            if data:
                gallery_items.append({"data":data,"width":1.0,"layout":"center"})
                refresh_gallery_list()
        def remove_gallery_image():
            row = gallery_list.currentRow()
            if 0 <= row < len(gallery_items):
                gallery_items.pop(row); refresh_gallery_list()
        def resize_gallery_image():
            row = gallery_list.currentRow()
            if not (0 <= row < len(gallery_items)): return
            old = int(float(gallery_items[row].get("width",1.0))*100)
            value, ok = QInputDialog.getInt(dialog,"Lebar Gambar","Lebar relatif (%)",old,20,100,5)
            if ok:
                gallery_items[row]["width"] = value/100.0; refresh_gallery_list(); gallery_list.setCurrentRow(row)
        def layout_gallery_image():
            row = gallery_list.currentRow()
            if not (0 <= row < len(gallery_items)): return
            value, ok = QInputDialog.getItem(dialog,"Layout Gambar","Posisi gambar",["center","left","right"],0,False)
            if ok:
                gallery_items[row]["layout"] = value; refresh_gallery_list(); gallery_list.setCurrentRow(row)
        add_gallery_button.clicked.connect(add_gallery_image); remove_gallery_button.clicked.connect(remove_gallery_image)
        size_gallery_button.clicked.connect(resize_gallery_image); layout_gallery_button.clicked.connect(layout_gallery_image)
        gallery_actions.addWidget(add_gallery_button); gallery_actions.addWidget(remove_gallery_button)
        gallery_actions.addWidget(size_gallery_button); gallery_actions.addWidget(layout_gallery_button)
        basic_layout.addWidget(gallery_list); basic_layout.addLayout(gallery_actions)
        tabs.addTab(basic_page, "Umum")

        profile_inputs = {}
        if kind == "characters":
            profile_page = QWidget()
            profile_layout = QVBoxLayout(profile_page)
            profile_form = QFormLayout()
            profile_fields = [
                ("age", "Usia", "Contoh: 17 tahun"),
                ("appearance", "Penampilan", "Ciri fisik, pakaian, warna mata..."),
                ("personality", "Kepribadian", "Sifat dan cara berperilaku..."),
                ("background", "Latar belakang", "Sejarah hidup karakter..."),
                ("motivation", "Motivasi", "Apa yang mendorong karakter?"),
                ("goals_conflicts", "Tujuan & konflik", "Tujuan, hambatan, konflik pribadi..."),
                ("strengths_weaknesses", "Kelebihan & kelemahan", "Kemampuan dan kelemahan..."),
                ("development", "Perkembangan karakter", "Perubahan karakter sepanjang cerita..."),
                ("ai_notes", "Catatan untuk AI", "Fakta penting yang perlu dijaga AI...")
            ]
            for field_key, label, placeholder in profile_fields:
                edit = QTextEdit()
                edit.setPlaceholderText(placeholder)
                edit.setPlainText(str(metadata.get(field_key, "")))
                edit.setMaximumHeight(76 if field_key in ("age",) else 100)
                profile_inputs[field_key] = edit
                profile_form.addRow(label + ":", edit)
            profile_layout.addLayout(profile_form)
            tabs.addTab(profile_page, "Profil Karakter")

        links_page = QWidget()
        links_layout = QVBoxLayout(links_page)
        links_layout.addWidget(QLabel("Hubungkan entri ini dengan bab yang relevan:"))
        chapter_checks = []
        for chapter_row in get_chapters(self.project_id):
            chapter_id, chapter_title = chapter_row[0], chapter_row[1]
            check = QCheckBox(chapter_title)
            check.setProperty("chapter_id", chapter_id)
            check.setChecked(chapter_id in linked_chapter_ids)
            chapter_checks.append(check)
            links_layout.addWidget(check)
        if not chapter_checks:
            links_layout.addWidget(QLabel("Belum ada bab di proyek ini."))
        links_layout.addStretch(1)
        tabs.addTab(links_page, "Bab terkait")

        relation_list = None
        relation_target = None
        relation_label = None
        relation_notes = None
        relation_rows = []
        if kind == "characters":
            rel_page = QWidget()
            rel_layout = QVBoxLayout(rel_page)
            rel_layout.addWidget(QLabel("Hubungkan karakter dengan karakter lain. Simpan karakter ini dahulu jika masih baru."))
            relation_list = QListWidget()
            for rel_id, relation, notes, other_name, other_id in existing_relationships:
                item = QListWidgetItem(f"{name_input.text() or (existing[0] if existing else 'Karakter')} — {relation} — {other_name}" + (f"\n{notes}" if notes else ""))
                item.setData(Qt.ItemDataRole.UserRole, rel_id)
                item.setData(Qt.ItemDataRole.UserRole + 1, other_id)
                relation_list.addItem(item)
            rel_layout.addWidget(relation_list, 1)
            rel_form = QFormLayout()
            relation_target = QComboBox()
            with db() as conn:
                others = conn.execute("SELECT id, name FROM story_records WHERE project_id=? AND kind='characters' AND id<>? ORDER BY name", (self.project_id, record_id or -1)).fetchall()
            for other_id, other_name in others:
                relation_target.addItem(other_name, other_id)
            relation_label = QLineEdit()
            relation_label.setPlaceholderText("Teman, musuh, keluarga, mentor...")
            relation_notes = QLineEdit()
            relation_notes.setPlaceholderText("Catatan hubungan (opsional)")
            rel_form.addRow("Karakter", relation_target)
            rel_form.addRow("Hubungan", relation_label)
            rel_form.addRow("Catatan", relation_notes)
            rel_layout.addLayout(rel_form)
            rel_actions = QHBoxLayout()
            add_rel = QPushButton("＋ Tambah hubungan")
            remove_rel = QPushButton("Hapus hubungan terpilih")
            rel_actions.addWidget(add_rel)
            rel_actions.addWidget(remove_rel)
            rel_layout.addLayout(rel_actions)
            def add_relationship_to_list():
                if record_id is None:
                    QMessageBox.information(dialog, "Simpan karakter dahulu", "Simpan karakter ini dahulu, lalu buka kembali untuk menambahkan hubungan.")
                    return
                target_id = relation_target.currentData()
                relation = relation_label.text().strip()
                if target_id is None or not relation:
                    QMessageBox.information(dialog, "Data belum lengkap", "Pilih karakter dan isi jenis hubungannya.")
                    return
                with db() as conn:
                    try:
                        cur = conn.execute("INSERT INTO character_relationships (project_id, source_id, target_id, relation, notes) VALUES (?, ?, ?, ?, ?)", (self.project_id, record_id, target_id, relation, relation_notes.text().strip()))
                        rel_id = cur.lastrowid
                    except sqlite3.IntegrityError:
                        QMessageBox.information(dialog, "Hubungan sudah ada", "Hubungan dengan label yang sama sudah tercatat.")
                        return
                target_name = relation_target.currentText()
                item = QListWidgetItem(f"{name_input.text()} — {relation} — {target_name}" + (f"\n{relation_notes.text().strip()}" if relation_notes.text().strip() else ""))
                item.setData(Qt.ItemDataRole.UserRole, rel_id)
                item.setData(Qt.ItemDataRole.UserRole + 1, target_id)
                relation_list.addItem(item)
                relation_label.clear(); relation_notes.clear()
            def remove_relationship_from_list():
                item = relation_list.currentItem() if relation_list else None
                if item is None:
                    return
                rel_id = item.data(Qt.ItemDataRole.UserRole)
                if rel_id is not None:
                    with db() as conn:
                        conn.execute("DELETE FROM character_relationships WHERE id=? AND project_id=?", (rel_id, self.project_id))
                relation_list.takeItem(relation_list.row(item))
            add_rel.clicked.connect(add_relationship_to_list)
            remove_rel.clicked.connect(remove_relationship_from_list)
            tabs.addTab(rel_page, "Hubungan")

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel | QDialogButtonBox.StandardButton.Save)
        buttons.button(QDialogButtonBox.StandardButton.Save).setText("Simpan")
        buttons.accepted.connect(dialog.accept)
        buttons.rejected.connect(dialog.reject)
        outer.addWidget(buttons)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        name = name_input.text().strip()
        if not name:
            QMessageBox.warning(self, "Stomake", "Nama atau judul tidak boleh kosong.")
            return
        if kind == "characters":
            metadata = {key: edit.toPlainText().strip() for key, edit in profile_inputs.items()}
        else:
            metadata = {}
        gallery_data = json.dumps(gallery_items, ensure_ascii=False)
        with db() as conn:
            if record_id is None:
                cur = conn.execute(
                    "INSERT INTO story_records (project_id, kind, name, category, details, status, metadata, role, image_data, image_width, gallery_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (self.project_id, kind, name, category_input.currentText().strip(), details_input.toPlainText(), status_input.currentText().strip(), json.dumps(metadata, ensure_ascii=False), status_input.currentText().strip() if kind == "characters" else "", image_data, image_width, gallery_data)
                )
                record_id = cur.lastrowid
            else:
                conn.execute(
                    "UPDATE story_records SET name=?, category=?, details=?, status=?, metadata=?, role=?, image_data=?, image_width=?, gallery_data=? WHERE id=? AND project_id=? AND kind=?",
                    (name, category_input.currentText().strip(), details_input.toPlainText(), status_input.currentText().strip(), json.dumps(metadata, ensure_ascii=False), status_input.currentText().strip() if kind == "characters" else "", image_data, image_width, gallery_data, record_id, self.project_id, kind)
                )
            # Replace chapter associations with the currently checked chapters.
            conn.execute("DELETE FROM story_record_chapters WHERE record_id=?", (record_id,))
            for check in chapter_checks:
                if check.isChecked():
                    conn.execute("INSERT OR IGNORE INTO story_record_chapters (record_id, chapter_id) VALUES (?, ?)", (record_id, check.property("chapter_id")))
        self.refresh_story_records(kind)
        self.save_label.setText("● " + title + " tersimpan")

    def edit_story_record(self, kind, item):
        if item is None:
            return
        record_id = item.data(Qt.ItemDataRole.UserRole)
        if record_id is not None:
            self.create_story_record(kind, record_id)

    def edit_selected_story_record(self, kind):
        self.edit_story_record(kind, self.data_lists[kind].currentItem())

    def delete_selected_story_record(self, kind):
        item = self.data_lists[kind].currentItem()
        record_id = item.data(Qt.ItemDataRole.UserRole) if item else None
        if record_id is None:
            return
        title = self.data_titles[kind][0]
        answer = QMessageBox.question(self, "Hapus entri", f"Hapus entri ini dari {title}?")
        if answer != QMessageBox.StandardButton.Yes:
            return
        with db() as conn:
            conn.execute("DELETE FROM story_records WHERE id=? AND project_id=? AND kind=?", (record_id, self.project_id, kind))
        self.refresh_story_records(kind)

    def story_record_menu(self, kind, pos):
        item = self.data_lists[kind].itemAt(pos)
        if item is None or item.data(Qt.ItemDataRole.UserRole) is None:
            return
        menu = QMenu(self)
        edit_action = menu.addAction("Edit")
        delete_action = menu.addAction("Hapus")
        action = menu.exec(self.data_lists[kind].mapToGlobal(pos))
        if action == edit_action:
            self.edit_story_record(kind, item)
        elif action == delete_action:
            self.data_lists[kind].setCurrentItem(item)
            self.delete_selected_story_record(kind)

    # -------------------------
    # PROJECT MANAGEMENT
    # -------------------------

    def load_projects(self, select_id=None):
        self.loading = True
        self.project_combo.clear()
        projects = get_projects()

        for project_id, title in projects:
            self.project_combo.addItem(title, project_id)

        target_id = select_id
        if target_id is None and projects:
            target_id = projects[0][0]

        index = self.project_combo.findData(target_id)
        if index >= 0:
            self.project_combo.setCurrentIndex(index)

        self.loading = False

        if target_id is not None:
            self.open_project(target_id)

    def on_project_changed(self, index):
        if self.loading or index < 0:
            return
        project_id = self.project_combo.currentData()
        if project_id is not None:
            self.open_project(project_id)

    def open_project(self, project_id):
        self.save_current_chapter()
        if self._home_mode:
            self._home_mode = False
            self.editor_header.show()
            self.sidebar.setVisible(self._home_sidebar_was_visible)
            self.ai_panel.setVisible(self._home_ai_was_visible)
        if self.project_id != project_id:
            self.ai_messages = []
            self.ai_conversation_id = None
            if hasattr(self, "chat"):
                self.chat.clear()
        self.project_id = project_id
        self.project_title = self.project_combo.currentText()
        self.load_chapters()
        self.refresh_context_lists()
        if hasattr(self, "center_stack"):
            self.center_stack.setCurrentWidget(self.chapter_page)
            self.reference_record_kind = None
            self.reference_record_id = None
            self.hide_reference_panel()

    def new_project(self):
        title, ok = QInputDialog.getText(
            self, "Cerita Baru", "Judul cerita:"
        )
        if not ok or not title.strip():
            return
        project_id = create_project(title.strip())
        self.load_projects(project_id)

    def show_project_menu(self, pos):
        """Menu klik kanan pada pemilih cerita."""
        if self.project_id is None:
            return
        menu = QMenu(self)
        rename_action = menu.addAction("✎ Ubah Judul Cerita")
        media_action = menu.addAction("▣ Atur Sampul dan Sinopsis")
        import_action = menu.addAction("⇧ Import .stomake")
        export_action = menu.addAction("⇩ Export .stomake")
        menu.addSeparator()
        delete_action = menu.addAction("⌫ Hapus Cerita")
        chosen = menu.exec(self.project_combo.mapToGlobal(pos))
        if chosen == rename_action:
            self.rename_project()
        elif chosen == media_action:
            self.edit_project_details()
        elif chosen == import_action:
            self.import_stomake()
        elif chosen == export_action:
            self.export_stomake()
        elif chosen == delete_action:
            self.delete_project()

    def delete_project(self):
        if self.project_id is None:
            return

        projects = get_projects()
        if len(projects) <= 1:
            QMessageBox.information(
                self, "Stomake",
                "Cerita terakhir tidak bisa dihapus. Buat cerita baru terlebih dahulu."
            )
            return

        title = self.project_title
        answer = QMessageBox.question(
            self, "Hapus Cerita",
            f"Yakin ingin menghapus cerita '{title}'?\n\n"
            "Semua bab, karakter, dan latar dalam cerita ini juga akan dihapus.\n"
            "Tindakan ini tidak bisa dibatalkan.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if answer != QMessageBox.StandardButton.Yes:
            return

        self.save_current_chapter()
        deleting_id = self.project_id
        with db() as conn:
            conn.execute("DELETE FROM chapters WHERE project_id = ?", (deleting_id,))
            conn.execute("DELETE FROM characters WHERE project_id = ?", (deleting_id,))
            conn.execute("DELETE FROM worldbuilding WHERE project_id = ?", (deleting_id,))
            conn.execute("DELETE FROM projects WHERE id = ?", (deleting_id,))

        remaining = get_projects()
        if remaining:
            self.load_projects(remaining[0][0])
        self.refresh_home_cards()
        if self.center_stack.currentWidget() is self.home_page:
            self.center_stack.setCurrentWidget(self.home_page)

    def rename_project(self):
        if self.project_id is None:
            return
        title, ok = QInputDialog.getText(
            self, "Ubah Judul Cerita", "Judul baru:",
            text=self.project_title
        )
        if not ok or not title.strip():
            return

        with db() as conn:
            conn.execute(
                "UPDATE projects SET title = ? WHERE id = ?",
                (title.strip(), self.project_id)
            )

        self.project_title = title.strip()
        self.loading = True
        index = self.project_combo.findData(self.project_id)
        if index >= 0:
            self.project_combo.setItemText(index, self.project_title)
        self.loading = False
        self.save_label.setText("● Judul cerita disimpan")
        if hasattr(self, "home_cards_layout"):
            self.refresh_home_cards()

    # -------------------------
    # EXPORT CERITA
    # -------------------------

    # -------------------------
    # CROSS-PLATFORM .STOMAKE
    # -------------------------

    def edit_project_details(self):
        if self.project_id is None:
            QMessageBox.information(self, "Stomake", "Pilih cerita terlebih dahulu.")
            return
        with db() as conn:
            row = conn.execute("SELECT cover_image, synopsis FROM projects WHERE id=?", (self.project_id,)).fetchone()
        cover = row[0] or ""
        dialog = QDialog(self)
        dialog.setWindowTitle("Sampul dan Sinopsis Cerita")
        dialog.resize(520, 520)
        layout = QVBoxLayout(dialog)
        preview = QLabel("Belum ada sampul")
        preview.setAlignment(Qt.AlignmentFlag.AlignCenter)
        preview.setMinimumHeight(230)
        def update_preview(data):
            if not data:
                preview.setPixmap(QPixmap())
                preview.setText("Belum ada sampul")
                return
            try:
                raw = base64.b64decode(data)
                pix = QPixmap()
                pix.loadFromData(raw)
                preview.setPixmap(pix.scaled(280, 230, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))
                preview.setText("")
            except Exception:
                preview.setText("Preview sampul tidak tersedia")
        update_preview(cover)
        layout.addWidget(preview)
        cover_row = QHBoxLayout()
        choose = QPushButton("Pilih / Ganti Sampul")
        remove = QPushButton("Hapus Sampul")
        def choose_cover():
            nonlocal cover
            chosen_image = crop_image_file(dialog, "Pilih Sampul")
            if chosen_image:
                cover = chosen_image
                update_preview(cover)
        choose.clicked.connect(choose_cover)
        def setattr_preview_clear():
            nonlocal cover
            cover = ""
            update_preview(cover)
        remove.clicked.connect(setattr_preview_clear)
        cover_row.addWidget(choose); cover_row.addWidget(remove)
        layout.addLayout(cover_row)
        layout.addWidget(QLabel("Sinopsis"))
        synopsis = QTextEdit()
        synopsis.setPlainText(row[1] or "")
        layout.addWidget(synopsis, 1)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(dialog.accept); buttons.rejected.connect(dialog.reject)
        layout.addWidget(buttons)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            with db() as conn:
                conn.execute("UPDATE projects SET cover_image=?, synopsis=? WHERE id=?", (cover, synopsis.toPlainText(), self.project_id))
            self.save_label.setText("● Sampul dan sinopsis tersimpan")
        if hasattr(self, "home_cards_layout"):
            self.refresh_home_cards()

    def _ensure_import_category(self, section, category):
        category = (category or "Umum").strip() or "Umum"
        if section == "story" and category.casefold() == "karakter":
            return "characters"
        with db() as conn:
            conn.execute("INSERT OR IGNORE INTO custom_categories(section,name) VALUES(?,?)", (section, category))
            row = conn.execute("SELECT id FROM custom_categories WHERE section=? AND name=?", (section, category)).fetchone()
        return f"custom_{row[0]}" if row else "characters"

    def import_stomake(self):
        path, _ = QFileDialog.getOpenFileName(self, "Import Cerita Stomake", "", "Stomake (*.stomake);;JSON (*.json);;Semua File (*.*)")
        if not path:
            return
        try:
            payload = json.loads(Path(path).read_text(encoding="utf-8"))
            if payload.get("app") != "Stomake" or payload.get("type") != "single_story":
                raise ValueError("File bukan export satu cerita Stomake yang didukung.")
            project = payload.get("project") or {}
            title = (project.get("title") or Path(path).stem).strip() or "Cerita Impor"
            final_title = title
            with db() as conn:
                existing = {r[0] for r in conn.execute("SELECT title FROM projects").fetchall()}
                suffix = 2
                while final_title in existing:
                    final_title = f"{title} ({suffix})"; suffix += 1
                cur = conn.execute("INSERT INTO projects(title,cover_image,synopsis) VALUES(?,?,?)", (final_title, project.get("cover_image") or "", project.get("synopsis") or ""))
                new_project_id = cur.lastrowid
                old_to_new = {}
                for rec in payload.get("records", []) or []:
                    section = "world" if rec.get("section") == "world" else "story"
                    category = rec.get("category") or "Umum"
                    if section == "story" and category.casefold() == "karakter":
                        kind = "characters"
                    else:
                        conn.execute("INSERT OR IGNORE INTO custom_categories(section,name) VALUES(?,?)", (section, category))
                        category_row = conn.execute("SELECT id FROM custom_categories WHERE section=? AND name=?", (section, category)).fetchone()
                        kind = f"custom_{category_row[0]}" if category_row else "characters"
                    metadata = {}
                    role = rec.get("role") or ""
                    if role:
                        metadata["role"] = role
                    cur_rec = conn.execute(
                        """INSERT INTO story_records
                        (project_id,kind,name,category,details,status,metadata,role,image_data,image_width,gallery_data)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                        (new_project_id, kind, rec.get("name") or "Tanpa Nama", category,
                         rec.get("details") or "", role, json.dumps(metadata, ensure_ascii=False), role,
                         rec.get("image_data") or "", rec.get("image_width") or 1.0, rec.get("gallery_data") or "[]")
                    )
                    if isinstance(rec.get("id"), int):
                        old_to_new[rec["id"]] = cur_rec.lastrowid
                chapter_id_map = {}
                for index, chapter in enumerate(payload.get("chapters", []) or [], 1):
                    chapter_content = chapter.get("content") or ""
                    image_bits = []
                    if chapter.get("image_data"):
                        image_bits.append(chapter.get("image_data"))
                    try:
                        for gallery_item in json.loads(chapter.get("gallery_data") or "[]"):
                            if gallery_item.get("data"):
                                image_bits.append(gallery_item["data"])
                    except Exception:
                        pass
                    html_content = "<html><body>" + "<p>" + chapter_content.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "</p><p>") + "</p>" + "".join(f'<p><img src="data:image/png;base64,{image}" width="420"></p>' for image in image_bits) + "</body></html>"
                    cur_ch = conn.execute(
                        """INSERT INTO chapters(project_id,title,content,position,subtitle,image_data,gallery_data,content_html)
                        VALUES(?,?,?,?,?,?,?,?)""",
                        (new_project_id, chapter.get("title") or f"Bab {index}", chapter_content,
                         chapter.get("position") or index, chapter.get("subtitle") or "",
                         "", "[]", html_content)
                    )
                    if isinstance(chapter.get("id"), int):
                        chapter_id_map[chapter["id"]] = cur_ch.lastrowid
                if not payload.get("chapters"):
                    conn.execute("INSERT INTO chapters(project_id,title,position) VALUES(?,?,1)", (new_project_id, "Bab 1"))
                for event in payload.get("timeline", []) or []:
                    conn.execute("INSERT INTO timeline_events(project_id,title,event_date,description) VALUES(?,?,?,?)", (new_project_id,event.get("title") or "Peristiwa",event.get("event_date") or "",event.get("description") or ""))
                for rel in payload.get("relationships", []) or []:
                    a = old_to_new.get(rel.get("from_record_id")); b = old_to_new.get(rel.get("to_record_id"))
                    if a and b and a != b:
                        conn.execute("INSERT OR IGNORE INTO character_relationships(project_id,source_id,target_id,relation,notes) VALUES(?,?,?,?,?)", (new_project_id,a,b,rel.get("label") or "Hubungan",rel.get("description") or ""))
            # Make imported custom categories immediately available in the current navigation.
            with db() as conn:
                custom_rows = conn.execute("SELECT id,section,name FROM custom_categories ORDER BY id").fetchall()
            for category_id, section_name, category_name in custom_rows:
                key = f"custom_{category_id}"
                if key not in self.data_pages:
                    self.data_titles[key] = (category_name, category_name, f"Kategori cerita buatanmu: {category_name}.")
                    self.create_data_page(key, category_name, category_name, self.data_titles[key][2])
                    self.add_nav_entry(key, "  " + category_name, section_name)
            self.load_projects(new_project_id)
            QMessageBox.information(self, "Import berhasil", f"Cerita '{final_title}' berhasil diimpor. Sampul, bab, Bible, gambar, timeline, dan relasi yang didukung ikut dibawa.")
        except Exception as exc:
            QMessageBox.critical(self, "Import gagal", f"File tidak bisa diimpor.\n\n{exc}")

    def export_stomake(self):
        if self.project_id is None:
            QMessageBox.warning(self, "Export Stomake", "Pilih cerita terlebih dahulu.")
            return
        self.save_current_chapter()
        with db() as conn:
            project = conn.execute("SELECT title,cover_image,synopsis FROM projects WHERE id=?", (self.project_id,)).fetchone()
            chapters = conn.execute("SELECT id,title,subtitle,content,position,image_data,gallery_data,content_html FROM chapters WHERE project_id=? ORDER BY position,id", (self.project_id,)).fetchall()
            records = conn.execute("SELECT id,kind,name,category,details,status,role,image_data,image_width,gallery_data FROM story_records WHERE project_id=? ORDER BY id", (self.project_id,)).fetchall()
            timeline = conn.execute("SELECT title,event_date,description FROM timeline_events WHERE project_id=? ORDER BY event_date,id", (self.project_id,)).fetchall()
            rels = conn.execute("SELECT source_id,target_id,relation,notes FROM character_relationships WHERE project_id=? ORDER BY id", (self.project_id,)).fetchall()
        record_out = []
        for rec_id,kind,name,category,details,status,role,image_data,image_width,gallery_data in records:
            with db() as conn:
                category_row = conn.execute("SELECT section,name FROM custom_categories WHERE id=?", (int(kind.split('_')[1]),)).fetchone() if kind.startswith('custom_') and kind.split('_')[1].isdigit() else None
            section = category_row[0] if category_row else "story"
            export_category = category_row[1] if category_row else ("Karakter" if kind == "characters" else (category or kind))
            record_out.append({"id":rec_id,"section":section,"category":export_category,"name":name,"role":role or status or "","details":details or "","image_data":image_data or "","image_width":image_width or 1.0,"gallery_data":gallery_data or "[]"})
        payload = {
            "app":"Stomake", "type":"single_story", "formatVersion":1,
            "exportedAt":__import__('datetime').datetime.now().isoformat(),
            "project":{"title":project[0],"cover_image":project[1] or "","cover_width":1.0,"synopsis":project[2] or ""},
            "chapters":[{"title":r[1],"subtitle":r[2] or "","content":r[3] or "","position":r[4],"image_data":r[5] or "","image_width":1.0,"gallery_data":json.dumps((json.loads(r[6] or "[]") if r[6] else []) + [{"data":m,"width":1.0,"layout":"center"} for m in __import__('re').findall(r'data:image/[A-Za-z0-9.+-]+;base64,([A-Za-z0-9+/=]+)', r[7] or "") if m], ensure_ascii=False)} for r in chapters],
            "records":record_out,
            "timeline":[{"category":"Peristiwa","title":r[0],"event_date":r[1] or "","description":r[2] or ""} for r in timeline],
            "relationships":[{"from_record_id":r[0],"to_record_id":r[1],"label":r[2],"description":r[3] or ""} for r in rels],
        }
        default = ''.join(c for c in project[0] if c not in '<>:"/\\|?*').strip() or 'Cerita'
        path, _ = QFileDialog.getSaveFileName(self, "Export cerita untuk Android / PC", str(Path.home()/(default+'.stomake')), "Stomake (*.stomake)")
        if not path: return
        if not path.lower().endswith('.stomake'): path += '.stomake'
        try:
            Path(path).write_text(json.dumps(payload,ensure_ascii=False,indent=2),encoding='utf-8')
            QMessageBox.information(self,"Export berhasil",f"File .stomake berhasil dibuat:\n{path}")
        except Exception as exc:
            QMessageBox.critical(self,"Export gagal",str(exc))

    def open_reading_mode(self):
        if self.project_id is None:
            return
        existing = getattr(self, "reader_dialog", None)
        if existing is not None and existing.isVisible():
            existing.raise_()
            existing.activateWindow()
            return

        self.save_current_chapter()
        with db() as conn:
            project = conn.execute("SELECT title,synopsis,cover_image FROM projects WHERE id=?", (self.project_id,)).fetchone()
            chapters = conn.execute("SELECT title,content,content_html,image_data,gallery_data FROM chapters WHERE project_id=? ORDER BY position,id", (self.project_id,)).fetchall()
            records = conn.execute("SELECT kind,name,category,details,status,image_data,gallery_data,role FROM story_records WHERE project_id=? ORDER BY kind,name", (self.project_id,)).fetchall()
            rels = conn.execute("SELECT a.name,r.relation,b.name,r.notes FROM character_relationships r JOIN story_records a ON a.id=r.source_id JOIN story_records b ON b.id=r.target_id WHERE r.project_id=?", (self.project_id,)).fetchall()
            timeline = conn.execute("SELECT title,event_date,description FROM timeline_events WHERE project_id=? ORDER BY event_date,id", (self.project_id,)).fetchall()

        # A parentless top-level window lets reading mode stand alone. The editor
        # window is hidden until the reader is closed, so both modes never overlap.
        dialog = QDialog(None)
        dialog.setStyleSheet(self.styleSheet())
        dialog.setWindowTitle("Mode Baca — " + project[0])
        dialog.setWindowFlags(Qt.WindowType.Window | Qt.WindowType.WindowMinMaxButtonsHint | Qt.WindowType.WindowCloseButtonHint)
        dialog.resize(1280, 860)
        dialog.setMinimumSize(820, 600)
        layout = QVBoxLayout(dialog)
        layout.setContentsMargins(22, 16, 22, 18)
        layout.setSpacing(12)

        topbar = QHBoxLayout()
        reader_title = QLabel("✦  " + project[0])
        reader_title.setObjectName("heading")
        topbar.addWidget(reader_title, 1)
        show_contents_button = QPushButton("☰ Daftar Isi")
        show_contents_button.setCheckable(True)
        show_contents_button.setChecked(True)
        topbar.addWidget(show_contents_button)
        fullscreen_button = QPushButton("⛶ Fullscreen")
        fullscreen_button.setCheckable(True)

        def toggle_reader_fullscreen(checked):
            if checked:
                dialog.showFullScreen()
                fullscreen_button.setText("⛶ Keluar Fullscreen")
            else:
                dialog.showNormal()
                fullscreen_button.setText("⛶ Fullscreen")

        fullscreen_button.clicked.connect(toggle_reader_fullscreen)
        topbar.addWidget(fullscreen_button)
        close_reader = QPushButton("Tutup Mode Baca")
        close_reader.clicked.connect(dialog.close)
        topbar.addWidget(close_reader)
        layout.addLayout(topbar)

        reader_splitter = QSplitter(Qt.Orientation.Horizontal)
        reader_splitter.setChildrenCollapsible(False)
        nav_panel = QFrame()
        nav_panel.setObjectName("panel")
        nav_panel.setMinimumWidth(210)
        nav_panel.setMaximumWidth(340)
        nav_layout = QVBoxLayout(nav_panel)
        nav_layout.setContentsMargins(10, 10, 10, 10)
        nav_layout.setSpacing(8)
        nav_header = QHBoxLayout()
        nav_heading = QLabel("Daftar Isi")
        nav_heading.setObjectName("heading")
        nav_header.addWidget(nav_heading, 1)
        hide_contents_button = QPushButton("×")
        hide_contents_button.setFixedSize(30, 30)
        hide_contents_button.setToolTip("Tutup daftar bab")
        nav_header.addWidget(hide_contents_button)
        nav_layout.addLayout(nav_header)
        nav = QListWidget()
        nav.setObjectName("readerContentsList")
        nav_layout.addWidget(nav, 1)

        content = QTextEdit()
        content.setObjectName("readerContent")
        content.setReadOnly(True)
        content.setAcceptRichText(True)
        content.setFont(QFont("Garamond", 14))
        content.document().setDefaultFont(QFont("Garamond", 14))
        content.document().setDocumentMargin(64)
        content.setLineWrapMode(QTextEdit.LineWrapMode.WidgetWidth)
        content.setStyleSheet("QTextEdit#readerContent { border: none; padding: 42px 9%; font-family: 'Garamond'; font-size: 14pt; line-height: 1.8; }")
        reader_splitter.addWidget(nav_panel)
        reader_splitter.addWidget(content)
        reader_splitter.setSizes([270, 950])
        layout.addWidget(reader_splitter, 1)

        def set_contents_visible(visible):
            nav_panel.setVisible(visible)
            show_contents_button.setChecked(visible)
            show_contents_button.setText("☰ Daftar Isi" if not visible else "☰ Daftar Isi ✓")
            if visible:
                reader_splitter.setSizes([270, max(700, dialog.width() - 330)])
            else:
                reader_splitter.setSizes([0, max(900, dialog.width() - 80)])

        hide_contents_button.clicked.connect(lambda: set_contents_visible(False))
        show_contents_button.clicked.connect(lambda checked: set_contents_visible(checked))

        sections = []
        if project[2]:
            sections.append(("Sampul", f'<div align="center"><img src="data:image/png;base64,{project[2]}" width="340"></div>'))
        if project[1]:
            synopsis_html = project[1].replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "</p><p>")
            sections.append(("Sinopsis", "<html><body><h1>Sinopsis</h1><p>" + synopsis_html + "</p></body></html>"))
        for index, chapter in enumerate(chapters, 1):
            html = chapter[2] or "<html><body>" + (chapter[1] or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>") + "</body></html>"
            if chapter[3]:
                html += f'<p><img src="data:image/png;base64,{chapter[3]}" width="440"></p>'
            try:
                for image in json.loads(chapter[4] or "[]"):
                    if image.get("data"):
                        html += f'<p><img src="data:image/png;base64,{image["data"]}" width="440"></p>'
            except Exception:
                pass
            sections.append((f"Bab {index} — {chapter[0]}", html))

        custom_sections = {f"custom_{cid}": section for cid, section in self._custom_category_rows_with_ids()}
        last_section = None
        for kind, name, category, details, status, image_data, gallery_data, role in records:
            section_key = "story" if kind == "characters" else custom_sections.get(kind, "world")
            if section_key != last_section:
                section_title = "Story Bible" if section_key == "story" else "World Bible"
                sections.append((section_title, f"<html><body><h1>{section_title}</h1></body></html>"))
                last_section = section_key
            heading = f"{category or kind} — {name}"
            html = "<html><body>" + f"<h2>{name}</h2>"
            if role or (kind == "characters" and status):
                html += f"<p><b>Role:</b> {role or status}</p>"
            if status and status != role:
                html += f"<p><b>Tag:</b> {status}</p>"
            html += "<p>" + (details or "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "</p><p>") + "</p>"
            if image_data:
                html += f'<p><img src="data:image/png;base64,{image_data}" width="360"></p>'
            try:
                for image in json.loads(gallery_data or "[]"):
                    if image.get("data"):
                        width_pct = max(20, min(100, int(float(image.get("width", 1.0)) * 100)))
                        float_side = "left" if image.get("layout") == "left" else ("right" if image.get("layout") == "right" else "none")
                        style = f'float:{float_side};margin:8px;' if float_side != "none" else 'display:block;margin:10px auto;'
                        html += f'<img src="data:image/png;base64,{image["data"]}" width="{int(360*width_pct/100)}" style="{style}">'
            except Exception:
                pass
            html += "</body></html>"
            sections.append((heading, html))
        if rels:
            relation_html = "<html><body><h1>Hubungan Karakter</h1>" + "".join(f"<p><b>{a}</b> — {relation} — <b>{b}</b><br>{notes}</p>" for a, relation, b, notes in rels) + "</body></html>"
            sections.append(("Hubungan Karakter", relation_html))
        if timeline:
            timeline_html = "<html><body><h1>Timeline</h1>" + "".join(f"<p><b>{date or 'Tanpa tanggal'} — {title}</b><br>{description}</p>" for title, date, description in timeline) + "</body></html>"
            sections.append(("Timeline", timeline_html))

        for section_title, _ in sections:
            nav.addItem(section_title)
        nav.currentRowChanged.connect(lambda i: content.setHtml(sections[i][1]) if 0 <= i < len(sections) else content.clear())
        if sections:
            nav.setCurrentRow(0)

        # Return to the same workspace after leaving the standalone reading window.
        return_home = bool(getattr(self, "_reader_return_home_on_close", False))
        self._reader_return_home_on_close = False

        def restore_main_window(_result=None):
            self.reader_dialog = None
            if return_home:
                self.show_home()
            self.showNormal()
            self.raise_()
            self.activateWindow()

        dialog.finished.connect(restore_main_window)
        dialog.setAttribute(Qt.WidgetAttribute.WA_DeleteOnClose, True)
        self.reader_dialog = dialog
        dialog.showMaximized()
        self.hide()

    def _custom_category_rows(self):
        with db() as conn:
            return conn.execute("SELECT section,name FROM custom_categories").fetchall()

    def _custom_category_rows_with_ids(self):
        with db() as conn:
            return conn.execute("SELECT id,section FROM custom_categories").fetchall()

    def show_export_menu(self):
        menu = QMenu(self)
        stomake_action = menu.addAction("Export Stomake (.stomake) — Android / PC")
        menu.addSeparator()
        word_action = menu.addAction("Export Word (.docx)")
        docs_action = menu.addAction("Export untuk Google Docs…")
        chosen = menu.exec(self.mapToGlobal(self.rect().center()))
        if chosen == stomake_action:
            self.export_stomake()
        elif chosen == word_action:
            self.export_cerita(open_google_docs=False)
        elif chosen == docs_action:
            self.export_cerita(open_google_docs=True)

    def export_cerita(self, open_google_docs=False):
        if self.project_id is None:
            QMessageBox.warning(self, "Export Cerita", "Pilih cerita terlebih dahulu.")
            return

        # Pastikan isi bab aktif tersimpan sebelum membaca semua bab dari database.
        self.save_current_chapter()
        safe_title = "".join(c for c in self.project_title if c not in '<>:\"/\\|?*').strip()
        safe_title = safe_title or "Cerita Stomake"
        default_path = str(Path.home() / f"{safe_title}.docx")
        file_path, _ = QFileDialog.getSaveFileName(
            self,
            "Export Cerita ke Word" if not open_google_docs else "Siapkan Cerita untuk Google Docs",
            default_path,
            "Dokumen Word (*.docx)"
        )
        if not file_path:
            return
        if not file_path.lower().endswith(".docx"):
            file_path += ".docx"

        try:
            from docx import Document
            from docx.shared import Inches, Pt
        except ImportError:
            QMessageBox.warning(
                self, "Library belum tersedia",
                "Fitur export Word membutuhkan python-docx.\n\n"
                "Buka Command Prompt di folder Stomake lalu jalankan:\n"
                "py -m pip install python-docx"
            )
            return

        try:
            with db() as conn:
                project = conn.execute(
                    "SELECT title FROM projects WHERE id = ?", (self.project_id,)
                ).fetchone()
                chapters = conn.execute(
                    "SELECT title, content FROM chapters WHERE project_id = ? ORDER BY position, id",
                    (self.project_id,)
                ).fetchall()
                characters = conn.execute(
                    "SELECT name, role, details FROM characters WHERE project_id = ? ORDER BY role, name",
                    (self.project_id,)
                ).fetchall()
                lore_entries = conn.execute(
                    "SELECT name, category, details FROM worldbuilding WHERE project_id = ? ORDER BY category, name",
                    (self.project_id,)
                ).fetchall()

            if not project:
                QMessageBox.warning(self, "Export Cerita", "Cerita tidak ditemukan.")
                return

            document = Document()
            styles = document.styles
            styles["Normal"].font.name = "Times New Roman"
            styles["Normal"].font.size = Pt(12)
            section = document.sections[0]
            section.top_margin = Inches(1)
            section.bottom_margin = Inches(1)
            section.left_margin = Inches(1.25)
            section.right_margin = Inches(1)

            document.add_heading(project[0], level=0)
            document.add_paragraph("")
            for index, (chapter_title, content) in enumerate(chapters):
                if index > 0:
                    document.add_page_break()
                document.add_heading(chapter_title, level=1)
                # Preserve paragraph breaks in the editor's plain text.
                paragraphs = (content or "").replace("\r\n", "\n").split("\n")
                for paragraph in paragraphs:
                    document.add_paragraph(paragraph)

            # Tambahkan lampiran karakter dan lore/worldbuilding setelah semua bab.
            document.add_page_break()
            document.add_heading("Karakter", level=1)
            if characters:
                for character_name, role, details in characters:
                    document.add_heading(character_name, level=2)
                    document.add_paragraph(f"Role: {role or 'Karakter Pendukung'}")
                    for paragraph in (details or "").replace("\r\n", "\n").split("\n"):
                        document.add_paragraph(paragraph)
            else:
                document.add_paragraph("Belum ada data karakter.")

            document.add_page_break()
            document.add_heading("Lore dan Worldbuilding", level=1)
            if lore_entries:
                for lore_name, category, details in lore_entries:
                    heading = f"{lore_name} ({category})" if category else lore_name
                    document.add_heading(heading, level=2)
                    for paragraph in (details or "").replace("\r\n", "\n").split("\n"):
                        document.add_paragraph(paragraph)
            else:
                document.add_paragraph("Belum ada data lore/worldbuilding.")

            document.save(file_path)
        except Exception as exc:
            QMessageBox.critical(self, "Export gagal", f"Dokumen tidak bisa dibuat.\n\n{exc}")
            return

        if open_google_docs:
            # Google Docs needs a signed-in Google account to import the generated DOCX.
            QDesktopServices.openUrl(QUrl("https://docs.google.com/document/u/0/"))
            QMessageBox.information(
                self, "Dokumen siap untuk Google Docs",
                "Cerita berhasil diekspor sebagai file Word (.docx).\n\n"
                "Google Docs sudah dibuka. Di Google Docs, pilih pembuka file, lalu upload/pilih file\n"
                f"ini:\n{file_path}\n\n"
                "Stomake tidak mengunggah otomatis ke akun Google; untuk itu diperlukan koneksi dan izin Google API."
            )
        else:
            QMessageBox.information(
                self, "Export berhasil",
                f"Cerita berhasil diekspor, termasuk {len(chapters)} bab, {len(characters)} karakter, "
                f"dan {len(lore_entries)} data lore/worldbuilding.\n\n{file_path}"
            )

    # -------------------------
    # CHAPTER MANAGEMENT
    # -------------------------

    def load_chapters(self, select_id=None):
        self.loading = True
        self.chapter_list.clear()

        chapters = get_chapters(self.project_id)
        for chapter_id, title, content, position in chapters:
            item = QListWidgetItem(title)
            item.setData(Qt.ItemDataRole.UserRole, chapter_id)
            self.chapter_list.addItem(item)

        target_row = 0
        if select_id is not None:
            for i in range(self.chapter_list.count()):
                if self.chapter_list.item(i).data(
                    Qt.ItemDataRole.UserRole
                ) == select_id:
                    target_row = i
                    break

        if self.chapter_list.count():
            self.chapter_list.setCurrentRow(target_row)
        else:
            self.chapter_id = None
            self.chapter_title = ""
            self.editor.clear()

        self.loading = False

        item = self.chapter_list.currentItem()
        if item:
            self.open_chapter(item.data(Qt.ItemDataRole.UserRole))

    def on_chapter_changed(self, current, previous):
        if self.loading:
            return
        if previous is not None:
            previous_id = previous.data(Qt.ItemDataRole.UserRole)
            if previous_id == self.chapter_id:
                self.save_current_chapter()
        if current is not None:
            self.open_chapter(current.data(Qt.ItemDataRole.UserRole))

    def open_chapter(self, chapter_id):
        if chapter_id == self.chapter_id:
            # Klik bab aktif juga bisa dipakai untuk kembali dari halaman karakter/latar.
            if hasattr(self, "center_stack") and self.center_stack.currentWidget() is self.record_page:
                if self.save_record_form(return_to_cerita=False):
                    self.center_stack.setCurrentWidget(self.chapter_page)
                    self.chapter_heading.setText(self.chapter_title or "Editor Cerita")
            return

        self.save_current_chapter()
        if hasattr(self, "center_stack") and self.center_stack.currentWidget() is self.record_page:
            self.save_record_form(return_to_cerita=False)
            self.center_stack.setCurrentWidget(self.chapter_page)

        with db() as conn:
            row = conn.execute("""
                SELECT title, content, content_html FROM chapters
                WHERE id = ? AND project_id = ?
            """, (chapter_id, self.project_id)).fetchone()

        if row is None:
            return

        self.chapter_id = chapter_id
        self.chapter_title, content, content_html = row

        self.loading = True
        if content_html:
            self.editor.setHtml(content_html)
        else:
            self.editor.setPlainText(content or "")
        # A blank chapter can still contain an empty HTML paragraph carrying
        # an old center/justify alignment. Detect the truly empty Qt document,
        # then explicitly reset its paragraph and typing cursor to left.
        # Do not reset chapters that contain text or embedded images.
        if self.editor.document().characterCount() <= 1:
            self.editor.setAlignment(Qt.AlignmentFlag.AlignLeft)
            cursor = self.editor.textCursor()
            cursor.setPosition(0)
            block_format = QTextBlockFormat()
            block_format.setAlignment(Qt.AlignmentFlag.AlignLeft)
            block_format.setLeftMargin(0)
            block_format.setRightMargin(0)
            cursor.setBlockFormat(block_format)
            typing_format = QTextCharFormat()
            typing_format.setFontFamily(self.font_family_combo.currentText() or "Garamond")
            try:
                typing_size = float(self.font_size_combo.currentText())
            except (TypeError, ValueError):
                typing_size = 12.0
            typing_format.setFontPointSize(typing_size)
            cursor.setCharFormat(typing_format)
            self.editor.setTextCursor(cursor)
            self.editor.setCurrentCharFormat(typing_format)
            self.editor.document().setDefaultFont(QFont(
                self.font_family_combo.currentText() or "Garamond",
                int(typing_size),
            ))
        self.loading = False
        self.update_format_indicators()

        self.chapter_heading.setText(self.chapter_title)
        if hasattr(self, "center_stack"):
            self.center_stack.setCurrentWidget(self.chapter_page)
        self.update_word_count()
        self.save_label.setText("● Bab dimuat")

    def new_chapter(self):
        if self.project_id is None:
            return
        title, ok = QInputDialog.getText(
            self, "Bab Baru", "Judul bab:"
        )
        if not ok or not title.strip():
            return
        self.save_current_chapter()
        chapter_id = create_chapter(self.project_id, title.strip())
        self.load_chapters(chapter_id)

    def show_chapter_menu(self, pos):
        item = self.chapter_list.itemAt(pos)
        menu = QMenu(self)
        add_action = menu.addAction("＋ Bab Baru")
        rename_action = menu.addAction("✎ Ubah Judul Bab")
        delete_action = menu.addAction("⌫ Hapus Bab")
        rename_action.setEnabled(item is not None)
        delete_action.setEnabled(item is not None)
        chosen = menu.exec(self.chapter_list.mapToGlobal(pos))
        if chosen == add_action:
            self.new_chapter()
        elif chosen == rename_action and item is not None:
            row = self.chapter_list.row(item)
            self.chapter_list.setCurrentRow(row)
            self.rename_chapter()
        elif chosen == delete_action and item is not None:
            self.delete_chapter(item)

    def delete_chapter(self, item=None):
        if item is None:
            item = self.chapter_list.currentItem()
        if item is None or self.project_id is None:
            return

        chapter_id = item.data(Qt.ItemDataRole.UserRole)
        chapter_title = item.text()
        answer = QMessageBox.question(
            self, "Hapus Bab",
            f"Yakin ingin menghapus bab '{chapter_title}'?\n\n"
            "Isi bab ini akan dihapus permanen.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if answer != QMessageBox.StandardButton.Yes:
            return

        # Simpan bab aktif terlebih dahulu, lalu hapus bab yang dipilih.
        self.save_current_chapter()
        with db() as conn:
            conn.execute(
                "DELETE FROM chapters WHERE id = ? AND project_id = ?",
                (chapter_id, self.project_id)
            )

        self.chapter_id = None
        self.chapter_title = ""
        self.loading = True
        self.editor.clear()
        self.chapter_list.clearSelection()
        self.loading = False
        self.load_chapters()
        self.save_label.setText("● Bab dihapus")

    def rename_chapter(self):
        if self.chapter_id is None:
            return
        title, ok = QInputDialog.getText(
            self, "Ubah Judul Bab", "Judul baru:",
            text=self.chapter_title
        )
        if not ok or not title.strip():
            return

        self.chapter_title = title.strip()
        self.chapter_heading.setText(self.chapter_title)

        item = self.chapter_list.currentItem()
        if item:
            item.setText(self.chapter_title)

        self.save_current_chapter()

    def insert_chapter_image(self):
        encoded = crop_image_file(self, "Sisipkan Gambar ke Bab")
        if not encoded:
            return
        try:
            raw = base64.b64decode(encoded)
            data_uri = f"data:image/png;base64,{encoded}"
            width, ok = QInputDialog.getInt(self, "Ukuran Gambar", "Lebar gambar (px):", 420, 80, 1400, 10)
            if not ok:
                return
            pixmap = QPixmap()
            pixmap.loadFromData(raw)
            self.editor.document().addResource(QTextDocument.ResourceType.ImageResource, QUrl(data_uri), pixmap)
            cursor = self.editor.textCursor()
            image_format = QTextImageFormat()
            image_format.setName(data_uri)
            image_format.setWidth(width)
            cursor.insertImage(image_format)
            self.editor.setTextCursor(cursor)
            self.save_current_chapter()
        except Exception as exc:
            QMessageBox.critical(self, "Gambar gagal disisipkan", str(exc))

    def save_current_chapter(self):
        if self.loading or self.chapter_id is None:
            return
        try:
            current_text = self.editor.toPlainText()
            current_html = self.editor.toHtml()
            # Count newly added words on actual content changes; autosave ticks
            # with unchanged text contribute nothing to the daily writing total.
            with db() as conn:
                previous = conn.execute(
                    "SELECT content FROM chapters WHERE id=? AND project_id=?",
                    (self.chapter_id, self.project_id)
                ).fetchone()
                previous_text = previous[0] if previous else ""
                old_count = len(previous_text.split())
                new_count = len(current_text.split())
                delta = max(0, new_count - old_count)
                if delta and self.project_id is not None:
                    from datetime import date
                    conn.execute(
                        "INSERT INTO writing_activity(project_id,activity_date,words_written) VALUES(?,?,?) "
                        "ON CONFLICT(project_id,activity_date) DO UPDATE SET words_written=words_written+excluded.words_written",
                        (self.project_id, date.today().isoformat(), delta)
                    )
            save_chapter(
                self.chapter_id,
                self.chapter_title,
                current_text,
                current_html
            )
            self.save_label.setText("● Tersimpan lokal")
        except sqlite3.Error as error:
            self.save_label.setText("● Gagal menyimpan")
            print("Database error:", error)

    def toggle_format_shortcut(self, key):
        """Toggle inline formatting from the keyboard without relying on toolbar focus."""
        if key not in self.format_buttons:
            return
        current = self.editor.currentCharFormat()
        if key == "bold":
            enabled = current.fontWeight() < 600
        elif key == "italic":
            enabled = not current.fontItalic()
        elif key == "underline":
            enabled = not current.fontUnderline()
        else:
            enabled = not current.fontStrikeOut()
        self.format_buttons[key].setChecked(enabled)
        self.apply_format(key)

    def apply_format(self, key):
        """Apply rich-text formatting to the selection or current typing position."""
        if key in ("bold", "italic", "underline", "strike"):
            fmt = QTextCharFormat()
            if key == "bold":
                fmt.setFontWeight(700 if self.format_buttons[key].isChecked() else 400)
            elif key == "italic":
                fmt.setFontItalic(self.format_buttons[key].isChecked())
            elif key == "underline":
                fmt.setFontUnderline(self.format_buttons[key].isChecked())
            elif key == "strike":
                fmt.setFontStrikeOut(self.format_buttons[key].isChecked())
            self.editor.mergeCurrentCharFormat(fmt)
        elif key in ("bullet", "number"):
            cursor = self.editor.textCursor()
            current_list = cursor.currentList()
            want_checked = self.format_buttons[key].isChecked()
            if current_list is not None and not want_checked:
                current_list.remove(cursor.block())
            else:
                list_format = QTextListFormat()
                list_format.setStyle(
                    QTextListFormat.Style.ListDisc if key == "bullet"
                    else QTextListFormat.Style.ListDecimal
                )
                cursor.createList(list_format)
            self.editor.setTextCursor(cursor)
        self.update_format_indicators()
        self.editor.setFocus()

    def apply_alignment(self, key):
        alignments = {
            "left": Qt.AlignmentFlag.AlignLeft,
            "center": Qt.AlignmentFlag.AlignHCenter,
            "right": Qt.AlignmentFlag.AlignRight,
            "justify": Qt.AlignmentFlag.AlignJustify,
        }
        alignment = alignments.get(key)
        if alignment is not None:
            self.editor.setAlignment(alignment)
        self.update_format_indicators()
        self.editor.setFocus()

    def _sync_editor_default_font(self):
        """Keep the widget, document default, and empty-editor placeholder in sync."""
        if not hasattr(self, "editor"):
            return
        family = self.font_family_combo.currentText() if hasattr(self, "font_family_combo") else "Garamond"
        raw_size = self.font_size_combo.currentText().strip() if hasattr(self, "font_size_combo") else "12"
        try:
            size = max(6.0, min(200.0, float(raw_size)))
        except (TypeError, ValueError):
            size = 12.0
        base_font = QFont(family or "Garamond")
        base_font.setPointSizeF(size)
        self.editor.setFont(base_font)
        self.editor.document().setDefaultFont(base_font)
        # apply_theme adds the same font family/size to the QTextEdit selector;
        # Qt stylesheet otherwise overrides QWidget.setFont() for placeholder text.
        self.apply_theme()

    def apply_font_family(self, family):
        """Apply the selected font family to selected text, future typing, and placeholder."""
        if not family or not hasattr(self, "editor") or getattr(self, "loading", False):
            return
        fmt = QTextCharFormat()
        fmt.setFontFamily(family)
        self.editor.mergeCurrentCharFormat(fmt)
        self._sync_editor_default_font()
        self.editor.setFocus()

    def apply_heading_level(self, index):
        # Apply paragraph style to the current block, similar to Medium's
        # Paragraph / Heading menu, and keep the choice for the next typing.
        sizes = {0: 12, 1: 30, 2: 24, 3: 20}
        size = sizes.get(index, 12)
        cursor = self.editor.textCursor()
        block_format = QTextBlockFormat()
        if index == 0:
            block_format.setTopMargin(0)
            block_format.setBottomMargin(12)
            block_format.setHeadingLevel(0)
        else:
            block_format.setTopMargin(22 if index == 1 else 16)
            block_format.setBottomMargin(10)
            block_format.setHeadingLevel(index)
        cursor.mergeBlockFormat(block_format)
        char_format = QTextCharFormat()
        char_format.setFontFamily(self.font_family_combo.currentText() or "Garamond")
        char_format.setFontPointSize(size)
        char_format.setFontWeight(QFont.Weight.Bold if index else QFont.Weight.Normal)
        cursor.mergeCharFormat(char_format)
        self.editor.mergeCurrentCharFormat(char_format)
        self.editor.setTextCursor(cursor)
        self.update_format_indicators()
        self.editor.setFocus()

    def apply_font_size(self, *_):
        raw_size = self.font_size_combo.currentText().strip()
        try:
            size = float(raw_size)
            if not 6 <= size <= 200:
                raise ValueError
        except ValueError:
            QMessageBox.warning(self, "Ukuran font tidak valid", "Masukkan ukuran font antara 6 sampai 200 pt.")
            self.update_format_indicators()
            self.editor.setFocus()
            return
        fmt = QTextCharFormat()
        fmt.setFontPointSize(size)
        fmt.setFontFamily(self.font_family_combo.currentText() or "Garamond")
        self.editor.mergeCurrentCharFormat(fmt)
        self._sync_editor_default_font()
        self.update_format_indicators()
        self.editor.setFocus()

    def update_format_indicators(self):
        if not hasattr(self, "format_buttons") or not hasattr(self, "editor"):
            return
        fmt = self.editor.currentCharFormat()
        cursor = self.editor.textCursor()
        current_list = cursor.currentList()
        states = {
            "bold": fmt.fontWeight() >= 600,
            "italic": fmt.fontItalic(),
            "underline": fmt.fontUnderline(),
            "strike": fmt.fontStrikeOut(),
            "bullet": current_list is not None and current_list.format().style() == QTextListFormat.Style.ListDisc,
            "number": current_list is not None and current_list.format().style() == QTextListFormat.Style.ListDecimal,
        }
        for key, button in self.format_buttons.items():
            button.blockSignals(True)
            button.setChecked(states.get(key, False))
            button.blockSignals(False)

        if hasattr(self, "alignment_buttons"):
            alignment = cursor.blockFormat().alignment()
            align_states = {
                "left": bool(alignment & Qt.AlignmentFlag.AlignLeft),
                "center": bool(alignment & Qt.AlignmentFlag.AlignHCenter),
                "right": bool(alignment & Qt.AlignmentFlag.AlignRight),
                "justify": bool(alignment & Qt.AlignmentFlag.AlignJustify),
            }
            # Qt may report a default/zero alignment for a left-aligned paragraph.
            if not any(align_states.values()):
                align_states["left"] = True
            for key, button in self.alignment_buttons.items():
                button.blockSignals(True)
                button.setChecked(align_states[key])
                button.blockSignals(False)

        if hasattr(self, "font_family_combo"):
            # Read the font through QFont instead of the deprecated
            # QTextCharFormat.fontFamily() API. Avoid resetting the native
            # font combo on every cursor movement when the selection is unchanged.
            family = fmt.font().family()
            if family and self.font_family_combo.findText(family) >= 0 and self.font_family_combo.currentText() != family:
                self.font_family_combo.blockSignals(True)
                try:
                    self.font_family_combo.setCurrentText(family)
                finally:
                    self.font_family_combo.blockSignals(False)

        if hasattr(self, "font_size_combo"):
            size = fmt.fontPointSize()
            if size > 0:
                size_text = str(int(size) if float(size).is_integer() else size)
                if self.font_size_combo.currentText() != size_text:
                    self.font_size_combo.blockSignals(True)
                    try:
                        self.font_size_combo.setCurrentText(size_text)
                    finally:
                        self.font_size_combo.blockSignals(False)

        if hasattr(self, "heading_combo"):
            block = cursor.block()
            heading_level = block.blockFormat().headingLevel()
            if heading_level not in (1, 2, 3):
                heading_level = 0
            if self.heading_combo.currentIndex() != heading_level:
                self.heading_combo.blockSignals(True)
                try:
                    self.heading_combo.setCurrentIndex(heading_level)
                finally:
                    self.heading_combo.blockSignals(False)

    def update_word_count(self):
        text = self.editor.toPlainText().strip()
        count = len(text.split()) if text else 0
        self.word_label.setText(f"{count:,} kata")

    # -------------------------
    # CHARACTER & WORLDBUILDING
    # -------------------------

    def refresh_context_lists(self):
        if not hasattr(self, "character_list") or not hasattr(self, "world_list") or self.project_id is None:
            return
        self.character_list.clear()
        self.world_list.clear()
        with db() as conn:
            chars = conn.execute("SELECT id, name, role, details FROM characters WHERE project_id = ? ORDER BY role, name", (self.project_id,)).fetchall()
            worlds = conn.execute("SELECT id, name, category, details FROM worldbuilding WHERE project_id = ? ORDER BY category, name", (self.project_id,)).fetchall()
        # Kelompokkan karakter berdasarkan role, serupa dengan pengelompokan latar.
        current_role = None
        for record_id, name, role, details in chars:
            role = role or "Karakter Pendukung"
            if role != current_role:
                current_role = role
                header = QListWidgetItem(role.upper())
                header.setFlags(Qt.ItemFlag.NoItemFlags)
                header.setData(Qt.ItemDataRole.UserRole, None)
                font = header.font()
                font.setBold(True)
                header.setFont(font)
                # Warna judul kelompok dibuat terang agar jelas di tema gelap.
                header.setForeground(QColor("#c3b8ff"))
                self.character_list.addItem(header)
            item = QListWidgetItem("   " + name)
            item.setData(Qt.ItemDataRole.UserRole, record_id)
            item.setToolTip(f"Role: {role}\nKlik dua kali untuk membuka halaman karakter")
            self.character_list.addItem(item)

        # Kelompokkan latar berdasarkan kategori supaya daftar panjang tetap rapi.
        current_category = None
        for record_id, name, category, details in worlds:
            if category != current_category:
                current_category = category
                header = QListWidgetItem(category.upper())
                header.setFlags(Qt.ItemFlag.NoItemFlags)
                header.setData(Qt.ItemDataRole.UserRole, None)
                # Gunakan warna terang yang sama dengan judul kelompok karakter.
                header.setForeground(QColor("#c3b8ff"))
                font = header.font()
                font.setBold(True)
                header.setFont(font)
                self.world_list.addItem(header)
            item = QListWidgetItem("   " + name)
            item.setData(Qt.ItemDataRole.UserRole, record_id)
            item.setToolTip("Klik dua kali untuk membuka halaman latar")
            self.world_list.addItem(item)

        if getattr(self, "reference_record_id", None) is not None:
            self.show_reference_for_active_record()

    def add_record(self, kind):
        if self.project_id is None:
            QMessageBox.information(self, "Stomake", "Pilih atau buat cerita terlebih dahulu.")
            return
        self.open_record_window(kind)

    def edit_record_from_item(self, kind, item):
        if item is None or self.project_id is None:
            return
        record_id = item.data(Qt.ItemDataRole.UserRole)
        if record_id is None:
            return
        self.open_record_window(kind, record_id)

    def open_record_window(self, kind, record_id=None):
        """Buka editor karakter/latar sebagai jendela mengambang non-modal.

        Jendela dapat dipindahkan, diubah ukurannya, diminimalkan, dan tetap
        membiarkan pengguna mengetik di editor cerita di belakangnya.
        """
        if kind not in ("characters", "worldbuilding") or self.project_id is None:
            return

        key = (self.project_id, kind, record_id)
        existing = self.record_windows.get(key)
        if existing is not None:
            try:
                if existing.isVisible():
                    existing.showNormal()
                    existing.raise_()
                    existing.activateWindow()
                    return
            except RuntimeError:
                pass

        is_character = kind == "characters"
        record = None
        if record_id is not None:
            with db() as conn:
                if is_character:
                    record = conn.execute(
                        "SELECT name, role, details FROM characters WHERE id = ? AND project_id = ?",
                        (record_id, self.project_id)
                    ).fetchone()
                else:
                    record = conn.execute(
                        "SELECT name, category, details FROM worldbuilding WHERE id = ? AND project_id = ?",
                        (record_id, self.project_id)
                    ).fetchone()
            if not record:
                return

        window = QDialog(self, Qt.WindowType.Window)
        window.setWindowTitle(("Karakter" if is_character else "Latar / Worldbuilding") + (f" — {record[0]}" if record else " — Baru"))
        window.resize(520, 560)
        window.setMinimumSize(380, 360)
        window.setModal(False)
        layout = QVBoxLayout(window)
        layout.setContentsMargins(14, 14, 14, 14)
        layout.setSpacing(8)

        form = QFormLayout()
        name_input = QLineEdit(record[0] if record else "")
        name_input.setPlaceholderText("Nama karakter atau latar")
        form.addRow("Nama:", name_input)

        role_input = None
        category_input = None
        if is_character:
            role_input = QComboBox()
            role_input.setEditable(True)
            role_input.addItems([
                "Protagonis", "Antagonis", "Deuteragonis", "Tritagonis",
                "Karakter Pendukung", "Mentor", "Love Interest", "Sidekick",
                "Figuran", "Lainnya"
            ])
            role_input.setCurrentText((record[1] if record else "Karakter Pendukung") or "Karakter Pendukung")
            role_input.setToolTip("Pilih role atau ketik role buatan sendiri")
            form.addRow("Role:", role_input)
        else:
            category_input = QComboBox()
            category_input.setEditable(True)
            category_input.addItems(["Lore", "Lokasi", "Faksi", "Sistem Kekuatan", "Benda", "Sejarah", "Lainnya"])
            category_input.setCurrentText((record[1] if record else "Lore") or "Lore")
            form.addRow("Kategori:", category_input)

        layout.addLayout(form)
        layout.addWidget(QLabel("Detail / Deskripsi:"))
        details_input = QTextEdit()
        details_input.setPlaceholderText("Tulis detail karakter atau latar di sini...")
        details_input.setPlainText((record[2] if record else "") or "")
        layout.addWidget(details_input, 1)

        button_row = QHBoxLayout()
        button_row.addStretch()
        save_button = QPushButton("Simpan")
        save_button.setObjectName("primary")
        close_button = QPushButton("Tutup")
        button_row.addWidget(close_button)
        button_row.addWidget(save_button)
        layout.addLayout(button_row)

        key_holder = [key]

        def save_record():
            nonlocal record_id
            name = name_input.text().strip()
            if not name:
                QMessageBox.warning(window, "Stomake", "Nama tidak boleh kosong.")
                name_input.setFocus()
                return
            details = details_input.toPlainText().strip()
            with db() as conn:
                if is_character:
                    role = role_input.currentText().strip() or "Karakter Pendukung"
                    if record_id is None:
                        cur = conn.execute(
                            "INSERT INTO characters (project_id, name, role, details) VALUES (?, ?, ?, ?)",
                            (self.project_id, name, role, details)
                        )
                        record_id = cur.lastrowid
                    else:
                        conn.execute(
                            "UPDATE characters SET name = ?, role = ?, details = ? WHERE id = ? AND project_id = ?",
                            (name, role, details, record_id, self.project_id)
                        )
                else:
                    category = category_input.currentText().strip() or "Lore"
                    if record_id is None:
                        cur = conn.execute(
                            "INSERT INTO worldbuilding (project_id, name, category, details) VALUES (?, ?, ?, ?)",
                            (self.project_id, name, category, details)
                        )
                        record_id = cur.lastrowid
                    else:
                        conn.execute(
                            "UPDATE worldbuilding SET name = ?, category = ?, details = ? WHERE id = ? AND project_id = ?",
                            (name, category, details, record_id, self.project_id)
                        )
            # Sesuaikan judul jendela sesudah penyimpanan; daftar terkelompok ikut diperbarui.
            window.setWindowTitle(("Karakter" if is_character else "Latar / Worldbuilding") + f" — {name}")
            # Jendela tambah berubah menjadi jendela edit setelah record pertama disimpan.
            new_key = (self.project_id, kind, record_id)
            if key_holder[0] != new_key:
                self.record_windows.pop(key_holder[0], None)
                self.record_windows[new_key] = window
                key_holder[0] = new_key
            self.refresh_context_lists()
            self.save_label.setText("● Data karakter/latar tersimpan")

        save_button.clicked.connect(save_record)
        close_button.clicked.connect(window.close)
        window.finished.connect(lambda _result: self.record_windows.pop(key_holder[0], None))
        self.record_windows[key] = window
        window.show()
        window.raise_()
        window.activateWindow()

    def save_record_form(self, return_to_cerita=False):
        if self.project_id is None or self.active_records_kind not in ("characters", "worldbuilding"):
            return False
        name = self.record_name_input.text().strip()
        if not name:
            QMessageBox.warning(self, "Stomake", "Nama tidak boleh kosong.")
            self.record_name_input.setFocus()
            return False

        details = self.record_details_input.toPlainText()
        is_character = self.active_records_kind == "characters"
        category = self.record_category_input.currentText().strip() or "Lore"
        role = self.record_role_input.currentText().strip() or "Karakter Pendukung"
        with db() as conn:
            if is_character:
                if self.active_record_id is None:
                    cur = conn.execute(
                        "INSERT INTO characters (project_id, name, role, details) VALUES (?, ?, ?, ?)",
                        (self.project_id, name, role, details)
                    )
                    self.active_record_id = cur.lastrowid
                else:
                    conn.execute(
                        "UPDATE characters SET name = ?, role = ?, details = ? WHERE id = ? AND project_id = ?",
                        (name, role, details, self.active_record_id, self.project_id)
                    )
            else:
                if self.active_record_id is None:
                    cur = conn.execute(
                        "INSERT INTO worldbuilding (project_id, name, category, details) VALUES (?, ?, ?, ?)",
                        (self.project_id, name, category, details)
                    )
                    self.active_record_id = cur.lastrowid
                else:
                    conn.execute(
                        "UPDATE worldbuilding SET name = ?, details = ?, category = ? WHERE id = ? AND project_id = ?",
                        (name, details, category, self.active_record_id, self.project_id)
                    )

        self.record_page_heading.setText(("Karakter: " if is_character else "Latar: ") + name)
        self.chapter_heading.setText(self.record_page_heading.text())
        self.refresh_context_lists()
        self.save_label.setText("● Data karakter/latar tersimpan")
        if return_to_cerita:
            self.reference_record_kind = self.active_records_kind
            self.reference_record_id = self.active_record_id
            self.show_reference_for_active_record()
            self.center_stack.setCurrentWidget(self.chapter_page)
            self.chapter_heading.setText(self.chapter_title or "Editor Cerita")
        return True

    def minimize_record_page(self):
        # Form tambah yang masih kosong boleh ditutup tanpa membuat data kosong.
        if self.active_record_id is None and not self.record_name_input.text().strip():
            self.center_stack.setCurrentWidget(self.chapter_page)
            self.chapter_heading.setText(self.chapter_title or "Editor Cerita")
            self.active_records_kind = None
            self.active_record_id = None
            return
        # Simpan sebelum mengecilkan halaman agar perubahan tidak hilang.
        self.save_record_form(return_to_cerita=True)

    def restore_reference_page(self):
        """Buka kembali halaman edit dari panel referensi yang sedang diminimize."""
        kind = self.reference_record_kind
        record_id = self.reference_record_id
        if kind not in ("characters", "worldbuilding") or record_id is None:
            return
        is_character = kind == "characters"
        with db() as conn:
            if is_character:
                row = conn.execute(
                    "SELECT name, details, role FROM characters WHERE id = ? AND project_id = ?",
                    (record_id, self.project_id)
                ).fetchone()
                category = None
                role = row[2] if row else "Karakter Pendukung"
            else:
                row = conn.execute(
                    "SELECT name, details, category FROM worldbuilding WHERE id = ? AND project_id = ?",
                    (record_id, self.project_id)
                ).fetchone()
                category = row[2] if row else "Lore"
                role = "Karakter Pendukung"
        if not row:
            self.hide_reference_panel()
            return
        self.active_records_kind = kind
        self.active_record_id = record_id
        self.record_name_input.setText(row[0])
        self.record_details_input.setPlainText(row[1] or "")
        self.record_page_heading.setText(("Karakter: " if is_character else "Latar: ") + row[0])
        self.record_role_label.setVisible(is_character)
        self.record_role_input.setVisible(is_character)
        self.record_category_label.setVisible(not is_character)
        self.record_category_input.setVisible(not is_character)
        if is_character:
            self.record_role_input.setCurrentText(role or "Karakter Pendukung")
        else:
            self.record_category_input.setCurrentText(category or "Lore")
        self.center_stack.setCurrentWidget(self.record_page)
        self.chapter_heading.setText(self.record_page_heading.text())

    def show_reference_for_active_record(self):
        if self.reference_record_kind not in ("characters", "worldbuilding") or self.reference_record_id is None:
            return
        is_character = self.reference_record_kind == "characters"
        with db() as conn:
            if is_character:
                row = conn.execute(
                    "SELECT name, details, role FROM characters WHERE id = ? AND project_id = ?",
                    (self.reference_record_id, self.project_id)
                ).fetchone()
                category = None
                role = row[2] if row else "Karakter Pendukung"
            else:
                row = conn.execute(
                    "SELECT name, details, category FROM worldbuilding WHERE id = ? AND project_id = ?",
                    (self.reference_record_id, self.project_id)
                ).fetchone()
                category = row[2] if row else None
        if not row:
            return
        name, details = row[0], row[1] or ""
        heading_prefix = f"{role or 'Karakter Pendukung'}: " if is_character else f"{category or 'Latar'}: "
        self.reference_heading.setText(heading_prefix + name)
        self.reference_details.setPlainText(details or "Belum ada detail.")
        self.reference_panel.show()
        self.editor_splitter.setSizes([max(350, self.editor_splitter.width() - 250), 250])

    def hide_reference_panel(self):
        self.reference_panel.hide()
        self.editor_splitter.setSizes([self.editor_splitter.width(), 0])

    def run_quick_prompt(self, prompt):
        self.prompt.setPlainText(prompt)
        self.ask_ai()

    # -------------------------
    # AI CONTEXT
    # -------------------------

    def build_story_context(self):
        with db() as conn:
            characters = conn.execute("""
                SELECT name, role, details FROM characters
                WHERE project_id = ? ORDER BY role, name
            """, (self.project_id,)).fetchall()

            world_items = conn.execute("""
                SELECT name, category, details FROM worldbuilding
                WHERE project_id = ? ORDER BY category, name
            """, (self.project_id,)).fetchall()

        character_context = "\n".join(
            f"- [{role or 'Karakter Pendukung'}] {name}: {details}"
            for name, role, details in characters
        ) or "Belum ada data karakter."

        world_context = "\n".join(
            f"- [{category}] {name}: {details}"
            for name, category, details in world_items
        ) or "Belum ada data worldbuilding."

        # Include the extensible Story/World Bible and its chapter/relationship links.
        with db() as conn:
            records = conn.execute(
                "SELECT id, kind, name, category, details, status, metadata FROM story_records WHERE project_id=? ORDER BY kind, name",
                (self.project_id,)
            ).fetchall()
            linked = conn.execute(
                "SELECT src.name, ch.title FROM story_record_chapters rc JOIN story_records src ON src.id=rc.record_id JOIN chapters ch ON ch.id=rc.chapter_id WHERE src.project_id=? ORDER BY src.name, ch.position",
                (self.project_id,)
            ).fetchall()
            relationships = conn.execute(
                "SELECT a.name, r.relation, b.name, r.notes FROM character_relationships r JOIN story_records a ON a.id=r.source_id JOIN story_records b ON b.id=r.target_id WHERE r.project_id=? ORDER BY a.name, b.name",
                (self.project_id,)
            ).fetchall()
        extra_records = []
        for rid, kind, name, category, details, status, raw_meta in records:
            try:
                meta = json.loads(raw_meta or "{}")
            except (TypeError, json.JSONDecodeError):
                meta = {}
            profile = "; ".join(f"{k}: {v}" for k, v in meta.items() if v)
            extra_records.append(f"- [{kind} / {category}] {name}; trait/tag: {status}; detail: {details}; {profile}")
        linked_context = "\n".join(f"- {name} terkait dengan {chapter}" for name, chapter in linked) or "Belum ada tautan bab."
        relationship_context = "\n".join(f"- {a} — {relation} — {b}" + (f" ({notes})" if notes else "") for a, relation, b, notes in relationships) or "Belum ada hubungan karakter yang dicatat."
        return (
            f"Judul cerita: {self.project_title}\n"
            f"Bab aktif: {self.chapter_title}\n\n"
            f"KARAKTER LAMA:\n{character_context}\n\n"
            f"WORLDBUILDING LAMA:\n{world_context}\n\n"
            f"STORY BIBLE / WORLD BIBLE:\n" + ("\n".join(extra_records) or "Belum ada entri.") +
            f"\n\nTAUTAN BAB:\n{linked_context}\n\nHUBUNGAN KARAKTER:\n{relationship_context}\n\n"
            f"ISI BAB AKTIF:\n{self.editor.toPlainText()[-5000:]}"
        )

    # -------------------------
    # AI ASSISTANT
    # -------------------------

    def _save_ai_conversation(self):
        if self.project_id is None or not self.ai_messages:
            return
        title = next((m["content"][:50] for m in self.ai_messages if m["role"] == "user"), "Percakapan Stomake")
        with db() as conn:
            if self.ai_conversation_id is None:
                cur = conn.execute("INSERT INTO ai_conversations(project_id,title,messages,updated_at) VALUES(?,?,?,CURRENT_TIMESTAMP)", (self.project_id,title,json.dumps(self.ai_messages,ensure_ascii=False)))
                self.ai_conversation_id = cur.lastrowid
            else:
                conn.execute("UPDATE ai_conversations SET title=?,messages=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND project_id=?", (title,json.dumps(self.ai_messages,ensure_ascii=False),self.ai_conversation_id,self.project_id))

    def show_ai_history(self):
        if self.project_id is None:
            return
        dialog = QDialog(self); dialog.setWindowTitle("Riwayat Chat AI"); dialog.resize(520,560)
        layout = QVBoxLayout(dialog); history = QListWidget(); layout.addWidget(history,1)
        with db() as conn:
            rows = conn.execute("SELECT id,title,updated_at FROM ai_conversations WHERE project_id=? ORDER BY updated_at DESC",(self.project_id,)).fetchall()
        for cid,title,updated in rows:
            item=QListWidgetItem(f"{title}\n{updated}");item.setData(Qt.ItemDataRole.UserRole,cid);history.addItem(item)
        actions=QHBoxLayout(); load_btn=QPushButton("Buka Chat"); delete_btn=QPushButton("Hapus Chat"); close_btn=QPushButton("Tutup")
        actions.addWidget(load_btn);actions.addWidget(delete_btn);actions.addStretch();actions.addWidget(close_btn);layout.addLayout(actions)
        def load_selected():
            item=history.currentItem()
            if not item:return
            cid=item.data(Qt.ItemDataRole.UserRole)
            with db() as conn: row=conn.execute("SELECT messages FROM ai_conversations WHERE id=?",(cid,)).fetchone()
            if row:
                try:self.ai_messages=json.loads(row[0] or "[]")
                except Exception:self.ai_messages=[]
                self.ai_conversation_id=cid;self.chat.clear()
                for message in self.ai_messages:self.chat.append(("KAMU:" if message.get("role")=="user" else "Stomake:")+"\n"+message.get("content","")+"\n")
                dialog.accept()
        def delete_selected():
            item=history.currentItem()
            if not item:return
            cid=item.data(Qt.ItemDataRole.UserRole)
            with db() as conn:conn.execute("DELETE FROM ai_conversations WHERE id=? AND project_id=?",(cid,self.project_id))
            if cid==self.ai_conversation_id:self.ai_conversation_id=None;self.ai_messages=[];self.chat.clear()
            history.takeItem(history.row(item))
        load_btn.clicked.connect(load_selected);delete_btn.clicked.connect(delete_selected);close_btn.clicked.connect(dialog.reject)
        history.itemDoubleClicked.connect(lambda _item:load_selected())
        dialog.exec()

    def refresh_ollama_models(self):
        """Periksa dua model pilihan tanpa menambahkan model lain ke dropdown."""
        allowed = ["llama3.2:3b", "qwen2.5:3b"]
        try:
            response = requests.get("http://localhost:11434/api/tags", timeout=5)
            response.raise_for_status()
            installed = {item.get("name", "") for item in response.json().get("models", [])}
            available = [name for name in allowed if name in installed or any(tag.startswith(name + ":") for tag in installed)]
            missing = [name for name in allowed if name not in available]
            message = "Model tersedia: " + (", ".join(available) if available else "belum ada")
            if missing:
                message += "\nBelum terpasang: " + ", ".join(missing)
                message += "\nUnduh melalui terminal dengan `ollama pull nama-model`."
            QMessageBox.information(self, "Model Ollama", message)
        except Exception as exc:
            QMessageBox.warning(self, "Ollama tidak tersedia", f"Tidak bisa memeriksa model. Pastikan Ollama berjalan.\n\n{exc}")

    def backup_restore_menu(self):
        menu=QMenu(self)
        backup=menu.addAction("Buat Backup Database (.db)")
        restore=menu.addAction("Pulihkan Backup Database (.db)")
        selected=menu.exec(self.sidebar.mapToGlobal(self.sidebar.rect().center()))
        if selected==backup:
            self.save_current_chapter()
            path,_=QFileDialog.getSaveFileName(self,"Backup Stomake",str(Path.home()/"Stomake-Backup.db"),"SQLite Database (*.db)")
            if path:
                import shutil
                shutil.copy2(DB_PATH,path);QMessageBox.information(self,"Backup","Backup berhasil disimpan.")
        elif selected==restore:
            path,_=QFileDialog.getOpenFileName(self,"Pulihkan Backup Stomake","","SQLite Database (*.db)")
            if not path:return
            if QMessageBox.question(self,"Pulihkan Backup","Database saat ini akan diganti. Lanjutkan?",QMessageBox.StandardButton.Yes|QMessageBox.StandardButton.No)!=QMessageBox.StandardButton.Yes:return
            import shutil
            shutil.copy2(path,DB_PATH)
            QMessageBox.information(self,"Backup dipulihkan","Database dipulihkan. Tutup dan buka kembali Stomake agar semua halaman memuat ulang.")

    def ask_ai(self):
        prompt = self.prompt.toPlainText().strip()
        if not prompt:
            QMessageBox.information(self, "Stomake", "Masukkan perintah untuk AI.")
            return
        if self.worker and self.worker.isRunning():
            return
        context = self.build_story_context()
        self.ai_messages.append({"role":"user","content":prompt})
        self._save_ai_conversation()
        self.chat.append(f"\nKAMU:\n{prompt}\n")
        self.chat.append("Stomake: Sedang berpikir...\n")
        self.prompt.clear()
        self.send_button.setEnabled(False)
        self.send_button.setText("Memproses...")
        selected_model = self.ai_model_combo.currentText().strip() or MODEL_NAME
        self.worker = AIWorker(prompt, context, selected_model)
        self.worker.success.connect(self.ai_success)
        self.worker.failure.connect(self.ai_failure)
        self.worker.finished.connect(self.ai_finished)
        self.worker.start()

    def clear_chat(self):
        answer = QMessageBox.question(
            self, "Chat Baru", "Mulai percakapan baru? Riwayat sebelumnya tetap tersimpan.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.chat.clear();self.last_answer="";self.ai_messages=[];self.ai_conversation_id=None

    def ai_success(self, answer):
        self.last_answer = answer
        self.ai_messages.append({"role":"assistant","content":answer})
        self._save_ai_conversation()
        self.chat.append(f"Stomake:\n{answer}\n")
        self.chat.append("-" * 32)

    def ai_failure(self, message):
        self.ai_messages.append({"role":"assistant","content":"Gagal: "+message})
        self._save_ai_conversation()
        self.chat.append(f"ERROR:\n{message}\n")

    def ai_finished(self):
        self.send_button.setEnabled(True)
        self.send_button.setText("Kirim ke AI ➤")

    def insert_answer(self):
        if not self.last_answer:
            QMessageBox.information(
                self, "Stomake", "Belum ada jawaban AI."
            )
            return
        self.editor.insertPlainText(self.last_answer)
        self.editor.setFocus()

    # -------------------------
    # THEME
    # -------------------------

    def set_theme(self, name):
        settings = QSettings("GendingLokananta", "StomakePC")
        settings.setValue("theme", name)
        self.apply_theme(name)

    def apply_theme(self, name=None):
        settings = QSettings("GendingLokananta", "StomakePC")
        theme = name or settings.value("theme", "Dark")
        if hasattr(self, "theme_combo") and self.theme_combo.currentText() != theme:
            self.theme_combo.blockSignals(True)
            self.theme_combo.setCurrentText(theme)
            self.theme_combo.blockSignals(False)
        palettes = {
            "Dark": {"bg":"#15131a","panel":"#211e27","field":"#19171f","fg":"#f2edf7","muted":"#b7aebd","accent":"#c9a7f5","accent_fg":"#241a30","border":"#51475a","hover":"#30293a","selection":"#684c86"},
            "Light": {"bg":"#f4f2f7","panel":"#ffffff","field":"#ffffff","fg":"#211d27","muted":"#696372","accent":"#7350a4","accent_fg":"#ffffff","border":"#d1cbd9","hover":"#ece6f3","selection":"#d9c7ee"},
            "Warm Light": {"bg":"#f7f0e5","panel":"#fffaf1","field":"#fffdf8","fg":"#34291f","muted":"#786858","accent":"#9a6941","accent_fg":"#ffffff","border":"#ddcbb7","hover":"#f0e4d5","selection":"#e8d2bb"},
            "Slate": {"bg":"#202630","panel":"#29323e","field":"#222b36","fg":"#e8edf4","muted":"#aab6c5","accent":"#83a8c9","accent_fg":"#17212c","border":"#485667","hover":"#344252","selection":"#405c78"},
        }
        c = palettes.get(theme, palettes["Dark"])
        editor_family = self.font_family_combo.currentText() if hasattr(self, "font_family_combo") else "Garamond"
        try:
            editor_size = float(self.font_size_combo.currentText()) if hasattr(self, "font_size_combo") else 12.0
        except (TypeError, ValueError):
            editor_size = 12.0
        self.setStyleSheet(f"""
            QWidget {{ background: {c['bg']}; color: {c['fg']}; font-family: 'Segoe UI'; font-size: 13px; }}
            QLabel {{ background: transparent; color: {c['fg']}; }}
            QToolButton {{ background: {c['field']}; color: {c['fg']}; border: 1px solid {c['border']}; border-radius: 7px; padding: 7px 12px; }}
            QToolButton:hover {{ background: {c['hover']}; border-color: {c['accent']}; }}
            QFrame#storyCard {{ background: {c['panel']}; border: 1px solid {c['border']}; border-radius: 10px; }}
            QFrame#storyCard QLabel {{ background: transparent; }}
            QFrame#sidebar {{ background: {c['panel']}; border-right: 1px solid {c['border']}; }}
            QFrame#sidebar QLabel {{ background: transparent; color: {c['fg']}; }}
            QLabel#logo {{ color: {c['accent']}; font-size: 22px; font-weight: 750; }}
            QLabel#heading {{ color: {c['fg']}; font-size: 19px; font-weight: 700; }}
            QLabel#muted {{ color: {c['muted']}; }}
            QLabel#sectionLabel {{ color: {c['muted']}; font-size: 10px; font-weight: 700; letter-spacing: 1px; padding-top: 8px; }}
            QFrame#panel {{ background: {c['panel']}; border: 1px solid {c['border']}; border-radius: 7px; }}
            QPushButton, QComboBox, QListWidget, QLineEdit, QSpinBox, QDateEdit, QTableWidget, QTableView, QTreeWidget, QTreeView, QPlainTextEdit {{
                background-color: {c['field']}; color: {c['fg']}; border: 1px solid {c['border']}; border-radius: 5px; padding: 6px 9px; selection-background-color: {c['selection']}; selection-color: {c['fg']};
            }}
            QComboBox QLineEdit, QComboBox QAbstractSpinBox {{ background-color: {c['field']}; color: {c['fg']}; border: none; padding: 2px 4px; }}
            QTableWidget, QTableView {{ gridline-color: {c['border']}; alternate-background-color: {c['panel']}; }}
            QHeaderView::section {{ background-color: {c['panel']}; color: {c['fg']}; border: 1px solid {c['border']}; padding: 7px; font-weight: 600; }}
            QTabWidget::pane {{ background-color: {c['panel']}; border: 1px solid {c['border']}; }}
            QTabBar::tab {{ background-color: {c['field']}; color: {c['fg']}; border: 1px solid {c['border']}; padding: 7px 12px; }}
            QTabBar::tab:selected {{ background-color: {c['hover']}; color: {c['fg']}; border-bottom: 2px solid {c['accent']}; }}
            QGroupBox {{ background-color: {c['panel']}; color: {c['fg']}; border: 1px solid {c['border']}; border-radius: 5px; margin-top: 10px; padding: 8px; }}
            QCheckBox, QRadioButton {{ background: transparent; color: {c['fg']}; spacing: 7px; }}
            QFormLayout QLabel, QDialog QLabel {{ background: transparent; color: {c['fg']}; }}
            QInputDialog, QMessageBox, QDialog {{ background-color: {c['panel']}; color: {c['fg']}; }}
            QPushButton:hover, QComboBox:hover {{ border-color: {c['accent']}; background: {c['hover']}; }}
            QComboBox {{ border-radius: 17px; padding: 5px 12px; min-height: 26px; }}
            QComboBox::drop-down {{ subcontrol-origin: padding; subcontrol-position: right center; width: 26px; height: 26px; border: none; border-radius: 13px; background: {c['hover']}; margin: 4px; }}
            QComboBox::down-arrow {{ width: 8px; height: 8px; }}
            QComboBox {{ min-height: 30px; padding: 4px 30px 4px 10px; }}
            QWidget#mediumToolbar {{ background: transparent; border: none; }}
            QWidget#editorCanvas {{ background: {c['bg']}; border: none; }}
            QLabel#toolbarLabel {{ background: transparent; color: {c['muted']}; font-size: 12px; font-weight: 600; padding: 0 2px; }}
            QTextEdit#mediumEditor {{ background: {c['bg']}; color: {c['fg']}; border: none; border-radius: 0px; padding: 52px 68px; font-family: '{editor_family}'; font-size: {editor_size}pt; selection-background-color: {c['selection']}; }}
            QPushButton:pressed {{ background: {c['selection']}; }}
            QPushButton:checked {{ background: {c['hover']}; color: {c['fg']}; border: 1px solid {c['accent']}; }}
            QPushButton#primary {{ background: {c['accent']}; color: {c['accent_fg']}; border: 1px solid {c['accent']}; font-weight: 700; }}
            QPushButton#primary:hover {{ background: {c['selection']}; }}
            QPushButton#navButton {{ text-align: left; border: 1px solid transparent; padding: 6px 8px; min-height: 18px; }}
            QPushButton#navButton:checked {{ background: {c['hover']}; color: {c['fg']}; border-left: 2px solid {c['accent']}; border-radius: 3px; }}
            QPushButton#iconButton {{ padding: 0; min-height: 0; border-radius: 5px; font-size: 18px; }}
            QComboBox QAbstractItemView {{ background: {c['panel']}; color: {c['fg']}; selection-background-color: {c['selection']}; selection-color: {c['fg']}; }}
            QTextEdit {{ background: {c['field']}; color: {c['fg']}; border: 1px solid {c['border']}; border-radius: 5px; padding: 9px; selection-background-color: {c['selection']}; }}
            QTextEdit:focus, QLineEdit:focus, QListWidget:focus {{ border: 1px solid {c['accent']}; }}
            QListWidget {{ padding: 4px; outline: none; }}
            QListWidget::item {{ padding: 8px; border-radius: 3px; }}
            QListWidget::item:hover {{ background: {c['hover']}; }}
            QListWidget::item:selected {{ background: {c['selection']}; color: {c['fg']}; border-left: 2px solid {c['accent']}; }}
            QSplitter::handle {{ background: {c['border']}; width: 3px; }}
            QSplitter::handle:hover {{ background: {c['accent']}; }}
            QScrollArea#bibleScroll {{ background: transparent; border: none; }}
            QScrollArea#bibleScroll QWidget {{ background: transparent; }}
            QScrollBar:vertical {{ background: {c['bg']}; width: 9px; margin: 2px; }}
            QScrollBar::handle:vertical {{ background: {c['border']}; min-height: 24px; border-radius: 4px; }}
            QScrollBar::handle:vertical:hover {{ background: {c['accent']}; }}
            QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical {{ height: 0; }}
            QMenu {{ background: {c['panel']}; color: {c['fg']}; border: 1px solid {c['border']}; padding: 5px; }}
            QMenu::item {{ padding: 7px 18px; }}
            QMenu::item:selected {{ background: {c['hover']}; color: {c['fg']}; }}
            QToolTip {{ background: {c['panel']}; color: {c['fg']}; border: 1px solid {c['border']}; padding: 5px; }}
        """)

    def closeEvent(self, event):
        self.save_current_chapter()
        if self.worker and self.worker.isRunning():
            self.worker.wait()
        event.accept()


if __name__ == "__main__":
    app = QApplication(sys.argv)
    setup_database()
    window = Stomake()
    window.show()
    sys.exit(app.exec())
