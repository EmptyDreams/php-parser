package top.kmar.php.lexer

import java.io.Closeable

/**
 * 单线程的字符流接口。
 *
 * 该流是单线程的，不允许同时读取多个流，但可以创建多个子流。
 *
 * 注意：该流是随机读取流，应当允许高性能的随机读取操作。
 */
interface SingleThreadRandomStream : Closeable {

    /**
     * 设置读取位置，从 0 开始
     *
     * @throws IndexOutOfBoundsException 如果超出流范围
     */
    fun pos(index: Int)

    /**
     * 获取下一次调用 [nextChar] 读取位置
     */
    fun pos(): Int

    /**
     * 跳过指定数量的字符，允许负数输入，超出流范围会抛出异常
     *
     * @throws IndexOutOfBoundsException 如果超出流范围
     */
    fun skip(count: Int)

    /**
     * 判断是否还有下一个字符
     */
    fun hasNext(): Boolean

    /**
     * 获取下一个字符，返回 -1 表示超出流范围
     */
    fun nextChar(): Int

    /**
     * 检查下一个字符是不是空白符，如果是则向后移动一位读取位置，否则什么都不做
     */
    fun nextIfWhitespace(): Boolean

    /**
     * 检查下一个字符是不是 `\n`，如果是则向后移动一位读取位置。
     *
     * 如果下一个字符是 `\r` 且后一个字符是 `\n` 则移动两位。
     *
     * 否则什么都不做
     */
    fun nextIfNewLine(): Boolean

    /**
     * 获取指定位置的字符，不修改读取位点
     *
     * @return 返回 -1 表示超出流范围
     */
    fun getChar(index: Int): Int

    /**
     * 获取当前读取位置向后偏移指定量的字符，不修改读取位点
     *
     * @param offset 偏移量，输入 `0` 读取 [nextChar] 的返回值，允许负数输入
     * @return 返回 -1 表示超出流范围
     */
    fun getOffsetChar(offset: Int): Int

    /**
     * 获取一个子流，范围是 [[start], [end])
     *
     * 子流的任何操作（包括 close）都不会影响父流，但关闭父流会关闭所有子流
     */
    fun subStream(start: Int, end: Int): SingleThreadRandomStream {
        return SubSingleThreadStream(this, start, end)
    }

}

/**
 * 从当前位置开始遍历流剩余的所有元素（**会修改**流当前的读取位点）
 *
 * 与 [forEachStream] 不同的是：该函数会在修改读取位点前触发 [action]，如果 [action] 返回 `false` 将不会修改位点。
 *
 * @param action 要执行的操作，返回 `false` 表示中断遍历
 */
inline fun SingleThreadRandomStream.forEachStreamBefore(action: (Char) -> Boolean) {
    var char = getOffsetChar(0)
    while (char != -1 && action(char.toChar())) {
        skip(1)
        char = getOffsetChar(0)
    }
}

/**
 * 从当前位置开始遍历流剩余的所有元素（**会修改**流当前的读取位点）
 *
 * 与 [forEachStreamConst] 不同的是：该函数会在修改读取位点后触发 [action]，即使 [action] 返回 `false` 读取位点也会被修改。
 *
 * @param action 要执行的操作，返回 `false` 表示中断遍历
 */
inline fun SingleThreadRandomStream.forEachStream(action: (Char) -> Boolean) {
    var char = nextChar()
    while (char != -1) {
        val state = action(char.toChar())
        if (!state) break
        char = nextChar()
    }
}

/**
 * 从当前位置开始遍历流剩余的所有元素（**不修改**流的状态）
 *
 * @param action 要执行的操作，返回 false 表示中断遍历
 */
inline fun SingleThreadRandomStream.forEachStreamConst(action: (Char) -> Boolean) {
    var char = getOffsetChar(0)
    var offset = 1
    while (char != -1) {
        val state = action(char.toChar())
        if (!state) break
        char = getOffsetChar(offset++)
    }
}

/**
 * 提取当前位置开始的字符串，直到遇到 -1 或指定长度，返回提取的字符串
 */
fun SingleThreadRandomStream.extractStringConst(limit: Int): String {
    val builder = StringBuilder(limit.coerceAtMost(128))
    var index = 0
    forEachStreamConst {
        builder.append(it)
        ++index != limit
    }
    return builder.toString()
}