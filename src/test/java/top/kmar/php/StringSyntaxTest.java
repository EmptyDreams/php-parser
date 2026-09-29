package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.Symbol;
import java_cup.runtime.symbol.complex.ComplexLocation;
import java_cup.runtime.symbol.complex.ComplexSymbolFactory;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 字符串前端回归：直接检查 token 和 AST，不依赖字符串 IR 的解码实现。 */
class StringSyntaxTest {

    // 验证双引号快路径止于第一个未转义引号，不会吞掉运算符和后续语句。
    @Test
    void constantStringFastPathDoesNotConsumeFollowingExpressions() throws Exception {
        List<Symbol> tokens = tokens("\"a\" . \"b\"; \"c\";");
        assertEquals(List.of(PhpSymbols.T_CONSTANT_ENCAPSED_STRING, PhpSymbols.DOT,
                        PhpSymbols.T_CONSTANT_ENCAPSED_STRING, PhpSymbols.SEMI,
                        PhpSymbols.T_CONSTANT_ENCAPSED_STRING, PhpSymbols.SEMI),
                tokens.stream().map(token -> token.sym).toList());
        assertEquals("\"a\"", tokens.get(0).value());
        assertEquals("\"b\"", tokens.get(2).value());
        assertEquals("\"c\"", tokens.get(4).value());
        assertEquals(2, nodes(Main.parse("<?php \"a\" . \"b\"; \"c\";"),
                NodeStatement.ExpressionStatement.class).size());
    }

    // 验证转义引号、二进制前缀和非插值的美元符号仍保留整体原文 token。
    @Test
    void constantStringsPreservePrefixesEscapesAndNonInterpolationMarkers() throws Exception {
        for (String source : List.of("\"a\\\"b\"", "b\"text\"", "B\"price $5 {plain} $$\"",
                "\"\\$name {plain}\"", "'single \\' quote'", "\"😀\"", "\"$😀\"")) {
            List<Symbol> tokens = tokens(source + ";");
            assertEquals(2, tokens.size(), source);
            assertEquals(PhpSymbols.T_CONSTANT_ENCAPSED_STRING, tokens.getFirst().sym, source);
            assertEquals(source, tokens.getFirst().value());
            assertDoesNotThrow(() -> Main.parse("<?php " + source + ";"));
        }
    }

    // 验证常量字符串仍可作为下标读取的基础，不能统一拆成插值字符串。
    @Test
    void constantStringsRemainDereferenceable() {
        AstNode tree = Main.parse("<?php \"abc\"[0]; b\"xyz\"{1}; 'def'[2]; \"😀\"[0]; \"$😀\"[0];");
        assertEquals(5, nodes(tree, NodeDereferencableScalar.ConstantString.class).size());
        assertEquals(4, nodes(tree, NodeCallableVariable.Index.class).size());
        assertEquals(1, nodes(tree, NodeCallableVariable.CurlyIndex.class).size());
    }

    // 验证重叠的美元符号和左花括号不会把后面的插值触发字符当作普通文本吞掉。
    @Test
    void overlappingInterpolationMarkersRetainAllVariables() {
        NodeScalar.InterpolatedString scalar = assertInstanceOf(NodeScalar.InterpolatedString.class,
                scalar("\"$$a|{{$b}|${name}\""));
        List<NodeEncapsVar> variables = parts(scalar.getList()).stream()
                .filter(NodeEncapsPart.Variable.class::isInstance)
                .map(NodeEncapsPart::getVar).toList();
        assertEquals(3, variables.size());
        assertInstanceOf(NodeEncapsVar.Var.class, variables.get(0));
        assertInstanceOf(NodeEncapsVar.CurlyVar.class, variables.get(1));
        assertInstanceOf(NodeEncapsVar.NamedIndirectVar.class, variables.get(2));
        assertEquals("$|{|", text(scalar.getList()));
    }

