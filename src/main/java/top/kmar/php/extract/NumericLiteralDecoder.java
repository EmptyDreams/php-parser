package top.kmar.php.extract;

import top.kmar.php.NodeScalar;
import top.kmar.php.ir.IrExpression;
import top.kmar.php.ir.IrFloatLiteral;
import top.kmar.php.ir.IrIntegerLiteral;

import java.util.Objects;

/** 仅解码数字字面量，遵循 64 位 PHP 7.2 的分类和舍入，不求值表达式。 */
final class NumericLiteralDecoder {
    private final ConversionContext context;

    NumericLiteralDecoder(ConversionContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    IrExpression convert(NodeScalar node, String path) {
        boolean integerExpected = node instanceof NodeScalar.Int;
        if (!integerExpected && !(node instanceof NodeScalar.Float)) {
            throw context.error(node, path, "无法识别的数字字面量结构");
        }
        String numberPath = path + ".num";
        String text = context.text(node.getNum(), node, numberPath);
        int radix = 10;
        int start = 0;
        if (text.length() > 1 && text.charAt(0) == '0') {
            // 先识别进制前缀，避免把十六进制的 e/E 误认为指数标记。
            radix = switch (text.charAt(1)) {
                case 'x', 'X' -> 16;
                case 'b', 'B' -> 2;
                default -> 10;
            };
            if (radix != 10) start = 2;
        }
        try {
            if (radix == 10) {
                if (isDecimalFloat(text, node, numberPath)) {
                    requireClassification(integerExpected, false, node, numberPath);
                    return new IrFloatLiteral(Double.parseDouble(text), context.source(node));
                }
                // 只有整数写法的前导零表示八进制；08.0、09e1 仍是十进制浮点数。
                if (text.length() > 1 && text.charAt(0) == '0') radix = 8;
            }
            int significantStart = validateInteger(text, start, radix, node, numberPath);
            boolean fitsInteger = fitsInteger(text, significantStart, radix);
            requireClassification(integerExpected, fitsInteger, node, numberPath);
            if (fitsInteger) {
                long value = significantStart == text.length() ? 0L
                        : Long.parseLong(text, significantStart, text.length(), radix);
                return new IrIntegerLiteral(value, context.source(node));
            }
            double value = radix == 10 ? Double.parseDouble(text)
                    : nonDecimalFloat(text, significantStart, radix);
            return new IrFloatLiteral(value, context.source(node));
        } catch (NumberFormatException error) {
            throw invalidNumber(text, node, numberPath);
        }
    }

    /** 校验无符号的十进制写法，返回是否含小数点或指数；不接受 Java 的扩展语法。 */
    private boolean isDecimalFloat(String text, NodeScalar node, String path) {
        int cursor = decimalDigitsEnd(text, 0);
        boolean hasDigits = cursor > 0;
        boolean floating = false;
        if (cursor < text.length() && text.charAt(cursor) == '.') {
            floating = true;
            int fractionStart = ++cursor;
            cursor = decimalDigitsEnd(text, cursor);
            hasDigits |= cursor > fractionStart;
        }
        if (!hasDigits) throw invalidNumber(text, node, path);
        if (cursor < text.length() && (text.charAt(cursor) == 'e' || text.charAt(cursor) == 'E')) {
            floating = true;
            cursor++;
            if (cursor < text.length() && (text.charAt(cursor) == '+' || text.charAt(cursor) == '-')) {
                cursor++;
            }
            int exponentStart = cursor;
            cursor = decimalDigitsEnd(text, cursor);
            if (cursor == exponentStart) throw invalidNumber(text, node, path);
        }
        if (cursor != text.length()) throw invalidNumber(text, node, path);
        return floating;
    }

    private static int decimalDigitsEnd(String text, int start) {
        int cursor = start;
        while (cursor < text.length() && text.charAt(cursor) >= '0' && text.charAt(cursor) <= '9') {
            cursor++;
        }
        return cursor;
    }

    /** 校验所有位并定位首个非零位；即使已经超限，也不跳过剩余字符的合法性检查。 */
    private int validateInteger(String text, int start, int radix, NodeScalar node, String path) {
        if (start == text.length()) throw invalidNumber(text, node, path);
        int significantStart = text.length();
        for (int i = start; i < text.length(); i++) {
            int digit = digit(text.charAt(i));
            if (digit < 0 || digit >= radix) throw invalidNumber(text, node, path);
            if (digit != 0 && significantStart == text.length()) significantStart = i;
        }
        return significantStart;
    }

    private static int digit(char character) {
        if (character >= '0' && character <= '9') return character - '0';
        if (character >= 'a' && character <= 'f') return character - 'a' + 10;
        if (character >= 'A' && character <= 'F') return character - 'A' + 10;
        return -1;
    }

    private static boolean fitsInteger(String text, int significantStart, int radix) {
        int digits = text.length() - significantStart;
        return switch (radix) {
            case 2 -> digits <= 63;
            case 8 -> digits <= 21;
            case 16 -> digits < 16 || (digits == 16 && text.charAt(significantStart) <= '7');
            // 多位前导零已归入八进制；此处可直接比较完整十进制文本。
            case 10 -> digits < 19 || (digits == 19 && text.compareTo("9223372036854775807") <= 0);
            default -> throw new IllegalArgumentException("Unsupported numeric radix: " + radix);
        };
    }

    private void requireClassification(boolean integerExpected, boolean fitsInteger,
                                       NodeScalar node, String path) {
        if (integerExpected != fitsInteger) {
            throw context.error(node, path, "数字文本与整数／浮点 AST 分类不一致");
        }
    }

    /**
     * 对齐 PHP 7.2.34 Zend/zend_strtod.c 的 zend_hex/oct/bin_strtod。
     * 八／二进制必须先加 ASCII 字符再减 '0'，不能改为先算 digit，
     * 也不能用 BigInteger.doubleValue 或一次正确舍入替换，以免改变历史舍入结果。
     * @see <a href="https://github.com/php/php-src/blob/php-7.2.34/Zend/zend_strtod.c#L4420-L4524">zend_strtod.c#L4420-L4524</a>
     */
    private static double nonDecimalFloat(String text, int start, int radix) {
        double value = 0.0;
        for (int i = start; i < text.length(); i++) {
            if (radix == 16) {
                value = value * 16 + digit(text.charAt(i));
            } else {
                value = (value * radix + text.charAt(i)) - '0';
            }
        }
        return value;
    }

    private SyntaxConversionException invalidNumber(String text, NodeScalar node, String path) {
        return context.error(node, path, "非法数字字面量: " + text);
    }
}