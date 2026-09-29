package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import top.kmar.php.ir.ByteString;

import java.util.List;
import java.util.Objects;

/** 解码字面量文本，不求值；单次转换内复用缓冲区，发布的字节快照不共享可写存储。 */
final class StringLiteralDecoder {
    enum Mode { RAW, SINGLE_QUOTED, DOUBLE_QUOTED, HEREDOC }

    /** 使用原字符串的区间，避免为去引号或连续文本段创建额外字符串。 */
    record Fragment(String text, int start, int end, AstNode origin, String path) {
        Fragment(String text, AstNode origin, String path) {
            this(text, 0, text.length(), origin, path);
        }
    }

    private final ConversionContext context;
    private ByteString.Builder buffer;

    StringLiteralDecoder(ConversionContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    ByteString decode(List<Fragment> fragments, Mode mode, int trimEnd) {
        var cursor = new Cursor(fragments, trimEnd);
        if (buffer == null) buffer = new ByteString.Builder(cursor.remaining);
        else buffer.reset();
        while (cursor.hasNext()) {
            Fragment origin = cursor.origin();
            int character = cursor.read();
            if (character != '\\' || mode == Mode.RAW || !cursor.hasNext()) {
                writeCharacter(character, cursor, origin);
                continue;
            }
            if (mode == Mode.SINGLE_QUOTED) {
                int next = cursor.peek();
                if (next == '\\' || next == '\'') buffer.write(cursor.read());
                else buffer.write('\\');
                continue;
            }
            int escape = cursor.read();
            switch (escape) {
                case 'n' -> buffer.write('\n');
                case 'r' -> buffer.write('\r');
                case 't' -> buffer.write('\t');
                case 'f' -> buffer.write('\f');
                case 'v' -> buffer.write(11);
                case 'e' -> buffer.write(27);
                case '\\', '$' -> buffer.write(escape);
                case '"' -> {
                    if (mode == Mode.HEREDOC) buffer.write('\\');
                    buffer.write('"');
                }
                case 'x', 'X' -> {
                    int digit = hex(cursor.peek());
                    if (digit < 0) {
                        buffer.write('\\');
                        buffer.write(escape);
                    } else {
                        cursor.read();
                        int second = hex(cursor.peek());
                        if (second >= 0) {
                            cursor.read();
                            digit = digit * 16 + second;
                        }
                        buffer.write(digit);
                    }
                }
                case 'u' -> unicodeEscape(cursor, origin);
                default -> {
                    if (escape >= '0' && escape <= '7') {
                        int value = escape - '0';
                        for (int i = 0; i < 2 && cursor.peek() >= '0' && cursor.peek() <= '7'; i++) {
                            value = value * 8 + cursor.read() - '0';
                        }
                        // PHP 7.2 对八进制转义溢出保留低八位；本层不模拟编译警告。
                        buffer.write(value);
                    } else {
                        buffer.write('\\');
                        writeCharacter(escape, cursor, origin);
                    }
                }
            }
        }
        return buffer.build();
    }

    private void unicodeEscape(Cursor cursor, Fragment origin) {
        if (cursor.peek() != '{') {
            buffer.write('\\');
            buffer.write('u');
            return;
        }
        cursor.read();
        int value = 0;
        boolean hasDigit = false;
        while (cursor.peek() != '}') {
            int digit = hex(cursor.peek());
            if (digit < 0) throw context.error(origin.origin(), origin.path(), "非法 Unicode 转义");
            cursor.read();
            hasDigit = true;
            if (value > (0x10ffff - digit) / 16) {
                throw context.error(origin.origin(), origin.path(), "Unicode 转义超出 0x10FFFF");
            }
            value = value * 16 + digit;
        }
        if (!hasDigit) throw context.error(origin.origin(), origin.path(), "Unicode 转义不能为空");
        cursor.read();
        // 固定 PHP 7.2 行为：转义中的代理区数值也直接按三字节编码，不替换成 Java 字符。
        writeCodePoint(value);
    }

    private void writeCharacter(int character, Cursor cursor, Fragment origin) {
        if (Character.isHighSurrogate((char) character)) {
            int low = cursor.peek();
            if (low < 0 || !Character.isLowSurrogate((char) low)) {
                throw context.error(origin.origin(), origin.path(), "源码文本包含未配对的高代理字符");
            }
            cursor.read();
            character = Character.toCodePoint((char) character, (char) low);
        } else if (Character.isLowSurrogate((char) character)) {
            throw context.error(origin.origin(), origin.path(), "源码文本包含未配对的低代理字符");
        }
        writeCodePoint(character);
    }

    private void writeCodePoint(int value) {
        if (value < 0x80) buffer.write(value);
        else if (value <= 0x7ff) {
            buffer.write(0xc0 | value >> 6);
            buffer.write(0x80 | value & 0x3f);
        } else if (value <= 0xffff) {
            buffer.write(0xe0 | value >> 12);
            buffer.write(0x80 | value >> 6 & 0x3f);
            buffer.write(0x80 | value & 0x3f);
        } else {
            buffer.write(0xf0 | value >> 18);
            buffer.write(0x80 | value >> 12 & 0x3f);
            buffer.write(0x80 | value >> 6 & 0x3f);
            buffer.write(0x80 | value & 0x3f);
        }
    }

    static int hex(int character) {
        if (character >= '0' && character <= '9') return character - '0';
        if (character >= 'a' && character <= 'f') return character - 'a' + 10;
        if (character >= 'A' && character <= 'F') return character - 'A' + 10;
        return -1;
    }

    /** 不拼接原文；一次 peek/read 可以跨越空 token、转义和 UTF-16 代理对的边界。 */
    private static final class Cursor {
        private final List<Fragment> fragments;
        private int fragmentIndex;
        private int offset;
        private int remaining;

        Cursor(List<Fragment> fragments, int trimEnd) {
            this.fragments = fragments;
            for (var fragment : fragments) remaining = Math.addExact(remaining, fragment.end() - fragment.start());
            remaining -= trimEnd;
            if (!fragments.isEmpty()) offset = fragments.getFirst().start();
        }

        boolean hasNext() { return remaining > 0; }

        Fragment origin() {
            while (offset == fragments.get(fragmentIndex).end()) {
                offset = fragments.get(++fragmentIndex).start();
            }
            return fragments.get(fragmentIndex);
        }

        int peek() {
            return hasNext() ? origin().text().charAt(offset) : -1;
        }

        int read() {
            int result = peek();
            offset++;
            remaining--;
            return result;
        }
    }
}