    // 验证七种插值形态都通过统一片段列表保留各自的 AST 结构。
    @Test
    void interpolationFormsUseExplicitVariableParts() {
        NodeScalar.InterpolatedString scalar = assertInstanceOf(NodeScalar.InterpolatedString.class,
                scalar("\"$a $a[key] $a->prop ${$name} ${name} ${name[1]} {$obj->field}\""));
        assertEquals(List.of(NodeEncapsVar.Var.class, NodeEncapsVar.Index.class,
                        NodeEncapsVar.Property.class, NodeEncapsVar.IndirectVar.class,
                        NodeEncapsVar.NamedIndirectVar.class, NodeEncapsVar.NamedIndirectVarIndex.class,
                        NodeEncapsVar.CurlyVar.class), parts(scalar.getList()).stream()
                .filter(NodeEncapsPart.Variable.class::isInstance)
                .map(part -> part.getVar().getClass()).toList());
    }

    // 验证无插值的多行 heredoc 和 nowdoc 可以保留连续文本，空内容使用非空的空列表包装。
    @Test
    void heredocAndNowdocKeepConsecutiveTextAndEmptyLists() {
        for (String newline : List.of("\n", "\r", "\r\n")) {
            for (String label : List.of("EOT", "\"EOT\"", "'EOT'")) {
                NodeScalar.Heredoc value = assertInstanceOf(NodeScalar.Heredoc.class,
                        scalar("<<<" + label + newline + "first" + newline + "second" + newline
                                + "EOT" + newline));
                assertEquals("first" + newline + "second" + newline, text(value.getList()));
                assertEquals("<<<" + label + newline, value.getH().getValue());
                assertEquals("EOT", value.getE().getValue());
                assertTrue(parts(value.getList()).stream().allMatch(NodeEncapsPart.Text.class::isInstance));
                NodeScalar.Heredoc empty = assertInstanceOf(NodeScalar.Heredoc.class,
                        scalar("<<<" + label + newline + "EOT" + newline));
                assertInstanceOf(NodeEncapsList.Parts.class, empty.getList());
                assertNotNull(empty.getList().getParts());
                assertTrue(parts(empty.getList()).isEmpty());
            }
        }
    }

    // 验证标签只有在真正的行首才会结束 heredoc，插值恢复和普通文本消费都必须退出行首状态。
    @Test
    void heredocLabelsAreRecognizedOnlyAtActualLineStarts() {
        for (String prefix : List.of("{$value}", "${name}", "$value[0]", "{")) {
            NodeScalar.Heredoc value = assertInstanceOf(NodeScalar.Heredoc.class,
                    scalar("<<<EOT\n" + prefix + "EOT\nEOT\n"));
            assertTrue(text(value.getList()).endsWith("EOT\n"), prefix);
            assertEquals(ComplexLocation.of(3, 1, 3, 4), value.getE().getLocation());
        }
    }

    // 验证 LF、CR 和 CRLF 都作为完整换行保留，标签候选失败后的位置和状态仍正确。
    @Test
    void heredocLineEndingsRemainWholeTokensWithCorrectLocations() throws Exception {
        for (String newline : List.of("\n", "\r", "\r\n")) {
            List<Symbol> tokens = tokens("<<<EOT" + newline + "OTHER" + newline + "text" + newline
                    + "EOT;" + newline);
            assertEquals(List.of(PhpSymbols.T_START_HEREDOC, PhpSymbols.T_ENCAPSED_AND_WHITESPACE,
                            PhpSymbols.T_ENCAPSED_AND_WHITESPACE, PhpSymbols.T_END_HEREDOC, PhpSymbols.SEMI),
                    tokens.stream().map(token -> token.sym).toList());
            assertEquals("OTHER" + newline, tokens.get(1).value());
            assertEquals(ComplexLocation.of(2, 1, 3, 1), tokens.get(1).getLocation());
            assertEquals(ComplexLocation.of(4, 1, 4, 4), tokens.get(3).getLocation());
        }
    }

    // 验证反斜杠后紧邻换行也不会阻止下一行的结束标签检查，原文仍逐字符保留。
    @Test
    void backslashesBeforeNewlinesDoNotHideHeredocEndLabels() {
        for (String newline : List.of("\n", "\r", "\r\n")) {
            NodeScalar.Heredoc value = assertInstanceOf(NodeScalar.Heredoc.class,
                    scalar("<<<EOT" + newline + "slash\\" + newline + "EOT" + newline));
            assertEquals("slash\\" + newline, text(value.getList()));
        }
    }

