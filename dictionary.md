# FileStudio 文件类型字典

> Phase 0 草案。首期目标：**80+ 格式**，后续随插件扩展逐步覆盖。
> 编辑深度：FULL=完整编辑 · PARTIAL=部分/导出 · VIEW_ONLY=仅查看
>
> 标记 **\[内置\]** 的格式已有专用处理器（见 `com.filestudio.plugin.handlers`）；
> 其余文本类格式由 `TextFileHandler` 兜底解析。
> 语法高亮覆盖 16 种语言（见 `SyntaxLanguageRegistry`），其中部分尚未配专用格式处理器。

## 1. 文本文件（FULL）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| txt | text/plain | FULL | 纯文本（内置） |
| log | text/plain | FULL | 日志文件 |
| ini | text/plain | FULL | INI 配置（内置 IniFileHandler） |
| cfg | text/plain | FULL | 通用配置 |
| conf | text/plain | FULL | 配置 |
| properties | application/x-java-properties | FULL | Java 属性文件（内置 PropertiesFileHandler） |
| text | text/plain | FULL | 纯文本 |

## 2. 代码文件（FULL）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| java | text/x-java-source | FULL | Java |
| kt, kts | text/x-kotlin | FULL | Kotlin |
| py | text/x-python | FULL | Python |
| js | text/javascript | FULL | JavaScript |
| ts | text/typescript | FULL | TypeScript |
| go | text/x-go | FULL | Go |
| rs | text/rust | FULL | Rust |
| c, h | text/x-c | FULL | C/C++ 头/源 |
| cpp, hpp | text/x-c++src | FULL | C++ |
| cs | text/x-csharp | FULL | C# |
| rb | text/x-ruby | FULL | Ruby |
| php | text/x-php | FULL | PHP |
| swift | text/x-swift | FULL | Swift |
| scala | text/x-scala | FULL | Scala |
| lua | text/x-lua | FULL | Lua |
| sh | application/x-sh | FULL | Shell |
| ps1 | text/x-powershell | FULL | PowerShell |
| sql | application/sql | FULL | SQL |

## 3. 标记类（FULL）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| md | text/markdown | FULL | Markdown（内置 MarkdownFileHandler） |
| markdown | text/markdown | FULL | Markdown（内置） |
| rst | text/x-rst | FULL | reStructuredText |
| adoc | text/x-asciidoc | FULL | AsciiDoc |
| html, htm, xhtml | text/html | FULL | HTML（内置 HtmlFileHandler，标题/深度统计） |
| css | text/css | FULL | CSS（内置 CssFileHandler，规则/属性统计） |
| tex | application/x-tex | FULL | LaTeX |

## 4. 数据交换类（FULL）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| json | application/json | FULL | JSON（内置 JsonFileHandler，校验+结构元数据） |
| yaml, yml | application/yaml | FULL | YAML（内置 YamlFileHandler，结构统计） |
| xml | application/xml | FULL | XML（内置 XmlFileHandler，格式校验） |
| toml | application/toml | FULL | TOML（内置 TomlFileHandler，表/键统计） |
| csv | text/csv | FULL | CSV（内置 CsvFileHandler，分隔符/行列统计） |
| tsv | text/tab-separated-values | FULL | TSV（内置 CsvFileHandler） |
| xml plist | application/plist+xml | FULL | plist |

## 5. 压缩归档类（PARTIAL）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| zip | application/zip | PARTIAL | ZIP |
| 7z | application/x-7z-compressed | PARTIAL | 7z |
| tar | application/x-tar | PARTIAL | tar \[内置\] TarArchiveHandler |
| gz, gzip, tgz | application/gzip | PARTIAL | gzip \[内置\] GzipArchiveHandler |
| rar | application/vnd.rar | PARTIAL | RAR |
| bz2 | application/x-bzip2 | PARTIAL | bzip2 |

## 6. 图片类（PARTIAL）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| png | image/png | PARTIAL | PNG |
| jpg, jpeg | image/jpeg | PARTIAL | JPEG |
| gif | image/gif | PARTIAL | GIF |
| webp | image/webp | PARTIAL | WebP |
| bmp | image/bmp | PARTIAL | BMP |
| svg | image/svg+xml | FULL | SVG（文本可编辑） |
| ico, cur | image/x-icon | VIEW_ONLY | Windows 图标 \[内置\] IcoFileHandler 各尺寸与位深 |
| heic, heif, heix, hevc, heim, heis, hevm, hevs | image/heic | VIEW_ONLY | HEIF/HEIC \[内置\] HeicFileHandler 尺寸/品牌/编码类型 |
| avif, avis | image/avif | VIEW_ONLY | AVIF \[内置\] 同一 ISO-BMFF 容器 |
| tif, tiff | image/tiff | VIEW_ONLY | TIFF \[内置\] TiffFileHandler 尺寸/位深/压缩/DPI |

