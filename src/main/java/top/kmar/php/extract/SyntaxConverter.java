package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.ir.IrBlock;
import top.kmar.php.ir.IrExpression;
import top.kmar.php.ir.IrFile;
import top.kmar.php.model.SyntaxBody;
import top.kmar.php.model.SyntaxExpression;

import java.util.Objects;

/**
 * 将完整文件、选定的语法体或表达式转换为独立 IR，不修改原 AST 或声明模型。
 * <p>支持基础运算、条件、循环、switch、异常处理结构、含引用项的数组、引用赋值、解构、引用 foreach，
 * 具名及动态对象操作、匿名类及其成员／trait 适配规则、属性／下标读写，
 * 具名函数、类、接口及 trait 声明（包括嵌套声明），
 * 动态变量与调用、实参解包、闭包与 yield/yield from、字符串模板、反引号命令及魔术常量，
 * 以及 global、局部 static、declare、goto／标签和 isset/empty/unset、强转、错误抑制、print、文件包含、eval、exit/die；
 * 遇到不支持或损坏的结构抛出
 * {@link SyntaxConversionException}，不绑定名称、不推断类型、不执行 PHP。
 * 数字字面量按 64 位 PHP 7.2 规则解码为 long/double，字符串文本按 UTF-8 和 PHP 7.2 转义规则
 * 解码为不可变字节值；布尔字面量保存为 boolean，PHP null 使用独立节点，与字段缺省的 Java null 区分。
 * 字面量原文仍保留在 AST 中，插值及魔术常量不求值，反引号命令不执行。
 * declare 的 ticks、encoding 和 strict_types 统一为枚举，未知指令明确失败；指令值仍为表达式，不执行设置。
 * 具名声明保留原语句位置和名称拼写，不注册符号或判定生效时机。
 * 参数及返回类型使用独立 IrTypeReference，区分内置、self／parent 和具名类型，不推导隐式可空性或绑定类名。
 * 类和成员修饰符转换为 Visibility 与布尔标志，补 public 及接口方法的隐式 abstract；
 * trait 别名的可空可见性表示是否调整。重复、冲突或不适用修饰符明确失败，原列表仍由 AST／声明层保留。
 * 修饰符规范化不检查方法体与抽象标志的关联、成员所属类型合法性、继承或 trait 合并。
 * 未绑定的名称使用 IrNameReference，名称主体不含外层反斜杠或 namespace 前缀，限定形式及完整名称来源独立保留。
 * 具名调用目标、调用结果写入基底和变量写目标的来源直接委托内部对象，不重复存储。
 * 文件入口保留独立命名空间区段、原位导入、顶层常量和停止解析标记，不建立声明索引；
 * 导入项使用独立 IrImport，直接保存有效别名，不保留是否显式书写 as 的区别，也不绑定导入目标。
 * 不保存词法器已丢弃的 halt 尾部数据或计算字节偏移。
 * 使用局部入口时，调用方仍负责保留所属声明及其命名空间、导入环境。</p>
 */
public final class SyntaxConverter {
    private SyntaxConverter() {
    }

    /** 直接转换 program AST，不需要先提取声明；来源标识缺省为 null。 */
    public static IrFile convertFile(AstNode syntax) {
        return convertFile(syntax, null);
    }

    /** sourceId 是不透明标识；错误根节点（包括 null）及损坏／不支持的子树均明确失败。 */
    public static IrFile convertFile(AstNode syntax, @Nullable String sourceId) {
        var context = new ConversionContext(sourceId);
        return new FileConverter(context, new ExpressionConverter(context)).convert(syntax);
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