    // 验证 nowdoc 中的引号、美元符号和花括号不触发插值。
    @Test
    void nowdocTreatsAllInterpolationMarkersAsText() {
        String body = "\"$name\" ${name} {$obj->field}\n\\n\\x41\n";
        NodeScalar.Heredoc value = assertInstanceOf(NodeScalar.Heredoc.class,
                scalar("b<<<'EOT'\n" + body + "EOT\n"));
        assertEquals(body, text(value.getList()));
        assertTrue(parts(value.getList()).stream().allMatch(NodeEncapsPart.Text.class::isInstance));
    }

    // 验证插值里的嵌套字符串以及嵌套 heredoc 结束后，可以正确恢复外层字符串状态。
    @Test
    void nestedStringsAndHeredocsRestoreOuterInterpolationState() {
        AstNode quoted = Main.parse("<?php \"before {$items[\"$key\"]} after\";");
        assertEquals(2, nodes(quoted, NodeScalar.InterpolatedString.class).size());
        AstNode heredoc = Main.parse("<?php <<<OUT\nbefore {$items[<<<INNER\nkey\nINNER\n]} after\nOUT;\n");
        assertEquals(2, nodes(heredoc, NodeScalar.Heredoc.class).size());
        assertTrue(nodes(heredoc, NodeEncapsPart.Text.class).stream()
                .anyMatch(part -> part.getText().getValue().contains(" after\n")));
        AstNode nestedClosure = Main.parse("<?php \"{$items[(function () { return \"$key\"; })()]}\";");
        assertEquals(2, nodes(nestedClosure, NodeScalar.InterpolatedString.class).size());
    }

    // 验证 PHP 7.2 的结束标签边界：不接受缩进、尾随空白、缺少末尾换行或仅为标签前缀。
    @Test
    void heredocEndLabelsKeepPhp72BoundaryRules() {
        for (String ending : List.of(" EOT;\n", "EOT ;\n", "EOT;", "EOT", "EOTsuffix;\n")) {
            assertThrows(PhpParseException.class, () -> Main.parse("<?php <<<EOT\ntext\n" + ending), ending);
        }
        NodeScalar.Heredoc value = assertInstanceOf(NodeScalar.Heredoc.class,
                scalar("<<<EOT\nEOTsuffix\nEOT;extra\nEOT\n"));
        assertEquals("EOTsuffix\nEOT;extra\n", text(value.getList()));
    }

    // 验证反引号沿用统一片段列表，空命令、纯文本和插值命令都能生成 AST。
    @Test
    void backticksShareTheSameExplicitPartSequence() {
        AstNode tree = Main.parse("<?php ``; `first\nsecond`; `echo $name`; ");
        List<NodeBackticksExpr.ShellExecList> commands = nodes(tree, NodeBackticksExpr.ShellExecList.class);
        assertEquals(3, commands.size());
        assertTrue(parts(commands.get(0).getList()).isEmpty());
        assertEquals("first\nsecond", text(commands.get(1).getList()));
        assertEquals(1, parts(commands.get(2).getList()).stream()
                .filter(NodeEncapsPart.Variable.class::isInstance).count());
    }

    private static List<Symbol> tokens(String source) throws Exception {
        PhpLexer lexer = new PhpLexer(new StringReader("<?php " + source), new ComplexSymbolFactory(
                PhpSymbols.TERMINAL_NAMES, PhpSymbols.NON_TERMINAL_NAMES));
        List<Symbol> result = new ArrayList<>();
        Symbol token;
        while ((token = lexer.next_token()).sym != PhpSymbols.EOF) result.add(token);
        return result;
    }

    private static NodeScalar scalar(String expression) {
        AstNode tree = Main.parse("<?php " + expression + ";");
        return assertInstanceOf(NodeScalar.class, tree.getByLabel("stmts").iterator().next().getValue()
                .getByLabel("stmt").getByLabel("expression").getByLabel("ev").getByLabel("scalar"));
    }

    private static List<NodeEncapsPart> parts(NodeEncapsList list) {
        return list.getParts().getValue();
    }

    private static String text(NodeEncapsList list) {
        StringBuilder result = new StringBuilder();
        for (NodeEncapsPart part : parts(list)) {
            if (part instanceof NodeEncapsPart.Text text) result.append(text.getText().getValue());
        }
        return result.toString();
    }

    private static <T extends AstNode> List<T> nodes(AstNode root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        for (var entry : root) result.addAll(nodes(entry.getValue(), type));
        return result;
    }
}