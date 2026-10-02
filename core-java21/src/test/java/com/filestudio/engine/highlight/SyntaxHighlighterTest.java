package com.filestudio.engine.highlight;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SyntaxHighlighterTest {

    private final SyntaxHighlighter java = new SyntaxHighlighter(
            SyntaxLanguageRegistry.byExtension("java"));

    private final SyntaxHighlighter python = new SyntaxHighlighter(
            SyntaxLanguageRegistry.byExtension("py"));

    private List<TokenKind> kinds(String code) {
        return java.tokenize(code).stream().map(Token::kind).collect(Collectors.toList());
    }

    @Test
    void emptyInputProducesNoTokens() {
        assertTrue(java.tokenize("").isEmpty());
        assertTrue(java.tokenize(null).isEmpty());
    }

    @Test
    void javaKeywordsAreDetected() {
        List<TokenKind> kinds = kinds("public class Foo { }");
        assertEquals(List.of(TokenKind.KEYWORD, TokenKind.KEYWORD, TokenKind.IDENTIFIER,
                TokenKind.PUNCTUATION, TokenKind.PUNCTUATION), kinds);
    }

    @Test
    void javaSingleLineComment() {
        List<Token> tokens = java.tokenize("int x; // hi");
        TokenKind last = tokens.get(tokens.size() - 1).kind();
        assertEquals(TokenKind.COMMENT, last);
        assertTrue(last == TokenKind.COMMENT);
        String commentText = tokens.get(tokens.size() - 1).text();
        assertEquals("// hi", commentText);
    }

    @Test
    void javaBlockComment() {
        List<Token> tokens = java.tokenize("int /* c */ x;");
        boolean hasComment = tokens.stream().anyMatch(t -> t.kind() == TokenKind.COMMENT
                && t.text().equals("/* c */"));
        assertTrue(hasComment);
    }

    @Test
    void javaStringLiteral() {
        List<Token> tokens = java.tokenize("String s = \"hi\";");
        Token stringToken = tokens.stream().filter(t -> t.kind() == TokenKind.STRING).findFirst().orElseThrow();
        assertEquals("\"hi\"", stringToken.text());
    }

    @Test
    void javaCharLiteral() {
        List<Token> tokens = java.tokenize("char c = 'a';");
        Token charToken = tokens.stream().filter(t -> t.kind() == TokenKind.CHAR).findFirst().orElseThrow();
        assertEquals("'a'", charToken.text());
    }

    @Test
    void javaNumberLiterals() {
        List<Token> tokens = java.tokenize("int a = 42; float b = 3.14; long c = 0xFF;");
        List<String> numbers = tokens.stream().filter(t -> t.kind() == TokenKind.NUMBER)
                .map(Token::text).collect(Collectors.toList());
        assertEquals(List.of("42", "3.14", "0xFF"), numbers);
    }

    @Test
    void javaAnnotation() {
        List<Token> tokens = java.tokenize("@Override public void f() {}");
        Token ann = tokens.get(0);
        assertEquals(TokenKind.ANNOTATION, ann.kind());
        assertEquals("@Override", ann.text());
    }

    @Test
    void tokenOffsetsAndLineCol() {
        String code = "public\nvoid f() {}";
        List<Token> tokens = java.tokenize(code);
        Token first = tokens.get(0);
        assertEquals(TokenKind.KEYWORD, first.kind());
        assertEquals("public", first.text());
        assertEquals(0, first.offset());
        assertEquals(0, first.line());
        assertEquals(0, first.column());

        Token voidToken = tokens.stream().filter(t -> t.text().equals("void")).findFirst().orElseThrow();
        assertEquals(7, voidToken.offset());
        assertEquals(1, voidToken.line());
        assertEquals(0, voidToken.column());
        assertEquals(11, voidToken.end());
    }

    @Test
    void pythonStringAndComment() {
        List<Token> tokens = python.tokenize("x = 'hi'\n# comment");
        assertTrue(tokens.stream().anyMatch(t -> t.kind() == TokenKind.STRING
                && t.text().equals("'hi'")));
        assertTrue(tokens.stream().anyMatch(t -> t.kind() == TokenKind.COMMENT
                && t.text().equals("# comment")));
    }

    @Test
    void operatorsVsPunctuation() {
        List<Token> tokens = java.tokenize("a = b + c;");
        List<TokenKind> ks = tokens.stream().map(Token::kind).collect(Collectors.toList());
        // a(ID) = (OP) b(ID) + (OP) c(ID) ; (PUNCT)
        assertEquals(TokenKind.IDENTIFIER, ks.get(0));
        assertEquals(TokenKind.OPERATOR, ks.get(1));
        assertEquals(TokenKind.IDENTIFIER, ks.get(2));
        assertEquals(TokenKind.OPERATOR, ks.get(3));
        assertEquals(TokenKind.IDENTIFIER, ks.get(4));
        assertEquals(TokenKind.PUNCTUATION, ks.get(5));
    }

    @Test
    void escapedQuotesInString() {
        List<Token> tokens = java.tokenize("\"she said \\\"hi\\\"\"");
        Token s = tokens.get(0);
        assertEquals(TokenKind.STRING, s.kind());
        assertEquals("\"she said \\\"hi\\\"\"", s.text());
    }

    @Test
    void regexLikeNumberStopsAtIdentifier() {
        List<Token> tokens = java.tokenize("int e = 1;");
        List<String> texts = tokens.stream().map(Token::text).collect(Collectors.toList());
        assertTrue(texts.contains("1"));
        // 'e' should be an identifier, not part of a number
        assertTrue(tokens.stream().anyMatch(t -> t.kind() == TokenKind.IDENTIFIER
                && t.text().equals("e")));
    }
}
