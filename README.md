# FileStudio

[![CI](https://github.com/Mfcorket/FileStudio/actions/workflows/ci.yml/badge.svg)](https://github.com/Mfcorket/FileStudio/actions/workflows/ci.yml)

> Cross-platform file viewer & editor — **Phase 1 Desktop MVP: buildable and verified**.
> Version: 0.1.0 · Target: Windows 8.1+, macOS 12+, Linux, Android 9+
> Core: Java 21 · UI: Kotlin + Compose Multiplatform (desktop) · Build: Gradle 8.14

FileStudio is a unified file viewing/editing platform with a plugin-based architecture.
It is **not** a file-format conversion tool — it focuses on opening, viewing, and editing
files across 70+ formats via a single consistent API.

## Status

Phase 0 (core skeleton) and the Phase 1 editing engine are complete and verified.
This iteration adds the **desktop application module**: a Kotlin + Compose
Multiplatform UI that binds to the existing Java core and gives the engine a
usable front end.

| Deliverable | Status |
|-------------|--------|
| Gradle multi-module scaffold (`core-java21` + `desktop`) | ✅ |
| Core data models (`Document`, `EditCapability`, `PluginHandlerInfo`) | ✅ |
| SPI plugin framework (`FileHandler`, `FileHandlerFactory`, `FileStudioPlugin`, `PluginManager`) | ✅ |
| Built-in text handlers: text, JSON, XML, SVG, properties, INI, CSV/TSV, Markdown, YAML, TOML, HTML, CSS | ✅ (12 handlers) |
| Binary/metadata handlers: image, ZIP, 7z, RAR, APK/AAB, TAR/gzip, bz2, audio, PDF, EPUB, OOXML/ODT (docx/xlsx/pptx/odt), font, certificate, video, SQLite, Java class, OBJ/STL/glTF, ICO, TIFF, HEIF/AVIF, PE/ELF executable, Parquet, OLE | ✅ (26 handlers) |
| `DocumentParser` engine facade | ✅ |
| `EditorEngine` (edit / undo / redo / save / modification tracking) | ✅ |
| `HistoryManager` (undo/redo stack, bounded, coalescing) | ✅ |
| `LineIndex` (offset ⇄ line/column mapping) | ✅ |
| `DocumentStats` (lines / words / chars / EOL style) | ✅ |
| `BookmarkManager` (line bookmarks + sidecar persistence) | ✅ |
| `SyntaxHighlighter` (lexer + 17 language profiles) | ✅ |
| `SearchReplace` (find / replace, regex, whole-word, case) | ✅ |
| `FileStudioCore` entry (init / parse / save / editor / listHandlers / shutdown) | ✅ |
| Protobuf/gRPC service definition (`filestudio.proto`) | ✅ (defined; codegen in later phase) |
| Sample SPI plugin (`.sample` format) | ✅ |
| `DiffEngine` (line-level LCS diff, stats, unified output) | ✅ |
| `EditorSession` / `EditorSessionManager` (multi-tab) | ✅ |
| `MagicBytesDetector` (content-based format sniffing) | ✅ |
| Desktop module (`desktop`, Kotlin + Compose Multiplatform 1.7.3) | ✅ |
| `AppState` (tabs / edit / undo / save / close, no Compose dependency) | ✅ |
| Compose UI: menu bar, tab sidebar, editor pane, metadata panel, status bar | ✅ |
| Editor commands wired: undo/redo, search & replace, Diff, syntax highlight, bookmarks | ✅ |
| Keyboard shortcuts (`Keymap` + `ShortcutDispatcher` + F1 help dialog) | ✅ |
| Top menu bar (文件 / 编辑 / 视图 / 工具 / 插件 / 帮助) | ✅ |
| User settings (`Settings` + `SettingsStore`, hand-editable JSON) | ✅ |
| EXIF metadata extraction (JPEG APP1 / bare TIFF, endian + GPS sub-IFD) | ✅ |
| JUnit 5 tests | ✅ (509 core + 117 desktop) |
| Gradle Wrapper | ✅ |

Upcoming phases: Phase 1 (Desktop MVP, Compose UI, 30+ formats), Phase 2 (80+ formats,
image/archive/markdown preview), Phase 3 (Android), Phase 4 (GraalVM Native Image).

## Module layout

```
FileStudio/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/wrapper/...
├── gradlew, gradlew.bat
├── dictionary.md                # File-type dictionary
├── desktop/                     # Kotlin + Compose Multiplatform desktop app
│   └── src/
│       ├── main/kotlin/com/filestudio/desktop/
│       │   ├── Main.kt          # entry point, window, CLI path arguments
│       │   ├── AppState.kt      # UI state: tabs / edit / undo / save / close
│       │   └── ui/              # MenuBar, Sidebar, DetailsPanel, StatusBar, EditorArea
│       └── test/kotlin/com/filestudio/desktop/AppStateTest.kt
└── core-java21/                 # Java 21 core engine
    └── src/
        ├── main/
        │   ├── java/com/filestudio/
        │   │   ├── core/        # Document, EditCapability, FileStudioCore, PluginHandlerInfo,
        │   │   │                # FileStudioException, FileStudioMain
        │   │   ├── plugin/      # FileHandler, FileHandlerFactory, FileStudioPlugin, PluginManager
        │   │   │   ├── handlers/# AbstractTextHandler + Text/JSON/XML/Properties/INI/CSV/
        │   │   │   │            # Markdown/YAML/TOML/HTML/CSS + binary/archival handlers
        │   │   │   └── sample/  # SamplePlugin (SPI demo)
        │   │   └── engine/      # DocumentParser, EncodingDetector, EditorEngine, HistoryManager,
        │   │                    # LineIndex, DocumentStats, BookmarkManager, Bookmark,
        │   │                    # SearchReplace, SearchOptions, EditorSession(+Manager),
        │   │                    # MagicBytesDetector, DiffEngine
        │   │       └── highlight/# SyntaxHighlighter, SyntaxProfile, SyntaxLanguageRegistry,
        │   │                      # Token, TokenKind
        │   ├── resources/
        │   │   ├── META-INF/services/com.filestudio.plugin.FileStudioPlugin
        │   │   └── logback.xml
        │   └── proto/filestudio.proto
        └── test/java/com/filestudio/
            ├── core/DocumentTest.java
            ├── plugin/PluginManagerTest.java
            ├── plugin/handlers/*HandlerTest.java
            ├── engine/{FileStudioCore,EncodingDetector,EditorEngine,HistoryManager,
            │           LineIndex,DocumentStats,BookmarkManager,SearchReplace}Test.java
            ├── engine/highlight/SyntaxHighlighterTest.java
            └── test/TestRunner.java
```

## Build & test

```bash
# Build everything (downloads JDK 21 toolchain via foojay if needed)
./gradlew build

# Run tests
./gradlew :core-java21:test
./gradlew :desktop:test

# Run the core engine self-demo (no args) or parse a file
./gradlew :core-java21:runDemo
./gradlew :core-java21:runDemo -Pfile=notes.txt

# Launch the desktop application
./gradlew :desktop:run

# Launch with a file (file association / "open with")
./gradlew :desktop:run --args="README.md"

# Package a distributable for the current OS (jpackage-based)
./gradlew :desktop:packageDistributionForCurrentOS

# Or a single self-contained jar
./gradlew :desktop:packageUberJarForCurrentOS
```

The desktop app opens an empty window when given no arguments, or opens the given
files when arguments are passed. Dark theme can be forced with
`-Dfilestudio.theme=dark`.

### Keyboard shortcuts

Press **F1** in the app for the live list.

| | | |
|---|---|---|
| `Ctrl+O` open | `Ctrl+S` save | `Ctrl+W` close tab |
| `Ctrl+Shift+W` close all | `Ctrl+Q` quit | `F1` shortcut help |
| `Ctrl+Z` / `Ctrl+Y` undo | `Ctrl+Shift+Z` redo | `F2` toggle bookmark |
| `Ctrl+F` find | `Ctrl+H` find & replace | `Ctrl+Alt+Enter` replace all |
| `F3` next match | `Shift+F3` previous match | |
| `Ctrl+Tab` next tab | `Ctrl+Shift+Tab` previous tab | |
| `Ctrl+Shift+K` syntax highlight | `Ctrl+Shift+L` line numbers | `Ctrl+Shift+D` details panel |
| `Ctrl+Shift+P` search panel | `Ctrl+Shift+I` diff panel | |

Shortcuts are defined in `desktop/src/main/kotlin/com/filestudio/desktop/Shortcuts.kt`
as data, and applied in `ShortcutDispatcher.kt`. Both are plain Kotlin with no
Compose rendering, so the binding table and every action's effect are covered by
headless unit tests — including a check that no two actions claim the same key.

### Top menu bar

| Menu | Contents |
|---|---|
| 文件 | open, save, recent files, close tab, close all, quit |
| 编辑 | undo, redo, find, find & replace, next/previous match, replace all, bookmark |
| 视图 | syntax highlight, line numbers, word wrap, details panel, font size, panels |
| 工具 | Diff against disk, settings |
| 插件 | registered format handlers (extension / description / capability) |
| 帮助 | shortcut reference, about |

Menu items and keyboard shortcuts both route through `ShortcutDispatcher`, so
"click Save" and "press Ctrl+S" cannot drift apart.

### User settings

Settings are stored as a hand-editable JSON file:

| Platform | Path |
|---|---|
| Windows | `%APPDATA%\FileStudio\settings.json` |
| macOS | `~/Library/Application Support/FileStudio/settings.json` |
| Linux | `$XDG_CONFIG_HOME/FileStudio/settings.json` (else `~/.config/...`) |

```json
{
  "theme": "LIGHT",
  "fontSizeSp": 13,
  "showLineNumbers": true,
  "syntaxHighlight": true,
  "wrapLines": false,
  "showDetailsPanel": true,
  "tabWidth": 4,
  "windowWidth": 1280,
  "windowHeight": 820,
  "recentFiles": ["A:\\path\\to\\file.txt"]
}
```

Configure them from **工具 → 设置…**. Changes apply immediately — there is no
apply/cancel, so what you see is always what is stored.

The file is designed to be tolerant of hand-editing: unknown fields are ignored,
missing or invalid values fall back to defaults per-field, out-of-range numbers
are clamped, and a completely unparseable file degrades to defaults rather than
preventing startup.

Requirements: a JDK 17+ to run Gradle; the build uses the Gradle JVM toolchain to compile
with Java 21 (auto-resolved via the foojay resolver plugin if not installed locally).

> **Memory note**: this project was developed on a machine with heavy memory pressure.
> The Gradle daemon occasionally fails to reserve heap under those conditions. The build
> configuration itself is standard; if the daemon crashes, retry, or run tests directly
> with `java -cp` (see `core-java21/src/test/.../test/TestRunner.java`, which invokes the
> JUnit Platform Launcher without a Gradle test fork).

## Core API quick tour

```java
FileStudioCore core = new FileStudioCore();
core.init();                                  // loads built-in handlers + SPI plugins

// parse / save
Document doc = core.parseFile("notes.txt");   // -> Document{mime=text/plain, ext=txt, FULL}
core.saveDocument(doc.withContent("edited"), "out.txt");

// list supported formats
core.listHandlers().forEach(System.out::println);

// editor session with undo/redo
EditorEngine ed = core.newEditor();
ed.open(core.parseFile("a.txt"));
ed.edit("Hello, world!");
ed.undo();
ed.save("result.txt");

core.shutdown();
```

### Syntax highlighting

```java
SyntaxHighlighter hl = SyntaxLanguageRegistry.forPath("Sample.java");
List<Token> tokens = hl.tokenize(source);
// each Token carries kind (KEYWORD/STRING/COMMENT/...), text, offset, line, column
```

17 language profiles are bundled: Java, Python, JavaScript, TypeScript, C, C++, Go,
Rust, Shell, SQL, JSON, XML, CSS, HTML, Markdown, YAML, plus a plaintext fallback.

### Search & replace

```java
List<SearchReplace.SearchMatch> m =
    SearchReplace.findMatches(text, "query", new SearchOptions(false, true, false, true));
String out = SearchReplace.replaceAll(text, "query", "$0 replacement",
        new SearchOptions(false, false, true, true)); // regex, $0 = whole match
```

### Editor primitives

```java
// offset ⇄ (line, column) mapping — O(log n) per query
LineIndex idx = LineIndex.of(content);
int line = idx.lineOf(offset);   // 0-based
int col   = idx.columnOf(offset);
int off   = idx.offsetOf(line, col);

// status-bar statistics
DocumentStats stats = DocumentStats.of(content);
int lines = stats.lineCount();   // words, chars, code points, longest line, EOL style

// line bookmarks with sidecar persistence (<file>.bookmarks)
BookmarkManager bm = new BookmarkManager();
bm.add(12, "refactor here");
bm.save(docFile);   // writes <docFile>.bookmarks
BookmarkManager reloaded = new BookmarkManager();
reloaded.load(docFile);
```

## Plugin authoring (SPI)

Implement `FileStudioPlugin`, return one or more `FileHandlerFactory` instances, and
register it in your JAR at
`META-INF/services/com.filestudio.plugin.FileStudioPlugin`.
See `com.filestudio.plugin.sample.SamplePlugin` for a minimal example.

For text-like formats, extend `AbstractTextHandler`:

```java
public class MyFormatHandler extends AbstractTextHandler {
    public String getExtension()  { return "myext"; }
    public String getMimeType()   { return "text/x-myext"; }
    public EditCapability getEditCapability() { return EditCapability.FULL; }
    public String getDescription(){ return "My format"; }

    // append format-specific metadata before the Document is built
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        meta.put("myField", content.length());
    }
}
```

The base class handles BOM detection, UTF-8 fallback, a 16 MiB in-memory guard, line
counting, and UTF-8 rendering — subclasses only declare identity and extra metadata.

## License

Proprietary — see planning document for project governance.
