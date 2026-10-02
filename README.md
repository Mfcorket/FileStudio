# FileStudio

> Cross-platform file viewer & editor — **Phase 0 complete, Phase 1 engine work in progress**.
> Version: 0.1.0 · Target: Windows 8.1+, macOS 12+, Linux, Android 9+
> Core: Java 21 · UI (later): Kotlin Multiplatform + Compose Multiplatform · Build: Gradle 8.14

FileStudio is a unified file viewing/editing platform with a plugin-based architecture.
It is **not** a file-format conversion tool — it focuses on opening, viewing, and editing
files across 70+ formats via a single consistent API.

## Status

Phase 0 (core skeleton) is complete and verified. This iteration extends the core engine
with the editing/syntax/search components named in the planning architecture and adds
format handlers, moving toward Phase 1's 30-format target.

| Deliverable | Status |
|-------------|--------|
| Gradle multi-module scaffold (`core-java21`) | ✅ |
| Core data models (`Document`, `EditCapability`, `PluginHandlerInfo`) | ✅ |
| SPI plugin framework (`FileHandler`, `FileHandlerFactory`, `FileStudioPlugin`, `PluginManager`) | ✅ |
| Built-in text handlers: text, JSON, XML, properties, INI, CSV/TSV, Markdown, YAML, TOML, HTML, CSS | ✅ (11 handlers) |
| Binary/metadata handlers: image, ZIP, audio, PDF, font, certificate, video, TAR | ✅ (8 handlers) |
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
| JUnit 5 tests | ✅ (282 tests) |
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
└── core-java21/                 # Java 21 core engine
    └── src/
        ├── main/
        │   ├── java/com/filestudio/
        │   │   ├── core/        # Document, EditCapability, FileStudioCore, PluginHandlerInfo,
        │   │   │                # FileStudioException, FileStudioMain
        │   │   ├── plugin/      # FileHandler, FileHandlerFactory, FileStudioPlugin, PluginManager
        │   │   │   ├── handlers/# AbstractTextHandler + Text/JSON/XML/Properties/INI/CSV/
        │   │   │   │            # Markdown/YAML/TOML/HTML/CSS
        │   │   │   └── sample/  # SamplePlugin (SPI demo)
        │   │   └── engine/      # DocumentParser, EncodingDetector, EditorEngine, HistoryManager,
        │   │                    # LineIndex, DocumentStats, BookmarkManager, Bookmark,
        │   │                    # SearchReplace, SearchOptions
        │   │       └── highlight/# SyntaxHighlighter, SyntaxProfile, SyntaxLanguageRegistry,
        │   │                      # Token, TokenKind
        │   ├── resources/
        │   │   ├── META-INF/services/com.filestudio.plugin.FileStudioPlugin
        │   │   └── logback.xml
        │   └── proto/filestudio.proto
        └── test/java/com/filestudio/
            ├── core/DocumentTest.java
            ├── plugin/PluginManagerTest.java
            ├── plugin/handlers/{Json,Xml,MiscText,ExtendedFormat}HandlerTest.java
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

# Run the core engine self-demo (no args) or parse a file
./gradlew :core-java21:runDemo
./gradlew :core-java21:runDemo -Pfile=notes.txt
```

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
