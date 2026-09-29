package top.kmar.php.ir;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

/** 不可变字节值；内部数组长度恰好等于内容长度，不约定其文本编码。 */
public final class ByteString {
    private static final ByteString EMPTY = new ByteString(new byte[0]);

    private final byte[] bytes;

    /** 仅接管本类已取得独占所有权的精确数组，不能作为公开的免复制入口。 */
    private ByteString(byte[] bytes) {
        this.bytes = bytes;
    }

    /** 复制调用方数组，后续修改该数组不会影响此值。 */
    public static ByteString copyOf(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return bytes.length == 0 ? EMPTY : new ByteString(bytes.clone());
    }

    public int size() {
        return bytes.length;
    }

    /** 按 Java byte 的有符号表示读取一个字节，不复制内部数组。 */
    public byte byteAt(int index) {
        return bytes[index];
    }

    /** 返回独立的精确长度数组，调用方可以自由修改。 */
    public byte[] toByteArray() {
        return bytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ByteString value && Arrays.equals(bytes, value.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    /** 可复用的局部缓冲区；构建快照不会移交缓冲区本身，也不会清空已有内容。 */
    public static final class Builder {
        private final ByteArrayOutputStream buffer;

        public Builder(int initialCapacity) {
            buffer = new ByteArrayOutputStream(initialCapacity);
        }

        /** 写入参数的低八位，与 ByteArrayOutputStream 的字节写入规则一致。 */
        public void write(int value) {
            buffer.write(value);
        }

        /** 清空有效内容并保留容量，已经构建的值不会改变。 */
        public void reset() {
            buffer.reset();
        }

        /** 只复制一次，结果的底层数组无预留容量。 */
        public ByteString build() {
            return buffer.size() == 0 ? EMPTY : new ByteString(buffer.toByteArray());
        }
    }
}
