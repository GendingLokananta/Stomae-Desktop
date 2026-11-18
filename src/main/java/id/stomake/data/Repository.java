package id.stomake.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class Repository {
    private final Database database;

    public Repository(Database database) {
        this.database = database;
    }

    public record Project(long id, String title, String synopsis, String coverImage) {
        @Override public String toString() { return title; }
    }
    public record Chapter(long id, long projectId, String title, String content, String html, int position,
                          String imageData, String galleryData) {
        public Chapter(long id, long projectId, String title, String content, String html, int position) {
            this(id, projectId, title, content, html, position, "", "[]");
        }
        @Override public String toString() { return title; }
    }
    public record StoryRecord(long id, long projectId, String kind, String name, String category, String details, String role, String metadata, String customTraits, String imageData, double imageWidth, String galleryData) {
        public StoryRecord(long id, long projectId, String kind, String name, String category, String details, String role, String metadata, String customTraits) {
            this(id, projectId, kind, name, category, details, role, metadata, customTraits, "", 1.0, "[]");
        }
        @Override public String toString() { return name; }
    }
    public record ChapterVersion(long id, long projectId, long chapterId, String title, String content, String html, String createdAt) {}
    public record Relationship(long id, long projectId, long sourceId, long targetId, String sourceName, String targetName, String relation, String notes) {}
    public record CharacterProfile(long id, String name, String role, String description, String personality,
                                   String traits, String background, String appearance, String speakingStyle,
                                   String scenario, String greeting, String extraInstructions,
                                   String relationshipToUser, String motivations, String flaws, String secrets,
                                   String memoryNotes, String modelName, double temperature) {
        @Override public String toString() { return name; }
    }
    public record ChatMessage(long id, long characterId, String role, String content, String createdAt) {}
    public record TimelineEvent(long id, long projectId, String title, String date, String description) {}
    public record CustomCategory(long id, String section, String name) {
        @Override public String toString() { return name; }
    }
    public record AiConversation(long id, long projectId, String title, String messages, String updatedAt) {
        @Override public String toString() { return title; }
    }

    public List<Project> projects() throws Exception {
        List<Project> rows = new ArrayList<>();
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT id,title,COALESCE(synopsis,''),COALESCE(cover_image,'') FROM projects ORDER BY id DESC"); ResultSet r = p.executeQuery()) {
            while (r.next()) rows.add(new Project(r.getLong(1), r.getString(2), r.getString(3), r.getString(4)));
        }
        return rows;
    }

    public long createProject(String title) throws Exception {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            long id;
            try (PreparedStatement p = c.prepareStatement("INSERT INTO projects(title) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
                p.setString(1, title.trim()); p.executeUpdate();
                try (ResultSet r = p.getGeneratedKeys()) { r.next(); id = r.getLong(1); }
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO chapters(project_id,title,content,position) VALUES(?, 'Bab 1','',1)")) {
                p.setLong(1, id); p.executeUpdate();
            }
            c.commit();
            return id;
        }
    }

    public void renameProject(long id, String title) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("UPDATE projects SET title=? WHERE id=?")) {
            p.setString(1, title.trim()); p.setLong(2, id); p.executeUpdate();
        }
    }

    public void updateProjectSynopsis(long id, String synopsis) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("UPDATE projects SET synopsis=? WHERE id=?")) {
            p.setString(1, synopsis); p.setLong(2, id); p.executeUpdate();
        }
    }

    public void deleteProject(long id) throws Exception {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                // ai_conversations predates a cascading project FK, so remove it explicitly.
                try (PreparedStatement chats = c.prepareStatement("DELETE FROM ai_conversations WHERE project_id=?")) {
                    chats.setLong(1, id); chats.executeUpdate();
                }
                try (PreparedStatement project = c.prepareStatement("DELETE FROM projects WHERE id=?")) {
                    project.setLong(1, id); project.executeUpdate();
                }
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            }
        }
    }

    public List<Chapter> chapters(long projectId) throws Exception {
        List<Chapter> rows = new ArrayList<>();
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT id,project_id,title,content,COALESCE(content_html,''),position,COALESCE(image_data,''),COALESCE(gallery_data,'[]') FROM chapters WHERE project_id=? ORDER BY position,id")) {
            p.setLong(1, projectId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) rows.add(new Chapter(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getInt(6), r.getString(7), r.getString(8)));
            }
        }
        return rows;
    }

    public long createChapter(long projectId) throws Exception {
        int position = 1;
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT COALESCE(MAX(position),0)+1 FROM chapters WHERE project_id=?")) {
            p.setLong(1, projectId); try (ResultSet r = p.executeQuery()) { if (r.next()) position = r.getInt(1); }
        }
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("INSERT INTO chapters(project_id,title,content,position) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, projectId); p.setString(2, "Bab " + position); p.setString(3, ""); p.setInt(4, position); p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { r.next(); return r.getLong(1); }
        }
    }

    /** Saves the current content and snapshots the previous version only when it changed. */
    public void saveChapter(long id, String title, String html, String plainText) throws Exception {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                String oldTitle = null, oldText = null, oldHtml = null;
                try (PreparedStatement q = c.prepareStatement("SELECT title,content,COALESCE(content_html,'') FROM chapters WHERE id=?")) {
                    q.setLong(1, id); try (ResultSet r = q.executeQuery()) { if (r.next()) { oldTitle = r.getString(1); oldText = r.getString(2); oldHtml = r.getString(3); } }
                }
                if (oldTitle != null && (!oldTitle.equals(title) || !n(oldText).equals(n(plainText)) || !n(oldHtml).equals(n(html)))) {
                    try (PreparedStatement snap = c.prepareStatement("INSERT INTO chapter_versions(project_id,chapter_id,title,content,content_html) SELECT project_id,id,title,content,COALESCE(content_html,'') FROM chapters WHERE id=?")) {
                        snap.setLong(1, id); snap.executeUpdate();
                    }
                }
                try (PreparedStatement p = c.prepareStatement("UPDATE chapters SET title=?,content_html=?,content=? WHERE id=?")) {
                    p.setString(1, title); p.setString(2, html); p.setString(3, plainText); p.setLong(4, id); p.executeUpdate();
                }
                int previousWords = countWords(oldText), currentWords = countWords(plainText);
                int addedWords = Math.max(0, currentWords - previousWords);
                if (addedWords > 0) {
                    try (PreparedStatement project = c.prepareStatement("SELECT project_id FROM chapters WHERE id=?")) {
                        project.setLong(1,id); try(ResultSet r=project.executeQuery()){ if(r.next()) try(PreparedStatement activity=c.prepareStatement("INSERT INTO writing_activity(project_id,activity_date,words_written) VALUES(?,date('now','localtime'),?) ON CONFLICT(project_id,activity_date) DO UPDATE SET words_written=writing_activity.words_written+excluded.words_written")){activity.setLong(1,r.getLong(1));activity.setInt(2,addedWords);activity.executeUpdate();} }
                    }
                }
                c.commit();
            } catch (Exception ex) { c.rollback(); throw ex; }
        }
    }

    public List<ChapterVersion> chapterVersions(long projectId, long chapterId) throws Exception {
        List<ChapterVersion> rows = new ArrayList<>();
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT id,project_id,chapter_id,title,content,COALESCE(content_html,''),created_at FROM chapter_versions WHERE project_id=? AND chapter_id=? ORDER BY id DESC")) {
            p.setLong(1, projectId); p.setLong(2, chapterId);
            try (ResultSet r = p.executeQuery()) { while (r.next()) rows.add(new ChapterVersion(r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7))); }
        }
        return rows;
    }

    public void snapshotChapter(long projectId, long chapterId) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("INSERT INTO chapter_versions(project_id,chapter_id,title,content,content_html) SELECT project_id,id,title,content,COALESCE(content_html,'') FROM chapters WHERE id=? AND project_id=? AND NOT EXISTS (SELECT 1 FROM chapter_versions v WHERE v.chapter_id=chapters.id AND v.title=chapters.title AND v.content=chapters.content AND v.content_html=COALESCE(chapters.content_html,''))")) {
            p.setLong(1, chapterId); p.setLong(2, projectId); p.executeUpdate();
        }
    }

    public void restoreChapterVersion(long projectId, long chapterId, long versionId) throws Exception {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement snap = c.prepareStatement("INSERT INTO chapter_versions(project_id,chapter_id,title,content,content_html) SELECT project_id,id,title,content,COALESCE(content_html,'') FROM chapters WHERE id=? AND project_id=?")) { snap.setLong(1,chapterId); snap.setLong(2,projectId); snap.executeUpdate(); }
                try (PreparedStatement restore = c.prepareStatement("UPDATE chapters SET title=(SELECT title FROM chapter_versions WHERE id=? AND project_id=? AND chapter_id=?),content=(SELECT content FROM chapter_versions WHERE id=? AND project_id=? AND chapter_id=?),content_html=(SELECT content_html FROM chapter_versions WHERE id=? AND project_id=? AND chapter_id=?) WHERE id=? AND project_id=?")) {
                    int i=1; for(int k=0;k<3;k++){ restore.setLong(i++,versionId); restore.setLong(i++,projectId); restore.setLong(i++,chapterId); } restore.setLong(i++,chapterId); restore.setLong(i,projectId); restore.executeUpdate();
                }
                c.commit();
            } catch (Exception ex) { c.rollback(); throw ex; }
        }
    }

    public void updateProjectCover(long projectId, String coverData, String synopsis) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("UPDATE projects SET cover_image=?,synopsis=? WHERE id=?")) {
            p.setString(1, n(coverData)); p.setString(2, n(synopsis)); p.setLong(3, projectId); p.executeUpdate();
        }
    }

    public List<Relationship> relationships(long projectId) throws Exception {
        List<Relationship> rows = new ArrayList<>();
        String sql = "SELECT r.id,r.project_id,r.source_id,r.target_id,a.name,b.name,r.relation,r.notes FROM character_relationships r JOIN story_records a ON a.id=r.source_id JOIN story_records b ON b.id=r.target_id WHERE r.project_id=? ORDER BY a.name,b.name";
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement(sql)) { p.setLong(1,projectId); try(ResultSet r=p.executeQuery()){ while(r.next()) rows.add(new Relationship(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8))); } }
        return rows;
    }

    public void addRelationship(long projectId,long sourceId,long targetId,String relation,String notes) throws Exception {
        if(sourceId==targetId) throw new IllegalArgumentException("Pilih dua karakter yang berbeda.");
        try(Connection c=database.open(); PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO character_relationships(project_id,source_id,target_id,relation,notes) VALUES(?,?,?,?,?)")) { p.setLong(1,projectId);p.setLong(2,sourceId);p.setLong(3,targetId);p.setString(4,n(relation));p.setString(5,n(notes));p.executeUpdate(); }
    }
    public void deleteRelationship(long id) throws Exception { try(Connection c=database.open();PreparedStatement p=c.prepareStatement("DELETE FROM character_relationships WHERE id=?")){p.setLong(1,id);p.executeUpdate();} }


    public List<StoryRecord> storyRecords(long projectId, String kind) throws Exception {
        List<StoryRecord> rows = new ArrayList<>();
        String filter = kind == null || kind.isBlank() ? "" : (kind.equals("character")
                ? " AND kind IN ('character','characters')" : " AND kind=?");
        String sql = "SELECT id,project_id,kind,name,category,details,COALESCE(role,''),COALESCE(metadata,'{}'),COALESCE(custom_traits,''),COALESCE(image_data,''),COALESCE(image_width,1.0),COALESCE(gallery_data,'[]') FROM story_records WHERE project_id=?" + filter + " ORDER BY name COLLATE NOCASE";
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, projectId); if (!filter.isEmpty() && !kind.equals("character")) p.setString(2, kind);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) rows.add(new StoryRecord(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5), r.getString(6), r.getString(7), r.getString(8), r.getString(9), r.getString(10), r.getDouble(11), r.getString(12)));
            }
        }
        return rows;
    }

    public long saveStoryRecord(StoryRecord row) throws Exception {
        if (row.id() > 0) {
            try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("UPDATE story_records SET kind=?,name=?,category=?,details=?,role=?,metadata=?,custom_traits=?,image_data=?,image_width=?,gallery_data=? WHERE id=?")) {
                p.setString(1, row.kind()); p.setString(2, row.name()); p.setString(3, row.category()); p.setString(4, row.details()); p.setString(5, row.role()); p.setString(6, row.metadata()); p.setString(7, row.customTraits()); p.setString(8, row.imageData()); p.setDouble(9, row.imageWidth()); p.setString(10, row.galleryData()); p.setLong(11, row.id()); p.executeUpdate();
            }
            return row.id();
        }
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("INSERT INTO story_records(project_id,kind,name,category,details,role,metadata,custom_traits,image_data,image_width,gallery_data) VALUES(?,?,?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, row.projectId()); p.setString(2, row.kind()); p.setString(3, row.name()); p.setString(4, row.category()); p.setString(5, row.details()); p.setString(6, row.role()); p.setString(7, row.metadata()); p.setString(8, row.customTraits()); p.setString(9, row.imageData()); p.setDouble(10, row.imageWidth()); p.setString(11, row.galleryData()); p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { r.next(); return r.getLong(1); }
        }
    }

    public void deleteStoryRecord(long id) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("DELETE FROM story_records WHERE id=?")) { p.setLong(1, id); p.executeUpdate(); }
    }

    public List<CharacterProfile> characters() throws Exception {
        List<CharacterProfile> rows = new ArrayList<>();
        String sql = "SELECT id,name,role,description,personality,traits,background,appearance,speaking_style,scenario,greeting,extra_instructions,relationship_to_user,motivations,flaws,secrets,memory_notes,model_name,temperature FROM ai_characters ORDER BY updated_at DESC,id DESC";
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement(sql); ResultSet r = p.executeQuery()) {
            while (r.next()) rows.add(readCharacter(r));
        }
        return rows;
    }

    public CharacterProfile character(long id) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT id,name,role,description,personality,traits,background,appearance,speaking_style,scenario,greeting,extra_instructions,relationship_to_user,motivations,flaws,secrets,memory_notes,model_name,temperature FROM ai_characters WHERE id=?")) {
            p.setLong(1, id); try (ResultSet r = p.executeQuery()) { return r.next() ? readCharacter(r) : null; }
        }
    }

    private CharacterProfile readCharacter(ResultSet r) throws Exception {
        return new CharacterProfile(r.getLong(1), n(r.getString(2)), n(r.getString(3)), n(r.getString(4)), n(r.getString(5)),
                n(r.getString(6)), n(r.getString(7)), n(r.getString(8)), n(r.getString(9)), n(r.getString(10)),
                n(r.getString(11)), n(r.getString(12)), n(r.getString(13)), n(r.getString(14)), n(r.getString(15)),
                n(r.getString(16)), n(r.getString(17)), n(r.getString(18)), r.getDouble(19));
    }

    public long saveCharacter(CharacterProfile x) throws Exception {
        if (x.id() > 0) {
            String sql = "UPDATE ai_characters SET name=?,role=?,description=?,personality=?,traits=?,background=?,appearance=?,speaking_style=?,scenario=?,greeting=?,extra_instructions=?,relationship_to_user=?,motivations=?,flaws=?,secrets=?,memory_notes=?,model_name=?,temperature=?,updated_at=CURRENT_TIMESTAMP WHERE id=?";
            try (Connection c = database.open(); PreparedStatement p = c.prepareStatement(sql)) {
                bindCharacter(p, x, false); p.setLong(19, x.id()); p.executeUpdate(); return x.id();
            }
        }
        String sql = "INSERT INTO ai_characters(name,role,description,personality,traits,background,appearance,speaking_style,scenario,greeting,extra_instructions,relationship_to_user,motivations,flaws,secrets,memory_notes,model_name,temperature) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindCharacter(p, x, true); p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { r.next(); return r.getLong(1); }
        }
    }

    private void bindCharacter(PreparedStatement p, CharacterProfile x, boolean insert) throws Exception {
        p.setString(1, x.name()); p.setString(2, x.role()); p.setString(3, x.description()); p.setString(4, x.personality());
        p.setString(5, x.traits()); p.setString(6, x.background()); p.setString(7, x.appearance()); p.setString(8, x.speakingStyle());
        p.setString(9, x.scenario()); p.setString(10, x.greeting()); p.setString(11, x.extraInstructions()); p.setString(12, x.relationshipToUser());
        p.setString(13, x.motivations()); p.setString(14, x.flaws()); p.setString(15, x.secrets()); p.setString(16, x.memoryNotes());
        p.setString(17, x.modelName()); p.setDouble(18, x.temperature());
    }

    public void deleteCharacter(long id) throws Exception {
        try (Connection c = database.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement p = c.prepareStatement("DELETE FROM ai_character_messages WHERE character_id=?")) { p.setLong(1, id); p.executeUpdate(); }
            try (PreparedStatement p = c.prepareStatement("DELETE FROM ai_characters WHERE id=?")) { p.setLong(1, id); p.executeUpdate(); }
            c.commit();
        }
    }

    public List<ChatMessage> messages(long characterId, int limit) throws Exception {
        List<ChatMessage> rows = new ArrayList<>();
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT id,character_id,role,content,created_at FROM (SELECT id,character_id,role,content,created_at FROM ai_character_messages WHERE character_id=? ORDER BY id DESC LIMIT ?) ORDER BY id")) {
            p.setLong(1, characterId); p.setInt(2, limit);
            try (ResultSet r = p.executeQuery()) { while (r.next()) rows.add(new ChatMessage(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5))); }
        }
        return rows;
    }

    public void addMessage(long characterId, String role, String content) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("INSERT INTO ai_character_messages(character_id,role,content) VALUES(?,?,?)")) {
            p.setLong(1, characterId); p.setString(2, role); p.setString(3, content); p.executeUpdate();
        }
    }

    public void clearMessages(long characterId) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("DELETE FROM ai_character_messages WHERE character_id=?")) { p.setLong(1, characterId); p.executeUpdate(); }
    }

    public List<AiConversation> aiConversations(long projectId) throws Exception {
        List<AiConversation> rows = new ArrayList<>();
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement(
                "SELECT id,project_id,title,messages,updated_at FROM ai_conversations WHERE project_id=? ORDER BY updated_at DESC,id DESC")) {
            p.setLong(1, projectId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) rows.add(new AiConversation(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5)));
            }
        }
        return rows;
    }

    public long saveAiConversation(AiConversation conversation) throws Exception {
        if (conversation.id() > 0) {
            try (Connection c = database.open(); PreparedStatement p = c.prepareStatement(
                    "UPDATE ai_conversations SET title=?,messages=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND project_id=?")) {
                p.setString(1, conversation.title()); p.setString(2, conversation.messages());
                p.setLong(3, conversation.id()); p.setLong(4, conversation.projectId()); p.executeUpdate();
            }
            return conversation.id();
        }
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement(
                "INSERT INTO ai_conversations(project_id,title,messages) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setLong(1, conversation.projectId()); p.setString(2, conversation.title()); p.setString(3, conversation.messages()); p.executeUpdate();
            try (ResultSet r = p.getGeneratedKeys()) { if (!r.next()) throw new SQLException("Tidak bisa menyimpan percakapan AI."); return r.getLong(1); }
        }
    }

    public void deleteAiConversation(long id, long projectId) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("DELETE FROM ai_conversations WHERE id=? AND project_id=?")) {
            p.setLong(1, id); p.setLong(2, projectId); p.executeUpdate();
        }
    }

    public long importCharacterToStory(long characterId, long projectId) throws Exception {
        CharacterProfile x = character(characterId);
        if (x == null) throw new IllegalArgumentException("Karakter tidak ditemukan.");
        // This is a one-way copy into the independent Story Maker character record.
        // User-specific RP relationship, chat history and memory are deliberately not transferred.
        String details = "Biodata:\n" + x.description()
                + "\n\nDeskripsi karakter:\n" + x.personality()
                + "\n\nBackstory karakter:\n" + x.background();
        try (Connection c = database.open(); PreparedStatement find = c.prepareStatement("SELECT id FROM story_records WHERE project_id=? AND kind IN ('character','characters') AND name=? LIMIT 1")) {
            find.setLong(1, projectId); find.setString(2, x.name());
            try (ResultSet r = find.executeQuery()) {
                if (r.next()) {
                    long recordId = r.getLong(1);
                    try (PreparedStatement update = c.prepareStatement("UPDATE story_records SET details=?,role=?,category=?,custom_traits=? WHERE id=?")) {
                        update.setString(1, details); update.setString(2, x.role()); update.setString(3, "Karakter"); update.setString(4, ""); update.setLong(5, recordId); update.executeUpdate();
                    }
                    return recordId;
                }
            }
        }
        return saveStoryRecord(new StoryRecord(0, projectId, "character", x.name(), "Karakter", details, x.role(), "{}", ""));
    }

    public List<CustomCategory> customCategories() throws Exception {
        List<CustomCategory> rows = new ArrayList<>();
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT id,section,name FROM custom_categories ORDER BY section,name COLLATE NOCASE"); ResultSet r = p.executeQuery()) {
            while (r.next()) rows.add(new CustomCategory(r.getLong(1), r.getString(2), r.getString(3)));
        }
        return rows;
    }

    public List<String> traits(long projectId) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT value FROM custom_options WHERE field_key='character_trait' AND project_id IN (0,?) ORDER BY value COLLATE NOCASE")) {
            p.setLong(1, projectId); try (ResultSet r = p.executeQuery()) { while (r.next()) rows.add(r.getString(1)); }
        }
        return rows;
    }

    public void addTrait(long projectId, String trait) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("INSERT OR IGNORE INTO custom_options(project_id,field_key,value) VALUES(?,?,?)")) {
            p.setLong(1, projectId); p.setString(2, "character_trait"); p.setString(3, trait.trim()); p.executeUpdate();
        }
    }

    public String setting(String key, String fallback) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT setting_value FROM app_settings WHERE setting_key=?")) {
            p.setString(1, key); try (ResultSet r = p.executeQuery()) { return r.next() ? r.getString(1) : fallback; }
        }
    }

    public void setSetting(String key, String value) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("INSERT INTO app_settings(setting_key,setting_value) VALUES(?,?) ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value")) {
            p.setString(1, key); p.setString(2, value); p.executeUpdate();
        }
    }

    public List<TimelineEvent> timeline(long projectId) throws Exception {
        List<TimelineEvent> rows = new ArrayList<>();
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT id,project_id,title,event_date,description FROM timeline_events WHERE project_id=? ORDER BY event_date,id")) {
            p.setLong(1, projectId); try (ResultSet r = p.executeQuery()) { while (r.next()) rows.add(new TimelineEvent(r.getLong(1), r.getLong(2), r.getString(3), r.getString(4), r.getString(5))); }
        }
        return rows;
    }

    public void addTimelineEvent(long projectId, String title, String date, String description) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("INSERT INTO timeline_events(project_id,title,event_date,description) VALUES(?,?,?,?)")) {
            p.setLong(1, projectId); p.setString(2, title); p.setString(3, date); p.setString(4, description); p.executeUpdate();
        }
    }

    public int wordCount(long projectId) throws Exception {
        int count = 0;
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT content FROM chapters WHERE project_id=?")) {
            p.setLong(1, projectId); try (ResultSet r = p.executeQuery()) {
                while (r.next()) { String text = n(r.getString(1)).trim(); if (!text.isBlank()) count += text.split("\\s+").length; }
            }
        }
        return count;
    }

    public int todayWordsWritten(long projectId) throws Exception {
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("SELECT COALESCE(words_written,0) FROM writing_activity WHERE project_id=? AND activity_date=date('now','localtime')")){p.setLong(1,projectId);try(ResultSet r=p.executeQuery()){return r.next()?r.getInt(1):0;}}
    }
    public int dailyWordGoal(long projectId) throws Exception {
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("SELECT daily_word_goal FROM writing_goals WHERE project_id=?")){p.setLong(1,projectId);try(ResultSet r=p.executeQuery()){if(r.next())return r.getInt(1);}}
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("INSERT OR IGNORE INTO writing_goals(project_id,daily_word_goal) VALUES(?,500)")){p.setLong(1,projectId);p.executeUpdate();}
        return 500;
    }
    public void setDailyWordGoal(long projectId,int goal) throws Exception {
        try(Connection c=database.open();PreparedStatement p=c.prepareStatement("INSERT INTO writing_goals(project_id,daily_word_goal) VALUES(?,?) ON CONFLICT(project_id) DO UPDATE SET daily_word_goal=excluded.daily_word_goal")){p.setLong(1,projectId);p.setInt(2,Math.max(1,goal));p.executeUpdate();}
    }
    private static int countWords(String text){String t=n(text).trim();return t.isBlank()?0:t.split("\\s+").length;}

    public int chapterCount(long projectId) throws Exception {
        try (Connection c = database.open(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM chapters WHERE project_id=?")) {
            p.setLong(1, projectId); try (ResultSet r = p.executeQuery()) { return r.next() ? r.getInt(1) : 0; }
        }
    }

    private static String n(String s) { return s == null ? "" : s; }
}
