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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证反引号命令的损坏 AST、分段解码、独立来源和无副作用转换契约。 */
class ShellExecConversionContractTest {
    private static final ComplexLocation WRAPPER = ComplexLocation.of(1, 1, 12, 30);
    private static final ComplexLocation SHELL = ComplexLocation.of(2, 2, 11, 28);
    private static final ComplexLocation COMMAND = ComplexLocation.of(3, 3, 10, 26);
    private static final ComplexLocation LIST = ComplexLocation.of(4, 4, 9, 24);
    private static final ComplexLocation PARTS = ComplexLocation.of(5, 5, 9, 22);
    private static final ComplexLocation FIRST = ComplexLocation.of(6, 4, 6, 8);
    private static final ComplexLocation SECOND = ComplexLocation.of(6, 8, 6, 11);
    private static final ComplexLocation VARIABLE = ComplexLocation.of(7, 2, 7, 12);
    private static final ComplexLocation LEAF = ComplexLocation.of(7, 4, 7, 10);
    private static final SourceInfo SOURCE = new SourceInfo("shell-contract.php", new SourceRange(99, 1, 99, 9));

    // 命令节点及其确定的文法变体不可缺失，未知节点不能依赖已有 getter 猜测含义。
    @Test
    void rejectsMissingAndUnknownCommandWrappers() {
        assertFailure(shell(null), ".cmd", SHELL);
        assertFailure(shell(new NodeBackticksExpr() {
            @Override public NodeEncapsList getList() { return parts(text("valid")); }
            @Override public ComplexLocation getLocation() { return COMMAND; }
        }), ".cmd", COMMAND);
        var unknown = new NodeExprWithoutVariable() {
            @Override public NodeBackticksExpr getCmd() { return command(text("valid")); }
            @Override public ComplexLocation getLocation() { return SHELL; }
        };
        assertFailure(new NodeExpr.ExprWithoutVariable(unknown, WRAPPER), "", SHELL);
    }

    // 列表包装和列表值都是必需字段；空列表合法，空元素则必须定位到对应下标。
    @Test
    void rejectsMissingAndUnknownPartLists() {
        assertFailure(shell(new NodeBackticksExpr.ShellExecList(null, COMMAND)), ".cmd.list", COMMAND);
        assertFailure(shell(new NodeBackticksExpr.ShellExecList(new NodeEncapsList() {
            @Override public NodeListNodeEncapsPart getParts() { return parts().getParts(); }
            @Override public ComplexLocation getLocation() { return LIST; }
        }, COMMAND)), ".cmd.list", LIST);
        assertFailure(shell(new NodeBackticksExpr.ShellExecList(new NodeEncapsList.Parts(null, LIST), COMMAND)),
                ".cmd.list.parts", LIST);
        assertFailure(shell(commandWithValues(null)), ".cmd.list.parts", PARTS);
        assertFailure(shell(commandWithValues(Arrays.asList(text("valid"), null))), ".cmd.list.parts[1]", PARTS);
        assertText("", convert(shell(command())).command());
    }

    // 文本 token、文本值和变量节点缺失时明确失败，未知片段即使带合法文本也不接受。
    @Test
    void rejectsMissingAndUnknownParts() {
        assertFailure(shell(command(new NodeEncapsPart.Text(null, FIRST))), ".cmd.list.parts[0].text", FIRST);
        assertFailure(shell(command(text(null))), ".cmd.list.parts[0].text", LEAF);
        assertFailure(shell(command(new NodeEncapsPart.Variable(null, FIRST))), ".cmd.list.parts[0].var", FIRST);
        assertFailure(shell(command(new NodeEncapsPart() {
            @Override public NodeString getText() { return token("valid"); }
            @Override public ComplexLocation getLocation() { return FIRST; }
        })), ".cmd.list.parts[0]", FIRST);
    }

