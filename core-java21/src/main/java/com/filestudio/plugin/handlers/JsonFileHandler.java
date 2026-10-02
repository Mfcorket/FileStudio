package com.filestudio.plugin.handlers;

import com.filestudio.core.EditCapability;

import java.util.Map;

/**
 * JSON 格式处理器：校验语法并提供结构元数据。
 *
 * <p>不依赖外部 JSON 库——内置一个轻量扫描器提取根节点类型、键/元素数量与嵌套深度，
 * 仅用于元数据展示，不构建对象模型（避免大文件内存开销）。
 *
 * <p>FULL 编辑能力：内容按原样回写（不做缩进重排，保持用户改动最小）。
 */
public class JsonFileHandler extends AbstractTextHandler {

    /**
     * JSON 处理器。
     */
    public JsonFileHandler() {}

    @Override
    public String getExtension() {
        return "json";
    }

    @Override
    public String getMimeType() {
        return "application/json";
    }

    @Override
    public EditCapability getEditCapability() {
        return EditCapability.FULL;
    }

    @Override
    public String getDescription() {
        return "JSON document";
    }

    @Override
    protected void enrichMetadata(Map<String, Object> meta, String content) {
        if (content == null || content.isBlank()) return;
        JsonScanResult r = scan(content);
        meta.put("valid", r.valid);
        meta.put("rootType", r.rootType);
        if (r.rootType.equals("object")) {
            meta.put("keyCount", r.rootSize);
        } else if (r.rootType.equals("array")) {
            meta.put("itemCount", r.rootSize);
        }
        meta.put("maxDepth", r.maxDepth);
        if (!r.valid) {
            meta.put("error", r.error);
        }
    }

    // ---- 轻量 JSON 扫描器（仅做结构与语法校验，不构建对象模型） ----

    /** 扫描结果。不可变。 */
    record JsonScanResult(boolean valid, String rootType, int rootSize, int maxDepth, String error) {
        static JsonScanResult invalid(String err) {
            return new JsonScanResult(false, "unknown", 0, 0, err);
        }
    }

    static JsonScanResult scan(String s) {
        int[] pos = {0};
        try {
            skipWs(s, pos);
            if (pos[0] >= s.length()) {
                return JsonScanResult.invalid("Empty input");
            }
            ValueInfo v = parseValue(s, pos, 1);
            skipWs(s, pos);
            if (pos[0] < s.length()) {
                return JsonScanResult.invalid("Trailing content at offset " + pos[0]);
            }
            return new JsonScanResult(true, v.type, v.size, v.maxDepth, null);
        } catch (JsonSyntaxException e) {
            return JsonScanResult.invalid(e.getMessage());
        }
    }

    private record ValueInfo(String type, int size, int maxDepth) {}

    private static final class JsonSyntaxException extends RuntimeException {
        JsonSyntaxException(String msg) { super(msg); }
    }

