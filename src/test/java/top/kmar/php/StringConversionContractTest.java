package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.*;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证字符串与魔术常量的损坏 AST、分段解码、来源以及模型隔离契约。 */
class StringConversionContractTest {
    private static final ComplexLocation LOCATION = ComplexLocation.of(4, 3, 8, 29);
    private static final ComplexLocation FIRST = ComplexLocation.of(5, 4, 5, 8);
    private static final ComplexLocation SECOND = ComplexLocation.of(5, 8, 5, 11);
    private static final ComplexLocation VARIABLE = ComplexLocation.of(6, 2, 6, 12);
    private static final ComplexLocation LEAF = ComplexLocation.of(6, 4, 6, 10);
    private static final SourceInfo SOURCE = new SourceInfo("strings.php", null);

    // 空列表是合法的空字符串，缺失和未知列表包装则必须明确报错。
    @Test
    void rejectsMissingAndUnknownPartWrappers() {
        assertFailure(new NodeScalar.InterpolatedString(null, LOCATION), ".list");
        assertFailure(new NodeScalar.InterpolatedString(new NodeEncapsList.Parts(null, LOCATION), LOCATION), ".parts");
        assertFailure(new NodeScalar.InterpolatedString(new NodeEncapsList() {
            @Override public NodeListNodeEncapsPart getParts() { return parts().getParts(); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        }, LOCATION), ".list");
        assertBytes(new byte[0], assertInstanceOf(IrStringLiteral.class, convert(string())).value());
    }

    // 列表值、元素和片段必需字段不可缺失，未知片段也不能凭标签降级。
    @Test
    void rejectsMalformedPartListsAndFields() {
        assertFailure(new NodeScalar.InterpolatedString(new NodeEncapsList.Parts(
                new NodeListNodeEncapsPart(null, LOCATION), LOCATION), LOCATION), ".parts");
        assertFailure(new NodeScalar.InterpolatedString(new NodeEncapsList.Parts(
                new NodeListNodeEncapsPart(Arrays.asList(text("ok"), null), LOCATION), LOCATION), LOCATION), ".parts[1]");
        assertFailure(string(new NodeEncapsPart.Text(null, LOCATION)), ".parts[0].text");
        assertFailure(string(new NodeEncapsPart.Text(token(null), LOCATION)), ".parts[0].text");
        assertFailure(string(new NodeEncapsPart.Variable(null, LOCATION)), ".parts[0].var");
        assertFailure(string(new NodeEncapsPart() {
            @Override public NodeString getText() { return token("valid"); }
            @Override public ComplexLocation getLocation() { return LOCATION; }
        }), ".parts[0]");
    }

    // 文本内容允许为空；空片段不能被当成缺失名称，也不能吞掉随后插值。
    @Test
    void acceptsEmptyTextAndRetainsFollowingInterpolation() {
        assertBytes(new byte[0], assertInstanceOf(IrStringLiteral.class, convert(string(text("")))).value());
        IrStringTemplate result = assertInstanceOf(IrStringTemplate.class,
                convert(string(text(""), variable(new NodeEncapsVar.Var(token("value"), VARIABLE)), text(""))));
        assertEquals(1, result.parts().stream().filter(IrStringInterpolation.class::isInstance).count());
        for (IrStringPart part : result.parts()) {
            if (part instanceof IrStringText literal) assertEquals(0, literal.value().size());
        }
    }

    // 原始常量字符串必须含合法前缀和配对引号，不能接收缺失 token 或裸内容。
    @Test
    void rejectsMalformedConstantStringTokens() {
        for (NodeString token : new NodeString[]{null, token(null), token(""), token("text"), token("'text"),
                token("\"text'"), token("q'text'"), token("b")}) {
            assertFailure(rawString(token), ".str");
        }
        assertBytes(new byte[0], assertInstanceOf(IrStringLiteral.class, convert(rawString(token("''")))).value());
        assertBytes(new byte[0], assertInstanceOf(IrStringLiteral.class, convert(rawString(token("B\"\"")))).value());
    }

    // 魔术常量只接受明确的八种拼写，大小写不影响种类；缺失和伪造标记报错。
    @Test
    void validatesMagicConstantMarkers() {
        for (NodeString token : new NodeString[]{null, token(null), token(""), token("__LINE__ "), token(" __LINE__"),
                token("__UNKNOWN__"), token("LINE"), token("__l\u0131ne__"), token("__CLA\u017f\u017f__")}) {
            assertFailure(new NodeScalar.MagicConst(token, LOCATION), ".kw");
        }
        IrMagicConstant value = assertInstanceOf(IrMagicConstant.class,
                convert(new NodeScalar.MagicConst(token("__lInE__"), LOCATION)));
        assertEquals(MagicConstantKind.LINE, value.kind());
        assertSource(LOCATION, value.source());
    }

    // heredoc 的起始、正文和结束标签都是必需字段，标记必须完整且相互匹配。
    @Test
    void rejectsMalformedHeredocMarkersAndLabels() {
        assertFailure(new NodeScalar.Heredoc(null, parts(), token("END"), LOCATION), ".h");
        assertFailure(new NodeScalar.Heredoc(token("<<<END\n"), null, token("END"), LOCATION), ".list");
        assertFailure(new NodeScalar.Heredoc(token("<<<END\n"), parts(), null, LOCATION), ".e");
        for (String header : List.of("", "END", "<<<END", "<<<'END\n", "<<<1END\n", "q<<<END\n")) {
            assertFailure(new NodeScalar.Heredoc(token(header), parts(), token("END"), LOCATION), ".h");
        }
        for (String end : List.of("", "OTHER", "end", "END;", " END", "END\n")) {
            assertFailure(new NodeScalar.Heredoc(token("<<<END\n"), parts(), token(end), LOCATION), ".e");
        }
    }

    // nowdoc 是纯文本形式，伪造插值节点不能被当成普通 heredoc 接受。
    @Test
    void rejectsInterpolationInsideNowdoc() {
        var scalar = new NodeScalar.Heredoc(token("<<<'END'\n"), parts(text("literal"),
                variable(new NodeEncapsVar.Var(token("value"), VARIABLE))), token("END"), LOCATION);
        assertFailure(scalar, ".parts[1].var");
    }

    // 所有七种插值形式都校验各自必需字段，未知变体不依据现有标签推测含义。
    @Test
    void rejectsMissingAndUnknownInterpolationFields() {
        assertVariableFailure(new NodeEncapsVar.Var(null, VARIABLE), ".var");
        assertVariableFailure(new NodeEncapsVar.Index(null, offset("1"), VARIABLE), ".var");
        assertVariableFailure(new NodeEncapsVar.Index(token("items"), null, VARIABLE), ".offset");
        assertVariableFailure(new NodeEncapsVar.Property(null, token("name"), VARIABLE), ".var");
        assertVariableFailure(new NodeEncapsVar.Property(token("object"), null, VARIABLE), ".prop");
        assertVariableFailure(new NodeEncapsVar.IndirectVar(null, VARIABLE), ".e");
        assertVariableFailure(new NodeEncapsVar.NamedIndirectVar(null, VARIABLE), ".name");
        assertVariableFailure(new NodeEncapsVar.NamedIndirectVarIndex(null, integer(), VARIABLE), ".name");
        assertVariableFailure(new NodeEncapsVar.NamedIndirectVarIndex(token("items"), null, VARIABLE), ".index");
        assertVariableFailure(new NodeEncapsVar.CurlyVar(null, VARIABLE), ".v");
        assertVariableFailure(new NodeEncapsVar() {
            @Override public NodeString getVar() { return token("value"); }
            @Override public ComplexLocation getLocation() { return VARIABLE; }
        }, ".parts[0].var");
        for (NodeString name : new NodeString[]{token(null), token("")}) {
            assertVariableFailure(new NodeEncapsVar.Var(name, VARIABLE), ".var");
            assertVariableFailure(new NodeEncapsVar.NamedIndirectVar(name, VARIABLE), ".name");
        }
    }

    // 四种简写下标校验必需值，数字 token 必须符合词法拼写而不是任意可转换字符串。
    @Test
    void rejectsMissingAndMalformedInterpolationOffsets() {
        assertOffsetFailure(new NodeEncapsVarOffset.StringOffset(null, LEAF), ".s");
        assertOffsetFailure(new NodeEncapsVarOffset.NumericOffset(null, LEAF), ".n");
        assertOffsetFailure(new NodeEncapsVarOffset.NegativeNumericOffset(null, LEAF), ".n");
        assertOffsetFailure(new NodeEncapsVarOffset.VariableOffset(null, LEAF), ".v");
        assertOffsetFailure(new NodeEncapsVarOffset() {
            @Override public NodeString getN() { return token("1"); }
            @Override public ComplexLocation getLocation() { return LEAF; }
        }, ".offset");
        for (String number : List.of("", "-1", "+1", "1.0", "1e3", "0x", "0xG", "0b", "0b2", "١", "1 ")) {
            assertOffsetFailure(new NodeEncapsVarOffset.NumericOffset(token(number), LEAF), ".n");
            assertOffsetFailure(new NodeEncapsVarOffset.NegativeNumericOffset(token(number), LEAF), ".n");
        }
    }

    // Unicode 转义不完整、含非十六进制字符或超过上限时给出转换异常，不静默替换。
    @Test
    void rejectsMalformedUnicodeEscapesWithContextualPaths() {
        for (String escape : List.of("u{}", "u{", "u{XYZ}", "u{41", "u{110000}", "u{FFFFFFFFFFFFFFFFFFFFFFFF}")) {
            assertFailure(string(text("\\" + escape)), ".parts[0].text");
            assertFailure(rawString(token("\"\\" + escape + "\"")), ".str");
        }
    }

    // Java 输入中的未配对代理项无明确 UTF-8 字节值，不能按替换字符掩盖损坏数据。
    @Test
    void rejectsUnpairedSurrogatesInEveryLiteralMode() {
        for (String text : List.of("\uD800", "\uDC00", "\uD800x", "x\uDC00")) {
            assertFailure(string(text(text)), ".parts[0].text");
            assertFailure(rawString(token("'" + text + "'")), ".str");
            assertFailure(new NodeScalar.Heredoc(token("<<<'END'\n"), parts(text(text + "\n")), token("END"), LOCATION),
                    ".parts[0].text");
        }
    }

    // 连续文本先作为同一个逻辑流解码，转义序列和 UTF-16 代理对可以跨 token。
    @Test
    void decodesEscapeSequencesAndSurrogatePairsAcrossTextTokens() {
        assertText("A", convert(string(text("\\u"), text("{"), text("41}"))));
        assertText("A", convert(string(text("\\"), text("x"), text("4"), text("1"))));
        assertText("A", convert(string(text("\\1"), text("01"))));
        assertText("😀", convert(string(text("\uD83D"), text("\uDE00"))));
        assertText("A\nB", convert(string(text("A\\"), text("nB"))));
    }

    // 显式 PHP Unicode 转义与 Java 代理项输入不同，保留 PHP 7.2 的三字节编码规则。
    @Test
    void preservesPhpUnicodeEscapeSurrogateBytes() {
        IrStringLiteral value = assertInstanceOf(IrStringLiteral.class,
                convert(string(text("\\u{D800}"))));
        assertBytes(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, value.value());
    }

    // heredoc 只移除结束标签前的一次物理换行，CRLF 可跨 token；nowdoc 也不执行转义。
    @Test
    void trimsOnlyTheFinalPhysicalHeredocNewlineAcrossTokens() {
        assertText("one", convert(heredoc("<<<END\r\n", text("one\r"), text("\n"))));
        assertText("one\r\n", convert(heredoc("<<<END\r\n", text("one\r\n\r"), text("\n"))));
        assertText("\\n", convert(heredoc("<<<'END'\n", text("\\n"), text("\n"))));
        assertText("\n", convert(heredoc("<<<END\n", text("\\n"), text("\n"))));
    }

    // 损坏正文缺少终止换行时失败，尾部伪造空文本不能掩盖这一缺失。
    @Test
    void rejectsHeredocContentsWithoutTerminatingNewlines() {
        assertFailure(heredoc("<<<END\n", text("missing")), ".parts[0].text");
        var value = variable(new NodeEncapsVar.Var(token("value"), VARIABLE));
        assertFailure(heredoc("<<<END\n", value), ".parts[0]");
        assertFailure(heredoc("<<<END\n", value, text("")), ".parts[1].text");
        assertText("", convert(heredoc("<<<END\n", text(""))));
    }

    // 常量节点不能伪装插值或容纳额外未转义引号，结束引号也不能被反斜杠消费。
    @Test
    void rejectsDamagedRawStringBoundariesAndInterpolation() {
        for (String spelling : List.of("'a'b'", "\"$value\"", "\"${name}\"", "\"{$value}\"", "'trailing\\'")) {
            assertFailure(rawString(token(spelling)), ".str");
        }
    }

    // 模板和嵌套表达式保留自身来源，连续文本只合并已有 token 的首尾范围。
    @Test
    void preservesDistinctTemplateTextAndExpressionOrigins() {
        NodeExpr child = integer();
        var interpolation = new NodeEncapsVar.IndirectVar(child, VARIABLE);
        var scalar = new NodeScalar.InterpolatedString(parts(
                new NodeEncapsPart.Text(new NodeString("A", FIRST), LOCATION),
                new NodeEncapsPart.Text(new NodeString("B", SECOND), LOCATION),
                new NodeEncapsPart.Variable(interpolation, LOCATION)), LOCATION);
        IrStringTemplate result = assertInstanceOf(IrStringTemplate.class, convert(scalar));
        assertSource(LOCATION, result.source());
        assertEquals(2, result.parts().size());
        IrStringText text = assertInstanceOf(IrStringText.class, result.parts().getFirst());
        assertSource(ComplexLocation.of(5, 4, 5, 11), text.source());
        assertBytes("AB".getBytes(StandardCharsets.UTF_8), text.value());
        IrStringInterpolation part = assertInstanceOf(IrStringInterpolation.class, result.parts().get(1));
        assertSource(VARIABLE, part.source());
        IrVariable variable = assertInstanceOf(IrVariable.class, part.expression());
        IrComputedName name = assertInstanceOf(IrComputedName.class, variable.name());
        assertSource(LEAF, name.expression().source());
    }

    // 任一参与文本范围未知时合并范围也未知；零宽范围仍是合法已知范围。
    @Test
    void distinguishesUnknownAndZeroWidthMergedTextOrigins() {
        for (ComplexLocation missing : Arrays.asList(ComplexLocation.NO_LOCATION, null)) {
            for (boolean firstUnknown : List.of(false, true)) {
                IrStringTemplate result = assertInstanceOf(IrStringTemplate.class, convert(string(
                        new NodeEncapsPart.Text(new NodeString("A", firstUnknown ? missing : FIRST), LOCATION),
                        new NodeEncapsPart.Text(new NodeString("B", firstUnknown ? SECOND : missing), LOCATION),
                        variable(new NodeEncapsVar.Var(token("value"), VARIABLE)))));
                assertNull(assertInstanceOf(IrStringText.class, result.parts().getFirst()).source().range());
            }
        }
        ComplexLocation zero = ComplexLocation.of(5, 4, 5, 4);
        IrStringTemplate result = assertInstanceOf(IrStringTemplate.class, convert(string(
                new NodeEncapsPart.Text(new NodeString("A", zero), zero),
                variable(new NodeEncapsVar.Var(token("value"), VARIABLE)))));
        assertSource(zero, result.parts().getFirst().source());
        assertSource(LOCATION, result.source());
    }

    // 纯文本字符串仍取整个标量范围，而不是仅使用第一个文本 token 的范围。
    @Test
    void preservesScalarOriginsForLiteralResults() {
        var scalar = new NodeScalar.InterpolatedString(parts(
                new NodeEncapsPart.Text(new NodeString("A", FIRST), FIRST),
                new NodeEncapsPart.Text(new NodeString("B", SECOND), SECOND)), LOCATION);
        assertSource(LOCATION, convert(scalar).source());
        var raw = new NodeDereferencableScalar.ConstantString(new NodeString("'A'", LEAF), FIRST);
        assertSource(FIRST, convert(new NodeScalar.DereferencableScalar(raw, LOCATION)).source());
    }

    // 不支持的插值子树必须沿完整字段路径失败，字符串外壳不能吞掉错误。
    @Test
    void propagatesUnsupportedNestedExpressionsThroughInterpolationPaths() {
        NodeExpr unsupported = syntaxExpression("`echo sentinel`");
        assertVariableFailure(new NodeEncapsVar.IndirectVar(unsupported, VARIABLE), ".e.ev");
        assertVariableFailure(new NodeEncapsVar.NamedIndirectVarIndex(token("items"), unsupported, VARIABLE), ".index.ev");
        for (String php : List.of("\"{$items[]}\"", "\"${items[$items[]]}\"")) {
            NodeExpr parsed = syntaxExpression(php);
            var error = assertThrows(SyntaxConversionException.class, () -> convertExpression(parsed));
            assertTrue(error.fieldPath().startsWith("expression.ev.scalar.list.parts[0].var"), error.fieldPath());
        }
    }

    // 转换不修改声明或 AST，结果中的字符串值和模板均不保留 CUP 节点。
    @Test
    void convertsWithoutMutatingSyntaxOrLeakingAst() throws ReflectiveOperationException {
        var file = DeclarationExtractor.extract(Main.parse("<?php function f() { return \"a{$value}b\"; }"), "strings.php");
        var function = (FunctionDefinition) file.namespaceSections().getFirst().declarations().getFirst();
        String before = file.syntax().toTreeString(false);
        IrBlock result = SyntaxConverter.convertBody(function.body());
        assertEquals(before, file.syntax().toTreeString(false));
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "f").getFirst());
        assertEquals(result, SyntaxConverter.convertBody(function.body()));
        assertNoAst(result, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static NodeScalar rawString(NodeString token) {
        return new NodeScalar.DereferencableScalar(new NodeDereferencableScalar.ConstantString(token, LOCATION), LOCATION);
    }

    private static NodeScalar string(NodeEncapsPart... parts) {
        return new NodeScalar.InterpolatedString(parts(parts), LOCATION);
    }

    private static NodeScalar heredoc(String header, NodeEncapsPart... parts) {
        return new NodeScalar.Heredoc(token(header), parts(parts), token("END"), LOCATION);
    }

    private static NodeEncapsList.Parts parts(NodeEncapsPart... parts) {
        return new NodeEncapsList.Parts(new NodeListNodeEncapsPart(List.of(parts), LOCATION), LOCATION);
    }

    private static NodeEncapsPart text(String text) {
        return new NodeEncapsPart.Text(token(text), LOCATION);
    }

    private static NodeEncapsPart variable(NodeEncapsVar variable) {
        return new NodeEncapsPart.Variable(variable, VARIABLE);
    }

    private static NodeEncapsVarOffset offset(String number) {
        return new NodeEncapsVarOffset.NumericOffset(token(number), LEAF);
    }

    private static NodeString token(String text) {
        return new NodeString(text, LEAF);
    }

    private static NodeExpr integer() {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(token("1"), LEAF), LEAF), LEAF);
    }

    private static IrExpression convert(NodeScalar scalar) {
        return convertExpression(new NodeExpr.ExprWithoutVariable(
                new NodeExprWithoutVariable.Scalar(scalar, LOCATION), LOCATION));
    }

    private static IrExpression convertExpression(NodeExpr expression) {
        return SyntaxConverter.convertExpression(new SyntaxExpression(expression, SOURCE));
    }

    private static NodeExpr syntaxExpression(String expression) {
        var file = DeclarationExtractor.extract(Main.parse("<?php const SAMPLE = " + expression + ";"));
        var constant = (ConstantDefinition) file.namespaceSections().getFirst().declarations().getFirst();
        return assertInstanceOf(NodeExpr.class, constant.value().syntax());
    }

    private static void assertVariableFailure(NodeEncapsVar variable, String suffix) {
        assertFailure(string(variable(variable)), suffix);
    }

    private static void assertOffsetFailure(NodeEncapsVarOffset offset, String suffix) {
        assertVariableFailure(new NodeEncapsVar.Index(token("items"), offset, VARIABLE), suffix);
    }

    private static void assertFailure(NodeScalar scalar, String suffix) {
        var error = assertThrows(SyntaxConversionException.class, () -> convert(scalar));
        assertTrue(error.fieldPath().startsWith("expression.ev.scalar"), error.fieldPath());
        assertTrue(error.fieldPath().endsWith(suffix), error.fieldPath());
        assertEquals("strings.php", error.source().sourceId());
        assertFalse(error.reason().isBlank());
    }

    private static void assertText(String text, IrExpression expression) {
        assertBytes(text.getBytes(StandardCharsets.UTF_8), assertInstanceOf(IrStringLiteral.class, expression).value());
    }

    private static void assertBytes(byte[] expected, ByteString actual) {
        assertArrayEquals(expected, actual.toByteArray());
        assertEquals(expected.length, actual.size());
    }

    private static void assertSource(ComplexLocation expected, SourceInfo actual) {
        assertEquals("strings.php", actual.sourceId());
        assertEquals(new SourceRange(expected.getStartLine(), expected.getStartColumn(),
                expected.getEndLine(), expected.getEndColumn()), actual.range());
    }

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        assertFalse(value instanceof AstNode, "字符串 IR 不应保留 CUP AST");
        if (value instanceof List<?> list) {
            for (Object child : list) assertNoAst(child, visited);
        } else if (value.getClass().isRecord()) {
            for (RecordComponent component : value.getClass().getRecordComponents()) {
                assertNoAst(component.getAccessor().invoke(value), visited);
            }
        } else {
            assertTrue(value instanceof ByteString || value instanceof String || value instanceof Enum<?>
                    || value instanceof Number || value instanceof Boolean, "未预期的结果字段类型：" + value.getClass());
        }
    }
}
