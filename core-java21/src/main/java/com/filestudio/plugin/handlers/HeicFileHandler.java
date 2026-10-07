package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * HEIF / HEIC 图像处理器：提取品牌、图像尺寸、图像项数量与编码类型。
 *
 * <p>VIEW_ONLY 能力——图像不可作为文本编辑。
 *
 * <p>HEIF 基于 ISO-BMFF 盒结构：{@code size(4) + type(4) + payload}，
 * 关键盒：
 * <ul>
 *   <li>{@code ftyp} —— major brand + compatible brands</li>
 *   <li>{@code meta} → {@code hdlr} / {@code pitm} / {@code iinf} / {@code iprp}</li>
 *   <li>{@code ispe} —— image spatial extent，其 payload 末 8 字节为 width/height</li>
 *   <li>{@code infe} —— item info，含 item_type（如 {@code hvc1}/{@code hev1}）</li>
 * </ul>
 *
 * <p>同时覆盖同一容器族的 AVIF（brand {@code avif}），二者结构一致。
 * 注意：本处理器<b>不</b>解码图像像素，仅读取容器级元数据。
 */
public class HeicFileHandler implements FileHandler {

    /**
     * HEIF / HEIC / AVIF 处理器。
     */
    public HeicFileHandler() {}

    private static final Set<String> SUPPORTED =
            Set.of("heic", "heif", "heix", "hevc", "heim", "heis", "hevm", "hevs", "avif", "avis");

    /** HEIF 图像系列的 major brand。 */
    private static final Set<String> HEIF_BRANDS = Set.of(
            "heic", "heix", "heim", "heis", "hevc", "hevm", "hevs", "mif1", "msf1");
    private static final Set<String> AVIF_BRANDS = Set.of("avif", "avis");

    /** 读取上限：盒头解析不需要图像数据本身。 */
    private static final int MAX_READ = 4 * 1024 * 1024;

    /** 最多报告的图像项数。 */
    private static final int MAX_ITEMS = 64;

    @Override
    public String getExtension() {
        return "heic";
    }

