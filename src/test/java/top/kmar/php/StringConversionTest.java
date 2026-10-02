package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.extract.DeclarationExtractor;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.FunctionDefinition;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SyntaxExpression;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实 PHP AST 验证字符串字节解码、插值结构和不求值的魔术常量。 */
@SuppressWarnings("UnnecessaryUnicodeEscape")
class StringConversionTest {

    // 单引号只解码反斜杠和单引号，其余转义原样保留，不尝试插值。
    @Test
    void decodesOnlySingleQuoteEscapesInSingleQuotedLiterals() {
        assertString("a'b\\c", "'a\\'b\\\\c'");
        assertString("\\n\\r\\t\\x41\\101\\u{41}\\$\\\"", "'\\n\\r\\t\\x41\\101\\u{41}\\$\\\"'");
        assertString("$name ${name} {$name}", "'$name ${name} {$name}'");
        assertString("", "''");
    }

    // 双引号的控制字符转义直接生成对应字节，转义的美元符号不产生插值。
    @Test
    void decodesDoubleQuotedControlEscapesWithoutInterpolation() {
        assertBytes(new byte[]{10, 13, 9, 11, 27, 12, '\\', '$', '"'},
                expression("\"\\n\\r\\t\\v\\e\\f\\\\\\$\\\"\""));
        assertString("$name {$name} ${name}", "\"\\$name {\\$name} \\${name}\"");
        assertString("", "\"\"");
    }

    // 八进制最多消费三位，十六进制最多两位；超出一个字节时截取低八位。
    @Test
    void decodesOctalAndHexadecimalEscapesIntoRawBytes() {
        assertBytes(new byte[]{0, 7, 65, (byte) 255, 0, (byte) 255, 0, '8', 0, 65, (byte) 255, 65, '4'},
                expression("\"\\0\\07\\101\\377\\400\\777\\08\\x0\\x41\\xFF\\x414\""));
        assertBytes(new byte[]{(byte) 128, (byte) 255, 0},
                expression("\"\\x80\\377\\0\""));
        assertBytes(new byte[]{65, (byte) 255}, expression("\"\\X41\\Xff\""));
    }

    // Unicode 转义转为 UTF-8；普通源码字符也采用 UTF-8，不能使用平台默认编码。
    @Test
    void encodesUnicodeEscapesAndSourceCharactersAsUtf8() {
        assertString("\0A\u007f\u0080\u07ff\u0800\uFFFF🙂",
                "\"\\u{0}\\u{41}\\u{7f}\\u{80}\\u{7ff}\\u{800}\\u{ffff}\\u{1F642}\"");
        for (String code : List.of("'中文🙂'", "\"中文🙂\"", "b'中文🙂'", "B\"中文🙂\"")) {
            assertString("中文🙂", code);
        }
        assertBytes(new byte[]{(byte) 0xf4, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf},
                expression("\"\\u{10FFFF}\""));
    }

    // 未知转义保留反斜杠；大写 U 不是 PHP 的 Unicode 转义。
    @Test
    void preservesUnknownDoubleQuotedEscapes() {
        assertString("\\q\\8\\9\\x\\uZ\\U{41}\\'",
                "\"\\q\\8\\9\\x\\uZ\\U{41}\\'\"");
        assertString("\\u{xyz}", "'\\u{xyz}'");
    }

    // b/B 前缀不改变字符串值或插值行为。
    @Test
    void normalizesBinaryPrefixesWithoutChangingBytes() {
        for (String prefix : List.of("", "b", "B")) {
            assertString("value", prefix + "'value'");
            assertString("value\n", prefix + "\"value\\n\"");
            IrStringTemplate template = template(prefix + "\"hello $name\"");
            assertEquals(2, template.parts().size());
            assertText(template, 0, "hello ");
            assertVariable(interpolation(template, 1), "name");
        }
    }

    // 词法必须在各自结束引号处停止，拼接仍为运算节点而不是一个大字符串。
    @Test
    void keepsAdjacentQuotedExpressionsSeparate() {
        IrBinary concat = assertInstanceOf(IrBinary.class, expression("\"a\" . \"b\""));
        assertEquals(BinaryOperator.CONCAT, concat.operator());
        assertBytes("a", concat.left());
        assertBytes("b", concat.right());
        IrCall call = assertInstanceOf(IrCall.class, expression("f(\"a\", \"b\", \"c\")"));
        assertEquals(3, call.arguments().size());
        for (int i = 0; i < 3; i++) {
            assertBytes(Character.toString('a' + i), call.arguments().get(i).expression());
        }
    }

