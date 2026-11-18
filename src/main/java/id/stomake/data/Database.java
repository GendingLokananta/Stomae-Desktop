package id.stomake.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQLite access and additive migrations for Stomake 1.x databases. */
public final class Database {
    private final Path file;

    public Database(Path file) throws SQLException, IOException {
        this.file = file.toAbsolutePath().normalize();
        Files.createDirectories(this.file.getParent());
        initialize();
    }

    public Path file() {
        return file;
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    private void initialize() throws SQLException {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("CREATE TABLE IF NOT EXISTS projects (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS chapters (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', position INTEGER NOT NULL DEFAULT 1, FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS characters (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'Karakter Pendukung', details TEXT NOT NULL DEFAULT '', FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS worldbuilding (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL DEFAULT 'Lore', details TEXT NOT NULL DEFAULT '', FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS manuscript (id INTEGER PRIMARY KEY, title TEXT NOT NULL, content TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS custom_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, section TEXT NOT NULL, name TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(section, name))");
            s.execute("CREATE TABLE IF NOT EXISTS custom_options (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL DEFAULT 0, field_key TEXT NOT NULL, value TEXT NOT NULL, UNIQUE(project_id, field_key, value))");
            s.execute("CREATE TABLE IF NOT EXISTS story_records (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, category TEXT NOT NULL DEFAULT '', details TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS story_record_chapters (record_id INTEGER NOT NULL, chapter_id INTEGER NOT NULL, PRIMARY KEY(record_id, chapter_id), FOREIGN KEY(record_id) REFERENCES story_records(id) ON DELETE CASCADE, FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS character_relationships (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, source_id INTEGER NOT NULL, target_id INTEGER NOT NULL, relation TEXT NOT NULL DEFAULT 'Terhubung', notes TEXT NOT NULL DEFAULT '', UNIQUE(source_id, target_id, relation), FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE, FOREIGN KEY(source_id) REFERENCES story_records(id) ON DELETE CASCADE, FOREIGN KEY(target_id) REFERENCES story_records(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS timeline_events (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, title TEXT NOT NULL, event_date TEXT NOT NULL DEFAULT '', description TEXT NOT NULL DEFAULT '', chapter_id INTEGER, character_names TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE, FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE SET NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS chapter_versions (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, chapter_id INTEGER NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', content_html TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE, FOREIGN KEY(chapter_id) REFERENCES chapters(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS writing_goals (project_id INTEGER PRIMARY KEY, daily_word_goal INTEGER NOT NULL DEFAULT 500, FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS writing_activity (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, activity_date TEXT NOT NULL, words_written INTEGER NOT NULL DEFAULT 0, UNIQUE(project_id, activity_date), FOREIGN KEY(project_id) REFERENCES projects(id) ON DELETE CASCADE)");
            s.execute("CREATE TABLE IF NOT EXISTS ai_conversations (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id INTEGER NOT NULL, title TEXT NOT NULL, messages TEXT NOT NULL DEFAULT '[]', updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS ai_characters (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, role TEXT NOT NULL DEFAULT 'Karakter original', description TEXT NOT NULL DEFAULT '', personality TEXT NOT NULL DEFAULT '', background TEXT NOT NULL DEFAULT '', appearance TEXT NOT NULL DEFAULT '', speaking_style TEXT NOT NULL DEFAULT '', scenario TEXT NOT NULL DEFAULT '', greeting TEXT NOT NULL DEFAULT '', extra_instructions TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS ai_character_messages (id INTEGER PRIMARY KEY AUTOINCREMENT, character_id INTEGER NOT NULL, role TEXT NOT NULL CHECK(role IN ('user','assistant')), content TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(character_id) REFERENCES ai_characters(id) ON DELETE CASCADE)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_ai_character_messages_character ON ai_character_messages(character_id, id)");
            s.execute("CREATE TABLE IF NOT EXISTS app_settings (setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)");
        }
        migrateColumns();
        migrateLegacyContentTables();
        seedFromManuscriptIfNeeded();
    }