    @Override
    public String getMimeType() {
        return "image/heic";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "HEIF/HEIC/AVIF image";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            return isHeif(file);
        }
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("HEIF file not readable: " + file);
        }
        Path path = file.toPath();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("viewOnly", true);

        byte[] data;
        try {
            data = readPrefix(path, MAX_READ);
        } catch (IOException e) {
            throw new FileStudioException("Failed reading HEIF: " + path, e);
        }

        String ext = extensionOf(file.getName());
        meta.put("format", ext.isEmpty() ? "heif" : ext);

        try {
            // 必须用 equals：boxType 返回的是 new String(...)，与字面量做 != 引用比较恒为 true
            if (data.length < 12 || !"ftyp".equals(boxType(data, 0))) {
                meta.put("valid", false);
                meta.put("error", "Missing ftyp box — not an ISO-BMFF file");
                return new Document(path, getMimeType(), getExtension(), "", meta,
                        file.length(), getEditCapability());
            }

            List<String> brands = readBrands(data);
            meta.put("brands", brands);
            meta.put("majorBrand", brands.isEmpty() ? null : brands.get(0));
            boolean avif = brands.stream().anyMatch(AVIF_BRANDS::contains);
            boolean heif = brands.stream().anyMatch(HEIF_BRANDS::contains);
            if (avif) {
                meta.put("format", "avif");
            } else if (ext.isEmpty() && heif) {
                meta.put("format", "heif");
            }
            meta.put("valid", true);

            Walk walk = walk(data);
            if (walk.width > 0) meta.put("width", walk.width);
            if (walk.height > 0) meta.put("height", walk.height);
            if (walk.width > 0 && walk.height > 0) {
                meta.put("megapixels",
                        Math.round(walk.width * (double) walk.height / 1_000_000.0 * 100) / 100.0);
            }
            meta.put("itemCount", walk.itemCount);
            if (!walk.itemTypes.isEmpty()) {
                meta.put("itemTypes", new ArrayList<>(walk.itemTypes));
                meta.put("codec", codecName(walk.itemTypes.iterator().next()));
            }
            if (walk.hasAlpha) {
                meta.put("hasAlpha", true);
            }
            meta.put("gridDerived", walk.gridCount > 0);
            if (walk.gridCount > 0) {
                // grid 盒是"渐变图描述符"，数量通常为 1；真正的分块数由 itemCount 体现
                meta.put("gridDescriptorCount", walk.gridCount);
            }
            // 明确标注只解析了容器元数据，避免调用方误以为拿到了像素
            meta.put("parsedFromHeaderOnly", true);
        } catch (RuntimeException e) {
            meta.put("valid", false);
            meta.put("error", "Failed to parse HEIF container: " + e);
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("HEIF files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            Document doc = parse(file);
            meta.put("format", doc.getMetadata().get("format"));
            meta.put("width", doc.getMetadata().get("width"));
            meta.put("height", doc.getMetadata().get("height"));
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    /** 遍历结果。 */
    private static final class Walk {
        int width;
        int height;
        int itemCount;
        int gridCount;
        boolean hasAlpha;
        final Set<String> itemTypes = new LinkedHashSet<>();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String boxType(byte[] d, int off) {
        if (off + 4 > d.length) {
            return "";
        }
        return new String(d, off + 4, 4, StandardCharsets.US_ASCII);
    }

    /** 读取 ftyp 的 major brand 与 compatible brands。 */
    private static List<String> readBrands(byte[] d) {
        List<String> out = new ArrayList<>();
        long size = boxSize(d, 0);
        int start = 8;                      // 跳过 size + type
        int end = (int) Math.min(size, d.length);
        if (start + 4 > end) {
            return out;
        }
        out.add(new String(d, start, 4, StandardCharsets.US_ASCII));   // major_brand
        // start+4..start+7 为 minor_version，与元数据展示无关，跳过
        int compatStart = start + 8;
        for (int i = compatStart; i + 4 <= end; i += 4) {
            out.add(new String(d, i, 4, StandardCharsets.US_ASCII));
        }
        return out;
    }

    /**
     * 返回盒的声明长度。
     *
     * <p>size 为 1 时表示后续 8 字节是真实的 64 位长度。
     */
    private static long boxSize(byte[] d, int off) {
        if (off + 4 > d.length) {
            return 0;
        }
        long size = ((long) (d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
        if (size == 1) {
            if (off + 16 > d.length) {
                return 0;
            }
            long big = 0;
            for (int i = 0; i < 8; i++) {
                big = (big << 8) | (d[off + 8 + i] & 0xFFL);
            }
            return big;
        }
        return size;
    }

    /** 遍历顶层及 meta/ipco 下的盒，收集 ispe 尺寸与 infe 项类型。 */
    private static Walk walk(byte[] d) {
        Walk w = new Walk();
        scan(d, 0, d.length, w, 0);
        return w;
    }

    private static void scan(byte[] d, int start, int end, Walk w, int depth) {
        if (depth > 8) {
            return;
        }
        int off = start;
        int guard = 0;
        while (off + 8 <= end && guard++ < 4096) {
            long size = boxSize(d, off);
            String type = boxType(d, off);
            int payload = off + 8;
            if (size == 1) {
                payload = off + 16;         // 64 位长度形式
            } else if (size == 0) {
                // 长度为 0 表示延伸到文件末尾
                size = end - off;
            }
            if (size < 8) {
                return;
            }
            int boxEnd = (int) Math.min(off + size, end);
            if (boxEnd <= off) {
                return;
            }

            switch (type) {
                case "ispe" -> {
                    // payload: version(1)+flags(3)+width(4)+height(4)
                    int base = payload + 4;
                    if (base + 8 <= boxEnd && (w.width == 0 || w.height == 0)) {
                        w.width = u32(d, base);
                        w.height = u32(d, base + 4);
                    }
                }
                case "infe" -> {
                    w.itemCount++;
                    if (w.itemTypes.size() < MAX_ITEMS) {
                        String t = readItemType(d, payload, boxEnd);
                        if (t != null) {
                            w.itemTypes.add(t);
                        }
                    }
                    // item_type 为 'auxC' 表示透明度辅助项
                    if ("auxC".equals(readItemType(d, payload, boxEnd))) {
                        w.hasAlpha = true;
                    }
                }
                case "grid" -> w.gridCount++;
                case "meta" -> {
                    // meta 是 FullBox：payload 前 4 字节为 version+flags，子盒紧随其后
                    int childStart = Math.min(payload + 4, boxEnd);
                    scan(d, childStart, boxEnd, w, depth + 1);
                }
                case "iinf" -> {
                    // iinf 是 FullBox，且其后还有 entry_count：
                    //   version 0 → 2 字节；version 1/2 → 4 字节
                    int childStart;
                    if (payload < boxEnd) {
                        int version = d[payload] & 0xFF;
                        childStart = payload + 4 + (version == 0 ? 2 : 4);
                    } else {
                        childStart = payload;
                    }
                    scan(d, Math.min(childStart, boxEnd), boxEnd, w, depth + 1);
                }
                // iprp / ipco 是普通容器盒，子项紧随 payload
                case "iprp", "ipco", "moov", "trak" ->
                        scan(d, payload, boxEnd, w, depth + 1);
                default -> {
                    // 其他盒类型跳过
                }
            }
            off = boxEnd;
        }
    }

    /** 读取 infe 盒中的 item_type。兼容 version 2/3。 */
    private static String readItemType(byte[] d, int payload, int boxEnd) {
        if (payload + 8 > boxEnd) {
            return null;
        }
        int version = d[payload] & 0xFF;
        // version 2/3: version+flags(4) + item_ID(2) + item_protection_index(2) + item_type(4)
        if (version == 2 || version == 3) {
            int at = payload + 8;
            if (at + 4 > boxEnd) {
                return null;
            }
            return new String(d, at, 4, StandardCharsets.US_ASCII);
        }
        // version 0/1: version+flags(4) + item_ID(2) + [protection(2)] + item_name(空串结尾)
        int at = payload + 6;
        if (at > boxEnd) {
            return null;
        }
        // 旧版本不含 item_type，无法判定
        return null;
    }

    private static String codecName(String itemType) {
        return switch (itemType) {
            case "hvc1" -> "HEVC (H.265)";
            case "hev1" -> "HEVC (H.265)";
            case "av01" -> "AV1";
            case "vp09" -> "VP9";
            case "jpeg" -> "JPEG";
            case "mjpg" -> "Motion JPEG";
            case "grid" -> "grid (derived)";
            default -> itemType;
        };
    }

    private static boolean isHeif(File file) {
        try {
            byte[] head = readPrefix(file.toPath(), 64);
            if (head.length < 12 || !"ftyp".equals(boxType(head, 0))) {
                return false;
            }
            String major = new String(head, 8, 4, StandardCharsets.US_ASCII);
            if (HEIF_BRANDS.contains(major) || AVIF_BRANDS.contains(major)) {
                return true;
            }
            // major_brand 之外再看 compatible brands
            long size = boxSize(head, 0);
            int end = (int) Math.min(size, head.length);
            for (int i = 16; i + 4 <= end; i += 4) {
                String b = new String(head, i, 4, StandardCharsets.US_ASCII);
                if (HEIF_BRANDS.contains(b) || AVIF_BRANDS.contains(b)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readPrefix(Path path, int len) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(len);
        }
    }

    private static int u32(byte[] d, int off) {
        if (off < 0 || off + 4 > d.length) {
            return 0;
        }
        return ((d[off] & 0xFF) << 24) | ((d[off + 1] & 0xFF) << 16)
                | ((d[off + 2] & 0xFF) << 8) | (d[off + 3] & 0xFF);
    }
}
