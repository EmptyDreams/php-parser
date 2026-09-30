package top.kmar.php.extract;

import top.kmar.php.ir.IrBlock;
import top.kmar.php.ir.IrExpression;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;

import java.util.Objects;

/**
 * 将选定的语法体或表达式转换为独立 IR，不修改原 AST 或声明模型。
 * <p>支持基础运算、条件、循环、switch、异常处理结构、含引用项的数组、引用赋值、解构、引用 foreach，
 * 具名及动态对象操作、匿名类及其成员／trait 适配规则、属性／下标读写，
 * 具名函数、类、接口及 trait 声明（包括嵌套声明），
 * 动态变量与调用、实参解包、闭包与 yield/yield from、字符串模板及魔术常量，
 * 以及 global、局部 static、declare、goto／标签和 isset/empty/unset、强转、错误抑制、print、文件包含、eval、exit/die；
 * 遇到不支持或损坏的结构抛出
 * {@link SyntaxConversionException}，不绑定名称、不推断类型、不执行 PHP。
 * 数字字面量按 64 位 PHP 7.2 规则解码为 long/double，字符串文本按 UTF-8 和 PHP 7.2 转义规则
 * 解码为不可变字节值；原文仍保留在 AST 中，插值及魔术常量不求值。
 * 具名声明保留原语句位置和名称拼写，不注册符号或判定生效时机；
 * 调用方负责保留所属声明及其命名空间、导入环境，这不是整文件转换入口。</p>
 */
public final class SyntaxConverter {
    private SyntaxConverter() {
    }

    /** 转换完整的选定语句序列；传入值不能为 null。 */
    public static IrBlock convertBody(SyntaxBody body) {
        Objects.requireNonNull(body, "body");
        var context = new ConversionContext(body.source().sourceId());
        return new StatementConverter(context, new ExpressionConverter(context)).body(body);
    }

    /** 转换一个表达式；缺省默认值等应由调用方先判空。 */
    public static IrExpression convertExpression(SyntaxExpression expression) {
        Objects.requireNonNull(expression, "expression");
        var context = new ConversionContext(expression.source().sourceId());
        return new ExpressionConverter(context).convert(expression.syntax(), "expression");
    }
}
