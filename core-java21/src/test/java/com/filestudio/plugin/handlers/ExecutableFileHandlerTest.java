package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExecutableFileHandlerTest {

    private final ExecutableFileHandler handler = new ExecutableFileHandler();

    @Test
    void declaresFormat() {
        assertEquals("exe", handler.getExtension());
        assertEquals("application/x-msdownload", handler.getMimeType());
        assertEquals(EditCapability.VIEW_ONLY, handler.getEditCapability());
    }

    // ---- PE ----

    @Test
    void readsPe32ConsoleExecutable(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.exe");
        Files.write(p, buildPe(false, false));

        assertTrue(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("pe", m.get("format"));
        assertEquals(true, m.get("valid"));
        assertEquals("x86 (i386)", m.get("machine"));
        assertEquals("0x014C", m.get("machineCode"));
        assertEquals(32, m.get("bits"));
        assertEquals("Windows console", m.get("subsystem"));
        assertEquals(false, m.get("isDll"));
        assertEquals(4, m.get("sectionCount"));
        assertEquals(0x1000L, m.get("entryPoint"));
        assertEquals("0x1000", m.get("entryPointHex"));
        assertEquals("0x400000", m.get("imageBase"));
        assertEquals(16, m.get("dataDirectoryCount"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secs = (List<Map<String, Object>>) m.get("sections");
        assertNotNull(secs);
        assertEquals(".text", secs.get(0).get("name"));
        assertEquals(".rdata", secs.get(1).get("name"));
        assertEquals(".data", secs.get(2).get("name"));
    }

    @Test
    void readsPe32PlusDll(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("x64.dll");
        Files.write(p, buildPe(true, true));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("pe", m.get("format"));
        assertEquals(true, m.get("valid"));
        assertEquals(64, m.get("bits"));
        assertEquals("x86-64 (AMD64)", m.get("machine"));
        assertEquals("0x8664", m.get("machineCode"));
        assertEquals(true, m.get("isDll"));
        assertEquals("Windows GUI", m.get("subsystem"));
        assertEquals("0x140000000", m.get("imageBase"));
        assertEquals(true, m.get("hasExports"));
    }

    @Test
    void decodesSecurityFlags(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("secure.exe");
        Files.write(p, buildPe(true, false));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        @SuppressWarnings("unchecked")
        List<String> dllChars = (List<String>) m.get("dllCharacteristics");
        assertNotNull(dllChars);
        assertTrue(dllChars.contains("DYNAMIC_BASE(ASLR)"));
        assertTrue(dllChars.contains("NX_COMPAT(DEP)"));
        assertTrue(dllChars.contains("GUARD_CF"));

        @SuppressWarnings("unchecked")
        List<String> chars = (List<String>) m.get("characteristics");
        assertTrue(chars.contains("EXECUTABLE_IMAGE"));
        assertTrue(chars.contains("LARGE_ADDRESS_AWARE"));
        assertTrue(chars.contains("32BIT_MACHINE"));
    }

    @Test
    void decodesSectionFlags(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("sec.exe");
        Files.write(p, buildPe(false, false));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secs = (List<Map<String, Object>>) m.get("sections");

        @SuppressWarnings("unchecked")
        List<String> text = (List<String>) secs.get(0).get("decoded");
        assertTrue(text.contains("CODE"));
        assertTrue(text.contains("EXECUTE"));
        assertTrue(text.contains("READ"));

        // .rdata 必须同时带 INITIALIZED_DATA，否则不会计入已初始化数据量
        @SuppressWarnings("unchecked")
        List<String> rdata = (List<String>) secs.get(1).get("decoded");
        assertTrue(rdata.contains("INITIALIZED_DATA"));
        assertTrue(rdata.contains("READ"));
        assertFalse(rdata.contains("EXECUTE"));

        @SuppressWarnings("unchecked")
        List<String> data = (List<String>) secs.get(2).get("decoded");
        assertTrue(data.contains("INITIALIZED_DATA"));
        assertTrue(data.contains("WRITE"));
        assertFalse(data.contains("UNINITIALIZED_DATA"));

        @SuppressWarnings("unchecked")
        List<String> bss = (List<String>) secs.get(3).get("decoded");
        assertTrue(bss.contains("UNINITIALIZED_DATA"));
        assertFalse(bss.contains("INITIALIZED_DATA"));
    }

    @Test
    void sumsSectionSizes(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("sum.exe");
        Files.write(p, buildPe(false, false));

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        // .text 0x600 为代码；.rdata + .data 各 0x200 为已初始化数据；.bss 0x200 未初始化
        assertEquals(0x600L, m.get("codeSize"));
        assertEquals(0x400L, m.get("initializedDataSize"));
        assertEquals(0x200L, m.get("uninitializedDataSize"));
    }

    @Test
    void rejectsMzWithoutPeSignature(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("dos.exe");
        byte[] data = new byte[512];
        data[0] = 'M';
        data[1] = 'Z';
        // e_lfanew 指向越界处
        data[0x3C] = 0x00;
        data[0x3D] = (byte) 0xF0;
        Files.write(p, data);

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
        assertTrue(String.valueOf(m.get("error")).contains("e_lfanew"));
    }

    @Test
    void rejectsBadPeSignature(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("bad.exe");
        byte[] data = new byte[512];
        data[0] = 'M';
        data[1] = 'Z';
        data[0x3C] = (byte) 0x80;
        data[0x3D] = 0;   // e_lfanew = 0x80
        data[0x80] = 'X'; // 不是 "PE\0\0"
        Files.write(p, data);

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
        assertTrue(String.valueOf(m.get("error")).contains("PE signature"));
    }

    @Test
    void handlesCoffOnlyObjectWithoutOptionalHeader(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("obj.obj");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write('M');
        o.write('Z');
        o.write(new byte[0x3A]);
        writeU32(o, 0x80);
        while (o.size() < 0x80) {
            o.write(0);
        }
        o.write('P');
        o.write('E');
        o.write(0);
        o.write(0);
        writeU16(o, 0x014C);   // machine
        writeU16(o, 1);        // sections
        writeU32(o, 0);        // timestamp
        writeU32(o, 0);        // symbol table
        writeU32(o, 0);        // symbol count
        writeU16(o, 0);        // SizeOfOptionalHeader = 0
        writeU16(o, 0);        // characteristics
        Files.write(p, o.toByteArray());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(true, m.get("valid"));
        assertEquals(false, m.get("peOptionalHeader"));
        assertEquals(1, m.get("sectionCount"));
    }

    // ---- ELF ----

    @Test
    void readsElf64SharedObject(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("libfoo.so");
        Files.write(p, buildElf64Dyn());

        assertTrue(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("elf", m.get("format"));
        assertEquals(true, m.get("valid"));
        assertEquals(64, m.get("bits"));
        assertEquals("little", m.get("endian"));
        assertEquals("Linux", m.get("osabi"));
        assertEquals("ET_DYN (shared object / PIE)", m.get("type"));
        assertEquals("x86-64 (AMD64)", m.get("machine"));
        assertEquals("0x3E", m.get("machineCode"));
        assertEquals("0x1040", m.get("entryPointHex"));
        assertEquals(5, m.get("sectionHeaderCount"));
        assertEquals(4, m.get("sectionHeaderStringTableIndex"));
        assertEquals(List.of("libc.so.6", "libm.so.6"), m.get("neededLibraries"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secs = (List<Map<String, Object>>) m.get("sections");
        assertNotNull(secs);
        // NULL 节的 sh_name 为 0，指向字符串表的首个 NUL，因此名字为空
        assertEquals("", secs.get(0).get("name"));
        assertEquals(".text", secs.get(1).get("name"));
        assertEquals(true, secs.get(1).get("executable"));
        assertEquals(".dynamic", secs.get(2).get("name"));
        assertEquals("DYNAMIC", secs.get(2).get("type"));
        assertEquals(".dynstr", secs.get(3).get("name"));
    }

    @Test
    void readsElf32BigEndianExecutable(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("be.elf");
        Files.write(p, buildElf32());

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("elf", m.get("format"));
        assertEquals(true, m.get("valid"));
        assertEquals(32, m.get("bits"));
        assertEquals("big", m.get("endian"));
        assertEquals("System V", m.get("osabi"));
        assertEquals("AArch64 (ARM64)", m.get("machine"));
        assertEquals("ET_EXEC (executable)", m.get("type"));
        assertEquals("0x8048000", m.get("entryPointHex"));
    }

    @Test
    void handlesTruncatedElf(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("tiny.so");
        Files.write(p, new byte[]{0x7F, 'E', 'L', 'F', 2, 1});

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
    }

    @Test
    void handlesElfSmallerThanFullHeader(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("hdr.elf");
        byte[] data = buildElf64Dyn();
        byte[] truncated = new byte[40];
        System.arraycopy(data, 0, truncated, 0, 40);
        Files.write(p, truncated);

        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals("elf", m.get("format"));
        assertEquals(true, m.get("truncated"));
    }

    @Test
    void rejectsUnknownBinary(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("blob.bin");
        byte[] junk = new byte[512];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) i;
        }
        Files.write(p, junk);

        assertFalse(handler.canHandle(p.toFile()));
        Map<String, Object> m = handler.parse(p.toFile()).getMetadata();
        assertEquals(false, m.get("valid"));
        assertTrue(String.valueOf(m.get("error")).contains("Not a recognized"));
    }

    @Test
    void renderIsRejected(@TempDir Path dir) throws IOException {
        Path p = dir.resolve("app.exe");
        Files.write(p, buildPe(false, false));

        Document doc = handler.parse(p.toFile());
        assertThrows(FileStudioException.class,
                () -> handler.render(doc, dir.resolve("out.exe").toFile()));
    }

    @Test
    void getMetadataReportsFormat(@TempDir Path dir) throws IOException {
        Path pe = dir.resolve("a.exe");
        Files.write(pe, buildPe(false, false));
        assertEquals("pe", handler.getMetadata(pe.toFile()).get("format"));

        Path elf = dir.resolve("a.so");
        Files.write(elf, buildElf64Dyn());
        assertEquals("elf", handler.getMetadata(elf.toFile()).get("format"));

        assertTrue(handler.getMetadata(dir.resolve("missing.exe").toFile()).isEmpty());
    }

    // ---- PE builder ----

    /**
     * 构造最小合法 PE。
     *
     * <p>字段严格按 PE/COFF 规范写入，特别注意 PE32+ 的 SizeOfStack/Heap
     * 是 8 字节而 PE32 是 4 字节——写错会让后续数据目录整体错位。
     *
     * @param plus 是否 PE32+（64 位）
     * @param dll  是否为 DLL
     */
    private static byte[] buildPe(boolean plus, boolean dll) throws IOException {
        // PE32 的可选头是 224 字节，PE32+ 因 ImageBase 与 Stack/Heap 字段更宽而是 240
        final int optSize = plus ? 240 : 224;
        ByteArrayOutputStream o = new ByteArrayOutputStream();

        // DOS 头
        o.write('M');
        o.write('Z');
        o.write(new byte[0x3A]);
        writeU32(o, 0x80);                 // e_lfanew
        while (o.size() < 0x80) {
            o.write(0);
        }

        o.write('P');
        o.write('E');
        o.write(0);
        o.write(0);

        int machine = plus ? 0x8664 : 0x014C;
        writeU16(o, machine);
        writeU16(o, 4);                    // NumberOfSections
        writeU32(o, 0x60000000);           // TimeDateStamp
        writeU32(o, 0);                    // PointerToSymbolTable
        writeU32(o, 0);                    // NumberOfSymbols
        writeU16(o, optSize);
        int characteristics = 0x0002 | 0x0020 | 0x0100;
        if (dll) {
            characteristics |= 0x2000;
        }
        writeU16(o, characteristics);

        // 可选头
        ByteArrayOutputStream opt = new ByteArrayOutputStream();
        writeU16(opt, plus ? 0x20B : 0x10B);
        opt.write(14);                     // MajorLinkerVersion
        opt.write(0);                      // MinorLinkerVersion
        writeU32(opt, 0x600);              // SizeOfCode
        writeU32(opt, 0x200);              // SizeOfInitializedData
        writeU32(opt, 0x200);              // SizeOfUninitializedData
        writeU32(opt, 0x1000);             // AddressOfEntryPoint
        writeU32(opt, 0x1000);             // BaseOfCode
        if (!plus) {
            writeU32(opt, 0x2000);         // BaseOfData（PE32 专有）
            writeU32(opt, 0x00400000);     // ImageBase
        } else {
            writeU64(opt, 0x140000000L);   // ImageBase
        }
        writeU32(opt, 0x1000);             // SectionAlignment
        writeU32(opt, 0x200);              // FileAlignment
        writeU16(opt, 6);                  // MajorOperatingSystemVersion
        writeU16(opt, 0);
        writeU16(opt, 0);                  // MajorImageVersion
        writeU16(opt, 0);
        writeU16(opt, 6);                  // MajorSubsystemVersion
        writeU16(opt, 0);
        writeU32(opt, 0);                  // Win32VersionValue
        writeU32(opt, 0x4000);             // SizeOfImage
        writeU32(opt, 0x400);              // SizeOfHeaders
        writeU32(opt, 0);                  // CheckSum
        writeU16(opt, dll ? 2 : 3);        // Subsystem: GUI / console
        writeU16(opt, 0x0040 | 0x0100 | 0x4000);   // DllCharacteristics
        if (plus) {
            writeU64(opt, 0x100000);       // SizeOfStackReserve
            writeU64(opt, 0x1000);         // SizeOfStackCommit
            writeU64(opt, 0x100000);       // SizeOfHeapReserve
            writeU64(opt, 0x1000);         // SizeOfHeapCommit
        } else {
            writeU32(opt, 0x100000);
            writeU32(opt, 0x1000);
            writeU32(opt, 0x100000);
            writeU32(opt, 0x1000);
        }
        writeU32(opt, 0);                  // LoaderFlags
        writeU32(opt, 16);                 // NumberOfRvaAndSizes

        // 16 个数据目录
        for (int i = 0; i < 16; i++) {
            writeU32(opt, 0);
            writeU32(opt, 0);
        }
        if (dll) {
            byte[] dd = opt.toByteArray();
            int exportDir = plus ? 112 : 96;
            dd[exportDir] = 0x00;
            dd[exportDir + 1] = 0x20;      // RVA = 0x2000
            dd[exportDir + 4] = 0x40;      // Size = 0x40
            opt.reset();
            opt.write(dd);
        }
        assertEquals(optSize, opt.size(), "optional header size");
        o.write(opt.toByteArray());

        // 节表
        writeSection(o, ".text", 0x1000, 0x600, 0x600, 0x20 | 0x40000000 | 0x20000000);
        writeSection(o, ".rdata", 0x2000, 0x200, 0x200, 0x40000040);
        writeSection(o, ".data", 0x3000, 0x200, 0x200, 0xC0000040);
        writeSection(o, ".bss", 0x4000, 0x200, 0x200, 0xC0000080);

        return o.toByteArray();
    }

    private static void writeSection(ByteArrayOutputStream out, String name,
                                     int virtAddr, int virtSize, int rawSize, int characteristics) {
        byte[] nb = new byte[8];
        byte[] src = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, nb, 0, Math.min(src.length, 8));
        out.write(nb, 0, 8);
        writeU32(out, virtSize);
        writeU32(out, virtAddr);
        writeU32(out, rawSize);
        writeU32(out, 0x400);              // PointerToRawData
        writeU32(out, 0);                  // PointerToRelocations
        writeU32(out, 0);                  // PointerToLinenumbers
        writeU16(out, 0);                  // NumberOfRelocations
        writeU16(out, 0);                  // NumberOfLinenumbers
        writeU32(out, characteristics);
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >>> 24) & 0xFF);
    }

    private static void writeU64(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) {
            o.write((int) ((v >>> (8 * i)) & 0xFF));
        }
    }

    // ---- ELF builders ----

    /**
     * 构造带 .dynamic/.dynstr 的 ELF64 动态库。
     *
     * <p>5 个节表项：NULL、.text、.dynamic、.dynstr、.shstrtab；
     * 动态段含两个 DT_NEEDED，分别指向 .dynstr 中的 libc.so.6 与 libm.so.6。
     */
    private static byte[] buildElf64Dyn() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0x7F).put((byte) 'E').put((byte) 'L').put((byte) 'F');
        b.put((byte) 2);       // ELFCLASS64
        b.put((byte) 1);       // ELFDATA2LSB
        b.put((byte) 1);       // EI_VERSION
        b.put((byte) 3);       // EI_OSABI = Linux
        b.put((byte) 0);
        for (int i = 0; i < 7; i++) {
            b.put((byte) 0);
        }
        b.putShort((short) 3);  // e_type = ET_DYN
        b.putShort((short) 62); // e_machine = x86-64
        b.putInt(1);            // e_version
        b.putLong(0x1040L);     // e_entry
        b.putLong(0);           // e_phoff
        b.putLong(0x200);       // e_shoff
        b.putInt(0);            // e_flags
        b.putShort((short) 64); // e_ehsize
        b.putShort((short) 56); // e_phentsize
        b.putShort((short) 0);  // e_phnum
        b.putShort((short) 64); // e_shentsize
        b.putShort((short) 5);  // e_shnum
        b.putShort((short) 4);  // e_shstrndx

        long shBase = 0x200;
        long dynOff = 0x400;
        long dynstrOff = 0x500;
        long shstrOff = 0x600;

        // 节名表：\0.text\0.dynamic\0.dynstr\0.shstrtab\0
        //         0 1     7      16       25
        writeSh(b, shBase,      0, 0, 0, 0, 0, 0);
        writeSh(b, shBase + 64,  1, 1, 0x4L, 0x1000L, 0x300L, 0x100L);
        writeSh(b, shBase + 128, 7, 6, 0x3L, 0x2000L, dynOff, 48);
        writeSh(b, shBase + 192, 16, 3, 0x2L, 0x2010L, dynstrOff, 21);
        writeSh(b, shBase + 256, 25, 3, 0, 0, shstrOff, 34);

        // .dynstr：\0libc.so.6\0libm.so.6\0
        b.position((int) dynstrOff);
        b.put((byte) 0);
        b.put("libc.so.6".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 0);
        b.put("libm.so.6".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 0);

        // .dynamic：DT_NEEDED(1) -> 1, DT_NEEDED(1) -> 11, DT_NULL(0)
        b.position((int) dynOff);
        b.putLong(1L);
        b.putLong(1L);
        b.putLong(1L);
        b.putLong(11L);
        b.putLong(0L);
        b.putLong(0L);

        b.position((int) shstrOff);
        b.put((byte) 0);
        b.put(".text".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 0);
        b.put(".dynamic".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 0);
        b.put(".dynstr".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 0);
        b.put(".shstrtab".getBytes(StandardCharsets.US_ASCII));
        b.put((byte) 0);

        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static void writeSh(ByteBuffer b, long off, int name, int type, long flags,
                                long addr, long offset, long size) {
        b.position((int) off);
        b.putInt(name);
        b.putInt(type);
        b.putLong(flags);
        b.putLong(addr);
        b.putLong(offset);
        b.putLong(size);
        b.putInt(0);   // sh_link
        b.putInt(0);   // sh_info
        b.putLong(1);  // sh_addralign
        b.putLong(0);  // sh_entsize
    }

    /** 构造 32 位大端 ELF 可执行文件。 */
    private static byte[] buildElf32() throws IOException {
        ByteBuffer b = ByteBuffer.allocate(1024).order(ByteOrder.BIG_ENDIAN);
        b.put((byte) 0x7F).put((byte) 'E').put((byte) 'L').put((byte) 'F');
        b.put((byte) 1);        // ELFCLASS32
        b.put((byte) 2);        // ELFDATA2MSB
        b.put((byte) 1);
        b.put((byte) 0);        // System V
        b.put((byte) 0);
        for (int i = 0; i < 7; i++) {
            b.put((byte) 0);
        }
        b.putShort((short) 2);    // ET_EXEC
        b.putShort((short) 183);  // AArch64
        b.putInt(1);
        b.putInt(0x8048000);      // e_entry
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.putShort((short) 52);
        b.putShort((short) 32);
        b.putShort((short) 0);
        b.putShort((short) 40);
        b.putShort((short) 0);
        b.putShort((short) 0);

        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }
}