package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class SevenZipFileHandlerTest {

    private final SevenZipFileHandler handler = new SevenZipFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("7z", handler.getExtension());
        assertEquals("application/x-7z-compressed", handler.getMimeType());
        assertEquals(EditCapability.PARTIAL, handler.getEditCapability());
    }

    @Test
    void listsEntries(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("archive.7z");
        Files.write(p, buildArchive(List.of("src", "src/Main.java", "README.txt"),
                new boolean[]{true, false, false},
                new long[]{2048, 512}));

        Document doc = handler.parse(p.toFile());
        assertEquals("application/x-7z-compressed", doc.getMimeType());
        assertEquals("7z", doc.getMetadata().get("format"));
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals("0.4", doc.getMetadata().get("formatVersion"));
        assertEquals(2, doc.getMetadata().get("fileCount"), "1 个目录 + 2 个文件");
        assertEquals(1, doc.getMetadata().get("directoryCount"));
        assertEquals(3, doc.getMetadata().get("entryCount"));

        @SuppressWarnings("unchecked")
        List<SevenZipFileHandler.Entry> entries =
                (List<SevenZipFileHandler.Entry>) doc.getMetadata().get("entries");
        assertEquals("src", entries.get(0).name());
        assertTrue(entries.get(0).isDirectory());
        assertEquals("src/Main.java", entries.get(1).name());
        assertFalse(entries.get(1).isDirectory());
        assertEquals(2048L, entries.get(1).size());
        assertEquals("README.txt", entries.get(2).name());
        assertEquals(512L, entries.get(2).size());
    }

    @Test
    void reportsCodersAndPackedBytes(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("lzma.7z");
        Files.write(p, buildArchive(List.of("a.bin"), new boolean[]{false}, new long[]{4096}));

        Document doc = handler.parse(p.toFile());
        assertEquals(1, doc.getMetadata().get("folderCount"));
        assertEquals(1, doc.getMetadata().get("packStreamCount"));
        assertEquals(900L, doc.getMetadata().get("packedBytes"));
        assertEquals(List.of("LZMA"), doc.getMetadata().get("codecs"));
        assertEquals(true, doc.getMetadata().get("nextHeaderCrcOk"));
    }

    /** 空归档：NextHeaderSize 为 0。 */
    @Test
    void parsesEmptyArchive(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("empty.7z");
        Files.write(p, buildEmptyArchive());

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(0, doc.getMetadata().get("entryCount"));
    }

    /** 压缩头部（kEncodedHeader）无法在不解压的情况下读取条目。 */
    @Test
    void reportsEncodedHeaderInsteadOfFailing(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("packed.7z");
        Files.write(p, buildArchiveWithNid(0x17));

        Document doc = handler.parse(p.toFile());
        assertEquals(true, doc.getMetadata().get("valid"));
        assertEquals(true, doc.getMetadata().get("encodedHeader"));
        assertNotNull(doc.getMetadata().get("note"));
    }

    @Test
    void rejectsNonSevenZipContent(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("fake.7z");
        Files.writeString(p, "not a 7z archive", StandardCharsets.UTF_8);
        assertFalse(handler.canHandle(p.toFile()));

        Document doc = handler.parse(p.toFile());
        assertEquals(false, doc.getMetadata().get("valid"));
        assertNotNull(doc.getMetadata().get("error"));
    }

    @Test
    void detectsByMagicWithoutExtension(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("noext");
        Files.write(p, buildArchive(List.of("x.txt"), new boolean[]{false}, new long[]{10}));
        assertTrue(handler.canHandle(p.toFile()));
    }

    @Test
    void canHandleByExtension() {
        assertTrue(handler.canHandle(new java.io.File("a.7z")));
        assertFalse(handler.canHandle(new java.io.File("b.zip")));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("r.7z");
        Files.write(p, buildArchive(List.of("x"), new boolean[]{false}, new long[]{1}));
        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.7z").toFile()));
    }

    // ---- 辅助：按 7z 规范构造头部 ----

    /** 7z 变长整数编码（与 READ_NUMBER 配对）。 */
    private static void writeNumber(ByteArrayOutputStream o, long value) {
        long original = value;
        int firstByte = 0;
        int mask = 0x80;
        int i;
        for (i = 0; i < 8; i++) {
            if (value < ((long) mask)) {
                firstByte |= (int) value;
                break;
            }
            firstByte |= mask;
            value >>>= 8;
            mask >>>= 1;
        }
        o.write(firstByte);
        // 后续字节是原数的低 8*i 位——必须用 original，value 此时已被右移
        for (int k = 0; k < i; k++) {
            o.write((int) (original & 0xFF));
            original >>>= 8;
        }
    }

    /** UINT32 字段使用 writeNumber 写入。 */
    private static void wNum(ByteArrayOutputStream o, long v) {
        writeNumber(o, v);
    }

    /** 名称属性：每个名称前 2 字节 UTF-16 码元长度。 */
    private static void writeNames(ByteArrayOutputStream o, List<String> names) {
        try {
            for (String n : names) {
                int units = n.length();
                o.write(units & 0xFF);
                o.write((units >> 8) & 0xFF);
                o.write(n.getBytes(StandardCharsets.UTF_16LE));
            }
        } catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream.write does not throw", e);
        }
    }

    /**
     * 位向量：allDefined=0 时给出 numDefined 与比特。
     *
     * @return 实际写入的字节数——必须与属性声明的 size 一致，
     *         否则读取方会跳到下一个属性的中间
     */
    private static int writeBitVector(ByteArrayOutputStream o, boolean[] bits) {
        int defined = 0;
        for (boolean b : bits) {
            if (b) {
                defined++;
            }
        }
        if (defined == bits.length) {
            o.write(1);                  // allDefined
            return 1;
        }
        o.write(0);
        o.write(0);                      // bitVector
        wNum(o, defined);
        o.write(0);                      // 占位的位字节
        return 4;
    }

    /**
     * 构造完整的 7z 文件：签名头 + 紧随其后的流数据占位 + 头部。
     */
    private static byte[] buildArchive(List<String> names, boolean[] isDir, long[] sizes)
            throws IOException {
        // 非空流条目才占用解包流，目录（EmptyStream 位图为 1）不占用
        int streamCount = 0;
        for (boolean d : isDir) {
            if (!d) {
                streamCount++;
            }
        }

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x01);               // kHeader

        // ArchiveProperties
        wNum(header, 0x06);               // kPackInfo
        wNum(header, 0x07);               // kUnpackInfo
        wNum(header, 0x08);               // kSubStreamsInfo
        wNum(header, 0x05);               // kFilesInfo
        wNum(header, 0x00);               // kEnd

        // PackInfo
        wNum(header, 32);                 // PackPos：紧跟签名头
        wNum(header, 1);                  // NumPackStreams
        wNum(header, 900);                // PackSize
        header.write(0x00);               // kEnd

        // UnpackInfo
        header.write(0x0B);               // kFolder
        wNum(header, 1);                  // NumFolders
        header.write(0x00);               // External = 0
        wNum(header, 1);                  // NumCoders
        header.write(0x00);               // coder flags
        wNum(header, 3);                  // CoderIDSize
        header.write(0x03);               // LZMA
        header.write(0x01);
        header.write(0x01);               // LZMA2
        wNum(header, 1);                  // NumInStreams
        wNum(header, 1);                  // NumOutStreams
        header.write(0x0D);               // kNumUnPackStream
        wNum(header, streamCount);        // 该 folder 含 streamCount 个解包流
        header.write(0x0C);               // kCodersUnpackSize
        for (int i = 0; i < streamCount; i++) {
            wNum(header, i < sizes.length ? Math.max(0, sizes[i]) : 0);
        }
        header.write(0x00);               // kEnd

        // SubStreamsInfo：显式给出每个解包流的最终大小
        header.write(0x09);               // kSize
        for (int i = 0; i < streamCount; i++) {
            wNum(header, i < sizes.length && sizes[i] > 0 ? sizes[i] : 0);
        }
        header.write(0x00);               // kEnd

        // FilesInfo
        wNum(header, names.size());       // NumFiles

        header.write(0x0E);               // kEmptyStream
        ByteArrayOutputStream bitData = new ByteArrayOutputStream();
        int bitLen = writeBitVector(bitData, isDir);
        wNum(header, bitLen);             // property size 必须等于实际字节数
        header.write(bitData.toByteArray());

        header.write(0x11);               // kName
        ByteArrayOutputStream nameData = new ByteArrayOutputStream();
        nameData.write(0x00);             // External
        writeNames(nameData, names);
        wNum(header, nameData.size());
        header.write(nameData.toByteArray());

        header.write(0x00);               // kEnd

        return wrapWithSignature(header.toByteArray());
    }

    private static byte[] buildEmptyArchive() throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0x01);   // kHeader
        header.write(0x00);   // kEnd (empty ArchiveProperties)
        byte[] headerBytes = header.toByteArray();
        return wrap(headerBytes, 0, headerBytes.length);
    }

    /** 头部首个 NID 为 kEncodedHeader(0x17)。 */
    private static byte[] buildArchiveWithNid(int nid) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(nid);
        header.write(0x00);
        byte[] headerBytes = header.toByteArray();
        return wrap(headerBytes, 0, headerBytes.length);
    }

    /** 头部紧跟在签名头之后（NextHeaderOffset = 0）。 */
    private static byte[] wrapWithSignature(byte[] header) throws IOException {
        return wrap(header, 0, header.length);
    }

    private static byte[] wrap(byte[] header, long nextOffset, long nextSize) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C});
        out.write(0x00);   // major
        out.write(0x04);   // minor
        w32be(out, 0);     // StartHeaderCRC（不校验）
        w64be(out, nextOffset);
        w64be(out, nextSize);
        // NextHeaderCRC
        CRC32 crc = new CRC32();
        crc.update(header);
        w32be(out, (int) crc.getValue());
        out.write(header);
        return out.toByteArray();
    }

    private static void w32be(ByteArrayOutputStream o, int v) {
        o.write((v >>> 24) & 0xFF);
        o.write((v >>> 16) & 0xFF);
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void w64be(ByteArrayOutputStream o, long v) {
        for (int i = 7; i >= 0; i--) {
            o.write((int) ((v >>> (8 * i)) & 0xFF));
        }
    }
}
