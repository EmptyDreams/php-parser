package top.kmar.php;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.CharBuffer;

/**
 * 自定义Reader，用于在读取文本时将\r\n替换为\n
 */
public class CodeReader extends Reader {

    private final Reader reader;
    private boolean hasCarriageReturn = false;

    /**
     * 构造函数
     *
     * @param reader 底层Reader
     */
    public CodeReader(Reader reader) {
        this.reader = reader;
    }

    @Override
    public int read(@Nonnull CharBuffer target) throws IOException {
        // 不支持直接读入CharBuffer，使用默认实现（会通过read(char[])间接调用）
        return super.read(target);
    }

    @Override
    public int read() throws IOException {
        int ch;
        if (hasCarriageReturn) {
            // 先前读到了\r，检查下一个字符是否是\n
            ch = reader.read();
            hasCarriageReturn = false;
            if (ch == '\n') {
                // \r\n序列，返回\n并标记已处理
                return '\n';
            } else if (ch != -1) {
                // \r后面跟着其他字符，我们需要先返回\n，然后缓存这个字符
                // 但简单起见，我们在这里采用另一种方式处理：把当前字符放回输入流
                // 由于没有unread方法，我们使用一个标志来记录之前读到的\r
                hasCarriageReturn = true;
                return '\n';
            } else {
                // 文件结尾，返回之前读到的\r
                return '\r';
            }
        } else {
            // 正常读取
            ch = reader.read();
            if (ch == '\r') {
                // 检查下一个字符是否是\n
                reader.mark(1);
                int nextCh = reader.read();
                if (nextCh == '\n') {
                    // 发现\r\n序列，只返回\n
                    return '\n';
                } else {
                    // 回退，保留\r供下次读取处理
                    reader.reset();
                    hasCarriageReturn = true;
                    return read(); // 递归调用一次以获取正确的下一个字符
                }
            }
            return ch;
        }
    }

    @Override
    public int read(@Nonnull char[] cbuf, int off, int len) throws IOException {
        int i = 0;
        for (; i < len; i++) {
            int ch = read();
            if (ch == -1) {
                return (i == 0) ? -1 : i; // 如果还没读取任何字符就遇到EOF则返回-1，否则返回实际读取数量
            }
            cbuf[off + i] = (char) ch;
        }
        return len;
    }

    @Override
    public long skip(long n) throws IOException {
        // 简单实现跳过n个字符
        return reader.skip(n);
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready() || hasCarriageReturn;
    }

    @Override
    public boolean markSupported() {
        return false; // 不支持mark/reset操作
    }

    @Override
    public void mark(int readAheadLimit) throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

}