package com.filestudio.plugin.handlers;

import com.filestudio.core.Document;
import com.filestudio.core.EditCapability;
import com.filestudio.core.FileStudioException;
import com.filestudio.plugin.FileHandler;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可执行文件处理器，同时支持 PE（Windows）与 ELF（Linux/Android/macOS）。
 *
 * <p>VIEW_ONLY——可查看结构化元数据，但不提供编辑或反汇编能力。
 *
 * <p>两者都以「可选头」为核心：PE 是 COFF + Optional Header，ELF 是
 * {@code e_ident} + 固定头。本处理器读取架构、子系统、入口点、节/段表，
 * 让用户在不运行文件的情况下判断「这是什么、能不能跑」。
 *
 * <p>安全说明：解析过程只读取元数据，不加载、不执行、也不解析符号表以外的
 * 任何内容。所有偏移都做范围校验后再读取，畸形文件不会导致越界。
 */
public class ExecutableFileHandler implements FileHandler {

    /**
     * 可执行文件处理器。
     */
    public ExecutableFileHandler() {}

    // ---- PE 常量 ----

    /** PE32 可选头魔数。 */
    private static final int PE32 = 0x10B;

    /** PE32+（64 位）可选头魔数。 */
    private static final int PE32_PLUS = 0x20B;

    /** 数据目录个数（含导入表、导出表、资源表等）。 */
    private static final int NUM_DATA_DIRS = 16;

    // ---- ELF 常量 ----

    /** ELF32 文件类型掩码位置：e_ident[EI_CLASS]。 */
    private static final int EI_CLASS = 4;

    /** ELF 数据编码：e_ident[EI_DATA]。 */
    private static final int EI_DATA = 5;

    private static final int ELFCLASS32 = 1;
    private static final int ELFCLASS64 = 2;
    private static final int ELFDATA2LSB = 1;
    private static final int ELFDATA2MSB = 2;

    @Override
    public String getExtension() {
        return "exe";
    }

    @Override
    public String getMimeType() {
        return "application/x-msdownload";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.VIEW_ONLY;
    }

    @Override
    public String getDescription() {
        return "Executable image (PE / ELF)";
    }