## 7. 音频类（VIEW_ONLY）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| mp3 | audio/mpeg | VIEW_ONLY | MP3 |
| wav | audio/wav | VIEW_ONLY | WAV |
| flac | audio/flac | VIEW_ONLY | FLAC |
| aac | audio/aac | VIEW_ONLY | AAC |
| ogg | audio/ogg | VIEW_ONLY | OGG |
| m4a | audio/mp4 | VIEW_ONLY | M4A |

## 8. 视频类（VIEW_ONLY）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| mp4 | video/mp4 | VIEW_ONLY | MP4 \[内置\] VideoFileHandler |
| mkv | video/x-matroska | VIEW_ONLY | Matroska \[内置\] 解析 EBML Info/Tracks |
| avi | video/x-msvideo | VIEW_ONLY | AVI \[内置\] VideoFileHandler |
| mov | video/quicktime | VIEW_ONLY | MOV \[内置\] VideoFileHandler |
| webm | video/webm | VIEW_ONLY | WebM \[内置\] 解析 EBML Info/Tracks |
| flv, wmv | video/* | — | 暂不支持（避免仅识别容器却无元数据） |

## 9. 文档类（PARTIAL）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| pdf | application/pdf | PARTIAL | PDF |
| epub | application/epub+zip | PARTIAL | EPUB 电子书 \[内置\] EpubFileHandler 书名/作者/章节数 |
| docx | application/vnd.openxmlformats | PARTIAL | Word |
| xlsx | application/vnd.openxmlformats | PARTIAL | Excel |
| pptx | application/vnd.openxmlformats | PARTIAL | PowerPoint |
| odt | application/vnd.oasis.opendocument | PARTIAL | ODT |

## 10. 数据库类（VIEW_ONLY）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| sqlite, sqlite3, db, db3 | application/vnd.sqlite3 | VIEW_ONLY | SQLite \[内置\] SqliteFileHandler（页大小/编码/表数量） |
| parquet | application/vnd.parquet | VIEW_ONLY | Parquet |

## 11. 字体类（VIEW_ONLY）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| ttf | font/ttf | VIEW_ONLY | TrueType |
| otf | font/otf | VIEW_ONLY | OpenType |
| woff | font/woff | VIEW_ONLY | WOFF |
| woff2 | font/woff2 | VIEW_ONLY | WOFF2 |

## 12. 证书/密钥类（VIEW_ONLY）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| pem, crt, cer, der | application/x-pem-file | VIEW_ONLY | X.509 证书 \[内置\] CertificateFileHandler |
| p12, pfx | application/x-pkcs12 | VIEW_ONLY | PKCS#12 密钥库 \[内置\] 读取别名/条目/是否含私钥 |
| key | application/x-pem-file | VIEW_ONLY | PEM 私钥 \[内置\] PKCS#8 / PKCS#1，识别 RSA/EC/DSA/Ed25519 |

## 13. 3D 文件（VIEW_ONLY）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| obj | model/obj | VIEW_ONLY | Wavefront OBJ \[内置\] 顶点/法线/纹理/面统计、对象与材质名 |
| stl | model/stl | VIEW_ONLY | STL \[内置\] 二进制与 ASCII 双编码、包围盒 |
| gltf, glb | model/gltf+json | VIEW_ONLY | glTF 2.0 \[内置\] JSON 与二进制容器、asset 信息 |
| fbx | model/fbx | VIEW_ONLY | FBX |

## 14. 游戏资产（VIEW_ONLY）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| unity | application/x-unity | VIEW_ONLY | Unity 场景 |
| uasset | application/x-uasset | VIEW_ONLY | UE 资产 |
| pak | application/x-pak | VIEW_ONLY | UE 包 |

## 15. 移动应用（PARTIAL）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| apk | application/vnd.android.package-archive | PARTIAL | Android 包 |
| aab | application/vnd.android.package-bundle | PARTIAL | Android App Bundle |
| ipa | application/octet-stream | PARTIAL | iOS 包 |

## 16. 字节码 / 可执行文件（VIEW_ONLY）
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| class | application/java-vm | VIEW_ONLY | Java 字节码 \[内置\] JavaClassFileHandler（编译版本/类名/修饰符） |

## 17. 自定义/示例
| 扩展名 | MIME | 编辑 | 说明 |
|--------|------|------|------|
| sample | text/x-sample | FULL | FileStudio 示例插件格式（内置） |

---

### 字段说明
- **编辑深度**：FULL=可读取+修改+回写原格式；PARTIAL=可解析/提取但不能无损回写；VIEW_ONLY=仅展示
- **(内置)**：已有专用 Handler（`com.filestudio.plugin.handlers`）；其余文本类由 `TextFileHandler` 兜底
- `MagicBytesDetector` 已能识别但尚无专用 Handler 的格式：7z、rar、parquet、exe、elf、ole
- 其它格式的专用 Handler 将在 Phase 1/2 通过插件实现
