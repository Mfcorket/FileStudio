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
 * <p>支持：WAV / FLAC / MP3 / OGG。
 * VIEW_ONLY 能力——音频不可作为文本编辑。
 *
 * <p>实现：
 * <ul>
 *   <li>WAV：解析 RIFF 头 + fmt 块 + data 块大小</li>
 *   <li>FLAC：解析 STREAMINFO 元数据块</li>
 *   <li>MP3：扫描帧头估算时长（无 Xing 头时）</li>
 *   <li>OGG：解析 Vorbis 标识头</li>
 * </ul>
 */
public class AudioFileHandler implements FileHandler {

    private static final Set<String> SUPPORTED = Set.of("wav", "flac", "mp3", "ogg", "oga");

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
            default -> "audio/*";
        };
    }

    /** 桥接 {@link com.filestudio.engine.MagicBytesDetector}。 */
    private static final class MagicBytesBridge {
        static boolean isAudio(File file) {
            try {
                var d = com.filestudio.engine.MagicBytesDetector.detect(file.toPath());
                String ext = d.extension();
                return ext.equals("flac") || ext.equals("ogg") || ext.equals("mp3");
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
