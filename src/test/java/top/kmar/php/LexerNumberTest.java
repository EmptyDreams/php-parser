package top.kmar.php;

import java_cup.runtime.Symbol;
import java_cup.runtime.symbol.complex.ComplexLocation;
import java_cup.runtime.symbol.complex.ComplexSymbolFactory;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LexerNumberTest {

    private PhpLexer lexer(String source) {
        return new PhpLexer(new StringReader(source), new ComplexSymbolFactory(
                PhpSymbols.TERMINAL_NAMES, PhpSymbols.NON_TERMINAL_NAMES));
    }

    private void assertNumbers(int kind, String... literals) throws Exception {
        PhpLexer lexer = lexer("<?php " + String.join(" ", literals));
        for (String literal : literals) {
            Symbol token = lexer.next_token();
            assertEquals(kind, token.sym, literal);
            assertEquals(literal, token.<String>value());
        }
        assertEquals(PhpSymbols.EOF, lexer.next_token().sym);
    }

    // 验证十进制先按有效位数分类，同为 19 位时比较首位、中间位与末位。
    @Test
    void decimalRangeUsesLengthAndLexicographicComparison() throws Exception {
        assertNumbers(PhpSymbols.T_LNUMBER, "0", "1", "999999999999999999",
                "1000000000000000000", "8223372036854775807", "9222372036854775807",
                "9223372036854775806", "9223372036854775807");
        assertNumbers(PhpSymbols.T_DNUMBER, "9223372036854775808", "9224372036854775807",
                "9323372036854775807", "9999999999999999999", "10000000000000000000",
                "9".repeat(1000));
    }

    // 验证十六进制大小写前缀与数字，16 位的 7/8 边界以及 17 位溢出。
    @Test
    void hexadecimalRangeUsesSignificantDigitsAndFirstDigit() throws Exception {
        assertNumbers(PhpSymbols.T_LNUMBER, "0x0", "0xFFFFFFFFFFFFFFF", "0X7000000000000000",
                "0x7ffffffffffffffe", "0x7fffffffffffffff", "0X7FFFFFFFFFFFFFFF");
        assertNumbers(PhpSymbols.T_DNUMBER, "0x8000000000000000", "0X8000000000000001",
                "0xFFFFFFFFFFFFFFFF", "0X10000000000000000");
    }

    // 验证八进制按实际进制计算范围，尤其不能将 2^60 的八进制文本误判为浮点。
    @Test
    void octalRangeUsesTwentyOneSignificantDigits() throws Exception {
        assertNumbers(PhpSymbols.T_LNUMBER, "00", "0777", "0100000000000000000000",
                "0" + "7".repeat(20), "01" + "0".repeat(20),
                "0" + "7".repeat(20) + "6", "0" + "7".repeat(21));
        assertNumbers(PhpSymbols.T_DNUMBER, "01" + "0".repeat(21),
                "01" + "0".repeat(20) + "1", "0" + "7".repeat(22));
    }

    // 验证二进制 63 位可表示整数，64 位及以上转为浮点，并接受大写前缀。
    @Test
    void binaryRangeUsesSixtyThreeSignificantDigits() throws Exception {
        assertNumbers(PhpSymbols.T_LNUMBER, "0b0", "0B1", "0b" + "1".repeat(62),
                "0b1" + "0".repeat(62), "0b" + "1".repeat(62) + "0",
                "0B" + "1".repeat(63));
        assertNumbers(PhpSymbols.T_DNUMBER, "0b1" + "0".repeat(63),
                "0B1" + "0".repeat(62) + "1", "0b" + "1".repeat(64));
    }

    // 验证任意数量前导零不影响范围判断，全零仍为整数；无前缀前导零遵循八进制。
    @Test
    void leadingZerosDoNotIncreaseSignificantLength() throws Exception {
        String zeros = "0".repeat(1000);
        assertNumbers(PhpSymbols.T_LNUMBER, zeros, "0x" + zeros, "0X" + zeros,
                "0b" + zeros, "0B" + zeros, zeros + "7".repeat(21),
                "0x" + zeros + "7fffffffffffffff", "0B" + zeros + "1".repeat(63));
        assertNumbers(PhpSymbols.T_DNUMBER, zeros + "1" + "0".repeat(21),
                "0X" + zeros + "8000000000000000", "0b" + zeros + "1" + "0".repeat(63));
    }

    // 验证非法八进制不能在长度判断后漏检，错误携带完整文本与左闭右开的源码位置。
    @Test
    void invalidOctalDigitsReportTheirLiteralLocation() {
        for (String literal : new String[]{"08", "09", "0008", "0009",
                "0" + "7".repeat(1000) + "8", "0" + "7".repeat(1000) + "9"}) {
            PhpLexer lexer = lexer("<?php\n  " + literal);
            PhpLexerException error = assertThrows(PhpLexerException.class, lexer::next_token, literal);
            assertEquals(ComplexLocation.of(2, 3, 2, 3 + literal.length()), error.getLocation());
            assertTrue(error.getMessage().contains("非法八进制"));
            assertTrue(error.getMessage().contains(literal));
        }
    }

    // 验证分类优化不改变原始文本，也不改变跨行读取时每个数字的源码位置。
    @Test
    void numericTokensPreserveRawTextAndSourceRange() throws Exception {
        PhpLexer lexer = lexer("<?php\n  0X000aBc\n  9223372036854775808\n  000077\n  0B000101");
        String[] literals = {"0X000aBc", "9223372036854775808", "000077", "0B000101"};
        int[] kinds = {PhpSymbols.T_LNUMBER, PhpSymbols.T_DNUMBER,
                PhpSymbols.T_LNUMBER, PhpSymbols.T_LNUMBER};
        for (int i = 0; i < literals.length; i++) {
            Symbol token = lexer.next_token();
            assertEquals(kinds[i], token.sym);
            assertEquals(literals[i], token.<String>value());
            assertEquals(ComplexLocation.of(i + 2, 3, i + 2, 3 + literals[i].length()),
                    token.getLocation());
        }
        assertEquals(PhpSymbols.EOF, lexer.next_token().sym);
    }

    // 验证普通小数、指数形式和浮点溢出文本仍直接归类为浮点，不在词法层求值。
    @Test
    void floatingLiteralsKeepTheirExistingClassification() throws Exception {
        assertNumbers(PhpSymbols.T_DNUMBER, "1.25", ".5", "1.", "1e3", "1E+3", "1e-3",
                "08.0", "09e1", "1e309", "0.0", "1e-9999");
    }

    // 验证负号是独立词法单元，不能将负数的范围判断混入无符号数字字面量。
    @Test
    void minusRemainsSeparateFromNumericTokens() throws Exception {
        PhpLexer lexer = lexer("<?php -42 -9223372036854775808 -1e309 -0x8000000000000000");
        String[] literals = {"42", "9223372036854775808", "1e309", "0x8000000000000000"};
        for (int i = 0; i < literals.length; i++) {
            assertEquals(PhpSymbols.MINUS, lexer.next_token().sym);
            Symbol token = lexer.next_token();
            assertEquals(i == 0 ? PhpSymbols.T_LNUMBER : PhpSymbols.T_DNUMBER, token.sym);
            assertEquals(literals[i], token.<String>value());
        }
        assertEquals(PhpSymbols.EOF, lexer.next_token().sym);
    }

    // 使用仅存在于测试中的大整数作为独立判据，交叉验证四种进制的边界与确定性样本。
    @Test
    void integerClassificationMatchesIndependentBigIntegerOracle() throws Exception {
        BigInteger maximum = BigInteger.valueOf(Long.MAX_VALUE);
        List<BigInteger> values = new ArrayList<>(List.of(BigInteger.ZERO, BigInteger.ONE,
                BigInteger.ONE.shiftLeft(60), maximum.subtract(BigInteger.ONE), maximum,
                maximum.add(BigInteger.ONE), maximum.add(BigInteger.TWO), BigInteger.ONE.shiftLeft(64)));
        Random random = new Random(72);
        for (int i = 0; i < 48; i++) {
            values.add(new BigInteger(1 + i * 3, random));
        }
        List<String> literals = new ArrayList<>();
        List<Integer> kinds = new ArrayList<>();
        for (BigInteger value : values) {
            for (int radix : new int[]{10, 16, 8, 2}) {
                String prefix = switch (radix) {
                    case 16 -> "0x";
                    case 8 -> "0";
                    case 2 -> "0b";
                    default -> "";
                };
                literals.add(prefix + value.toString(radix));
                kinds.add(value.compareTo(maximum) <= 0 ? PhpSymbols.T_LNUMBER : PhpSymbols.T_DNUMBER);
            }
        }
        PhpLexer lexer = lexer("<?php " + String.join(" ", literals));
        for (int i = 0; i < literals.size(); i++) {
            Symbol token = lexer.next_token();
            assertEquals(kinds.get(i).intValue(), token.sym, literals.get(i));
            assertEquals(literals.get(i), token.<String>value());
        }
        assertEquals(PhpSymbols.EOF, lexer.next_token().sym);
    }
}
