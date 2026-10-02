package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 仅解析并转换反引号表达式的命令文本，测试不执行 PHP、shell 或任何命令。 */
@SuppressWarnings("UnnecessaryUnicodeEscape")
class ShellExecConversionTest {
    private static final String BACKTICK = Character.toString(96);

    // 空、纯文本和 UTF-8 命令均使用独立 shell 节点，正文为已解码的字符串字面量。
    @Test
    void convertsEmptyPlainAndUtf8CommandsWithoutExecutingThem() {
        for (String text : List.of("", "echo sentinel", "not a real command && ???", "中文🙂")) {
            IrShellExec result = command(text);
            assertBytes(text, result.command());
            assertEquals("shell.php", result.source().sourceId());
        }
        assertBytes("$\n{literal}", command("$\n{literal}").command());
    }

    // 物理换行全部保留；反引号不是 heredoc，正文结尾的换行也不能裁剪。
    @Test
    void preservesEveryPhysicalNewlineIncludingTrailingNewlines() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            String text = "first" + newline + "second" + newline + newline;
            assertBytes(text, command(text).command());
            assertBytes("\\" + newline + "tail", command("\\" + newline + "tail").command());
            IrStringTemplate template = template("first" + newline + "$value" + newline);
            assertEquals(3, template.parts().size());
            assertText(template, 0, "first" + newline);
            assertVariable(interpolation(template, 1), "value");
            assertText(template, 2, newline);
        }
    }

    // 反引号转义去掉反斜杠，转义双引号则保留；普通单双引号并非命令定界符。
    @Test
    void appliesBacktickSpecificQuoteEscapes() {
        assertBytes(BACKTICK, command("\\" + BACKTICK).command());
        assertBytes("\\\"", command("\\\"").command());
        assertBytes("\"'", command("\"'").command());
        assertBytes("\\'", command("\\'").command());
        assertBytes("left" + BACKTICK + "\\\"right",
                command("left\\" + BACKTICK + "\\\"right").command());
        IrStringTemplate template = template("left\\" + BACKTICK + "$name\\\"right");
        assertEquals(3, template.parts().size());
        assertText(template, 0, "left" + BACKTICK);
        assertVariable(interpolation(template, 1), "name");
        assertText(template, 2, "\\\"right");
    }

    // 共同控制字符转义解码为字节，转义美元符号不会成为插值。
    @Test
    void decodesControlEscapesAndKeepsEscapedDollarsLiteral() {
        assertBytes(new byte[]{10, 13, 9, 11, 27, 12, '\\', '$'},
                command("\\n\\r\\t\\v\\e\\f\\\\\\$").command());
        assertBytes("$name {$name} ${name}", command("\\$name {\\$name} \\${name}").command());
        assertBytes("\\q\\8\\9\\x\\uZ\\U{41}",
                command("\\q\\8\\9\\x\\uZ\\U{41}").command());
    }

    // 八进制、十六进制和 Unicode 转义复用 PHP 7.2 字节规则，不经平台编码或字符串重解释。
    @Test
    void decodesNumericAndUnicodeEscapesIntoExactBytes() {
        assertBytes(new byte[]{0, 7, 65, (byte) 255, 0, (byte) 255, 0, '8', 0, 65, (byte) 255, 65, '4'},
                command("\\0\\07\\101\\377\\400\\777\\08\\x0\\x41\\xFF\\x414").command());
        assertBytes(new byte[]{65, (byte) 255}, command("\\X41\\Xff").command());
        assertBytes("\0A\u007f\u0080\u07ff\u0800\uFFFF🙂",
                command("\\u{0}\\u{41}\\u{7f}\\u{80}\\u{7ff}\\u{800}\\u{ffff}\\u{1F642}").command());
        assertBytes(new byte[]{(byte) 0xf4, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf},
                command("\\u{10FFFF}").command());
    }

    // 七种插值 AST 形式全部沿既有字符串模型转换，不把变量值提前变成命令文本。
    @Test
    void convertsAllSevenInterpolationForms() {
        assertVariable(interpolation(template("$name"), 0), "name");
        IrIndex simpleIndex = assertInstanceOf(IrIndex.class, interpolation(template("$items[key]"), 0));
        assertVariable(simpleIndex.base(), "items");
        assertBytes("key", simpleIndex.index());
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class,
                interpolation(template("$object->field"), 0));
        assertVariable(property.receiver(), "object");
        assertEquals("field", assertInstanceOf(IrFixedName.class, property.property()).value());
        IrVariable indirect = assertInstanceOf(IrVariable.class, interpolation(template("${$selector}"), 0));
        assertVariable(assertInstanceOf(IrComputedName.class, indirect.name()).expression(), "selector");
        assertVariable(interpolation(template("${name}"), 0), "name");
        IrIndex namedIndex = assertInstanceOf(IrIndex.class, interpolation(template("${items[1 + 2]}"), 0));
        assertVariable(namedIndex.base(), "items");
        IrBinary sum = assertInstanceOf(IrBinary.class, namedIndex.index());
        assertEquals(BinaryOperator.ADD, sum.operator());
        assertInteger(sum.left(), 1);
        assertInteger(sum.right(), 2);
        IrIndex curly = assertInstanceOf(IrIndex.class,
                interpolation(template("{$object->{$field}[next()]}"), 0));
        IrPropertyAccess dynamic = assertInstanceOf(IrPropertyAccess.class, curly.base());
        assertVariable(dynamic.receiver(), "object");
        assertVariable(assertInstanceOf(IrComputedName.class, dynamic.property()).expression(), "field");
        assertCall(curly.index(), "next", 0);
    }

    // 简单插值下标保留特殊字符串键规则，变量键与规范且未越界的整数键分别建模。
    @Test
    void preservesSimpleInterpolationOffsetRules() {
        IrIndex variable = assertInstanceOf(IrIndex.class, interpolation(template("$items[$key]"), 0));
        assertVariable(variable.base(), "items");
        assertVariable(variable.index(), "key");
        Map.of("0", 0L, "42", 42L, "-42", -42L, "9223372036854775807", Long.MAX_VALUE,
                "-9223372036854775807", -Long.MAX_VALUE).forEach((spelling, value) -> {
            IrIndex index = assertInstanceOf(IrIndex.class, interpolation(template("$items[" + spelling + "]"), 0));
            assertInteger(index.index(), value);
        });
        for (String spelling : List.of("00", "042", "-0", "-01", "0x2a", "0b10",
                "9223372036854775808", "-9223372036854775808")) {
            IrIndex index = assertInstanceOf(IrIndex.class, interpolation(template("$items[" + spelling + "]"), 0));
            assertBytes(spelling, index.index());
        }
    }

    // 文本与插值保持先后顺序，连续插值和重复变量不被合并，方法调用副作用只保存一次。
    @Test
    void preservesOrderedTextAndSideEffectingInterpolations() {
        IrStringTemplate template = template("left\\n$name\\x21{$object->run()} right");
        assertEquals(5, template.parts().size());
        assertText(template, 0, "left\n");
        assertVariable(interpolation(template, 1), "name");
        assertText(template, 2, "!");
        IrMethodCall method = assertInstanceOf(IrMethodCall.class, interpolation(template, 3));
        assertVariable(method.receiver(), "object");
        assertEquals("run", assertInstanceOf(IrFixedName.class, method.method()).value());
        assertTrue(method.arguments().isEmpty());
        assertText(template, 4, " right");
        IrStringTemplate adjacent = template("$name$name");
        assertEquals(2, adjacent.parts().size());
        assertVariable(interpolation(adjacent, 0), "name");
        assertVariable(interpolation(adjacent, 1), "name");
    }

    // shell 正文中可嵌套双引号模板和另一 shell；递归转换不能覆盖外层已发布文本。
    @Test
    void keepsOuterCommandTextAroundNestedStringsAndCommands() {
        IrStringTemplate outer = template("before {$items[\"nested $index\"]} between {$items["
                + shell("inner $name") + "]} after");
        assertEquals(5, outer.parts().size());
        assertText(outer, 0, "before ");
        assertText(outer, 2, " between ");
        assertText(outer, 4, " after");
        IrIndex quotedIndex = assertInstanceOf(IrIndex.class, interpolation(outer, 1));
        IrStringTemplate quoted = assertInstanceOf(IrStringTemplate.class, quotedIndex.index());
        assertText(quoted, 0, "nested ");
        assertVariable(interpolation(quoted, 1), "index");
        IrIndex commandIndex = assertInstanceOf(IrIndex.class, interpolation(outer, 3));
        IrStringTemplate inner = assertInstanceOf(IrStringTemplate.class,
                assertInstanceOf(IrShellExec.class, commandIndex.index()).command());
        assertText(inner, 0, "inner ");
        assertVariable(interpolation(inner, 1), "name");
        command("unrelated text");
        assertText(outer, 0, "before ");
        assertText(inner, 0, "inner ");
    }

    // 双引号、heredoc 与间接变量插值也可包含 shell，外层模板不降级或提前求值。
    @Test
    void embedsCommandsInsideOtherStringFormsAndComputedNames() {
        String content = "before {$items[" + shell("inner $name") + "]} after";
        for (String code : List.of("\"" + content + "\"", "<<<END\n" + content + "\nEND\n")) {
            IrStringTemplate outer = assertInstanceOf(IrStringTemplate.class, expression(code));
            assertEquals(3, outer.parts().size());
            assertText(outer, 0, "before ");
            assertText(outer, 2, " after");
            IrIndex index = assertInstanceOf(IrIndex.class, interpolation(outer, 1));
            IrStringTemplate command = assertInstanceOf(IrStringTemplate.class,
                    assertInstanceOf(IrShellExec.class, index.index()).command());
            assertText(command, 0, "inner ");
            assertVariable(interpolation(command, 1), "name");
        }
        IrVariable indirect = assertInstanceOf(IrVariable.class,
                interpolation(template("${(" + shell("variable_name") + ")}"), 0));
        IrShellExec computed = assertInstanceOf(IrShellExec.class,
                assertInstanceOf(IrComputedName.class, indirect.name()).expression());
        assertBytes("variable_name", computed.command());
    }

    // 文件入口递归转换函数默认值、闭包、具名和匿名类成员，不对命令或常量合法性求值。
    @Test
    void convertsCommandsThroughoutFileDeclarationsAndNestedBodies() {
        String code = "namespace Demo; const TEXT = " + shell("constant") + ";"
                + "function outer($value = " + shell("default") + ") {"
                + "return function() { return " + shell("closure") + "; }; }"
                + "class Named { public $value = " + shell("property")
                + "; function run() { return " + shell("method") + "; } }"
                + "$object = new class(" + shell("argument") + ") { const TEXT = "
                + shell("anonymous") + "; };";
        IrFile file = SyntaxConverter.convertFile(Main.parse("<?php " + code), "shell.php");
        assertEquals(1, file.namespaceSections().size());
        List<IrStatement> statements = file.namespaceSections().getFirst().body().statements();
        assertEquals(4, statements.size());
        assertCommand(assertInstanceOf(IrConstantDeclaration.class, statements.getFirst()).value(), "constant");
        IrFunctionDeclaration function = assertInstanceOf(IrFunctionDeclaration.class, statements.get(1));
        assertCommand(function.parameters().getFirst().defaultValue(), "default");
        IrClosure closure = assertInstanceOf(IrClosure.class,
                assertInstanceOf(IrReturn.class, function.body().statements().getFirst()).value());
        assertCommand(assertInstanceOf(IrReturn.class, closure.body().statements().getFirst()).value(), "closure");
        IrClassDeclaration named = assertInstanceOf(IrClassDeclaration.class, statements.get(2));
        assertCommand(assertInstanceOf(IrProperty.class, named.members().getFirst()).initialValue(), "property");
        IrMethod method = assertInstanceOf(IrMethod.class, named.members().get(1));
        assertNotNull(method.body());
        assertCommand(assertInstanceOf(IrReturn.class, method.body().statements().getFirst()).value(), "method");
        IrNewAnonymous anonymous = assertInstanceOf(IrNewAnonymous.class,
                assertInstanceOf(IrAssignment.class,
                        assertInstanceOf(IrExpressionStatement.class, statements.get(3)).expression()).value());
        assertCommand(anonymous.arguments().getFirst().expression(), "argument");
        assertCommand(assertInstanceOf(IrClassConstant.class, anonymous.definition().members().getFirst()).value(),
                "anonymous");
    }

    // 局部主体入口与内置表达式按原结构组合，错误抑制不丢失，退出节点不执行命令。
    @Test
    void convertsCommandsInsideSelectedBodiesAndBuiltinExpressions() {
        IrBlock body = body("$value = @" + shell("suppressed") + "; print " + shell("printed")
                + "; exit(" + shell("status") + ");");
        assertEquals(3, body.statements().size());
        IrAssignment assignment = assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, body.statements().getFirst()).expression());
        assertCommand(assertInstanceOf(IrErrorSuppress.class, assignment.value()).expression(), "suppressed");
        IrPrint print = assertInstanceOf(IrPrint.class,
                assertInstanceOf(IrExpressionStatement.class, body.statements().get(1)).expression());
        assertCommand(print.expression(), "printed");
        IrExit exit = assertInstanceOf(IrExit.class,
                assertInstanceOf(IrExpressionStatement.class, body.statements().get(2)).expression());
        assertCommand(exit.expression(), "status");
        assertCommand(assertInstanceOf(IrEmptyCheck.class, expression("empty(" + shell("checked") + ")"))
                .expression(), "checked");
        assertCommand(assertInstanceOf(IrInclude.class, expression("include " + shell("path")))
                .expression(), "path");
    }

    // 动态调用目标、动态方法名及实参可以包含 shell，但每个求值位置仅有一份表达式。
    @Test
    void retainsCommandsInsideDynamicCallsAndMembers() {
        IrCall call = assertInstanceOf(IrCall.class,
                expression("$callbacks[" + shell("index") + "](" + shell("argument") + ")"));
        IrIndex callable = assertInstanceOf(IrIndex.class,
                assertInstanceOf(IrExpressionCallTarget.class, call.target()).expression());
        assertVariable(callable.base(), "callbacks");
        assertCommand(callable.index(), "index");
        assertEquals(1, call.arguments().size());
        assertCommand(call.arguments().getFirst().expression(), "argument");
        IrMethodCall method = assertInstanceOf(IrMethodCall.class,
                expression("$object->{" + shell("method") + "}(" + shell("argument") + ")"));
        assertVariable(method.receiver(), "object");
        assertCommand(assertInstanceOf(IrComputedName.class, method.method()).expression(), "method");
        assertEquals(1, method.arguments().size());
        assertCommand(method.arguments().getFirst().expression(), "argument");
    }

    // shell_exec 只是普通函数名，反引号表达式不能统一降成函数调用或与相邻命令合并。
    @Test
    void distinguishesShellExpressionsFromOrdinaryShellExecCalls() {
        IrCall call = assertCall(expression("shell_exec('literal')"), "shell_exec", 1);
        assertBytes("literal", call.arguments().getFirst().expression());
        IrBinary concat = assertInstanceOf(IrBinary.class, expression(shell("left") + " . " + shell("right")));
        assertEquals(BinaryOperator.CONCAT, concat.operator());
        assertCommand(concat.left(), "left");
        assertCommand(concat.right(), "right");
        IrCall wrapper = assertCall(expression("shell_exec(" + shell("inner") + ")"), "shell_exec", 1);
        assertCommand(wrapper.arguments().getFirst().expression(), "inner");
    }

    // 读取空下标仍失败，支持 shell 外壳不允许插值中的读取路径悄悄变成追加目标。
    @Test
    void rejectsAppendReadsInsideCommandInterpolations() {
        for (String content : List.of("{$items[]}", "${$items[]}", "${items[$keys[]]}",
                "{$object->run($items[])}", "{$items[" + shell("{$keys[]}") + "]}")) {
            assertRejected(shell(content));
        }
        for (String code : List.of("empty(" + shell("{$items[]}") + ")",
                "$callbacks[" + shell("{$items[]}") + "]()")) {
            assertRejected(code);
        }
    }

    // 不合法的 Unicode 转义仍以转换异常报告，不替换为问号或执行部分命令。
    @Test
    void rejectsMalformedUnicodeEscapesInsideCommands() {
        for (String content : List.of("\\u{}", "\\u{xyz}", "\\u{110000}", "\\u{1")) {
            assertRejected(shell(content));
        }
    }

    // 同次转换中的长、短和空命令分别生成精确快照，不能泄漏共享缓冲区旧内容。
    @Test
    void keepsCommandSnapshotsIndependentAcrossSharedBufferReuse() {
        String large = "command".repeat(4096);
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class,
                expression("[" + shell(large) + ", " + shell("x") + ", " + shell("") + ", 'tail']"));
        assertEquals(4, array.entries().size());
        IrShellExec first = assertInstanceOf(IrShellExec.class,
                assertInstanceOf(IrValueArrayEntry.class, array.entries().getFirst()).value());
        assertBytes(large, first.command());
        assertCommand(assertInstanceOf(IrValueArrayEntry.class, array.entries().get(1)).value(), "x");
        assertCommand(assertInstanceOf(IrValueArrayEntry.class, array.entries().get(2)).value(), "");
        assertBytes("tail", assertInstanceOf(IrValueArrayEntry.class, array.entries().get(3)).value());
        byte[] exported = assertInstanceOf(IrStringLiteral.class, first.command()).value().toByteArray();
        exported[0] = 0;
        assertBytes(large, first.command());
    }

    // 重复和并行转换复用只读 AST 不产生共享可变状态，也不修改原有插值树。
    @Test
    void keepsRepeatedAndConcurrentConversionsIndependentWithoutChangingAst() {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php "
                + shell("outer {$items[" + shell("inner $name") + "]} tail") + ";");
        String before = parsed.toTreeString(false);
        SyntaxExpression syntax = new SyntaxExpression(parsed.getStmts().getValue().getFirst().getStmt().getExpression(),
                new SourceInfo("shell.php", null));
        IrShellExec expected = assertInstanceOf(IrShellExec.class, SyntaxConverter.convertExpression(syntax));
        assertEquals(expected, SyntaxConverter.convertExpression(syntax));
        List<IrExpression> results = IntStream.range(0, 24).parallel()
                .mapToObj(ignored -> SyntaxConverter.convertExpression(syntax)).toList();
        for (IrExpression result : results) assertEquals(expected, result);
        assertEquals(before, parsed.toTreeString(false));
        IrStringTemplate template = assertInstanceOf(IrStringTemplate.class, expected.command());
        assertText(template, 0, "outer ");
        assertText(template, 2, " tail");
    }

    private static String shell(String content) {
        return BACKTICK + content + BACKTICK;
    }

    private static IrShellExec command(String content) {
        return assertInstanceOf(IrShellExec.class, expression(shell(content)));
    }

    private static IrStringTemplate template(String content) {
        return assertInstanceOf(IrStringTemplate.class, command(content).command());
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        return new SyntaxExpression(parsed.getStmts().getValue().getFirst().getStmt().getExpression(),
                new SourceInfo("shell.php", null));
    }

    private static IrBlock body(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php function f() { " + code + " }");
        var statements = parsed.getStmts().getValue().getFirst().getFunction().getStmts().getValue();
        return SyntaxConverter.convertBody(new SyntaxBody(List.copyOf(statements), new SourceInfo("shell.php", null)));
    }

    private static IrExpression interpolation(IrStringTemplate template, int index) {
        return assertInstanceOf(IrStringInterpolation.class, template.parts().get(index)).expression();
    }

    private static void assertText(IrStringTemplate template, int index, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringText.class, template.parts().get(index)).value().toByteArray());
    }

    private static void assertCommand(IrExpression expression, String value) {
        assertBytes(value, assertInstanceOf(IrShellExec.class, expression).command());
    }

    private static void assertBytes(String value, IrExpression expression) {
        assertBytes(value.getBytes(StandardCharsets.UTF_8), expression);
    }

    private static void assertBytes(byte[] value, IrExpression expression) {
        assertArrayEquals(value, assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
    }

    private static void assertInteger(IrExpression expression, long value) {
        assertEquals(value, assertInstanceOf(IrIntegerLiteral.class, expression).value());
    }

    private static void assertVariable(IrExpression expression, String name) {
        assertEquals(name, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariable.class, expression).name()).value());
    }

    private static IrCall assertCall(IrExpression expression, String name, int argumentCount) {
        IrCall call = assertInstanceOf(IrCall.class, expression);
        assertEquals(name, assertInstanceOf(IrNamedCallTarget.class, call.target()).name().value());
        assertEquals(argumentCount, call.arguments().size());
        return call;
    }

    private static void assertRejected(String code) {
        SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
        var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertExpression(syntax), code);
        assertEquals("shell.php", error.source().sourceId());
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }
}