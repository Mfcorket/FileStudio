package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 音频格式处理器：从文件头解析时长、采样率、声道、位深等元数据。
 *
 * <p>支持：WAV / FLAC / MP3 / OGG / AAC(ADTS) / M4A。
 * VIEW_ONLY 能力——音频不可作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>WAV：解析 RIFF 头 + fmt 块 + data 块大小</li>
 *   <li>FLAC：解析 STREAMINFO 元数据块</li>
 *   <li>MP3：扫描帧头估算时长（无 Xing 头时）</li>
 *   <li>OGG：解析 Vorbis 标识头</li>
 *   <li>AAC：解析 ADTS 帧头（采样率索引/声道/比特率）</li>
 *   <li>M4A：解析 MP4 ftyp 品牌 + moov/mvhd/mdhd（时长与采样率）</li>
 * </ul>
 */
public class AudioFileHandler implements FileHandler {

    /**
     * 音频格式处理器。
     */
    public AudioFileHandler() {}

    private static final Set<String> SUPPORTED =
            Set.of("wav", "flac", "mp3", "ogg", "oga", "aac", "m4a", "m4b");

    @Override
    public String getExtension() {
        return "wav";
    }

    @Override
    public String getMimeType() {
        return "audio/wav";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Audio file (WAV/FLAC/MP3/OGG)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) return false;
        if (file.isFile()) {
            // 文件存在：按魔数校验内容
            return MagicBytesBridge.isAudio(file);
        }
        // 文件不存在（假设路径）：按扩展名
        return SUPPORTED.contains(extensionOf(file.getName()));
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Audio file not readable: " + file);
        }
        Path path = file.toPath();
        byte[] head = readHead(path, 65536);
        String realExt = MagicBytesBridge.audioExtension(head);
        if (realExt.isEmpty()) {
            // 魔数识别失败时回退到扩展名
            realExt = extensionOf(file.getName());
        }
        String mime = mimeFor(realExt);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", realExt.isEmpty() ? extensionOf(file.getName()) : realExt);
        meta.put("viewOnly", true);

        AudioInfo info = parseAudioInfo(realExt, head, file.length());
        if (info != null) {
            if (info.durationSeconds() > 0) {
                meta.put("durationSeconds", info.durationSeconds());
                meta.put("duration", formatDuration(info.durationSeconds()));
            }
            if (info.sampleRate() > 0) meta.put("sampleRate", info.sampleRate());
            if (info.channels() > 0) meta.put("channels", info.channels());
            if (info.bitsPerSample() > 0) meta.put("bitsPerSample", info.bitsPerSample());
            if (info.bitrate() > 0) meta.put("bitrate", info.bitrate());
        } else {
            meta.put("metadata", "unavailable");
        }

        return new Document(path, mime, realExt.isEmpty() ? extensionOf(file.getName()) : realExt,
                "", meta, file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Audio files are view-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) return Map.of();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("lastModified", file.lastModified());
        try {
            byte[] head = readHead(file.toPath(), 65536);
            String ext = MagicBytesBridge.audioExtension(head);
            meta.put("format", ext.isEmpty() ? extensionOf(file.getName()) : ext);
            AudioInfo info = parseAudioInfo(ext, head, file.length());
            if (info != null && info.durationSeconds() > 0) {
                meta.put("durationSeconds", info.durationSeconds());
            }
        } catch (FileStudioException ignored) {
            // 尽力而为
        }
        return meta;
    }

    // ---- 内部 ----

    private record AudioInfo(double durationSeconds, int sampleRate, int channels,
                            int bitsPerSample, int bitrate) {}

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static byte[] readHead(Path path, int len) {
        byte[] head = new byte[len];
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {
            int n = in.read(head);
            if (n < 0) n = 0;
            byte[] trimmed = new byte[n];
            System.arraycopy(head, 0, trimmed, 0, n);
            return trimmed;
        } catch (IOException e) {
            throw new FileStudioException("Failed reading audio header: " + path, e);
        }
    }

    /** 依据魔数判断音频类型并解析元数据。 */
    static AudioInfo parseAudioInfo(String ext, byte[] h, long fileSize) {
        if (h.length == 0) return null;
        try {
            return switch (ext) {
                case "wav" -> parseWav(h);
                case "flac" -> parseFlac(h);
                case "mp3" -> parseMp3(h, fileSize);
                case "ogg", "oga" -> parseOgg(h);
                case "aac" -> parseAac(h, fileSize);
                case "m4a", "m4b" -> parseM4a(h);
                default -> null;
            };
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
    }

    private static AudioInfo parseWav(byte[] h) {
        if (h.length < 44) return null;
        if (h[0] != 'R' || h[1] != 'I' || h[2] != 'F' || h[3] != 'F') return null;
        // 查找 fmt 块
        int pos = 12;
        int sampleRate = 0, channels = 0, bitsPerSample = 0, byteRate = 0;
        int dataSize = 0;
        while (pos + 8 <= h.length) {
            String chunkId = new String(h, pos, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int chunkSize = leInt(h, pos + 4);
            if (chunkId.equals("fmt ")) {
                if (pos + 24 > h.length) break;
                channels = leShort(h, pos + 10);
                sampleRate = leInt(h, pos + 12);
                byteRate = leInt(h, pos + 16);
                bitsPerSample = leShort(h, pos + 22);
            } else if (chunkId.equals("data")) {
                dataSize = chunkSize;
                break;
            }
            pos += 8 + chunkSize + (chunkSize & 1); // 对齐到偶数
        }
        double duration = (byteRate > 0) ? (double) dataSize / byteRate : 0;
        int bitrate = (byteRate > 0) ? byteRate * 8 / 1000 : 0;
        return new AudioInfo(duration, sampleRate, channels, bitsPerSample, bitrate);
    }

    private static AudioInfo parseFlac(byte[] h) {
        if (h.length < 42) return null;
        if (h[0] != 'f' || h[1] != 'L' || h[2] != 'a' || h[3] != 'C') return null;
        // 查找 STREAMINFO 元数据块（类型 0）
        int pos = 4;
        while (pos + 4 <= h.length) {
            int blockHeader = h[pos] & 0xFF;
            int blockType = blockHeader & 0x7F;
            boolean last = (blockHeader & 0x80) != 0;
            int blockSize = (h[pos + 1] & 0xFF) << 16 | (h[pos + 2] & 0xFF) << 8 | (h[pos + 3] & 0xFF);
            if (blockType == 0 && pos + 4 + 34 <= h.length) { // STREAMINFO
                int sampleRate = ((h[pos + 14] & 0xFF) << 12) | ((h[pos + 15] & 0xFF) << 4)
                        | ((h[pos + 16] & 0xFF) >> 4);
                int channels = ((h[pos + 16] & 0x0E) >> 1) + 1;
                int bitsPerSample = (((h[pos + 16] & 0x01) << 4) | ((h[pos + 17] & 0xF0) >> 4)) + 1;
                long totalSamples = ((long) (h[pos + 17] & 0x0F) << 32)
                        | ((long) (h[pos + 18] & 0xFF) << 24)
                        | ((long) (h[pos + 19] & 0xFF) << 16)
                        | ((long) (h[pos + 20] & 0xFF) << 8)
                        | ((long) (h[pos + 21] & 0xFF));
                double duration = (sampleRate > 0 && totalSamples > 0)
                        ? (double) totalSamples / sampleRate : 0;
                return new AudioInfo(duration, sampleRate, channels, bitsPerSample, 0);
            }
            if (last) break;
            pos += 4 + blockSize;
        }
        return null;
    }

    private static AudioInfo parseMp3(byte[] h, long fileSize) {
        // 扫描帧头：MPEG 音频帧同步 0xFFE
        int pos = 0;
        int frameCount = 0;
        int sampleRate = 0;
        int bitrate = 0;
        while (pos + 4 <= h.length) {
            if ((h[pos] & 0xFF) == 0xFF && (h[pos + 1] & 0xE0) == 0xE0) {
                int version = (h[pos + 1] >> 3) & 0x03;
                int layer = (h[pos + 1] >> 1) & 0x03;
                int bitrateIndex = (h[pos + 2] >> 4) & 0x0F;
                int sampleRateIndex = (h[pos + 2] >> 2) & 0x03;
                if (version != 1 && layer != 0 && bitrateIndex > 0 && bitrateIndex < 15
                        && sampleRateIndex < 3) {
                    int[] bitrates = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
                    int[] sampleRates = {44100, 48000, 32000};
                    bitrate = bitrates[bitrateIndex];
                    sampleRate = sampleRates[sampleRateIndex];
                    int frameLen = (version == 3) // MPEG1
                            ? (144 * bitrate * 1000) / sampleRate
                            : (72 * bitrate * 1000) / sampleRate;
                    if (frameLen > 0) {
                        frameCount++;
                        pos += frameLen;
                        continue;
                    }
                }
            }
            pos++;
        }
        double duration = (bitrate > 0) ? (double) fileSize * 8 / (bitrate * 1000) : 0;
        return frameCount > 0 ? new AudioInfo(duration, sampleRate, 2, 0, bitrate) : null;
    }

    private static AudioInfo parseOgg(byte[] h) {
        if (h.length < 27) return null;
        if (h[0] != 'O' || h[1] != 'g' || h[2] != 'g' || h[3] != 'S') return null;
        // Vorbis 标识头：字节 28-31 采样率 LE
        if (h.length < 32) return null;
        int sampleRate = leInt(h, 28);
        int channels = h[11] & 0xFF;
        return sampleRate > 0 ? new AudioInfo(0, sampleRate, channels, 0, 0) : null;
    }

    /**
     * 解析 AAC ADTS 帧头。
     *
     * <p>ADTS 头：同步字 0xFFF（12 位），随后是 ID/层/保护位，然后是
     * profile、采样率索引、声道配置与 13 位帧长。这里取第一帧头部参数，
     * 用「每帧 1024 采样」估算总时长。
     */
    static AudioInfo parseAac(byte[] h, long fileSize) {
        if (h.length < 7) return null;
        if ((h[0] & 0xFF) != 0xFF || (h[1] & 0xF6) != 0xF0) return null; // 同步 0xFFF，层=00
        // 采样率索引：第 2 字节 bit3-6；声道配置：第 2 字节 bit0 + 第 3 字节 bit7-8
        int srIndex = (h[2] >> 2) & 0x0F;
        int[] sampleRates = {96000, 88200, 64000, 48000, 44100,
                32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        if (srIndex >= sampleRates.length) return null;
        int sampleRate = sampleRates[srIndex];
        int channels = ((h[2] & 0x01) << 2) | ((h[3] & 0xC0) >> 6);
        // 帧长 13 位：第 3 字节 bit2-3 + 第 4 字节全 + 第 5 字节 bit7-5
        int frameLength = ((h[3] & 0x03) << 11)
                | ((h[4] & 0xFF) << 3)
                | ((h[5] & 0xE0) >> 5);
        if (frameLength <= 0) return null;
        // 每帧 1024 采样；估算事件时长：大概帧数 × 每帧时长
        long totalFrames = fileSize / Math.max(1, frameLength);
        double duration = totalFrames * 1024.0 / sampleRate;
        // 比特率：帧长(字节) × 8 × 采样率 / 1024，再转 kbps
        int bitrate = (int) ((frameLength * 8L * sampleRate + 511) / (1024L * 1000));
        return new AudioInfo(duration, sampleRate, channels, 0, bitrate);
    }

    /**
     * 解析 M4A：MP4 容器，读 ftyp 品牌、moov/mvhd（时长）与 mdhd（采样率）。
     * 简单遍历 box 层级，防护越界。
     */
    static AudioInfo parseM4a(byte[] h) {
        if (h.length < 12) return null;
        if (h[4] != 'f' || h[5] != 't' || h[6] != 'y' || h[7] != 'p') return null;
        String brand = new String(h, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).trim();
        int sampleRate = 0;
        int channels = 0;
        double duration = 0;
        int pos = 0;
        // 先定位 moov
        Integer moov = findBox(h, 0, "moov");
        if (moov != null) {
            // mvhd 在 moov 一级
            Integer mvhd = findBox(h, moov + 8, "mvhd");
            if (mvhd != null) {
                int v = h[mvhd + 8] & 0xFF;
                if (v == 1) {
                    if (mvhd + 8 + 32 > h.length) return null;
                    int timescale = beInt(h, mvhd + 8 + 20);
                    long dur = beLong(h, mvhd + 8 + 24);
                    if (timescale > 0) duration = (double) dur / timescale;
                } else {
                    if (mvhd + 8 + 20 > h.length) return null;
                    int timescale = beInt(h, mvhd + 8 + 12);
                    int dur = beInt(h, mvhd + 8 + 16);
                    if (timescale > 0) duration = (double) dur / timescale;
                }
            }
            // mdhd 在 moov/trak/mdia 下，采样率即 timescale
            Integer mdhd = findNestedBox(h, moov + 8, "mdhd", 0);
            if (mdhd != null) {
                int v = h[mdhd + 8] & 0xFF;
                if (v == 1) {
                    if (mdhd + 8 + 24 > h.length) return null;
                    sampleRate = beInt(h, mdhd + 8 + 20);
                } else {
                    if (mdhd + 8 + 16 > h.length) return null;
                    sampleRate = beInt(h, mdhd + 8 + 12);
                }
            }
        }
        AudioInfo info = new AudioInfo(duration, sampleRate, channels, 0, 0);
        return info.durationSeconds() > 0 || info.sampleRate() > 0 ? info : null;
    }

    /** 在当前层级查找名为 type 的 box，返回其起始偏移。 */
    private static Integer findBox(byte[] h, int start, String type) {
        int pos = start;
        while (pos + 8 <= h.length) {
            int size = beInt(h, pos);
            if (size < 8 || pos + size > h.length) break;
            String t = new String(h, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (type.equals(t)) {
                return pos;
            }
            pos += size;
        }
        return null;
    }

    /** 递归嵌套查找（如 moov→trak→mdia→mdhd），限深度防递归过深。 */
    private static Integer findNestedBox(byte[] h, int start, String type, int depth) {
        if (depth > 6) return null;
        int pos = start;
        while (pos + 8 <= h.length) {
            int size = beInt(h, pos);
            if (size < 8 || pos + size > h.length) break;
            String t = new String(h, pos + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if (type.equals(t)) {
                return pos;
            }
            Integer nested = findNestedBox(h, pos + 8, type, depth + 1);
            if (nested != null) {
                return nested;
            }
            pos += size;
        }
        return null;
    }

    private static int beInt(byte[] h, int off) {
        return ((h[off] & 0xFF) << 24) | ((h[off + 1] & 0xFF) << 16)
                | ((h[off + 2] & 0xFF) << 8) | (h[off + 3] & 0xFF);
    }

    private static long beLong(byte[] h, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[off + i] & 0xFFL);
        }
        return v;
    }

    private static int leInt(byte[] h, int off) {
        return (h[off] & 0xFF) | ((h[off + 1] & 0xFF) << 8)
                | ((h[off + 2] & 0xFF) << 16) | ((h[off + 3] & 0xFF) << 24);
    }

    private static int leShort(byte[] h, int off) {
        return (h[off] & 0xFF) | ((h[off + 1] & 0xFF) << 8);
    }

    private static String formatDuration(double seconds) {
        int total = (int) Math.round(seconds);
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private static String mimeFor(String ext) {
        return switch (ext) {
            case "wav" -> "audio/wav";
            case "flac" -> "audio/flac";
            case "mp3" -> "audio/mpeg";
            case "ogg", "oga" -> "audio/ogg";
            case "aac" -> "audio/aac";
            case "m4a", "m4b" -> "audio/mp4";
            default -> "audio/*";
        };
    }

    /** 桥接 {@link com.filestudio.engine.MagicBytesDetector}。 */
    private static final class MagicBytesBridge {
        static boolean isAudio(File file) {
            try {
                var d = com.filestudio.engine.MagicBytesDetector.detect(file.toPath());
                String ext = d.extension();
                return ext.equals("flac") || ext.equals("ogg") || ext.equals("mp3")
                        || ext.equals("aac") || ext.equals("m4a");
            } catch (RuntimeException e) {
                return false;
            }
        }

        static String audioExtension(byte[] head) {
            var d = com.filestudio.engine.MagicBytesDetector.detect(head, head.length);
            String ext = d.extension();
            return SUPPORTED.contains(ext) ? ext : "";
        }
    }
}
