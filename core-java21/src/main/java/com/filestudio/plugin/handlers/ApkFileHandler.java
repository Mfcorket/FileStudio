package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Android 应用包处理器（APK / AAB / IPA 等 ZIP 型应用包）。
 *
 * <p>PARTIAL 能力——可查看包结构，但不解析二进制 AXML 或提取代码。
 *
 * <p>APK 本质是 ZIP。本实现统计包内关键构件：
 * <ul>
 *   <li>{@code AndroidManifest.xml}（二进制 AXML）与 {@code resources.arsc}</li>
 *   <li>{@code classes*.dex}（代码）</li>
 *   <li>{@code lib/<abi>/*.so}（原生库，含 ABI 列表）</li>
 *   <li>签名文件（{@code META-INF/*.RSA|.DSA|.EC}）与 V2/V3 签名块</li>
 * </ul>
 *
 * <p>AAB（Android App Bundle）与 IPA 结构类似，同样按 ZIP 统计，
 * 但其清单位于 {@code base/manifest/AndroidManifest.xml}。
 *
 * <p>注意：本处理器<b>不</b>解析二进制 {@code AndroidManifest.xml} 的字段
 * （包名、版本号、权限等），需要 XML 反序列化支持；
 * 这里只给出包级结构事实，避免给出未经解析的猜测值。
 */
public class ApkFileHandler implements FileHandler {

    /**
     * Android 应用包处理器。
     */
    public ApkFileHandler() {}

    private static final Set<String> SUPPORTED = Set.of("apk", "aab", "ipa", "xapk", "apks");

    /** 最多列出的最大条目数。 */
    private static final int MAX_LARGEST = 10;

    @Override
    public String getExtension() {
        return "apk";
    }

    @Override
    public String getMimeType() {
        return "application/vnd.android.package-archive";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.PARTIAL;
    }

    @Override
    public String getDescription() {
        return "Android application package";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isAppPackage(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("APK file not readable: " + file);
        }
        Path path = file.toPath();
        String ext = extensionOf(file.getName());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", ext.isEmpty() ? "apk" : ext);
        meta.put("viewOnly", true);

        try (ZipFile zip = new ZipFile(file)) {
            meta.put("valid", true);
            meta.put("entryCount", zip.size());

            List<ZipEntry> entries = new ArrayList<>();
            var it = zip.entries();
            while (it.hasMoreElements()) {
                entries.add(it.nextElement());
            }
            List<String> dex = new ArrayList<>();
            List<String> nativeLibs = new ArrayList<>();
            Set<String> abis = new LinkedHashSet<>();
            Set<String> signers = new LinkedHashSet<>();
            long manifestBytes = -1;
            long resourcesArscBytes = -1;
            long assetBytes = 0;
            boolean hasBundleManifest = false;

            List<Map<String, Object>> largest = new ArrayList<>();

            for (ZipEntry e : entries) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                long size = e.getSize();

                if (name.equals("AndroidManifest.xml")
                        || name.equals("base/manifest/AndroidManifest.xml")) {
                    manifestBytes = size;
                    hasBundleManifest = name.startsWith("base/");
                } else if (name.equals("resources.arsc")) {
                    resourcesArscBytes = size;
                } else if (name.endsWith(".dex")) {
                    dex.add(name);
                } else if (name.startsWith("lib/")) {
                    nativeLibs.add(name);
                    // lib/<abi>/libfoo.so
                    String[] parts = name.split("/");
                    if (parts.length >= 3) {
                        abis.add(parts[1]);
                    }
                } else if (name.startsWith("META-INF/")
                        && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                    signers.add(name.substring("META-INF/".length()));
                } else if (name.startsWith("assets/")) {
                    assetBytes += Math.max(0, size);
                }

                if (size > 0) {
                    largest.add(Map.of("name", name, "size", size));
                }
            }

            meta.put("hasAndroidManifest", manifestBytes >= 0);
            if (manifestBytes >= 0) {
                meta.put("manifestSize", manifestBytes);
                meta.put("bundleLayout", hasBundleManifest);
            }
            if (resourcesArscBytes >= 0) {
                meta.put("resourcesArscSize", resourcesArscBytes);
            }
            meta.put("dexCount", dex.size());
            if (!dex.isEmpty()) {
                meta.put("dexFiles", dex);
            }
            meta.put("nativeLibraryCount", nativeLibs.size());
            if (!abis.isEmpty()) {
                meta.put("abis", new ArrayList<>(abis));
            }
            if (assetBytes > 0) {
                meta.put("assetBytes", assetBytes);
            }

            // 签名：v1 看 META-INF，v2/v3 在 ZIP 注释区
            meta.put("v1Signed", !signers.isEmpty());
            if (!signers.isEmpty()) {
                meta.put("signers", new ArrayList<>(signers));
            }
            List<String> apkSigningBlock = findApkSigningBlock(file);
            if (apkSigningBlock != null) {
                meta.put("apkSigningBlockPresent", true);
                meta.put("apkSigningBlockIds", apkSigningBlock);
            }

            // 体积最大的条目
            largest.sort((a, b) -> Long.compare((Long) b.get("size"), (Long) a.get("size")));
            if (!largest.isEmpty()) {
                meta.put("largestEntries",
                        new ArrayList<>(largest.subList(0, Math.min(MAX_LARGEST, largest.size()))));
            }

            // 明确边界，避免调用方误以为已拿到包名/版本号
            meta.put("manifestParsed", false);
            meta.put("manifestNote", "二进制 AndroidManifest.xml 需要 AXML 反序列化，本处理器未解析");
        } catch (IOException e) {
            meta.put("valid", false);
            meta.put("error", "Failed to read package: " + e.getMessage());
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("App packages are read-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("entryCount", doc.getMetadata().get("entryCount"));
            meta.put("dexCount", doc.getMetadata().get("dexCount"));
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static boolean isAppPackage(File file) {
        String ext = extensionOf(file.getName());
        boolean known = SUPPORTED.contains(ext);
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] head = in.readNBytes(4);
            if (head.length < 4) {
                return false;
            }
            boolean zip = head[0] == 'P' && head[1] == 'K'
                    && (head[2] == 0x03 || head[2] == 0x05 || head[2] == 0x07);
            if (!zip) {
                return false;
            }
            // ZIP 也可能是普通压缩包：需要看到 AndroidManifest 或已知扩展名
            if (known) {
                return true;
            }
            try (ZipFile zf = new ZipFile(file)) {
                return zf.getEntry("AndroidManifest.xml") != null
                        || zf.getEntry("base/manifest/AndroidManifest.xml") != null
                        || zf.getEntry("Payload/") != null;   // IPA
            }
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 查找 APK Signing Block（v2/v3 签名）。
     *
     * <p>结构位于 EOCD 之前，块内含一组以 64 位长度（4 字节 id + 4 字节长度）
     * 描述的 pair。本实现只收集 block id，不解析签名内容。
     *
     * @return 找到的 block id 列表；没有则返回 {@code null}
     */
    private static List<String> findApkSigningBlock(File file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
            long size = raf.length();
            if (size < 32) {
                return null;
            }
            // EOCD 最多 22 + 65535 字节
            int tailLen = (int) Math.min(size, 22 + 65535);
            byte[] tail = new byte[tailLen];
            raf.seek(size - tailLen);
            raf.readFully(tail);

            int eocd = -1;
            for (int i = tail.length - 22; i >= 0; i--) {
                if ((tail[i] & 0xFF) == 0x50 && (tail[i + 1] & 0xFF) == 0x4B
                        && (tail[i + 2] & 0xFF) == 0x05 && (tail[i + 3] & 0xFF) == 0x06) {
                    eocd = i;
                    break;
                }
            }
            if (eocd < 0) {
                return null;
            }
            long cdOffset = le32(tail, eocd + 16);
            if (cdOffset <= 0 || cdOffset >= size) {
                return null;
            }
            // 读取 EOCD 之前的 "APK Sig Block 42" 魔数
            byte[] magic = new byte[16];
            long magicPos = cdOffset - 16;
            if (magicPos < 0) {
                return null;
            }
            raf.seek(magicPos);
            raf.readFully(magic);
            String s = new String(magic, java.nio.charset.StandardCharsets.US_ASCII);
            if (!s.contains("APK Sig Block")) {
                return null;
            }

            // 该 16 字节之前是 (size-of-block) 8 字节 + pairs 的总长度 8 字节
            raf.seek(magicPos - 8);
            byte[] sizeBuf = new byte[8];
            raf.readFully(sizeBuf);
            long blockSize = readLe64(sizeBuf, 0);
            long pairsSize = blockSize - 24;   // 去掉两个 size 字段与魔数
            if (pairsSize <= 0 || pairsSize > size) {
                return null;
            }
            byte[] pairs = new byte[(int) Math.min(pairsSize, 1024 * 1024)];
            raf.seek(magicPos - 16 - pairsSize);
            raf.readFully(pairs);

            List<String> ids = new ArrayList<>();
            int p = 0;
            while (p + 12 <= pairs.length) {
                long pairLen = le32(pairs, p);
                if (pairLen <= 0 || p + 8 + pairLen > pairs.length) {
                    break;
                }
                long id = le32(pairs, p + 4);
                ids.add(String.format("0x%08X", id));
                p += 8 + (int) pairLen;
            }
            return ids.isEmpty() ? null : ids;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int le32(byte[] b, int off) {
        if (off + 4 > b.length) {
            return -1;
        }
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static long readLe64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }
}