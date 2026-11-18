package id.stomake.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.stomake.data.Database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Import/export for the Android-compatible .stomake single-story JSON format and Word DOCX. */
public final class StoryTransferService {
    private static final Pattern INLINE_IMAGE = Pattern.compile("data:image/[A-Za-z0-9.+-]+;base64,([A-Za-z0-9+/=]+)");
    private final Database database;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public StoryTransferService(Database database) { this.database = database; }

    public void exportStomake(long projectId, Path target) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("app", "Stomake");
        root.put("type", "single_story");
        root.put("formatVersion", 1);
        root.put("exportedAt", LocalDateTime.now().toString());

        try (Connection c = database.open()) {
            try (PreparedStatement p = c.prepareStatement("SELECT title,COALESCE(cover_image,''),COALESCE(synopsis,'') FROM projects WHERE id=?")) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("Cerita tidak ditemukan.");
                    ObjectNode project = root.putObject("project");
                    project.put("title", r.getString(1));
                    project.put("cover_image", r.getString(2));
                    project.put("cover_width", 1.0);
                    project.put("synopsis", r.getString(3));
                }
            }

            ArrayNode chapters = root.putArray("chapters");
            String chapterSql = "SELECT id,title,COALESCE(subtitle,''),COALESCE(content,''),position,COALESCE(image_data,''),COALESCE(gallery_data,'[]'),COALESCE(content_html,'') FROM chapters WHERE project_id=? ORDER BY position,id";
            try (PreparedStatement p = c.prepareStatement(chapterSql)) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        ObjectNode row = chapters.addObject();
                        row.put("id", r.getLong(1));
                        row.put("title", r.getString(2));
                        row.put("subtitle", r.getString(3));
                        row.put("content", r.getString(4));
                        row.put("position", r.getInt(5));
                        row.put("image_data", r.getString(6));
                        row.put("image_width", 1.0);
                        ArrayNode gallery = parseArray(r.getString(7));
                        Set<String> knownImages = new HashSet<>();
                        for (JsonNode item : gallery) knownImages.add(item.path("data").asText(""));
                        Matcher matcher = INLINE_IMAGE.matcher(r.getString(8));
                        while (matcher.find()) {
                            String image = matcher.group(1);
                            if (!image.isBlank() && knownImages.add(image)) {
                                ObjectNode item = gallery.addObject();
                                item.put("data", image);
                                item.put("width", 1.0);
                                item.put("layout", "center");
                            }
                        }
                        row.put("gallery_data", mapper.writeValueAsString(gallery));
                    }
                }
            }

            ArrayNode records = root.putArray("records");
            String recordsSql = "SELECT id,kind,name,category,details,COALESCE(status,''),COALESCE(role,''),COALESCE(image_data,''),COALESCE(image_width,1.0),COALESCE(gallery_data,'[]'),COALESCE(custom_traits,'') FROM story_records WHERE project_id=? ORDER BY id";
            try (PreparedStatement p = c.prepareStatement(recordsSql)) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        String kind = r.getString(2);
                        String section = "story";
                        String category = r.getString(4);
                        if (kind.startsWith("custom_") && kind.substring(7).matches("\\d+")) {
                            long categoryId = Long.parseLong(kind.substring(7));
                            try (PreparedStatement cat = c.prepareStatement("SELECT section,name FROM custom_categories WHERE id=?")) {
                                cat.setLong(1, categoryId);
                                try (ResultSet cr = cat.executeQuery()) {
                                    if (cr.next()) {
                                        section = cr.getString(1);
                                        category = cr.getString(2);
                                    }
                                }
                            }
                        } else if (!isCharacterKind(kind)) {
                            section = "world";
                            if (category == null || category.isBlank()) category = defaultWorldCategory(kind);
                        } else {
                            category = "Karakter";
                        }
                        ObjectNode row = records.addObject();
                        row.put("id", r.getLong(1));
                        row.put("section", section);
                        row.put("category", category == null || category.isBlank() ? "Umum" : category);
                        row.put("name", r.getString(3));
                        row.put("role", r.getString(7).isBlank() ? r.getString(6) : r.getString(7));
                        row.put("details", r.getString(5));
                        row.put("image_data", r.getString(8));
                        row.put("image_width", r.getDouble(9));
                        row.put("gallery_data", mapper.writeValueAsString(parseArray(r.getString(10))));
                        row.put("custom_traits", r.getString(11));
                    }
                }
            }

            ArrayNode timeline = root.putArray("timeline");
            try (PreparedStatement p = c.prepareStatement("SELECT title,event_date,description FROM timeline_events WHERE project_id=? ORDER BY event_date,id")) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        ObjectNode row = timeline.addObject();
                        row.put("category", "Peristiwa");
                        row.put("title", r.getString(1));
                        row.put("event_date", r.getString(2));
                        row.put("description", r.getString(3));
                    }
                }
            }

            ArrayNode relationships = root.putArray("relationships");
            try (PreparedStatement p = c.prepareStatement("SELECT source_id,target_id,relation,notes FROM character_relationships WHERE project_id=? ORDER BY id")) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        ObjectNode row = relationships.addObject();
                        row.put("from_record_id", r.getLong(1));
                        row.put("to_record_id", r.getLong(2));
                        row.put("label", r.getString(3));
                        row.put("description", r.getString(4));
                    }
                }
            }
        }
        Files.writeString(target, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
    }

    public long importStomake(Path source) throws Exception {
        JsonNode root = mapper.readTree(source.toFile());
        if (!"Stomake".equals(root.path("app").asText()) || !"single_story".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("File bukan export satu cerita Stomake yang didukung.");
        }
        JsonNode project = root.path("project");
        String base = project.path("title").asText(source.getFileName().toString().replaceFirst("\\.[^.]+$", "")).trim();
        if (base.isBlank()) base = "Cerita Impor";

        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                String title = uniqueProjectTitle(c, base);
                long projectId;
                try (PreparedStatement p = c.prepareStatement("INSERT INTO projects(title,cover_image,synopsis) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    p.setString(1, title);
                    p.setString(2, project.path("cover_image").asText(""));
                    p.setString(3, project.path("synopsis").asText(""));
                    p.executeUpdate();
                    try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new IllegalStateException("Tidak dapat membuat proyek hasil impor."); projectId = r.getLong(1); }
                }

                Map<Long, Long> recordMap = new HashMap<>();
                JsonNode records = root.path("records");
                if (records.isArray()) {
                    for (JsonNode rec : records) {
                        String section = rec.path("section").asText("story");
                        String category = rec.path("category").asText("Umum");
                        String kind = resolveRecordKind(c, section, category);
                        String role = rec.path("role").asText("");
                        String metadata = mapper.writeValueAsString(mapper.createObjectNode().put("role", role));
                        long recordId;
                        try (PreparedStatement p = c.prepareStatement(
                                "INSERT INTO story_records(project_id,kind,name,category,details,status,metadata,role,custom_traits,image_data,image_width,gallery_data) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                            p.setLong(1, projectId);
                            p.setString(2, kind);
                            p.setString(3, rec.path("name").asText("Tanpa Nama"));
                            p.setString(4, category);
                            p.setString(5, rec.path("details").asText(""));
                            p.setString(6, role);
                            p.setString(7, metadata);
                            p.setString(8, role);
                            p.setString(9, rec.path("custom_traits").asText(""));
                            p.setString(10, rec.path("image_data").asText(""));
                            p.setDouble(11, rec.path("image_width").asDouble(1.0));
                            p.setString(12, jsonArrayString(rec, "gallery_data"));
                            p.executeUpdate();
                            try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new IllegalStateException("Gagal mengimpor entri Story Bible."); recordId = r.getLong(1); }
                        }
                        if (rec.path("id").canConvertToLong()) recordMap.put(rec.path("id").asLong(), recordId);
                    }
                }

                JsonNode chapters = root.path("chapters");
                int position = 1;
                if (chapters.isArray()) {
                    for (JsonNode chapter : chapters) {
                        String content = chapter.path("content").asText("");
                        String galleryJson = jsonArrayString(chapter, "gallery_data");
                        String imageData = chapter.path("image_data").asText("");
                        String html = chapter.path("content_html").asText("");
                        if (html.isBlank()) html = chapterHtml(content, imageData, galleryJson);
                        try (PreparedStatement p = c.prepareStatement(
                                "INSERT INTO chapters(project_id,title,subtitle,content,position,image_data,gallery_data,content_html) VALUES(?,?,?,?,?,?,?,?)")) {
                            p.setLong(1, projectId);
                            p.setString(2, chapter.path("title").asText("Bab " + position));
                            p.setString(3, chapter.path("subtitle").asText(""));
                            p.setString(4, content);
                            p.setInt(5, chapter.path("position").asInt(position));
                            p.setString(6, imageData);
                            p.setString(7, galleryJson);
                            p.setString(8, html);
                            p.executeUpdate();
                        }
                        position++;
                    }
                }
                if (position == 1) {
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO chapters(project_id,title,position) VALUES(?,'Bab 1',1)")) {
                        p.setLong(1, projectId); p.executeUpdate();
                    }
                }

                JsonNode timeline = root.path("timeline");
                if (timeline.isArray()) for (JsonNode event : timeline) {
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO timeline_events(project_id,title,event_date,description) VALUES(?,?,?,?)")) {
                        p.setLong(1, projectId); p.setString(2, event.path("title").asText("Peristiwa"));
                        p.setString(3, event.path("event_date").asText("")); p.setString(4, event.path("description").asText("")); p.executeUpdate();
                    }
                }

                JsonNode relationships = root.path("relationships");
                if (relationships.isArray()) for (JsonNode rel : relationships) {
                    long a = recordMap.getOrDefault(rel.path("from_record_id").asLong(), -1L);
                    long b = recordMap.getOrDefault(rel.path("to_record_id").asLong(), -1L);
                    if (a > 0 && b > 0 && a != b) {
                        try (PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO character_relationships(project_id,source_id,target_id,relation,notes) VALUES(?,?,?,?,?)")) {
                            p.setLong(1, projectId); p.setLong(2, a); p.setLong(3, b);
                            p.setString(4, rel.path("label").asText("Hubungan")); p.setString(5, rel.path("description").asText("")); p.executeUpdate();
                        }
                    }
                }
                c.commit();
                return projectId;
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    /** Creates a minimal Office Open XML DOCX containing the story text and planning material. */
    public void exportDocx(long projectId, Path target) throws Exception {
        StringBuilder body = new StringBuilder();
        try (Connection c = database.open()) {
            String title;
            String synopsis;
            try (PreparedStatement p = c.prepareStatement("SELECT title,COALESCE(synopsis,'') FROM projects WHERE id=?")) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) throw new IllegalArgumentException("Cerita tidak ditemukan.");
                    title = r.getString(1); synopsis = r.getString(2);
                }
            }
            para(body, title, "Title");
            if (!synopsis.isBlank()) { para(body, "Sinopsis", "Heading1"); for (String line : synopsis.split("\\R", -1)) para(body, line, null); }
            try (PreparedStatement p = c.prepareStatement("SELECT title,content FROM chapters WHERE project_id=? ORDER BY position,id")) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        para(body, r.getString(1), "Heading1");
                        String content = r.getString(2);
                        for (String line : (content == null ? "" : content).split("\\R", -1)) para(body, line, null);
                    }
                }
            }
            para(body, "Story Bible", "Heading1");
            try (PreparedStatement p = c.prepareStatement("SELECT name,category,COALESCE(role,''),details FROM story_records WHERE project_id=? ORDER BY kind,name")) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        String label = r.getString(1) + (r.getString(2) == null || r.getString(2).isBlank() ? "" : " (" + r.getString(2) + ")");
                        para(body, label, "Heading2");
                        if (!r.getString(3).isBlank()) para(body, "Peran: " + r.getString(3), null);
                        for (String line : (r.getString(4) == null ? "" : r.getString(4)).split("\\R", -1)) para(body, line, null);
                    }
                }
            }
            para(body, "Timeline", "Heading1");
            try (PreparedStatement p = c.prepareStatement("SELECT event_date,title,description FROM timeline_events WHERE project_id=? ORDER BY event_date,id")) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        para(body, (r.getString(1) == null || r.getString(1).isBlank() ? "" : r.getString(1) + " — ") + r.getString(2), "Heading2");
                        for (String line : (r.getString(3) == null ? "" : r.getString(3)).split("\\R", -1)) para(body, line, null);
                    }
                }
            }
            para(body, "Hubungan Karakter", "Heading1");
            try (PreparedStatement p = c.prepareStatement("SELECT a.name,r.relation,b.name,r.notes FROM character_relationships r JOIN story_records a ON a.id=r.source_id JOIN story_records b ON b.id=r.target_id WHERE r.project_id=? ORDER BY a.name,b.name")) {
                p.setLong(1, projectId);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        para(body, r.getString(1) + " — " + r.getString(2) + " — " + r.getString(3), "Heading2");
                        if (r.getString(4) != null && !r.getString(4).isBlank()) para(body, r.getString(4), null);
                    }
                }
            }
        }

        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1800\"/></w:sectPr></w:body></w:document>";
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/><w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:rPr><w:b/><w:sz w:val=\"30\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:rPr><w:b/><w:sz w:val=\"26\"/></w:rPr></w:style>"
                + "</w:styles>";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target), StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            put(zip, "word/_rels/document.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                    + "</Relationships>");
            put(zip, "word/styles.xml", styles);
            put(zip, "word/document.xml", document);
        }
    }

    private String resolveRecordKind(Connection c, String section, String category) throws Exception {
        if ("story".equalsIgnoreCase(section) && "karakter".equalsIgnoreCase(category)) return "character";
        String normalized = category == null ? "" : category.trim().toLowerCase();
        if ("world".equalsIgnoreCase(section)) {
            return switch (normalized) {
                case "dunia", "world" -> "world";
                case "lokasi", "location", "tempat" -> "location";
                case "faksi", "faction" -> "faction";
                case "barang", "benda", "item" -> "item";
                case "lore", "pengetahuan", "sejarah" -> "lore";
                default -> customKind(c, section, category);
            };
        }
        return customKind(c, section, category);
    }

    private String customKind(Connection c, String section, String category) throws Exception {
        String safeSection = "world".equalsIgnoreCase(section) ? "world" : "story";
        long categoryId;
        try (PreparedStatement insert = c.prepareStatement("INSERT OR IGNORE INTO custom_categories(section,name) VALUES(?,?)")) {
            insert.setString(1, safeSection); insert.setString(2, category == null || category.isBlank() ? "Umum" : category); insert.executeUpdate();
        }
        try (PreparedStatement q = c.prepareStatement("SELECT id FROM custom_categories WHERE section=? AND name=?")) {
            q.setString(1, safeSection); q.setString(2, category == null || category.isBlank() ? "Umum" : category);
            try (ResultSet r = q.executeQuery()) { if (!r.next()) throw new IllegalStateException("Kategori tidak bisa disimpan."); categoryId = r.getLong(1); }
        }
        return "custom_" + categoryId;
    }

    private static String uniqueProjectTitle(Connection c, String base) throws Exception {
        String candidate = base;
        int suffix = 2;
        try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM projects WHERE title=?")) {
            while (true) {
                q.setString(1, candidate);
                try (ResultSet r = q.executeQuery()) { if (!r.next()) return candidate; }
                candidate = base + " (" + suffix++ + ")";
            }
        }
    }

    private ArrayNode parseArray(String json) {
        try {
            JsonNode node = mapper.readTree(json == null || json.isBlank() ? "[]" : json);
            if (node != null && node.isArray()) return (ArrayNode) node.deepCopy();
        } catch (Exception ignored) { }
        return mapper.createArrayNode();
    }

    private String jsonArrayString(JsonNode object, String field) throws Exception {
        JsonNode value = object.path(field);
        if (value.isMissingNode() || value.isNull()) return "[]";
        if (value.isTextual()) {
            String raw = value.asText();
            if (raw.isBlank()) return "[]";
            return mapper.writeValueAsString(parseArray(raw));
        }
        if (value.isArray()) return mapper.writeValueAsString(value);
        return "[]";
    }

    private String chapterHtml(String content, String imageData, String galleryJson) {
        StringBuilder html = new StringBuilder("<!doctype html><html><body>");
        String safeContent = escapeHtml(content == null ? "" : content);
        for (String line : safeContent.split("\\R", -1)) html.append("<p>").append(line.isEmpty() ? "&nbsp;" : line).append("</p>");
        if (imageData != null && !imageData.isBlank()) html.append(imageTag(imageData, 1.0, "center"));
        ArrayNode gallery = parseArray(galleryJson);
        for (JsonNode item : gallery) {
            String data = item.path("data").asText("");
            if (!data.isBlank()) html.append(imageTag(data, item.path("width").asDouble(1.0), item.path("layout").asText("center")));
        }
        return html.append("</body></html>").toString();
    }

    private static String imageTag(String base64, double width, String layout) {
        String align = "left".equalsIgnoreCase(layout) ? "left" : "right".equalsIgnoreCase(layout) ? "right" : "center";
        int percent = (int) Math.max(10, Math.min(100, width * 100));
        String margin = switch (align) {
            case "left" -> "margin:8px 18px 8px 0;float:left;";
            case "right" -> "margin:8px 0 8px 18px;float:right;";
            default -> "display:block;margin:14px auto;";
        };
        return "<p><img style=\"max-width:" + percent + "%;height:auto;" + margin + "\" src=\"data:image/png;base64," + base64 + "\"></p>";
    }

    private static boolean isCharacterKind(String kind) { return "character".equals(kind) || "characters".equals(kind); }
    private static String defaultWorldCategory(String kind) {
        return switch (kind) { case "location" -> "Lokasi"; case "faction" -> "Faksi"; case "item" -> "Benda"; default -> "Lore"; };
    }
    private static String escapeHtml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static void para(StringBuilder b, String text, String style) {
        b.append("<w:p>");
        if (style != null) b.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>");
        b.append("<w:r><w:t xml:space=\"preserve\">").append(escapeXml(text == null ? "" : text)).append("</w:t></w:r></w:p>");
    }
    private static String escapeXml(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }
    private static void put(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
}