    // heredoc 解码与双引号类似，但引号无需转义，反斜杠加双引号必须保留。
    @Test
    void decodesPlainHeredocTextAndPreservesEscapedQuotes() {
        for (String opening : List.of("<<<END", "<<<\"END\"", "b<<<END", "B<<<\"END\"")) {
            assertString("first\nsecond\t\\\"last", opening + "\nfirst\\nsecond\\t\\\"last\nEND\n");
            assertBytes(new byte[]{(byte) 255, 0, 'A'},
                    expression(opening + "\n\\xFF\\0\\u{41}\nEND\n"));
        }
    }

    // nowdoc 中转义、美元符号和引号均为原始文本，不生成模板或读取变量。
    @Test
    void preservesNowdocContentsWithoutDecodingOrInterpolating() {
        String value = "$name ${name} {$name}\n\\n\\xFF\\u{xyz}\\\"'中文";
        for (String opening : List.of("<<<'END'", "b<<<'END'", "B<<<'END'")) {
            assertString(value, opening + "\n" + value + "\nEND\n");
        }
    }

    // 每种换行形式只移除结束标签前的一个换行，正文内部和额外空行全部保留。
    @Test
    void removesExactlyOneTerminatingHeredocNewline() {
        for (String newline : List.of("\n", "\r\n", "\r")) {
            for (String opener : List.of("<<<END", "<<<'END'")) {
                assertString("", opener + newline + "END" + newline);
                assertString("", opener + newline + newline + "END" + newline);
                assertString("a" + newline, opener + newline + "a" + newline + newline + "END" + newline);
                assertString("a" + newline + "b", opener + newline + "a" + newline + "b" + newline + "END" + newline);
            }
        }
        assertString("\n", "<<<END\n\\n\nEND\n");
    }

    // 分散的纯文本 token 合并为一个字面量，美元符号或花括号本身不等于插值。
    @Test
    void combinesFragmentedTextWithoutInventingInterpolation() {
        assertString("a$\n{b}\nc", "\"a$\n{b}\nc\"");
        assertString("a$\n{b}\nc", "<<<END\na$\n{b}\nc\nEND\n");
        assertString("before END after\nEND_suffix", "<<<END\nbefore END after\nEND_suffix\nEND\n");
    }

    // 模板保留文本与插值的先后顺序，文本只解码一次，不预先字符串化变量。
    @Test
    void preservesOrderedDecodedTextAndVariableInterpolations() {
        IrStringTemplate template = template("\"left\\n$name\\x21$other right\"");
        assertEquals(5, template.parts().size());
        assertText(template, 0, "left\n");
        assertVariable(interpolation(template, 1), "name");
        assertText(template, 2, "!");
        assertVariable(interpolation(template, 3), "other");
        assertText(template, 4, " right");
        IrStringTemplate adjacent = template("\"$name$name\"");
        assertEquals(2, adjacent.parts().size());
        assertVariable(interpolation(adjacent, 0), "name");
        assertVariable(interpolation(adjacent, 1), "name");
    }

    // heredoc 模板移除终止换行后省略空文本，但保留之前文本中的真实换行。
    @Test
    void normalizesHeredocTemplatesWithoutDroppingBodyText() {
        IrStringTemplate template = template("<<<END\nfirst\n$name\nlast\nEND\n");
        assertEquals(3, template.parts().size());
        assertText(template, 0, "first\n");
        assertVariable(interpolation(template, 1), "name");
        assertText(template, 2, "\nlast");
        IrStringTemplate only = template("<<<END\n$name\nEND\n");
        assertEquals(1, only.parts().size());
        assertVariable(interpolation(only, 0), "name");
    }

