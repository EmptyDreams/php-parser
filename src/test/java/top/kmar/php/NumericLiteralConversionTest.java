package top.kmar.php;

import java_cup.runtime.symbol.complex.ComplexLocation;
import org.junit.jupiter.api.Test;
import top.kmar.php.extract.SyntaxConversionException;
import top.kmar.php.extract.SyntaxConverter;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SourceRange;
import top.kmar.php.model.SyntaxExpression;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证数字字面量在 IR 边界统一解码，不把表达式求值或名称解析混入转换。 */
@SuppressWarnings("UnnecessaryUnicodeEscape")
class NumericLiteralConversionTest {

    private static final ComplexLocation ERROR_LOCATION = ComplexLocation.of(3, 5, 3, 12);

    // 不同进制及大小写前缀统一为 long，不再要求调用方解释原始写法。
    @Test
    void decodesIntegerRadicesAndPrefixes() {
        for (String code : List.of("42", "052", "0x2a", "0X2A", "0b101010", "0B101010")) {
            assertInteger(42, expression(code));
        }
        for (String code : List.of("0", "00", "0x0", "0X00", "0b0", "0B00")) {
            assertInteger(0, expression(code));
        }
        assertInteger(83, expression("0123"));
        assertInteger(255, expression("0xFf"));
    }

    // 每种进制的最大整数及其相邻值必须精确保存，不能先转 double 再转 long。
    @Test
    void preservesExactIntegerBoundaries() {
        for (String code : List.of("9223372036854775807", "0x7FFFFFFFFFFFFFFF",
                "0" + "7".repeat(21), "0b" + "1".repeat(63))) {
            assertInteger(Long.MAX_VALUE, expression(code));
        }
        for (String code : List.of("9223372036854775806", "0X7ffffffffffffffe",
                "0" + "7".repeat(20) + "6", "0B" + "1".repeat(62) + "0")) {
            assertInteger(Long.MAX_VALUE - 1, expression(code));
        }
        assertInteger(9_007_199_254_740_993L, expression("9007199254740993"));
    }

    // 前导零不占用数值范围，但普通整数的前导零仍代表八进制。
    @Test
    void ignoresLeadingZerosWithoutChangingRadix() {
        String zeros = "0".repeat(4_096);
        assertInteger(7, expression(zeros + "7"));
        assertInteger(42, expression("0x" + zeros + "2A"));
        assertInteger(42, expression("0B" + zeros + "101010"));
        assertInteger(Long.MAX_VALUE, expression("0x" + zeros + "7fffffffffffffff"));
        assertInteger(Long.MAX_VALUE, expression(zeros + "7".repeat(21)));
        assertInteger(Long.MAX_VALUE, expression("0b" + zeros + "1".repeat(63)));
    }

    // 带小数点或指数的写法按十进制解码，不能因前导零而误按八进制解析。
    @Test
    void decodesDecimalFloatingPointSyntax() {
        Map<String, Double> values = Map.ofEntries(
                Map.entry(".5", 0.5), Map.entry("1.", 1.0), Map.entry("1.25", 1.25),
                Map.entry("1e3", 1_000.0), Map.entry("1E+3", 1_000.0),
                Map.entry("1.e-3", 0.001), Map.entry(".5E+2", 50.0),
                Map.entry("012.5", 12.5), Map.entry("08.5", 8.5),
                Map.entry("08e1", 80.0), Map.entry("0000123e0", 123.0));
        values.forEach((code, value) -> assertFloatBits(Double.doubleToRawLongBits(value), expression(code)));
    }

    // 浮点语法和超限整数保持浮点节点，即使解码值为整数、零或因历史舍入落回整数范围。
    @Test
    void preservesFloatingPointClassification() {
        assertFloatBits(0x3ff0000000000000L, expression("1.0"));
        assertFloatBits(0L, expression("0e0"));
        assertFloatBits(0x43e0000000000000L, expression("9223372036854775808"));
        assertFloatBits(0x43e0000000000000L, expression("9223372036854775809"));
        assertFloatBits(0x43f0000000000000L, expression("0xFFFFFFFFFFFFFFFF"));
        assertFloatBits(0x43dfffffffffffffL, expression("0b1" + "0".repeat(63)));
    }

