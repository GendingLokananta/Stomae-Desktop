# Stomake PC — Feature checklist against Android

| Area | PC implementation |
|---|---|
| Projects, chapters, autosave | SQLite project/chapter model, 1.5-second autosave |
| Rich text | Qt rich-text editor, bold/italic/underline/strike, lists, font family/size, H1-H3, paragraph alignment |
| Fonts | Garamond, Times New Roman, Courier New and common Windows fonts |
| Book cover and synopsis | Per-project cover selection/crop and synopsis editor |
| Story Bible / World Bible | Data-driven categories, search, CRUD, custom categories |
| Character profiles and roles | Role, profile fields, custom values and descriptions |
| Multiple images | Primary image plus gallery per Bible entry; inline image insertion for chapters |
| Crop / image sizing / layout | Crop selection before applying; gallery width/layout controls; inline chapter image width setting |
| Character relationships | Character-to-character relationship and notes/description |
| Timeline | CRUD for events, dates/order and descriptions |
| Statistics | Word count, daily goals, per-chapter statistics |
| Version history | Chapter snapshots and restore |
| Reading mode | Separate reading window for synopsis, chapters, Bible, relationships and timeline |
| Themes | Dark, Light, Warm Light, Slate; persisted between launches |
| AI Assistant | Local Ollama endpoint, model selector, model refresh, story context, saved chat history |
| Backup / Restore | Copy and restore the complete SQLite database |
| Android ↔ PC transfer | Import/export Android-compatible single-story `.stomake` JSON format |

## Known cross-platform limitations

The Android single-story `.stomake` format currently exports chapter `content` as plain text plus separate image fields. PC rich-text HTML formatting is preserved in the PC database and backup, but paragraph-level formatting may be simplified when a story is transferred through this current Android format. Chapter images are retained and mapped as gallery images on import/export; their exact inline paragraph position may become an image block after the text.

The PC AI provider is Ollama running locally. It does not use Android's Gemini/OpenAI/Groq API-key settings; this is intentional for the desktop build requested to use Ollama.