    private void migrateColumns() throws SQLException {
        ensureColumn("projects", "cover_image", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("projects", "synopsis", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("chapters", "content_html", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("chapters", "subtitle", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("chapters", "image_data", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("chapters", "gallery_data", "TEXT NOT NULL DEFAULT '[]'");
        ensureColumn("chapters", "text_align", "TEXT NOT NULL DEFAULT 'left'");
        ensureColumn("characters", "role", "TEXT NOT NULL DEFAULT 'Karakter Pendukung'");
        ensureColumn("worldbuilding", "category", "TEXT NOT NULL DEFAULT 'Lore'");
        ensureColumn("story_records", "metadata", "TEXT NOT NULL DEFAULT '{}'");
        ensureColumn("story_records", "custom_traits", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("story_records", "role", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("story_records", "image_data", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("story_records", "image_width", "REAL NOT NULL DEFAULT 1.0");
        ensureColumn("story_records", "gallery_data", "TEXT NOT NULL DEFAULT '[]'");
        // V2 persona details. Existing V1 fields are kept untouched.
        ensureColumn("ai_characters", "traits", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("ai_characters", "relationship_to_user", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("ai_characters", "motivations", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("ai_characters", "flaws", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("ai_characters", "secrets", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("ai_characters", "memory_notes", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("ai_characters", "model_name", "TEXT NOT NULL DEFAULT ''");
        ensureColumn("ai_characters", "temperature", "REAL NOT NULL DEFAULT 0.95");
    }

    /** Bring records stored by older Stomake editions into the unified Story Bible.
     *  The migration is idempotent and leaves the original tables untouched.
     */
    private void migrateLegacyContentTables() throws SQLException {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                INSERT INTO story_records(project_id,kind,name,category,details,role,metadata,custom_traits)
                SELECT old.project_id,'character',old.name,'Karakter',COALESCE(old.details,''),
                       COALESCE(old.role,'Karakter Pendukung'),'{}',''
                FROM characters old
                WHERE NOT EXISTS (
                    SELECT 1 FROM story_records current
                    WHERE current.project_id=old.project_id
                      AND current.kind IN ('character','characters')
                      AND current.name=old.name
                )
                """);
            s.executeUpdate("""
                INSERT INTO story_records(project_id,kind,name,category,details,role,metadata,custom_traits)
                SELECT old.project_id,
                       CASE lower(trim(COALESCE(old.category,'')))
                           WHEN 'lokasi' THEN 'location'
                           WHEN 'faksi' THEN 'faction'
                           WHEN 'benda' THEN 'item'
                           WHEN 'barang' THEN 'item'
                           ELSE 'lore'
                       END,
                       old.name,COALESCE(old.category,'Lore'),COALESCE(old.details,''),'','{}',''
                FROM worldbuilding old
                WHERE NOT EXISTS (
                    SELECT 1 FROM story_records current
                    WHERE current.project_id=old.project_id
                      AND current.name=old.name
                      AND (current.kind IN ('world','location','faction','item','lore')
                           OR current.category=COALESCE(old.category,'Lore'))
                )
                """);
        }
    }

    private void ensureColumn(String table, String column, String definition) throws SQLException {
        List<String> names = new ArrayList<>();
        try (Connection c = open(); Statement s = c.createStatement(); var rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) names.add(rs.getString("name"));
        }
        if (!names.contains(column)) {
            try (Connection c = open(); Statement s = c.createStatement()) {
                s.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private void seedFromManuscriptIfNeeded() throws SQLException {
        try (Connection c = open()) {
            int projectCount;
            try (Statement s = c.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM projects")) {
                projectCount = rs.next() ? rs.getInt(1) : 0;
            }
            if (projectCount == 0) {
                String title = "Cerita Pertamaku";
                String body = "";
                try (Statement s = c.createStatement(); var old = s.executeQuery("SELECT title, content FROM manuscript WHERE id = 1")) {
                    if (old.next()) {
                        if (old.getString("title") != null && !old.getString("title").isBlank()) title = old.getString("title");
                        body = old.getString("content") == null ? "" : old.getString("content");
                    }
                }
                long projectId;
                try (var insert = c.prepareStatement("INSERT INTO projects(title) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, title);
                    insert.executeUpdate();
                    try (var keys = insert.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Tidak bisa membuat proyek awal.");
                        projectId = keys.getLong(1);
                    }
                }
                try (var chapter = c.prepareStatement("INSERT INTO chapters(project_id,title,content,position) VALUES(?, 'Bab 1', ?, 1)")) {
                    chapter.setLong(1, projectId);
                    chapter.setString(2, body);
                    chapter.executeUpdate();
                }
            }
            try (Statement s = c.createStatement(); var rs = s.executeQuery("SELECT id FROM projects")) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) ids.add(rs.getLong(1));
                for (long id : ids) {
                    try (var count = c.prepareStatement("SELECT COUNT(*) FROM chapters WHERE project_id=?")) {
                        count.setLong(1, id);
                        try (var cr = count.executeQuery()) {
                            if (cr.next() && cr.getInt(1) == 0) {
                                try (var ins = c.prepareStatement("INSERT INTO chapters(project_id,title,content,position) VALUES(?, 'Bab 1','',1)")) {
                                    ins.setLong(1, id);
                                    ins.executeUpdate();
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public void checkpoint() throws SQLException {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(FULL)");
        }
    }

    /** Copy an old Stomake database into the V2 app-data directory, before opening it. */
    public static Path prepareDataFile() throws IOException {
        String appData = System.getenv("APPDATA");
        Path home = Path.of(System.getProperty("user.home"));
        Path targetDir = (appData != null && !appData.isBlank())
                ? Path.of(appData, "Stomake", "data")
                : home.resolve(".stomake").resolve("data");
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve("Stomake.db");
        if (Files.exists(target)) return target;

        List<Path> candidates = List.of(
                Path.of(System.getProperty("user.dir"), "data", "Stomake.db"),
                Path.of(System.getProperty("user.dir"), "data", "nopai.db"),
                home.resolve("Stomake").resolve("data").resolve("Stomake.db"),
                home.resolve("Documents").resolve("Stomake").resolve("data").resolve("Stomake.db")
        );
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)
                    && !candidate.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
                Files.copy(candidate, target, StandardCopyOption.COPY_ATTRIBUTES);
                return target;
            }
        }
        return target;
    }
}