    // 有限上限、无穷大、最小正规数、次正规数及下溢均以 double 的实际值交付。
    @Test
    void handlesFloatingPointOverflowAndUnderflow() {
        assertFloatBits(0x7fefffffffffffffL, expression("1.7976931348623157e308"));
        assertFloatBits(0x7ff0000000000000L, expression("1.7976931348623159e308"));
        assertFloatBits(0x7ff0000000000000L, expression("1e309"));
        assertFloatBits(0x0010000000000000L, expression("2.2250738585072014e-308"));
        assertFloatBits(0x000fffffffffffffL, expression("2.225073858507201e-308"));
        assertFloatBits(1L, expression("4.9406564584124654e-324"));
        assertFloatBits(0L, expression("2e-324"));
        assertFloatBits(1L, expression("3e-324"));
        assertFloatBits(0L, expression("1e-400"));
        assertFloatBits(0L, expression("0.0e999999999999999999999999"));
        assertFloatBits(0x7ff0000000000000L, expression("1e999999999999999999999999"));
        assertFloatBits(0L, expression("1e-999999999999999999999999"));
    }

    // 固定位模式锁定 PHP 7.2 逐位运算的舍入，不用数学精确值或实现副本作为判据。
    @Test
    void preservesLegacyNonDecimalRounding() {
        assertFloatBits(0x43e0000000000000L, expression("0x8000000000000401"));
        assertFloatBits(0x43e0000000000000L, expression("0X00008000000000000401"));
        assertFloatBits(0x43dfffffffffffffL, expression("0b1" + "0".repeat(63)));
        assertFloatBits(0x43dfffffffffffffL, expression("0B0001" + "0".repeat(63)));
        assertFloatBits(0x43e0000000000000L, expression("01000000000000000003000"));
        assertFloatBits(0x43e0000000000000L, expression("0001000000000000000003000"));
    }

    // 超长合法数值允许上溢，扫描和解码不能泄漏整数解析异常或依赖固定文本长度。
    @Test
    void decodesVeryLongLiteralsWithoutRejectingOverflow() {
        for (String code : List.of("9".repeat(2_000), "0x" + "F".repeat(2_000),
                "0b" + "1".repeat(4_096), "0" + "7".repeat(2_000))) {
            assertFloatBits(0x7ff0000000000000L, expression(code));
        }
        assertFloatBits(0L, expression("0." + "0".repeat(2_000) + "1"));
        assertFloatBits(0x3ff0000000000000L, expression("1." + "0".repeat(2_000)));
    }

    // 正负号和运算保留为独立表达式，不把负的最小整数特判或提前执行溢出运算。
    @Test
    void keepsSignsAndArithmeticOutsideLiteralDecoding() {
        IrUnary negative = assertInstanceOf(IrUnary.class, expression("-42"));
        assertEquals(UnaryOperator.MINUS, negative.operator());
        assertInteger(42, negative.operand());
        IrUnary positive = assertInstanceOf(IrUnary.class, expression("+42"));
        assertEquals(UnaryOperator.PLUS, positive.operator());
        assertInteger(42, positive.operand());
        IrUnary minimum = assertInstanceOf(IrUnary.class, expression("-9223372036854775808"));
        assertEquals(UnaryOperator.MINUS, minimum.operator());
        assertFloatBits(0x43e0000000000000L, minimum.operand());
        IrUnary infinity = assertInstanceOf(IrUnary.class, expression("-1e309"));
        assertFloatBits(0x7ff0000000000000L, infinity.operand());
        IrUnary zero = assertInstanceOf(IrUnary.class, expression("-0.0"));
        assertFloatBits(0L, zero.operand());
        IrBinary addition = assertInstanceOf(IrBinary.class, expression("9223372036854775807 + 1"));
        assertEquals(BinaryOperator.ADD, addition.operator());
        assertInteger(Long.MAX_VALUE, addition.left());
        assertInteger(1, addition.right());
    }