    // 七种插值包装仍执行各自必需字段检查，不能因位于反引号中而弱化校验。
    @Test
    void rejectsMalformedInterpolationFields() {
        assertVariableFailure(new NodeEncapsVar.Var(null, VARIABLE), ".var", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.Index(null, offset(), VARIABLE), ".var", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.Index(token("items"), null, VARIABLE), ".offset", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.Property(null, token("name"), VARIABLE), ".var", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.Property(token("object"), null, VARIABLE), ".prop", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.IndirectVar(null, VARIABLE), ".e", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.NamedIndirectVar(null, VARIABLE), ".name", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.NamedIndirectVarIndex(null, integer(), VARIABLE), ".name", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.NamedIndirectVarIndex(token("items"), null, VARIABLE), ".index", VARIABLE);
        assertVariableFailure(new NodeEncapsVar.CurlyVar(null, VARIABLE), ".v", VARIABLE);
        for (String name : Arrays.asList(null, "")) {
            assertVariableFailure(new NodeEncapsVar.Var(token(name), VARIABLE), ".var", VARIABLE);
        }
        assertVariableFailure(new NodeEncapsVar() {
            @Override public NodeString getVar() { return token("valid"); }
            @Override public ComplexLocation getLocation() { return VARIABLE; }
        }, "", VARIABLE);
    }

    // 插值下标及递归表达式的错误保留完整路径，不被命令外壳吞掉或降级成文本。
    @Test
    void propagatesInvalidOffsetsAndUnknownNestedExpressions() {
        assertVariableFailure(new NodeEncapsVar.Index(token("items"),
                new NodeEncapsVarOffset.NumericOffset(token("1.2"), LEAF), VARIABLE), ".offset.n", LEAF);
        assertVariableFailure(new NodeEncapsVar.Index(token("items"), new NodeEncapsVarOffset() {
            @Override public NodeString getN() { return token("1"); }
            @Override public ComplexLocation getLocation() { return LEAF; }
        }, VARIABLE), ".offset", LEAF);
        NodeExpr unknown = new NodeExpr() {
            @Override public ComplexLocation getLocation() { return LEAF; }
        };
        assertVariableFailure(new NodeEncapsVar.IndirectVar(unknown, VARIABLE), ".e", LEAF);
        assertVariableFailure(new NodeEncapsVar.NamedIndirectVarIndex(token("items"), unknown, VARIABLE),
                ".index", LEAF);
    }

    // 空文本可穿插在任意位置，纯空文本折叠为空字节值，插值依然按原顺序保留。
    @Test
    void acceptsEmptyTextWithoutLosingInterpolationOrder() {
        assertText("", convert(shell(command(text(""), text("")))).command());
        IrStringTemplate template = assertInstanceOf(IrStringTemplate.class, convert(shell(command(text(""),
                variable(new NodeEncapsVar.Var(token("first"), VARIABLE)), text(""), text(""),
                variable(new NodeEncapsVar.Var(token("second"), VARIABLE)), text("")))).command());
        assertEquals(2, template.parts().size());
        assertEquals("first", fixedVariable(template.parts().getFirst()).value());
        assertEquals("second", fixedVariable(template.parts().getLast()).value());
    }

    // 连续文本是同一逻辑流；跨 token 的反引号转义被解码，双引号转义保留反斜杠。
    @Test
    void decodesShellEscapesAcrossEmptyAndNonemptyTextTokens() {
        assertText("`", convert(shell(command(text("\\"), text(""), text("`")))).command());
        assertText("\\\"", convert(shell(command(text("\\"), text(""), text("\"")))).command());
        assertText("\\", convert(shell(command(text("\\"), text(""), text("\\")))).command());
        assertText("$", convert(shell(command(text("\\"), text("$")))).command());
        assertText("\n", convert(shell(command(text("\\"), text("n")))).command());
        assertText("A", convert(shell(command(text("\\"), text("x"), text(""), text("4"), text("1")))).command());
        assertText("A", convert(shell(command(text("\\1"), text(""), text("01")))).command());
        assertText("A", convert(shell(command(text("\\u"), text(""), text("{"), text("41}")))).command());
        assertText("\\q", convert(shell(command(text("\\"), text("q")))).command());
        assertText("tail\\", convert(shell(command(text("tail"), text("\\"), text("")))).command());
        assertText("line\r\n", convert(shell(command(text("line\r"), text("\n")))).command());
    }

    // 非法 Unicode 转义定位到起始反斜杠所属 token，即使错误在后续片段才暴露。
    @Test
    void rejectsInvalidUnicodeEscapesAtTheirStartingTextToken() {
        for (String escape : List.of("u{}", "u{", "u{XYZ}", "u{41", "u{110000}", "u{FFFFFFFFFFFFFFFFFFFFFFFF}")) {
            assertFailure(shell(command(text("\\" + escape))), ".cmd.list.parts[0].text", LEAF);
        }
        assertFailure(shell(command(text(""), new NodeEncapsPart.Text(new NodeString("\\", FIRST), PARTS),
                text(""), new NodeEncapsPart.Text(new NodeString("u{110000}", SECOND), PARTS))),
                ".cmd.list.parts[1].text", FIRST);
    }

