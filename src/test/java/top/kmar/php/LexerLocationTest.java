package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.Symbol;
import java_cup.runtime.symbol.complex.ComplexLocation;
import java_cup.runtime.symbol.complex.ComplexSymbolFactory;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.*;

class LexerLocationTest {

    private PhpLexer lexer(String source) {
        return new PhpLexer(new StringReader(source), new ComplexSymbolFactory(
                PhpSymbols.TERMINAL_NAMES, PhpSymbols.NON_TERMINAL_NAMES));
    }

    private void token(PhpLexer lexer, int kind, int startLine, int startColumn,
                       int endLine, int endColumn) throws Exception {
        Symbol token = lexer.next_token();
        assertEquals(kind, token.sym);
        assertEquals(ComplexLocation.of(startLine, startColumn, endLine, endColumn),
                token.getLocation());
    }

    // 验证单行词法单元的位置采用左闭右开的列范围。
    @Test
    void tokenSpansUseExclusiveEndColumns() throws Exception {
        PhpLexer lexer = lexer("<?php $foo += 12;");
        token(lexer, PhpSymbols.T_VARIABLE, 1, 7, 1, 11);
        token(lexer, PhpSymbols.T_PLUS_EQUAL, 1, 12, 1, 14);
        token(lexer, PhpSymbols.T_LNUMBER, 1, 15, 1, 17);
        token(lexer, PhpSymbols.SEMI, 1, 17, 1, 18);
    }

    // 验证跨行词法单元在 LF、CR 和 CRLF 换行下的位置一致。
    @Test
    void multilineTokensTrackLfCrAndCrLf() throws Exception {
        for (String newline : new String[]{"\n", "\r", "\r\n"}) {
            PhpLexer lexer = lexer("<?php 'first" + newline + "x'; 7;");
            token(lexer, PhpSymbols.T_CONSTANT_ENCAPSED_STRING, 1, 7, 2, 3);
            token(lexer, PhpSymbols.SEMI, 2, 3, 2, 4);
            token(lexer, PhpSymbols.T_LNUMBER, 2, 5, 2, 6);
        }
    }

    // 验证以换行结束的 heredoc 词法单元，其结束位置是下一行首列。
    @Test
    void newlineTerminatedHeredocTokensEndAtNextLineStart() throws Exception {
        PhpLexer lexer = lexer("<?php <<<EOT\ntext\nEOT;\n");
        token(lexer, PhpSymbols.T_START_HEREDOC, 1, 7, 2, 1);
        token(lexer, PhpSymbols.T_ENCAPSED_AND_WHITESPACE, 2, 1, 3, 1);
        token(lexer, PhpSymbols.T_END_HEREDOC, 3, 1, 3, 4);
        token(lexer, PhpSymbols.SEMI, 3, 4, 3, 5);
    }

    // 验证跨行 AST 节点正确合并子节点位置，不混用不同行的列号。
    @Test
    void astSpansCrossLinesWithoutMixingColumns() {
        AstNode root = Main.parse("<?php $a +\n  12;");
        AstNode binary = root.getByLabel("stmts").iterator().next().getValue()
                .getByLabel("stmt").getByLabel("expression").getByLabel("ev");
        assertEquals(ComplexLocation.of(1, 7, 2, 5), binary.getLocation());
        assertEquals(ComplexLocation.of(1, 7, 1, 9), binary.getByLabel("left").getLocation());
        assertEquals(ComplexLocation.of(2, 3, 2, 5), binary.getByLabel("right").getLocation());
    }

    // 验证末尾可选字段缺失时，节点位置仍取自已有子节点。
    @Test
    void missingTrailingOptionalFieldKeepsThePresentChildSpan() {
        AstNode root = Main.parse("<?php new Foo;");
        AstNode constructor = root.getByLabel("stmts").iterator().next().getValue()
                .getByLabel("stmt").getByLabel("expression").getByLabel("ev")
                .getByLabel("newExpr");
        assertNull(constructor.getByLabel("ctorArgs"));
        // CUP 节点位置由带标签的字段决定；这里仅包含类名 Foo。
        assertEquals(ComplexLocation.of(1, 11, 1, 14), constructor.getLocation());
    }

    // 验证不同词法结束状态下的 EOF 均使用工厂规范化的位置，并可重复读取。
    @Test
    void eofUsesFactoryNormalizedLocationInEveryTerminalState() throws Exception {
        for (String source : new String[]{"<?php ", "<?php 1;", "<?php ?>ignored",
                "<?php __halt_compiler();ignored"}) {
            PhpLexer lexer = lexer(source);
            Symbol symbol;
            do {
                symbol = lexer.next_token();
            } while (symbol.sym != PhpSymbols.EOF);
            assertSame(ComplexLocation.NO_LOCATION, symbol.getLocation());
            assertEquals(PhpSymbols.EOF, lexer.next_token().sym);
        }
        assertEquals(ComplexLocation.NO_LOCATION, Main.parse("<?php ").getLocation());
    }

    // 验证非法输入仍分别抛出词法异常和语法异常。
    @Test
    void invalidInputsKeepTheirExceptionTypes() {
        assertThrows(PhpLexerException.class, () -> Main.parse("echo 1;"));
        assertThrows(PhpParseException.class, () -> Main.parse("<?php $a = ;"));
    }
}