    // 源码中的 INF 和 NAN 仍是常量名；double 的特殊值存储不等于名称绑定。
    @Test
    void keepsSpecialValueNamesAsUnresolvedReferences() {
        for (String code : List.of("INF", "NAN", "\\INF", "\\NAN", "Some\\INF", "namespace\\NAN")) {
            IrConstantReference reference = assertInstanceOf(IrConstantReference.class, expression(code));
            NameForm form = code.startsWith("\\") ? NameForm.FULLY_QUALIFIED
                    : code.startsWith("namespace\\") ? NameForm.NAMESPACE_RELATIVE
                    : code.contains("\\") ? NameForm.QUALIFIED : NameForm.UNQUALIFIED;
            String value = switch (form) {
                case FULLY_QUALIFIED -> code.substring(1);
                case NAMESPACE_RELATIVE -> "NAN";
                case UNQUALIFIED, QUALIFIED -> code;
            };
            assertEquals(value, reference.name().value());
            assertEquals(form, reference.name().form());
        }
    }

    // 手工损坏的数字文本必须按 PHP 格式拒绝，不能直接放宽为 Java 支持的解析语法。
    @Test
    void rejectsMalformedNumericTextsWithContext() {
        for (String text : List.of(" ", " 1", "1 ", "\t1.0", "1.0\n", "+1", "-1", "+1.0", "-1.0",
                "1_000", "1.0f", "1d", "NaN", "Infinity", "INF", "NAN", "0x1p0", "0X1.8P2",
                ".", "1e", "1e+", "1e-", "1e2.0", "1..0", "0x", "0b", "0xG", "0b2", "08", "09",
                "00" + "7".repeat(100) + "9", "0x" + "F".repeat(100) + "g", "0b" + "1".repeat(100) + "2",
                "\u0661", "\uff11", "0x\uff21")) {
            assertMalformed(false, text);
            assertMalformed(true, text);
        }
    }

    // AST 的 Int/Float 分类必须和合法文本一致，超限整数与小数不能塞进 Int。
    @Test
    void rejectsNumericClassificationMismatches() {
        for (String text : List.of("9223372036854775808", "0x8000000000000000",
                "01" + "0".repeat(21), "0b1" + "0".repeat(63), "1.0", ".5", "1e0")) {
            assertMalformed(false, text);
        }
        for (String text : List.of("0", "1", "9223372036854775807", "077", "0x7fffffffffffffff",
                "0B" + "1".repeat(63), "0".repeat(100) + "1", "0x" + "0".repeat(100) + "1")) {
            assertMalformed(true, text);
        }
    }

    // 缺失数字字段、null 文本和空字符串统一生成含来源与 .num 路径的转换错误。
    @Test
    void rejectsMissingNumericTextsWithContext() {
        for (boolean floating : List.of(false, true)) {
            assertMalformed(floating, null);
            assertMalformed(floating, "");
            NodeScalar scalar = floating ? new NodeScalar.Float(null, ERROR_LOCATION)
                    : new NodeScalar.Int(null, ERROR_LOCATION);
            assertNumericError(scalar);
        }
    }