    private static void skipWs(String s, int[] pos) {
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                pos[0]++;
            } else {
                return;
            }
        }
    }

    private static ValueInfo parseValue(String s, int[] pos, int depth) {
        skipWs(s, pos);
        if (pos[0] >= s.length()) throw new JsonSyntaxException("Unexpected end of input");
        char c = s.charAt(pos[0]);
        return switch (c) {
            case '{' -> parseObject(s, pos, depth);
            case '[' -> parseArray(s, pos, depth);
            case '"' -> parseString(s, pos);
            case 't' -> expect(s, pos, "true", "boolean", 0);
            case 'f' -> expect(s, pos, "false", "boolean", 0);
            case 'n' -> expect(s, pos, "null", "null", 0);
            default -> {
                if (c == '-' || Character.isDigit(c) || c == '.') {
                    yield parseNumber(s, pos);
                }
                throw new JsonSyntaxException("Unexpected character '" + c + "' at offset " + pos[0]);
            }
        };
    }

    private static ValueInfo parseObject(String s, int[] pos, int depth) {
        pos[0]++; // consume '{'
        int keys = 0;
        int maxDepth = depth;
        skipWs(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == '}') {
            pos[0]++;
            return new ValueInfo("object", 0, maxDepth);
        }
        while (true) {
            skipWs(s, pos);
            if (pos[0] >= s.length()) throw new JsonSyntaxException("Unterminated object");
            if (s.charAt(pos[0]) != '"') {
                throw new JsonSyntaxException("Expected key string at offset " + pos[0]);
            }
            parseString(s, pos);
            skipWs(s, pos);
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ':') {
                throw new JsonSyntaxException("Expected ':' at offset " + pos[0]);
            }
            pos[0]++;
            ValueInfo v = parseValue(s, pos, depth + 1);
            if (v.maxDepth > maxDepth) maxDepth = v.maxDepth;
            keys++;
            skipWs(s, pos);
            if (pos[0] >= s.length()) throw new JsonSyntaxException("Unterminated object");
            char ch = s.charAt(pos[0]);
            if (ch == ',') {
                pos[0]++;
            } else if (ch == '}') {
                pos[0]++;
                return new ValueInfo("object", keys, maxDepth);
            } else {
                throw new JsonSyntaxException("Expected ',' or '}' at offset " + pos[0]);
            }
        }
    }

    private static ValueInfo parseArray(String s, int[] pos, int depth) {
        pos[0]++; // consume '['
        int items = 0;
        int maxDepth = depth;
        skipWs(s, pos);
        if (pos[0] < s.length() && s.charAt(pos[0]) == ']') {
            pos[0]++;
            return new ValueInfo("array", 0, maxDepth);
        }
        while (true) {
            ValueInfo v = parseValue(s, pos, depth + 1);
            if (v.maxDepth > maxDepth) maxDepth = v.maxDepth;
            items++;
            skipWs(s, pos);
            if (pos[0] >= s.length()) throw new JsonSyntaxException("Unterminated array");
            char ch = s.charAt(pos[0]);
            if (ch == ',') {
                pos[0]++;
            } else if (ch == ']') {
                pos[0]++;
                return new ValueInfo("array", items, maxDepth);
            } else {
                throw new JsonSyntaxException("Expected ',' or ']' at offset " + pos[0]);
            }
        }
    }

    private static ValueInfo parseString(String s, int[] pos) {
        int start = pos[0];
        pos[0]++; // consume '"'
        int len = 0;
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (c == '\\') {
                pos[0] += 2;
                len += 2;
                continue;
            }
            if (c == '"') {
                pos[0]++;
                return new ValueInfo("string", len, 0);
            }
            if (c == '\n') {
                throw new JsonSyntaxException("Unexpected newline in string at offset " + start);
            }
            pos[0]++;
            len++;
        }
        throw new JsonSyntaxException("Unterminated string at offset " + start);
    }

    private static ValueInfo parseNumber(String s, int[] pos) {
        int start = pos[0];
        boolean dot = false;
        boolean exp = false;
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (Character.isDigit(c)) {
                pos[0]++;
            } else if (c == '-') {
                if (pos[0] == start) {
                    pos[0]++;
                } else {
                    break;
                }
            } else if (c == '.') {
                if (dot) throw new JsonSyntaxException("Invalid number at offset " + start);
                dot = true;
                pos[0]++;
            } else if ((c == 'e' || c == 'E') && !exp) {
                exp = true;
                pos[0]++;
                if (pos[0] < s.length() && (s.charAt(pos[0]) == '+' || s.charAt(pos[0]) == '-')) {
                    pos[0]++;
                }
            } else {
                break;
            }
        }
        if (pos[0] == start) {
            throw new JsonSyntaxException("Invalid number at offset " + start);
        }
        return new ValueInfo("number", 0, 0);
    }

    private static ValueInfo expect(String s, int[] pos, String literal, String type, int size) {
        if (s.startsWith(literal, pos[0])) {
            pos[0] += literal.length();
            return new ValueInfo(type, size, 0);
        }
        throw new JsonSyntaxException("Unexpected token at offset " + pos[0]);
    }
}