    // 普通插值中的属性、标识符键和变量键转换为已有的访问表达式。
    @Test
    void convertsSimplePropertyAndIndexInterpolations() {
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class,
                interpolation(template("\"$object->field\""), 0));
        assertVariable(property.receiver(), "object");
        assertEquals("field", assertInstanceOf(IrFixedName.class, property.property()).value());
        IrIndex stringIndex = assertInstanceOf(IrIndex.class,
                interpolation(template("\"$items[key]\""), 0));
        assertVariable(stringIndex.base(), "items");
        assertBytes("key", stringIndex.index());
        IrIndex variableIndex = assertInstanceOf(IrIndex.class,
                interpolation(template("\"$items[$key]\""), 0));
        assertVariable(variableIndex.index(), "key");
    }

    // 简单插值中的数字键只在规范十进制且未越界时转 long，其他形式保留字符串键。
    @Test
    void classifiesNumericInterpolationOffsetsWithoutChangingTheirSpelling() {
        Map.of("0", 0L, "42", 42L, "-42", -42L, "9223372036854775807", Long.MAX_VALUE,
                "-9223372036854775807", -Long.MAX_VALUE).forEach((spelling, value) -> {
            IrIndex index = assertInstanceOf(IrIndex.class,
                    interpolation(template("\"$items[" + spelling + "]\""), 0));
            assertEquals(value.longValue(), assertInstanceOf(IrIntegerLiteral.class, index.index()).value());
        });
        for (String spelling : List.of("00", "042", "-0", "-01", "0x2a", "0b10",
                "9223372036854775808", "-9223372036854775808")) {
            IrIndex index = assertInstanceOf(IrIndex.class,
                    interpolation(template("\"$items[" + spelling + "]\""), 0));
            assertBytes(spelling, index.index());
        }
    }

    // ${name} 直接读取 name；${表达式} 则计算变量名；命名形式的下标仍为普通表达式。
    @Test
    void distinguishesNamedAndComputedIndirectInterpolations() {
        assertVariable(interpolation(template("\"${name}\""), 0), "name");
        IrVariable computed = assertInstanceOf(IrVariable.class,
                interpolation(template("\"${$selector}\""), 0));
        assertVariable(assertInstanceOf(IrComputedName.class, computed.name()).expression(), "selector");
        IrIndex index = assertInstanceOf(IrIndex.class,
                interpolation(template("\"${items[1 + 2]}\""), 0));
        assertVariable(index.base(), "items");
        assertEquals(BinaryOperator.ADD, assertInstanceOf(IrBinary.class, index.index()).operator());
    }

    // 花括号插值递归使用既有表达式转换；调用、动态属性及索引副作用不重复展开。
    @Test
    void preservesComplexInterpolationExpressionsAndSideEffects() {
        IrIndex index = assertInstanceOf(IrIndex.class,
                interpolation(template("\"{$object->{$field}[next()]}\""), 0));
        IrPropertyAccess property = assertInstanceOf(IrPropertyAccess.class, index.base());
        assertVariable(property.receiver(), "object");
        assertVariable(assertInstanceOf(IrComputedName.class, property.property()).expression(), "field");
        IrCall call = assertInstanceOf(IrCall.class, index.index());
        assertEquals("next", assertInstanceOf(IrNamedCallTarget.class, call.target()).name().value());
        assertTrue(call.arguments().isEmpty());
        IrMethodCall method = assertInstanceOf(IrMethodCall.class,
                interpolation(template("\"{$object->run()}\""), 0));
        assertEquals("run", assertInstanceOf(IrFixedName.class, method.method()).value());
        assertTrue(method.arguments().isEmpty());
    }

    // 字符串可出现在默认值、嵌套闭包及后续语句中，共用转换缓冲不能覆盖先前值。
    @Test
    void keepsStringSnapshotsIndependentAcrossNestedConversions() {
        var file = DeclarationExtractor.extract(Main.parse("""
                <?php
                function outer($value = 'default') {
                    $first = "before";
                    $closure = function($arg = "nested") { return "inside $arg"; };
                    return "after";
                }
                """), "string-values.php");
        FunctionDefinition function = (FunctionDefinition) file.namespaceSections().getFirst().declarations().getFirst();
        IrExpression defaultValue = SyntaxConverter.convertExpression(function.signature().parameters().getFirst().defaultValue());
        IrBlock body = SyntaxConverter.convertBody(function.body());
        IrAssignment first = assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, body.statements().getFirst()).expression());
        IrAssignment assignedClosure = assertInstanceOf(IrAssignment.class,
                assertInstanceOf(IrExpressionStatement.class, body.statements().get(1)).expression());
        IrClosure closure = assertInstanceOf(IrClosure.class, assignedClosure.value());
        assertBytes("default", defaultValue);
        assertBytes("before", first.value());
        assertBytes("nested", closure.parameters().getFirst().defaultValue());
        IrStringTemplate template = assertInstanceOf(IrStringTemplate.class,
                assertInstanceOf(IrReturn.class, closure.body().statements().getFirst()).value());
        assertText(template, 0, "inside ");
        assertVariable(interpolation(template, 1), "arg");
        assertBytes("after", assertInstanceOf(IrReturn.class, body.statements().get(2)).value());
        expression("\"unrelated\"");
        assertBytes("before", first.value());
        assertText(template, 0, "inside ");
    }

    // 插值内部还可包含另一模板，递归解码不会覆盖外层已保存的文本片段。
    @Test
    void preservesOuterTextAroundNestedStringTemplates() {
        IrStringTemplate outer = template("\"before {$items[\"nested $index\"]} after\"");
        assertEquals(3, outer.parts().size());
        assertText(outer, 0, "before ");
        assertText(outer, 2, " after");
        IrIndex index = assertInstanceOf(IrIndex.class, interpolation(outer, 1));
        assertVariable(index.base(), "items");
        IrStringTemplate inner = assertInstanceOf(IrStringTemplate.class, index.index());
        assertEquals(2, inner.parts().size());
        assertText(inner, 0, "nested ");
        assertVariable(interpolation(inner, 1), "index");
    }

    // 大字符串后的短值与空值使用各自的精确快照，不能泄漏缓冲区的旧后缀。
    @Test
    void keepsLargeSmallAndEmptyLiteralSnapshotsIndependent() {
        String large = "value".repeat(8192);
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class,
                expression("['" + large + "', 'x', '', 'tail']"));
        List<IrValueArrayEntry> entries = array.entries().stream()
                .map(entry -> assertInstanceOf(IrValueArrayEntry.class, entry)).toList();
        assertBytes(large, entries.getFirst().value());
        assertBytes("x", entries.get(1).value());
        assertBytes("", entries.get(2).value());
        assertBytes("tail", entries.get(3).value());
        ByteString bytes = assertInstanceOf(IrStringLiteral.class, entries.getFirst().value()).value();
        byte[] exported = bytes.toByteArray();
        exported[0] = 0;
        assertBytes(large, entries.getFirst().value());
        assertBytes("x", entries.get(1).value());
    }

    // 并行的公开转换调用拥有独立缓冲区，产物按各自源码保留字节，不共享全局可写状态。
    @Test
    void isolatesStringDecodingAcrossConcurrentConversions() {
        List<ByteString> results = IntStream.range(0, 32).parallel()
                .mapToObj(index -> assertInstanceOf(IrStringLiteral.class,
                        expression("\"item" + index + "\\0tail\"")).value())
                .toList();
        for (int i = 0; i < results.size(); i++) {
            assertArrayEquals(("item" + i + "\0tail").getBytes(StandardCharsets.UTF_8),
                    results.get(i).toByteArray());
        }
    }

    // 八种魔术常量都保留种类和来源；即使可从上下文猜到值，也不在这里求值。
    @Test
    void preservesEveryMagicConstantWithoutBindingItsValue() {
        for (MagicConstantKind kind : MagicConstantKind.values()) {
            String token = "__" + kind.name() + "__";
            for (String spelling : List.of(token, token.toLowerCase(Locale.ROOT))) {
                IrMagicConstant constant = assertInstanceOf(IrMagicConstant.class, expression(spelling));
                assertEquals(kind, constant.kind());
                assertEquals("strings.php", constant.source().sourceId());
                assertNotNull(constant.source().range());
            }
        }
        IrBinary concat = assertInstanceOf(IrBinary.class, expression("__DIR__ . '/file.php'"));
        assertEquals(MagicConstantKind.DIR, assertInstanceOf(IrMagicConstant.class, concat.left()).kind());
        assertBytes("/file.php", concat.right());
    }

    // 魔术常量可嵌入原有容器、闭包和生成器，不解析文件名、命名空间或函数名称。
    @Test
    void convertsMagicConstantsInsideExistingExpressionContainers() {
        IrArrayLiteral array = assertInstanceOf(IrArrayLiteral.class, expression("[__LINE__, __FILE__ => __CLASS__]"));
        assertEquals(MagicConstantKind.LINE,
                assertInstanceOf(IrMagicConstant.class,
                        assertInstanceOf(IrValueArrayEntry.class, array.entries().getFirst()).value()).kind());
        assertEquals(MagicConstantKind.FILE,
                assertInstanceOf(IrMagicConstant.class, array.entries().get(1).key()).kind());
        assertEquals(MagicConstantKind.CLASS,
                assertInstanceOf(IrMagicConstant.class,
                        assertInstanceOf(IrValueArrayEntry.class, array.entries().get(1)).value()).kind());
        IrClosure closure = assertInstanceOf(IrClosure.class,
                expression("function($value = __NAMESPACE__) { yield __METHOD__ => __FUNCTION__; }"));
        assertEquals(MagicConstantKind.NAMESPACE,
                assertInstanceOf(IrMagicConstant.class, closure.parameters().getFirst().defaultValue()).kind());
        IrYield yielded = assertInstanceOf(IrYield.class,
                assertInstanceOf(IrExpressionStatement.class, closure.body().statements().getFirst()).expression());
        assertEquals(MagicConstantKind.METHOD, assertInstanceOf(IrMagicConstant.class, yielded.key()).kind());
        assertEquals(MagicConstantKind.FUNCTION, assertInstanceOf(IrMagicConstant.class, yielded.value()).kind());
    }

    // 已解析但不合法的 Unicode 转义以包含来源的转换异常报告，而不是替换为问号。
    @Test
    void rejectsMalformedUnicodeEscapesWithContext() {
        for (String code : List.of("\"\\u{}\"", "\"\\u{xyz}\"", "\"\\u{110000}\"", "\"\\u{1\"",
                "<<<END\n\\u{110000}\nEND\n")) {
            assertRejected(code);
        }
    }

    // 命令本身及其在动态插值名称、下标中的位置都保留为 IrShellExec，不执行或提前求值。
    @Test
    void preservesShellExpressionsInsideStringInterpolations() {
        IrShellExec direct = assertInstanceOf(IrShellExec.class, expression("`echo hello`"));
        assertBytes("echo hello", direct.command());
        IrVariable indirect = assertInstanceOf(IrVariable.class,
                interpolation(template("\"${(`echo sentinel`)}\""), 0));
        IrComputedName name = assertInstanceOf(IrComputedName.class, indirect.name());
        assertBytes("echo sentinel", assertInstanceOf(IrShellExec.class, name.expression()).command());
        IrIndex indexed = assertInstanceOf(IrIndex.class,
                interpolation(template("\"{$items[(`echo sentinel`)]}\""), 0));
        assertVariable(indexed.base(), "items");
        assertBytes("echo sentinel", assertInstanceOf(IrShellExec.class, indexed.index()).command());
    }

    // 插值中的名称与下标仍为读取上下文，不能因字符串外壳而接受空下标读取。
    @Test
    void rejectsInvalidReadContextsInsideInterpolations() {
        for (String code : List.of("\"{$items[]}\"", "\"${($invalid[])}\"", "\"{$items[($invalid[])]}\"")) {
            assertRejected(code);
        }
    }

    private static IrExpression expression(String code) {
        return SyntaxConverter.convertExpression(syntaxExpression(code));
    }

    private static SyntaxExpression syntaxExpression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        return new SyntaxExpression(parsed.getStmts().getValue().getFirst().getStmt().getExpression(),
                new SourceInfo("strings.php", null));
    }

    private static IrStringTemplate template(String code) {
        return assertInstanceOf(IrStringTemplate.class, expression(code));
    }

    private static IrExpression interpolation(IrStringTemplate template, int index) {
        return assertInstanceOf(IrStringInterpolation.class, template.parts().get(index)).expression();
    }

    private static void assertText(IrStringTemplate template, int index, String value) {
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8),
                assertInstanceOf(IrStringText.class, template.parts().get(index)).value().toByteArray());
    }

    private static void assertString(String expected, String code) {
        assertBytes(expected, expression(code));
    }

    private static void assertBytes(String expected, IrExpression expression) {
        assertBytes(expected.getBytes(StandardCharsets.UTF_8), expression);
    }

    private static void assertBytes(byte[] expected, IrExpression expression) {
        assertArrayEquals(expected, assertInstanceOf(IrStringLiteral.class, expression).value().toByteArray());
    }

    private static void assertVariable(IrExpression expression, String expected) {
        assertEquals(expected, assertInstanceOf(IrFixedName.class,
                assertInstanceOf(IrVariable.class, expression).name()).value());
    }

    private static void assertRejected(String code) {
        SyntaxExpression syntax = assertDoesNotThrow(() -> syntaxExpression(code), code);
        SyntaxConversionException error = assertThrows(SyntaxConversionException.class,
                () -> SyntaxConverter.convertExpression(syntax), code);
        assertEquals("strings.php", error.source().sourceId());
        assertFalse(error.fieldPath().isBlank(), code);
        assertFalse(error.reason().isBlank(), code);
    }
}