    // 解码只改变输出表示，原 AST 的进制写法、对象身份和源码定位都应保持。
    @Test
    void preservesSourceAndInputAstWhileDiscardingNumericLexemes() {
        for (String code : List.of("0x2A", "1e309", "0b1" + "0".repeat(63))) {
            NodeProgram parsed = (NodeProgram) Main.parse("<?php\n  " + code + ";");
            String before = parsed.toTreeString(false);
            NodeExpr node = parsed.getStmts().getValue().getFirst().getStmt().getExpression();
            NodeString token = node.getEv().getScalar().getNum();
            SourceInfo inputSource = new SourceInfo("numbers.php", null);
            IrExpression result = SyntaxConverter.convertExpression(new SyntaxExpression(node, inputSource));
            assertEquals(new SourceInfo("numbers.php", new SourceRange(2, 3, 2, 3 + code.length())), result.source());
            assertSame(token, node.getEv().getScalar().getNum());
            assertEquals(code, token.getValue());
            assertEquals(before, parsed.toTreeString(false));
            assertEquals(result, SyntaxConverter.convertExpression(new SyntaxExpression(node, inputSource)));
        }
    }

    // 数值节点只有基本类型 value 和 source；浮点允许无穷大、NaN、正零与负零。
    @Test
    void exposesFixedPrimitiveNumericRecordsAndAcceptsSpecialValues() {
        SourceInfo source = new SourceInfo(null, null);
        for (Class<?> type : List.of(IrIntegerLiteral.class, IrFloatLiteral.class)) {
            assertTrue(type.isRecord());
            assertEquals(List.of("value", "source"), Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName).toList());
            assertEquals(type == IrIntegerLiteral.class ? long.class : double.class,
                    type.getRecordComponents()[0].getType());
            assertEquals(SourceInfo.class, type.getRecordComponents()[1].getType());
        }
        assertInteger(Long.MIN_VALUE, new IrIntegerLiteral(Long.MIN_VALUE, source));
        assertThrows(NullPointerException.class, () -> new IrIntegerLiteral(0, null));
        assertThrows(NullPointerException.class, () -> new IrFloatLiteral(0, null));
        for (double value : new double[]{Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 0.0, -0.0}) {
            IrFloatLiteral literal = new IrFloatLiteral(value, source);
            assertEquals(Double.doubleToRawLongBits(value), Double.doubleToRawLongBits(literal.value()));
            assertSame(source, literal.source());
        }
    }

    private static IrExpression expression(String code) {
        NodeProgram parsed = (NodeProgram) Main.parse("<?php " + code + ";");
        NodeExpr node = parsed.getStmts().getValue().getFirst().getStmt().getExpression();
        return SyntaxConverter.convertExpression(new SyntaxExpression(node, new SourceInfo("numeric.php", null)));
    }

    private static void assertInteger(long expected, IrExpression actual) {
        assertEquals(expected, assertInstanceOf(IrIntegerLiteral.class, actual).value());
    }

    private static void assertFloatBits(long expected, IrExpression actual) {
        double value = assertInstanceOf(IrFloatLiteral.class, actual).value();
        assertEquals(expected, Double.doubleToRawLongBits(value), () -> "实际浮点值为 " + value);
    }

    private static void assertMalformed(boolean floating, String text) {
        NodeString token = new NodeString(text, ERROR_LOCATION);
        NodeScalar scalar = floating ? new NodeScalar.Float(token, ERROR_LOCATION)
                : new NodeScalar.Int(token, ERROR_LOCATION);
        assertNumericError(scalar);
    }

    private static void assertNumericError(NodeScalar scalar) {
        var node = new NodeExpr.ExprWithoutVariable(
                new NodeExprWithoutVariable.Scalar(scalar, ERROR_LOCATION), ERROR_LOCATION);
        var syntax = new SyntaxExpression(node, new SourceInfo("broken-number.php", null));
        var error = assertThrows(SyntaxConversionException.class, () -> SyntaxConverter.convertExpression(syntax),
                () -> "损坏节点应失败：" + scalar.toTreeString(false));
        assertEquals(new SourceInfo("broken-number.php", new SourceRange(3, 5, 3, 12)), error.source());
        assertTrue(error.fieldPath().endsWith(".num"), error.fieldPath());
        assertFalse(error.reason().isBlank());
        assertTrue(error.getMessage().contains("broken-number.php"));
        assertTrue(error.getMessage().contains("3:5"));
        assertTrue(error.getMessage().contains(error.fieldPath()));
    }
}