    // UTF-16 代理对允许跨文本 token；孤立代理项报错，显式 PHP Unicode 代理区转义保留三字节值。
    @Test
    void distinguishesRawSurrogatesFromExplicitUnicodeEscapes() {
        assertText("😀", convert(shell(command(text("\uD83D"), text(""), text("\uDE00")))).command());
        for (String text : List.of("\uD800", "\uDC00", "\uD800x", "x\uDC00")) {
            assertFailure(shell(command(text(text))), ".cmd.list.parts[0].text", LEAF);
        }
        IrStringLiteral escaped = assertInstanceOf(IrStringLiteral.class,
                convert(shell(command(text("\\u"), text("{D800}")))).command());
        assertArrayEquals(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80}, escaped.value().toByteArray());
    }

    // 插值隔断文本流：代理对和 Unicode 转义不能跨插值拼接，末尾反斜杠保持原值。
    @Test
    void keepsEscapeAndSurrogateDecodingWithinEachTextRun() {
        NodeEncapsPart interpolation = variable(new NodeEncapsVar.Var(token("value"), VARIABLE));
        assertFailure(shell(command(text("\uD83D"), interpolation, text("\uDE00"))),
                ".cmd.list.parts[0].text", LEAF);
        assertFailure(shell(command(text("\\u{"), interpolation, text("41}"))),
                ".cmd.list.parts[0].text", LEAF);
        IrStringTemplate result = assertInstanceOf(IrStringTemplate.class,
                convert(shell(command(text("\\"), interpolation, text("`")))).command());
        assertPartText("\\", result.parts().getFirst());
        assertInstanceOf(IrStringInterpolation.class, result.parts().get(1));
        assertPartText("`", result.parts().getLast());
    }

    // 外层命令、正文、合并文本、插值和递归表达式分别使用自己的来源，不借用包装范围。
    @Test
    void preservesIndependentShellCommandAndChildSources() {
        IrShellExec shell = convert(shell(command(
                new NodeEncapsPart.Text(new NodeString("A", FIRST), PARTS),
                new NodeEncapsPart.Text(new NodeString("B", SECOND), PARTS),
                variable(new NodeEncapsVar.IndirectVar(integer(), VARIABLE)))));
        assertSource(SHELL, shell.source());
        IrStringTemplate result = assertInstanceOf(IrStringTemplate.class, shell.command());
        assertSource(COMMAND, result.source());
        assertEquals(2, result.parts().size());
        assertSource(ComplexLocation.of(6, 4, 6, 11), result.parts().getFirst().source());
        assertPartText("AB", result.parts().getFirst());
        IrStringInterpolation interpolation = assertInstanceOf(IrStringInterpolation.class, result.parts().getLast());
        assertSource(VARIABLE, interpolation.source());
        IrVariable variable = assertInstanceOf(IrVariable.class, interpolation.expression());
        assertSource(VARIABLE, variable.source());
        IrComputedName name = assertInstanceOf(IrComputedName.class, variable.name());
        assertSource(VARIABLE, name.source());
        assertSource(LEAF, name.expression().source());
        assertSource(COMMAND, convert(shell(command(text("literal")))).command().source());
        assertSource(COMMAND, convert(shell(command())).command().source());
    }

    // 外层与正文的未知范围互不填补，也不回退到 SyntaxExpression 提供的范围。
    @Test
    void keepsUnknownShellAndCommandSourcesIndependent() {
        for (ComplexLocation unknown : Arrays.asList(ComplexLocation.NO_LOCATION, null)) {
            IrShellExec unknownShell = convert(shell(new NodeBackticksExpr.ShellExecList(parts(text("x")), COMMAND), unknown));
            assertSource(unknown, unknownShell.source());
            assertSource(COMMAND, unknownShell.command().source());
            IrShellExec unknownCommand = convert(shell(new NodeBackticksExpr.ShellExecList(parts(text("x")), unknown)));
            assertSource(SHELL, unknownCommand.source());
            assertSource(unknown, unknownCommand.command().source());
            IrShellExec allUnknown = convert(locatedShell(unknown));
            assertSource(unknown, allUnknown.source());
            IrStringTemplate template = assertInstanceOf(IrStringTemplate.class, allUnknown.command());
            assertSource(unknown, template.source());
            for (IrStringPart part : template.parts()) assertSource(unknown, part.source());
            IrVariable variable = assertInstanceOf(IrVariable.class,
                    assertInstanceOf(IrStringInterpolation.class, template.parts().getLast()).expression());
            assertSource(unknown, variable.source());
            assertSource(unknown, variable.name().source());
        }
    }

    // 真实零宽范围不能与未知范围混淆，所有层次原样保留零宽位置。
    @Test
    void preservesZeroWidthSourcesAtEveryShellLayer() {
        ComplexLocation zero = ComplexLocation.of(8, 9, 8, 9);
        IrShellExec shell = convert(locatedShell(zero));
        assertSource(zero, shell.source());
        IrStringTemplate template = assertInstanceOf(IrStringTemplate.class, shell.command());
        assertSource(zero, template.source());
        for (IrStringPart part : template.parts()) assertSource(zero, part.source());
        IrVariable variable = assertInstanceOf(IrVariable.class,
                assertInstanceOf(IrStringInterpolation.class, template.parts().getLast()).expression());
        assertSource(zero, variable.source());
        assertSource(zero, variable.name().source());
    }

    // 合并文本仅在每个参与 token 范围都已知时给出范围，不能用相邻已知位置猜测。
    @Test
    void keepsMergedTextUnknownWhenAnyTokenSourceIsUnknown() {
        for (ComplexLocation unknown : Arrays.asList(ComplexLocation.NO_LOCATION, null)) {
            for (boolean firstUnknown : List.of(false, true)) {
                IrStringTemplate template = assertInstanceOf(IrStringTemplate.class, convert(shell(command(
                        new NodeEncapsPart.Text(new NodeString("A", firstUnknown ? unknown : FIRST), PARTS),
                        new NodeEncapsPart.Text(new NodeString("B", firstUnknown ? SECOND : unknown), PARTS),
                        variable(new NodeEncapsVar.Var(token("value"), VARIABLE))))).command());
                assertSource(unknown, template.parts().getFirst().source());
                assertSource(COMMAND, template.source());
            }
        }
    }

    // 错误来源也遵循节点自身范围，零宽和未知范围不从命令外壳或输入包装回填。
    @Test
    void preservesUnknownAndZeroWidthFailureSources() {
        for (ComplexLocation location : Arrays.asList(null, ComplexLocation.NO_LOCATION, ComplexLocation.of(8, 9, 8, 9))) {
            assertFailure(shell(null, location), ".cmd", location);
            assertFailure(shell(command(new NodeEncapsPart.Text(new NodeString("\\u{}", location), PARTS))),
                    ".cmd.list.parts[0].text", location);
        }
    }

    // 公共模型仅要求命令和来源非空，不限制上层手工构造时命令表达式的具体类型。
    @Test
    void requiresNonNullModelFieldsWithoutNarrowingTheCommandType() {
        IrExpression command = new IrIntegerLiteral(1, SOURCE);
        assertThrows(NullPointerException.class, () -> new IrShellExec(null, SOURCE));
        assertThrows(NullPointerException.class, () -> new IrShellExec(command, null));
        IrShellExec shell = new IrShellExec(command, SOURCE);
        assertSame(command, shell.command());
        assertSame(SOURCE, shell.source());
    }

    // 转换结果独立于 AST 可变列表，模板列表和字节数组均不暴露可写内部存储。
    @Test
    void snapshotsMutableSyntaxPartsAndExposesImmutableCommandData() {
        var values = new ArrayList<>(List.of(text("before"),
            variable(new NodeEncapsVar.Var(token("value"), VARIABLE)), text("after")));
        IrShellExec shell = convert(shell(commandWithValues(values)));
        IrStringTemplate template = assertInstanceOf(IrStringTemplate.class, shell.command());
        values.clear();
        values.add(text("changed"));
        assertEquals(3, template.parts().size());
        assertPartText("before", template.parts().getFirst());
        assertPartText("after", template.parts().getLast());
        assertThrows(UnsupportedOperationException.class, () -> template.parts().clear());
        ByteString bytes = assertInstanceOf(IrStringText.class, template.parts().getFirst()).value();
        byte[] copy = bytes.toByteArray();
        copy[0] = 'X';
        assertPartText("before", template.parts().getFirst());
        var mutableParts = new ArrayList<>(template.parts());
        IrShellExec snapshot = new IrShellExec(new IrStringTemplate(mutableParts, SOURCE), SOURCE);
        mutableParts.clear();
        assertEquals(template.parts(), assertInstanceOf(IrStringTemplate.class, snapshot.command()).parts());
    }

    // 递归反引号和双引号共享解码器时，已发布的前缀快照及各自转义模式都不能被覆盖。
    @Test
    void isolatesDecoderBuffersAndModesDuringNestedConversion() {
        NodeExpr quoted = new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.InterpolatedString(parts(text("\\`\\\"")), COMMAND), SHELL), WRAPPER);
        String longPrefix = "inner".repeat(500);
        NodeExpr nested = shell(command(text(longPrefix + "\\`\\\""),
                variable(new NodeEncapsVar.IndirectVar(quoted, VARIABLE)), text("inner-tail\\`\\\"")));
        IrStringTemplate outer = assertInstanceOf(IrStringTemplate.class, convert(shell(command(text("outer\\`\\\""),
                variable(new NodeEncapsVar.IndirectVar(nested, VARIABLE)), text("outer-tail\\`\\\"")))).command());
        assertPartText("outer`\\\"", outer.parts().getFirst());
        assertPartText("outer-tail`\\\"", outer.parts().getLast());
        IrShellExec inner = assertInstanceOf(IrShellExec.class, computedExpression(outer.parts().get(1)));
        IrStringTemplate innerTemplate = assertInstanceOf(IrStringTemplate.class, inner.command());
        assertPartText(longPrefix + "`\\\"", innerTemplate.parts().getFirst());
        assertPartText("inner-tail`\\\"", innerTemplate.parts().getLast());
        assertText("\\`\"", computedExpression(innerTemplate.parts().get(1)));
        assertPartText("outer`\\\"", outer.parts().getFirst());
    }

    // 转换只读取 AST，不执行命令、不改写第一阶段声明或索引，结果不携带 CUP 节点。
    @Test
    void convertsWithoutMutatingSyntaxOrDeclarationIndexes() throws ReflectiveOperationException {
        var syntax = Main.parse("<?php function run($value) { return `prefix $value suffix`; } `standalone`;");
        var file = DeclarationExtractor.extract(syntax, "shell-contract.php");
        FunctionDefinition function = assertInstanceOf(FunctionDefinition.class,
                file.namespaceSections().getFirst().declarations().getFirst());
        String before = syntax.toTreeString(false);
        IrBlock body = SyntaxConverter.convertBody(function.body());
        IrShellExec shell = assertInstanceOf(IrShellExec.class,
                assertInstanceOf(IrReturn.class, body.statements().getFirst()).value());
        assertInstanceOf(IrStringTemplate.class, shell.command());
        IrFile converted = SyntaxConverter.convertFile(syntax, "shell-contract.php");
        assertEquals(before, syntax.toTreeString(false));
        assertSame(syntax, file.syntax());
        assertSame(function, file.declarationIndex().findTopLevel(TopLevelKind.FUNCTION, "run").getFirst());
        assertSame(function, file.namespaceSections().getFirst().declarations().getFirst());
        assertEquals(body, SyntaxConverter.convertBody(function.body()));
        assertEquals(converted, SyntaxConverter.convertFile(syntax, "shell-contract.php"));
        assertNoAst(body, Collections.newSetFromMap(new IdentityHashMap<>()));
        assertNoAst(converted, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    // 解码或递归失败后重新调用入口，路径、来源标识及字节缓冲均不泄漏到后续转换。
    @Test
    void keepsFailureStateLocalToEachConversion() {
        NodeExpr valid = shell(command(text("prefix\\`\\\""),
                variable(new NodeEncapsVar.Var(token("value"), VARIABLE)), text("suffix")));
        IrShellExec expected = convert(valid);
        assertFailure(shell(command(text("prefix"), variable(new NodeEncapsVar.IndirectVar(
                shell(command(text("\\u{}"))), VARIABLE)))),
                ".cmd.list.parts[1].var.e.ev.cmd.list.parts[0].text", LEAF);
        assertEquals(expected, convert(valid));
        IrShellExec other = assertInstanceOf(IrShellExec.class, SyntaxConverter.convertExpression(
                new SyntaxExpression(shell(command(text("new"))), new SourceInfo("other.php", null))));
        assertEquals("other.php", other.source().sourceId());
        assertEquals("other.php", other.command().source().sourceId());
        assertText("new", other.command());
        IrShellExec anonymous = assertInstanceOf(IrShellExec.class, SyntaxConverter.convertExpression(
                new SyntaxExpression(shell(command(text("anonymous"))), new SourceInfo(null, null))));
        assertNull(anonymous.source().sourceId());
        assertNull(anonymous.command().source().sourceId());
        assertEquals(expected, convert(valid));
    }

    private static NodeExpr shell(NodeBackticksExpr command) {
        return shell(command, SHELL);
    }

    private static NodeExpr shell(NodeBackticksExpr command, ComplexLocation location) {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.ShellExec(command, location), WRAPPER);
    }

    private static NodeBackticksExpr command(NodeEncapsPart... values) {
        return new NodeBackticksExpr.ShellExecList(parts(values), COMMAND);
    }

    private static NodeBackticksExpr commandWithValues(List<NodeEncapsPart> values) {
        return new NodeBackticksExpr.ShellExecList(new NodeEncapsList.Parts(
                new NodeListNodeEncapsPart(values, PARTS), LIST), COMMAND);
    }

    private static NodeEncapsList.Parts parts(NodeEncapsPart... values) {
        return new NodeEncapsList.Parts(new NodeListNodeEncapsPart(List.of(values), PARTS), LIST);
    }

    private static NodeEncapsPart text(String value) {
        return new NodeEncapsPart.Text(token(value), PARTS);
    }

    private static NodeEncapsPart variable(NodeEncapsVar value) {
        return new NodeEncapsPart.Variable(value, PARTS);
    }

    private static NodeString token(String value) {
        return new NodeString(value, LEAF);
    }

    private static NodeEncapsVarOffset offset() {
        return new NodeEncapsVarOffset.NumericOffset(token("1"), LEAF);
    }

    private static NodeExpr integer() {
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.Scalar(
                new NodeScalar.Int(token("1"), LEAF), LEAF), LEAF);
    }

    private static NodeExpr locatedShell(ComplexLocation location) {
        var values = List.of(new NodeEncapsPart.Text(new NodeString("text", location), location),
                new NodeEncapsPart.Variable(new NodeEncapsVar.Var(new NodeString("value", location), location), location));
        var command = new NodeBackticksExpr.ShellExecList(new NodeEncapsList.Parts(
                new NodeListNodeEncapsPart(values, location), location), location);
        return new NodeExpr.ExprWithoutVariable(new NodeExprWithoutVariable.ShellExec(command, location), location);
    }

    private static IrShellExec convert(NodeExpr expression) {
        return assertInstanceOf(IrShellExec.class, SyntaxConverter.convertExpression(new SyntaxExpression(expression, SOURCE)));
    }

    private static void assertVariableFailure(NodeEncapsVar variable, String suffix, ComplexLocation location) {
        assertFailure(shell(command(variable(variable))), ".cmd.list.parts[0].var" + suffix, location);
    }

    private static void assertFailure(NodeExpr expression, String suffix, ComplexLocation location) {
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class, () -> convert(expression));
        assertEquals("expression.ev" + suffix, error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertSource(location, error.source());
    }

    private static IrFixedName fixedVariable(IrStringPart part) {
        IrVariable variable = assertInstanceOf(IrVariable.class,
                assertInstanceOf(IrStringInterpolation.class, part).expression());
        return assertInstanceOf(IrFixedName.class, variable.name());
    }

    private static IrExpression computedExpression(IrStringPart part) {
        IrVariable variable = assertInstanceOf(IrVariable.class,
                assertInstanceOf(IrStringInterpolation.class, part).expression());
        return assertInstanceOf(IrComputedName.class, variable.name()).expression();
    }

    private static void assertText(String expected, IrExpression expression) {
        ByteString value = assertInstanceOf(IrStringLiteral.class, expression).value();
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), value.toByteArray());
    }

    private static void assertPartText(String expected, IrStringPart part) {
        ByteString value = assertInstanceOf(IrStringText.class, part).value();
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), value.toByteArray());
    }

    private static void assertSource(ComplexLocation location, SourceInfo source) {
        assertEquals("shell-contract.php", source.sourceId());
        if (location == null || location.isNoLocation()) assertNull(source.range());
        else assertEquals(new SourceRange(location.getStartLine(), location.getStartColumn(),
                location.getEndLine(), location.getEndColumn()), source.range());
    }

    private static void assertNoAst(Object value, Set<Object> visited) throws ReflectiveOperationException {
        if (value == null || !visited.add(value)) return;
        assertFalse(value instanceof AstNode, "命令 IR 不应保留 CUP AST");
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