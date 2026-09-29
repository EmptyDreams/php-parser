package top.kmar.php.extract;

import top.kmar.php.ir.IrBlock;
import top.kmar.php.ir.IrExpression;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;

import java.util.Objects;

/**
 * 将选定的语法体或表达式转换为独立 IR，不修改原 AST 或声明模型。
 * <p>支持基础运算、条件、循环、switch、异常处理结构、普通数组、具名及动态对象操作、属性／下标读写，
 * 动态变量与调用、实参解包，
 * 以及 isset/empty/unset、强转、错误抑制、print、文件包含、eval 和 exit/die 等内置结构；遇到不支持或损坏的结构抛出
 * {@link SyntaxConversionException}，不绑定名称、不推断类型、不执行 PHP。
 * 数字字面量在转换时按 64 位 PHP 7.2 的规则解码为 long/double，原文仍保留在 AST 中。
 * 调用方负责保留所属声明及其命名空间、导入环境。</p>
 */
public final class SyntaxConverter {
    private SyntaxConverter() {
    }

    /** 转换完整的选定语句序列；传入值不能为 null。 */
    public static IrBlock convertBody(SyntaxBody body) {
        Objects.requireNonNull(body, "body");
        var context = new ConversionContext(body.source().sourceId());
        return new StatementConverter(context).body(body);
    }

    /** 转换一个表达式；缺省默认值等应由调用方先判空。 */
    public static IrExpression convertExpression(SyntaxExpression expression) {
        Objects.requireNonNull(expression, "expression");
        var context = new ConversionContext(expression.source().sourceId());
        return new ExpressionConverter(context).convert(expression.syntax(), "expression");
    }
}