    @Override
    public boolean canHandle(File file) {
        if (file == null) {
            return false;
        }
        if (file.isFile()) {
            return peekFormat(file) != null;
        }
        String name = file.getName().toLowerCase();
        return name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".so")
                || name.endsWith(".elf") || name.endsWith(".dylib");
    }

    @Override
    public Document parse(File file) {
        if (file == null || !file.isFile()) {
            throw new FileStudioException("Executable not readable: " + file);
        }
        Path path = file.toPath();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("viewOnly", true);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            String format = peekFormat(file);
            if ("pe".equals(format)) {
                readPe(raf, meta);
            } else if ("elf".equals(format)) {
                readElf(raf, meta);
            } else {
                meta.put("valid", false);
                meta.put("error", "Not a recognized executable image");
            }
        } catch (IOException e) {
            meta.putIfAbsent("valid", false);
            meta.put("error", "Failed to read executable: " + e.getMessage());
        }

        return new Document(path, getMimeType(), getExtension(), "", meta,
                file.length(), getEditCapability());
    }

    @Override
    public void render(Document doc, File output) {
        throw new FileStudioException("Executables are read-only");
    }

    @Override
    public Map<String, Object> getMetadata(File file) {
        if (file == null || !file.isFile()) {
            return Map.of();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("size", file.length());
        meta.put("format", peekFormat(file));
        return meta;
    }

    // ---- 格式探测 ----

    /** 识别为 PE 或 ELF 时返回短名，否则返回 {@code null}。 */
    private static String peekFormat(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[4];
            if (raf.length() < 4 || raf.read(head) != 4) {
                return null;
            }
            if (head[0] == 'M' && head[1] == 'Z') {
                // 需要确认 PE 头确实存在，避免把任意 MZ 文件当作 PE
                return "pe";
            }
            if ((head[0] & 0xFF) == 0x7F && head[1] == 'E' && head[2] == 'L' && head[3] == 'F') {
                return "elf";
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    // ---- PE ----

    private void readPe(RandomAccessFile raf, Map<String, Object> meta) throws IOException {
        long fileLen = raf.length();
        if (fileLen < 0x40) {
            meta.put("valid", false);
            meta.put("error", "File too small for a DOS header");
            return;
        }

        long peOffset = readU32(raf, 0x3C);
        meta.put("format", "pe");
        meta.put("peOffset", peOffset);

        if (peOffset <= 0 || peOffset + 24 > fileLen) {
            meta.put("valid", false);
            meta.put("error", "e_lfanew points outside the file: " + peOffset);
            return;
        }

        byte[] sig = new byte[4];
        raf.seek(peOffset);
        raf.readFully(sig);
        if (sig[0] != 'P' || sig[1] != 'E' || sig[2] != 0 || sig[3] != 0) {
            meta.put("valid", false);
            meta.put("error", "Missing PE signature at e_lfanew");
            return;
        }

        long coff = peOffset + 4;
        int machine = readU16(raf, coff);
        int sections = readU16(raf, coff + 2);
        int timestamp = (int) readU32(raf, coff + 4);
        int optSize = readU16(raf, coff + 16);
        int characteristics = readU16(raf, coff + 18);

        meta.put("valid", true);
        meta.put("machine", machineName(machine));
        meta.put("machineCode", String.format("0x%04X", machine));
        meta.put("sectionCount", sections);
        meta.put("timestamp", timestamp);
        meta.put("timestampIso", isoUtc(timestamp));
        meta.put("optionalHeaderSize", optSize);
        meta.put("isDll", (characteristics & 0x2000) != 0);
        meta.put("characteristics", decodePeCharacteristics(characteristics));

        long opt = coff + 20;
        if (optSize == 0 || opt + 2 > fileLen) {
            meta.put("peOptionalHeader", false);
            // COFF-only（纯 obj）到此为止
            return;
        }

        int magic = readU16(raf, opt);
        meta.put("peOptionalHeader", true);
        meta.put("optionalMagic", String.format("0x%04X", magic));

        boolean plus = magic == PE32_PLUS;
        if (magic != PE32 && magic != PE32_PLUS) {
            meta.put("optionalHeaderUnknown", true);
            return;
        }
        meta.put("bits", plus ? 64 : 32);

        long entry = readU32(raf, opt + 16);
        meta.put("entryPoint", entry);
        meta.put("entryPointHex", String.format("0x%X", entry));

        // PE32 的 ImageBase 是 4 字节；PE32+ 是 8 字节且整体布局不同
        long imageBase = plus ? readU64(raf, opt + 24, true) : readU32(raf, opt + 28, true);
        meta.put("imageBase", plus ? String.format("0x%X", imageBase) : String.format("0x%X", imageBase));
        meta.put("sectionAlignment", readU32(raf, opt + 32));
        meta.put("fileAlignment", readU32(raf, opt + 36));
        meta.put("sizeOfImage", readU32(raf, opt + 56));
        meta.put("sizeOfHeaders", readU32(raf, opt + 60));
        int subsystem = readU16(raf, opt + 68);
        meta.put("subsystem", peSubsystemName(subsystem));
        int dllChars = readU16(raf, opt + 70);
        meta.put("dllCharacteristics", decodeDllCharacteristics(dllChars));

        // 数据目录起点：PE32 为 +96，PE32+ 为 +112
        long dirBase = opt + (plus ? 112 : 96);
        long numDirs = readU32(raf, dirBase - 4);
        meta.put("dataDirectoryCount", (int) Math.min(numDirs, NUM_DATA_DIRS));

        String[] dirNames = {"export", "import", "resource", "exception", "security",
                "relocation", "debug", "architecture", "globalPtr", "tls",
                "loadConfig", "boundImport", "iat", "delayImport", "clrRuntime", "reserved"};
        List<Map<String, Object>> dirs = new ArrayList<>();
        for (int i = 0; i < Math.min(numDirs, NUM_DATA_DIRS) && i < dirNames.length; i++) {
            long rva = readU32(raf, dirBase + i * 8);
            long size = readU32(raf, dirBase + i * 8 + 4);
            if (rva != 0 || size != 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", dirNames[i]);
                d.put("rva", rva);
                d.put("size", size);
                dirs.add(d);
            }
        }
        if (!dirs.isEmpty()) {
            meta.put("dataDirectories", dirs);
        }

        // 节表紧随可选头
        long secBase = opt + optSize;
        List<Map<String, Object>> secs = new ArrayList<>();
        long codeSize = 0;
        long initData = 0;
        long uninitData = 0;
        for (int i = 0; i < sections; i++) {
            long off = secBase + (long) i * 40;
            if (off + 40 > fileLen) {
                break;
            }
            String name = readAscii(raf, off, 8).trim();
            int virtSize = (int) readU32(raf, off + 8);
            int virtAddr = (int) readU32(raf, off + 12);
            int rawSize = (int) readU32(raf, off + 16);
            int rawPtr = (int) readU32(raf, off + 20);
            int chars = (int) readU32(raf, off + 36);

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name.isEmpty() ? "<unnamed>" : name);
            s.put("virtualSize", virtSize);
            s.put("virtualAddress", virtAddr);
            s.put("rawSize", rawSize);
            s.put("pointerToRawData", rawPtr);
            s.put("characteristics", String.format("0x%08X", chars));
            s.put("decoded", decodeSectionCharacteristics(chars));
            secs.add(s);

            if ((chars & 0x20) != 0) {
                codeSize += rawSize;
            } else if ((chars & 0x80) != 0) {
                uninitData += rawSize;
            } else if ((chars & 0x40) != 0) {
                initData += rawSize;
            }
        }
        if (!secs.isEmpty()) {
            meta.put("sections", secs);
            meta.put("codeSize", codeSize);
            meta.put("initializedDataSize", initData);
            meta.put("uninitializedDataSize", uninitData);
        }

        // DLL 特有：导出表的存在意味着该 DLL 对外提供函数
        for (Map<String, Object> d : dirs) {
            if ("export".equals(d.get("name"))) {
                meta.put("hasExports", true);
                break;
            }
        }
    }

    private static String machineName(int m) {
        return switch (m) {
            case 0x014C -> "x86 (i386)";
            case 0x0166 -> "MIPS R4000";
            case 0x01C0 -> "ARM";
            case 0x01C2 -> "ARM Thumb-2";
            case 0x01C4 -> "ARM NT";
            case 0x0200 -> "IA-64";
            case 0x8664 -> "x86-64 (AMD64)";
            case 0xAA64 -> "ARM64 (AArch64)";
            case 0x5032 -> "RISC-V 32";
            case 0x5064 -> "RISC-V 64";
            default -> "unknown";
        };
    }

    private static String peSubsystemName(int s) {
        return switch (s) {
            case 1 -> "native";
            case 2 -> "Windows GUI";
            case 3 -> "Windows console";
            case 5 -> "OS/2 console";
            case 7 -> "POSIX console";
            case 9 -> "Windows CE GUI";
            case 10 -> "EFI application";
            case 11 -> "EFI boot service driver";
            case 12 -> "EFI runtime driver";
            case 13 -> "EFI ROM";
            case 16 -> "Windows boot application";
            default -> "unknown";
        };
    }

    private static List<String> decodePeCharacteristics(int c) {
        List<String> out = new ArrayList<>();
        if ((c & 0x0001) != 0) out.add("RELOCS_STRIPPED");
        if ((c & 0x0002) != 0) out.add("EXECUTABLE_IMAGE");
        if ((c & 0x0004) != 0) out.add("LINE_NUMS_STRIPPED");
        if ((c & 0x0008) != 0) out.add("LOCAL_SYMS_STRIPPED");
        if ((c & 0x0020) != 0) out.add("LARGE_ADDRESS_AWARE");
        if ((c & 0x0100) != 0) out.add("32BIT_MACHINE");
        if ((c & 0x2000) != 0) out.add("DLL");
        if ((c & 0x4000) != 0) out.add("UP_SYSTEM_ONLY");
        return out;
    }

    private static List<String> decodeDllCharacteristics(int c) {
        List<String> out = new ArrayList<>();
        if ((c & 0x0020) != 0) out.add("HIGH_ENTROPY_VA");
        if ((c & 0x0040) != 0) out.add("DYNAMIC_BASE(ASLR)");
        if ((c & 0x0080) != 0) out.add("FORCE_INTEGRITY");
        if ((c & 0x0100) != 0) out.add("NX_COMPAT(DEP)");
        if ((c & 0x0200) != 0) out.add("NO_ISOLATION");
        if ((c & 0x0400) != 0) out.add("NO_SEH");
        if ((c & 0x0800) != 0) out.add("NO_BIND");
        if ((c & 0x1000) != 0) out.add("APPCONTAINER");
        if ((c & 0x2000) != 0) out.add("WDM_DRIVER");
        if ((c & 0x4000) != 0) out.add("GUARD_CF");
        if ((c & 0x8000) != 0) out.add("TERMINAL_SERVER_AWARE");
        return out;
    }

    private static List<String> decodeSectionCharacteristics(int c) {
        List<String> out = new ArrayList<>();
        if ((c & 0x00000020) != 0) out.add("CODE");
        if ((c & 0x00000040) != 0) out.add("INITIALIZED_DATA");
        if ((c & 0x00000080) != 0) out.add("UNINITIALIZED_DATA");
        if ((c & 0x02000000) != 0) out.add("DISCARDABLE");
        if ((c & 0x04000000) != 0) out.add("NOT_CACHED");
        if ((c & 0x08000000) != 0) out.add("NOT_PAGED");
        if ((c & 0x10000000) != 0) out.add("SHARED");
        if ((c & 0x20000000) != 0) out.add("EXECUTE");
        if ((c & 0x40000000) != 0) out.add("READ");
        if ((c & 0x80000000) != 0) out.add("WRITE");
        return out;
    }

    // ---- ELF ----

    private void readElf(RandomAccessFile raf, Map<String, Object> meta) throws IOException {
        long fileLen = raf.length();
        meta.put("format", "elf");

        if (fileLen < 24) {
            meta.put("valid", false);
            meta.put("error", "File too small for an ELF header");
            return;
        }

        int eiClass = readU16(raf, EI_CLASS) & 0xFF;
        int eiData = readU16(raf, EI_DATA) & 0xFF;
        int eiOsabi = readU16(raf, 7) & 0xFF;

        boolean is64 = eiClass == ELFCLASS64;
        boolean little = eiData == ELFDATA2LSB;

        meta.put("valid", true);
        meta.put("bits", is64 ? 64 : 32);
        meta.put("endian", little ? "little" : "big");
        meta.put("osabi", elfOsabiName(eiOsabi));

        int ehsize = is64 ? 64 : 52;
        if (fileLen < ehsize) {
            meta.put("truncated", true);
            return;
        }

        int eType = readU16(raf, 16, little);
        int eMachine = readU16(raf, 18, little);
        long eEntry = is64 ? readU64(raf, 24, little) : readU32(raf, 24, little);
        long ePhoff = is64 ? readU64(raf, 32, little) : readU32(raf, 28, little);
        long eShoff = is64 ? readU64(raf, 40, little) : readU32(raf, 32, little);
        int ePhnum = readU16(raf, is64 ? 56 : 44, little);
        int eShnum = readU16(raf, is64 ? 60 : 48, little);
        int eShstrndx = readU16(raf, is64 ? 62 : 50, little);

        meta.put("type", elfTypeName(eType));
        meta.put("typeCode", eType);
        meta.put("machine", elfMachineName(eMachine));
        meta.put("machineCode", String.format("0x%X", eMachine));
        meta.put("entryPoint", eEntry);
        meta.put("entryPointHex", String.format("0x%X", eEntry));
        meta.put("programHeaderCount", ePhnum);
        meta.put("sectionHeaderCount", eShnum);
        meta.put("sectionHeaderStringTableIndex", eShstrndx);

        // 动态依赖：解析 PT_DYNAMIC / SHT_DYNAMIC 中的 DT_NEEDED
        List<String> needed = readDynamicNeeded(raf, fileLen, eShoff, eShnum, eShstrndx, is64, little);
        if (needed.isEmpty()) {
            needed = readDynamicNeededViaProgramHeaders(raf, fileLen, ePhoff, ePhnum, is64, little);
        }
        if (!needed.isEmpty()) {
            meta.put("neededLibraries", needed);
        }

        // 节表
        List<Map<String, Object>> secs = new ArrayList<>();
        if (eShoff != 0 && eShnum > 0) {
            long shEnt = is64 ? 64 : 40;
            long strtabOff = -1;
            long strtabSize = 0;
            if (eShstrndx >= 0 && eShstrndx < eShnum) {
                long so = eShoff + eShstrndx * shEnt;
                if (so + shEnt <= fileLen) {
                    strtabOff = is64 ? readU64(raf, so + 24, little) : readU32(raf, so + 16, little);
                    strtabSize = is64 ? readU64(raf, so + 32, little) : readU32(raf, so + 20, little);
                }
            }
            for (int i = 0; i < eShnum; i++) {
                long off = eShoff + (long) i * shEnt;
                if (off + shEnt > fileLen) {
                    break;
                }
                int nameOff = (int) readU32(raf, off, little);
                int shType = (int) readU32(raf, off + 4, little);
                long shFlags = is64 ? readU64(raf, off + 8, little) : readU32(raf, off + 8, little);
                long shAddr = is64 ? readU64(raf, off + 16, little) : readU32(raf, off + 12, little);
                long shOffset = is64 ? readU64(raf, off + 24, little) : readU32(raf, off + 16, little);
                long shSize = is64 ? readU64(raf, off + 32, little) : readU32(raf, off + 20, little);

                Map<String, Object> s = new LinkedHashMap<>();
                s.put("index", i);
                s.put("name", readTableString(raf, strtabOff, strtabSize, nameOff));
                s.put("type", elfSectionTypeName(shType));
                s.put("flags", String.format("0x%X", shFlags));
                s.put("address", shAddr);
                s.put("offset", shOffset);
                s.put("size", shSize);
                s.put("executable", (shFlags & 0x4L) != 0);
                s.put("writable", (shFlags & 0x1L) != 0);
                secs.add(s);
            }
            if (!secs.isEmpty()) {
                meta.put("sections", secs);
            }
        }
    }

    /**
     * 通过节表定位 .dynamic 与 .dynstr，收集 DT_NEEDED 的库名。
     */
    private List<String> readDynamicNeeded(RandomAccessFile raf, long fileLen, long eShoff,
                                           int eShnum, int eShstrndx, boolean is64, boolean little)
            throws IOException {
        List<String> out = new ArrayList<>();
        if (eShoff == 0 || eShnum <= 0) {
            return out;
        }
        long shEnt = is64 ? 64 : 40;
        if (eShstrndx < 0 || eShstrndx >= eShnum) {
            return out;
        }
        long so = eShoff + (long) eShstrndx * shEnt;
        if (so + shEnt > fileLen) {
            return out;
        }
        long strtabOff = is64 ? readU64(raf, so + 24, little) : readU32(raf, so + 16, little);
        long strtabSize = is64 ? readU64(raf, so + 32, little) : readU32(raf, so + 20, little);
        if (strtabSize <= 0) {
            return out;
        }

        long dynOff = -1;
        long dynSize = 0;
        long dynstrOff = -1;
        long dynstrSize = 0;
        for (int i = 0; i < eShnum; i++) {
            long off = eShoff + (long) i * shEnt;
            if (off + shEnt > fileLen) {
                break;
            }
            int nameOff = (int) readU32(raf, off, little);
            int shType = (int) readU32(raf, off + 4, little);
            long shOffset = is64 ? readU64(raf, off + 24, little) : readU32(raf, off + 16, little);
            long shSize = is64 ? readU64(raf, off + 32, little) : readU32(raf, off + 20, little);
            String name = readTableString(raf, strtabOff, strtabSize, nameOff);
            if (".dynamic".equals(name)) {
                dynOff = shOffset;
                dynSize = shSize;
            } else if (".dynstr".equals(name)) {
                dynstrOff = shOffset;
                dynstrSize = shSize;
            }
        }
        if (dynOff < 0 || dynstrOff < 0) {
            return out;
        }
        long entSize = is64 ? 16 : 8;
        for (long p = dynOff; p + entSize <= dynOff + dynSize && p + entSize <= fileLen; p += entSize) {
            long tag = is64 ? readU64(raf, p, little) : readU32(raf, p, little);
            long val = is64 ? readU64(raf, p + 8, little) : readU32(raf, p + 4, little);
            if (tag == 0) {            // DT_NULL，动态段结束
                break;
            }
            if (tag == 1) {            // DT_NEEDED
                String lib = readTableString(raf, dynstrOff, dynstrSize, val);
                if (!lib.isEmpty()) {
                    out.add(lib);
                }
            }
        }
        return out;
    }

    /**
     * 节表被剥离时的退路：直接走 PT_DYNAMIC 段。
     */
    private List<String> readDynamicNeededViaProgramHeaders(RandomAccessFile raf, long fileLen,
                                                             long ePhoff, int ePhnum,
                                                             boolean is64, boolean little)
            throws IOException {
        List<String> out = new ArrayList<>();
        if (ePhoff == 0 || ePhnum <= 0) {
            return out;
        }
        long phEnt = is64 ? 56 : 32;
        for (int i = 0; i < ePhnum; i++) {
            long off = ePhoff + (long) i * phEnt;
            if (off + phEnt > fileLen) {
                break;
            }
            int pType = (int) readU32(raf, off, little);
            if (pType != 2) {         // PT_DYNAMIC
                continue;
            }
            long pOffset = is64 ? readU64(raf, off + 8, little) : readU32(raf, off + 4, little);
            long pFileSize = is64 ? readU64(raf, off + 32, little) : readU32(raf, off + 16, little);
            long entSize = is64 ? 16 : 8;
            for (long p = pOffset; p + entSize <= pOffset + pFileSize && p + entSize <= fileLen;
                    p += entSize) {
                long tag = is64 ? readU64(raf, p, little) : readU32(raf, p, little);
                long val = is64 ? readU64(raf, p + 8, little) : readU32(raf, p + 4, little);
                if (tag == 0) {
                    break;
                }
                if (tag == 1 && val > 0 && val < fileLen) {
                    // 动态段路径下无法确定 .dynstr 位置，退回直接读字符串
                    String lib = readCString(raf, val);
                    if (!lib.isEmpty()) {
                        out.add(lib);
                    }
                }
            }
        }
        return out;
    }

    private static String elfTypeName(int t) {
        return switch (t) {
            case 0 -> "ET_NONE";
            case 1 -> "ET_REL (relocatable)";
            case 2 -> "ET_EXEC (executable)";
            case 3 -> "ET_DYN (shared object / PIE)";
            case 4 -> "ET_CORE (core dump)";
            default -> "unknown";
        };
    }

    private static String elfMachineName(int m) {
        return switch (m) {
            case 3 -> "x86 (Intel 80386)";
            case 8 -> "MIPS";
            case 20 -> "PowerPC";
            case 21 -> "PowerPC64";
            case 40 -> "ARM";
            case 42 -> "SuperH";
            case 43 -> "SPARC v9";
            case 50 -> "Intel IA-64";
            case 62 -> "x86-64 (AMD64)";
            case 183 -> "AArch64 (ARM64)";
            case 243 -> "RISC-V";
            case 258 -> "LoongArch";
            default -> "unknown";
        };
    }

    private static String elfOsabiName(int o) {
        return switch (o) {
            case 0 -> "System V";
            case 1 -> "HP-UX";
            case 2 -> "NetBSD";
            case 3 -> "Linux";
            case 6 -> "Solaris";
            case 9 -> "FreeBSD";
            case 12 -> "OpenBSD";
            default -> "unknown (" + o + ")";
        };
    }

    private static String elfSectionTypeName(int t) {
        return switch (t) {
            case 0 -> "NULL";
            case 1 -> "PROGBITS";
            case 2 -> "SYMTAB";
            case 3 -> "STRTAB";
            case 4 -> "RELA";
            case 5 -> "HASH";
            case 6 -> "DYNAMIC";
            case 7 -> "NOTE";
            case 8 -> "NOBITS";
            case 9 -> "REL";
            case 10 -> "SHLIB";
            case 11 -> "DYNSYM";
            case 14 -> "INIT_ARRAY";
            case 15 -> "FINI_ARRAY";
            default -> "0x" + Integer.toHexString(t);
        };
    }

    // ---- 低层读取 ----

    private static int readU16(RandomAccessFile raf, long off) throws IOException {
        return readU16(raf, off, true);
    }

    private static int readU16(RandomAccessFile raf, long off, boolean little) throws IOException {
        byte[] b = readBytes(raf, off, 2);
        return little ? ((b[0] & 0xFF) | ((b[1] & 0xFF) << 8))
                : (((b[0] & 0xFF) << 8) | (b[1] & 0xFF));
    }

    private static long readU32(RandomAccessFile raf, long off) throws IOException {
        return readU32(raf, off, true);
    }

    private static long readU32(RandomAccessFile raf, long off, boolean little) throws IOException {
        byte[] b = readBytes(raf, off, 4);
        long v = 0;
        if (little) {
            for (int i = 0; i < 4; i++) {
                v |= ((long) (b[i] & 0xFF)) << (8 * i);
            }
        } else {
            for (int i = 0; i < 4; i++) {
                v = (v << 8) | (b[i] & 0xFF);
            }
        }
        return v;
    }

    private static long readU64(RandomAccessFile raf, long off, boolean little) throws IOException {
        byte[] b = readBytes(raf, off, 8);
        long v = 0;
        if (little) {
            for (int i = 0; i < 8; i++) {
                v |= ((long) (b[i] & 0xFF)) << (8 * i);
            }
        } else {
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (b[i] & 0xFF);
            }
        }
        return v;
    }

    /**
     * 在指定偏移读取固定长度字节；越界时抛 {@link IOException} 由上层降级为解析失败。
     */
    private static byte[] readBytes(RandomAccessFile raf, long off, int len) throws IOException {
        if (off < 0 || off + len > raf.length()) {
            throw new IOException("read out of range at " + off + " (+" + len + ")");
        }
        byte[] b = new byte[len];
        raf.seek(off);
        raf.readFully(b);
        return b;
    }

    private static String readAscii(RandomAccessFile raf, long off, int len) throws IOException {
        byte[] b = new byte[len];
        raf.seek(off);
        int n = raf.read(b);
        if (n <= 0) {
            return "";
        }
        return new String(b, 0, n, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /**
     * 在字符串表中按相对偏移读取 NUL 结尾的字符串。
     *
     * <p>注意：{@code relOff} 必须与 {@code tableSize} 比较（而不是与绝对
     * 文件偏移比较），否则远离文件头的字符串表会全部被判为越界。
     */
    private static String readTableString(RandomAccessFile raf, long tableOff,
                                         long tableSize, long relOff) throws IOException {
        if (tableOff < 0 || tableSize <= 0 || relOff < 0 || relOff >= tableSize) {
            return "";
        }
        return readCString(raf, tableOff + relOff);
    }

    private static String readCString(RandomAccessFile raf, long off) throws IOException {
        if (off < 0 || off >= raf.length()) {
            return "";
        }
        // 字符串表中的 NUL 终止，且不会超过文件末尾
        int available = (int) Math.min(4096, raf.length() - off);
        byte[] buf = new byte[available];
        raf.seek(off);
        raf.read(buf);
        int len = 0;
        while (len < buf.length && buf[len] != 0) {
            len++;
        }
        return new String(buf, 0, len, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String isoUtc(int epochSeconds) {
        if (epochSeconds <= 0) {
            return "unset";
        }
        try {
            return java.time.Instant.ofEpochSecond(epochSeconds).toString();
        } catch (RuntimeException e) {
            return "invalid";
        }
    }
}