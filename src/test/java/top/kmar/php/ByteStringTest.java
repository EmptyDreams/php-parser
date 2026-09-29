package top.kmar.php;

import org.junit.jupiter.api.Test;
import top.kmar.php.ir.*;
import top.kmar.php.model.SourceInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ByteStringTest {
    private static final SourceInfo SOURCE = new SourceInfo("strings.php", null);

    // 输入和导出数组都不能修改已经创建的字节值。
    @Test
    void copiesInputAndOutputArrays() {
        byte[] input = {0, 65, (byte) 255};
        ByteString value = ByteString.copyOf(input);
        input[1] = 66;
        byte[] output = value.toByteArray();
        output[2] = 0;
        assertEquals(3, value.size());
        assertEquals(65, value.byteAt(1));
        assertEquals((byte) 255, value.byteAt(2));
        assertArrayEquals(new byte[]{0, 65, (byte) 255}, value.toByteArray());
        assertNotSame(value.toByteArray(), value.toByteArray());
    }

    // 相等性和哈希只取决于字节内容，不取决于数组身份。
    @SuppressWarnings({"MisorderedAssertEqualsArguments", "AssertBetweenInconvertibleTypes"})
    @Test
    void comparesValuesByByteContents() {
        ByteString first = ByteString.copyOf(new byte[]{1, 2, (byte) 255});
        ByteString same = ByteString.copyOf(new byte[]{1, 2, (byte) 255});
        //noinspection EqualsWithItself
        assertEquals(first, first);
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertEquals(Arrays.hashCode(new byte[]{1, 2, (byte) 255}), first.hashCode());
        assertNotEquals(first, ByteString.copyOf(new byte[]{1, 2}));
        assertNotEquals(first, ByteString.copyOf(new byte[]{1, 2, 3}));
        assertNotEquals(first, new byte[]{1, 2, (byte) 255});
        assertNotEquals(first, null);
    }

    // 空值共享不可变对象，但导出的空数组仍然是独立数组。
    @Test
    void reusesEmptyValues() {
        ByteString empty = ByteString.copyOf(new byte[0]);
        var builder = new ByteString.Builder(0);
        assertSame(empty, ByteString.copyOf(new byte[0]));
        assertSame(empty, builder.build());
        builder.write(1);
        builder.reset();
        assertSame(empty, builder.build());
        assertEquals(0, empty.size());
        assertNotSame(empty.toByteArray(), empty.toByteArray());
    }

    // 构建只取快照；追加、清空和再次构建都不能改变此前的结果。
    @Test
    void preservesSnapshotsWhenBuilderIsReused() {
        var builder = new ByteString.Builder(1);
        builder.write(65);
        ByteString first = builder.build();
        assertEquals(first, builder.build());
        builder.write(66);
        ByteString second = builder.build();
        builder.reset();
        builder.write(67);
        ByteString third = builder.build();
        assertArrayEquals(new byte[]{65}, first.toByteArray());
        assertArrayEquals(new byte[]{65, 66}, second.toByteArray());
        assertArrayEquals(new byte[]{67}, third.toByteArray());
    }

    // 缓冲区可以扩容，但包括大值后的小值在内，最终底层数组都必须是精确长度。
    @Test
    void storesExactLengthArraysAfterGrowthAndReset() throws ReflectiveOperationException {
        var builder = new ByteString.Builder(4096);
        builder.write(42);
        ByteString shortValue = builder.build();
        assertEquals(1, backingArray(shortValue).length);
        builder.reset();
        for (int i = 0; i < 10000; i++) builder.write(i);
        ByteString longValue = builder.build();
        assertEquals(10000, backingArray(longValue).length);
        assertEquals((byte) 9999, longValue.byteAt(9999));
        builder.reset();
        builder.write(7);
        ByteString nextShortValue = builder.build();
        assertEquals(1, backingArray(nextShortValue).length);
        assertEquals(7, nextShortValue.byteAt(0));
        assertEquals(10000, longValue.size());
        assertEquals(42, shortValue.byteAt(0));
        assertEquals(0, backingArray(ByteString.copyOf(new byte[0])).length);
    }

    // 写入遵循低八位规则；非法索引、空数组引用和负初始容量明确失败。
    @Test
    void validatesByteAccessAndBuilderInputs() {
        var builder = new ByteString.Builder(0);
        builder.write(256);
        builder.write(-1);
        builder.write(511);
        ByteString value = builder.build();
        assertArrayEquals(new byte[]{0, (byte) 255, (byte) 255}, value.toByteArray());
        assertThrows(IndexOutOfBoundsException.class, () -> value.byteAt(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> value.byteAt(value.size()));
        assertThrows(NullPointerException.class, () -> ByteString.copyOf(null));
        assertThrows(IllegalArgumentException.class, () -> new ByteString.Builder(-1));
    }

    // 模板保留片段顺序并冻结列表；插值里的字符串字面量仍与纯文本片段区分。
    @Test
    void snapshotsTemplatePartsAndPreservesInterpolationBoundaries() {
        ByteString bytes = ByteString.copyOf(new byte[]{65});
        var text = new IrStringText(bytes, SOURCE);
        var literal = new IrStringLiteral(bytes, SOURCE);
        var interpolation = new IrStringInterpolation(literal, SOURCE);
        var input = new ArrayList<IrStringPart>(List.of(text, interpolation, text));
        var template = new IrStringTemplate(input, SOURCE);
        input.clear();
        assertEquals(List.of(text, interpolation, text), template.parts());
        assertSame(literal, assertInstanceOf(IrStringInterpolation.class, template.parts().get(1)).expression());
        assertThrows(UnsupportedOperationException.class, () -> template.parts().clear());
        assertEquals(template, new IrStringTemplate(List.of(text, interpolation, text), SOURCE));
        assertSame(SOURCE, template.source());
    }

    // 纯文本应该使用字面量节点；模板必须至少有一个插值且不能含空元素。
    @Test
    void rejectsTemplatesWithoutInterpolationOrWithNullParts() {
        var text = new IrStringText(ByteString.copyOf(new byte[0]), SOURCE);
        assertThrows(IllegalArgumentException.class, () -> new IrStringTemplate(List.of(), SOURCE));
        assertThrows(IllegalArgumentException.class, () -> new IrStringTemplate(List.of(text), SOURCE));
        assertThrows(NullPointerException.class, () -> new IrStringTemplate(null, SOURCE));
        assertThrows(NullPointerException.class, () -> new IrStringTemplate(Arrays.asList(text, null), SOURCE));
    }

    // 新增节点的值、表达式、类型与来源都是必需字段，未知源码范围仍是合法来源。
    @Test
    void validatesRequiredModelFields() {
        ByteString empty = ByteString.copyOf(new byte[0]);
        var literal = new IrStringLiteral(empty, SOURCE);
        var interpolation = new IrStringInterpolation(literal, SOURCE);
        assertThrows(NullPointerException.class, () -> new IrStringLiteral(null, SOURCE));
        assertThrows(NullPointerException.class, () -> new IrStringLiteral(empty, null));
        assertThrows(NullPointerException.class, () -> new IrStringText(null, SOURCE));
        assertThrows(NullPointerException.class, () -> new IrStringText(empty, null));
        assertThrows(NullPointerException.class, () -> new IrStringInterpolation(null, SOURCE));
        assertThrows(NullPointerException.class, () -> new IrStringInterpolation(literal, null));
        assertThrows(NullPointerException.class, () -> new IrStringTemplate(List.of(interpolation), null));
        assertThrows(NullPointerException.class, () -> new IrMagicConstant(null, SOURCE));
        assertThrows(NullPointerException.class, () -> new IrMagicConstant(MagicConstantKind.LINE, null));
        assertSame(empty, literal.value());
        assertSame(SOURCE, literal.source());
        assertNull(literal.source().range());
    }

    // 八种魔术常量只保存种类，不在模型中要求或生成上下文相关值。
    @Test
    void representsEveryMagicConstantWithoutBindingItsValue() {
        assertEquals(8, MagicConstantKind.values().length);
        for (MagicConstantKind kind : MagicConstantKind.values()) {
            var constant = new IrMagicConstant(kind, SOURCE);
            assertSame(kind, constant.kind());
            assertSame(SOURCE, constant.source());
        }
    }

    private static byte[] backingArray(ByteString value) throws ReflectiveOperationException {
        Field field = ByteString.class.getDeclaredField("bytes");
        assertTrue(field.trySetAccessible());
        return (byte[]) field.get(value);
    }
}