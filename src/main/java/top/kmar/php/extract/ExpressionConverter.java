package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.NameReference;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/** 将支持的表达式转换为基础 IR；解码数值并保留 AST 分组，不求值或绑定名称。 */
final class ExpressionConverter {
    private final ConversionContext context;
    private final NumericLiteralDecoder numbers;

    ExpressionConverter(ConversionContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.numbers = new NumericLiteralDecoder(context);
    }

    IrExpression convert(AstNode node, String path) {
        if (!(node instanceof NodeExpr expression)) {
            throw context.error(node, path, "表达式根节点必须是 NodeExpr");
        }
        return switch (expression) {
            case NodeExpr.VariableExpr ignored -> variable(
                    context.required(expression.getV(), expression, path + ".v"), path + ".v");
            case NodeExpr.ExprWithoutVariable ignored -> nonVariable(
                    context.required(expression.getEv(), expression, path + ".ev"), path + ".ev");
            default -> throw context.error(expression, path, "无法识别的表达式包装");
        };
    }

    private IrExpression nonVariable(NodeExprWithoutVariable node, String path) {
        return switch (node) {
            case NodeExprWithoutVariable.Scalar ignored -> scalar(
                    context.required(node.getScalar(), node, path + ".scalar"), path + ".scalar");
            case NodeExprWithoutVariable.Paren ignored -> convert(
                    context.required(node.getExpr(), node, path + ".expr"), path + ".expr");
            case NodeExprWithoutVariable.Assign ignored -> new IrAssignment(
                    assignmentTarget(context.required(node.getTarget(), node, path + ".target"), path + ".target"),
                    convert(context.required(node.getValue(), node, path + ".value"), path + ".value"),
                    context.source(node));
            case NodeExprWithoutVariable.AssignOp ignored -> compoundAssignment(node, path);
            case NodeExprWithoutVariable.PreIncDec ignored -> update(node, path, true);
            case NodeExprWithoutVariable.PostIncDec ignored -> update(node, path, false);
            case NodeExprWithoutVariable.Binary ignored -> binary(node, path);
            case NodeExprWithoutVariable.Unary ignored -> unary(node, path);
            case NodeExprWithoutVariable.Conditional ignored -> new IrConditional(
                    convert(context.required(node.getCond(), node, path + ".cond"), path + ".cond"),
                    convert(context.required(node.getThenExpr(), node, path + ".thenExpr"), path + ".thenExpr"),
                    convert(context.required(node.getElseExpr(), node, path + ".elseExpr"), path + ".elseExpr"),
                    context.source(node));
            case NodeExprWithoutVariable.ConditionalShort ignored -> new IrConditional(
                    convert(context.required(node.getCond(), node, path + ".cond"), path + ".cond"),
                    null,
                    convert(context.required(node.getElseExpr(), node, path + ".elseExpr"), path + ".elseExpr"),
                    context.source(node));
            // 这些已知变体也有 op/expr 字段，但不属于一元正负号，不能落入字段识别分支。
            case NodeExprWithoutVariable.Cast ignored ->
                    throw context.error(node, path, "暂不支持类型转换表达式");
            case NodeExprWithoutVariable.YieldFrom ignored ->
                    throw context.error(node, path, "暂不支持 yield from 表达式");
            default -> signedUnary(node, path);
        };
    }

    private IrExpression signedUnary(NodeExprWithoutVariable node, String path) {
        // CUP 1.1.0 在 %prec 与 %namer 同用时忽略名称；只依赖稳定的 op/expr 字段。
        if (node.getOp() != null) {
            String operator = context.text(node.getOp(), node, path + ".op");
            if (operator.equals("+") || operator.equals("-")) return unary(node, path);
        }
        throw context.error(node, path, "暂不支持或无法识别的表达式形式");
    }

    private IrUnary unary(NodeExprWithoutVariable node, String path) {
        String spelling = context.text(node.getOp(), node, path + ".op");
        UnaryOperator operator = switch (spelling) {
            case "+" -> UnaryOperator.PLUS;
            case "-" -> UnaryOperator.MINUS;
            case "!" -> UnaryOperator.NOT;
            case "~" -> UnaryOperator.BITWISE_NOT;
            default -> throw context.error(node, path + ".op", "暂不支持的一元运算符: " + spelling);
        };
        return new IrUnary(operator,
                convert(context.required(node.getExpr(), node, path + ".expr"), path + ".expr"),
                context.source(node));
    }

    private IrCompoundAssignment compoundAssignment(NodeExprWithoutVariable node, String path) {
        String spelling = context.text(node.getOp(), node, path + ".op");
        CompoundAssignmentOperator operator = switch (spelling) {
            case "+=" -> CompoundAssignmentOperator.ADD;
            case "-=" -> CompoundAssignmentOperator.SUBTRACT;
            case "*=" -> CompoundAssignmentOperator.MULTIPLY;
            case "/=" -> CompoundAssignmentOperator.DIVIDE;
            case "%=" -> CompoundAssignmentOperator.MODULO;
            case "**=" -> CompoundAssignmentOperator.POWER;
            case ".=" -> CompoundAssignmentOperator.CONCAT;
            case "<<=" -> CompoundAssignmentOperator.SHIFT_LEFT;
            case ">>=" -> CompoundAssignmentOperator.SHIFT_RIGHT;
            case "&=" -> CompoundAssignmentOperator.BITWISE_AND;
            case "|=" -> CompoundAssignmentOperator.BITWISE_OR;
            case "^=" -> CompoundAssignmentOperator.BITWISE_XOR;
            default -> throw context.error(node, path + ".op", "暂不支持的复合赋值运算符: " + spelling);
        };
        return new IrCompoundAssignment(operator,
                assignmentTarget(context.required(node.getTarget(), node, path + ".target"), path + ".target"),
                convert(context.required(node.getValue(), node, path + ".value"), path + ".value"),
                context.source(node));
    }

    private IrUpdate update(NodeExprWithoutVariable node, String path, boolean prefix) {
        String spelling = context.text(node.getOp(), node, path + ".op");
        UpdateOperator operator = switch (spelling) {
            case "++" -> prefix ? UpdateOperator.PRE_INCREMENT : UpdateOperator.POST_INCREMENT;
            case "--" -> prefix ? UpdateOperator.PRE_DECREMENT : UpdateOperator.POST_DECREMENT;
            default -> throw context.error(node, path + ".op", "无法识别的自增／自减运算符: " + spelling);
        };
        return new IrUpdate(operator,
                assignmentTarget(context.required(node.getTarget(), node, path + ".target"), path + ".target"),
                context.source(node));
    }

    private IrExpression binary(NodeExprWithoutVariable node, String path) {
        String spelling = context.text(node.getOp(), node, path + ".op");
        String operator = spelling.toLowerCase(Locale.ROOT);
        IrExpression left = convert(context.required(node.getLeft(), node, path + ".left"), path + ".left");
        IrExpression right = convert(context.required(node.getRight(), node, path + ".right"), path + ".right");
        return switch (operator) {
            case "&&", "and" -> new IrLogical(LogicalOperator.AND, left, right, context.source(node));
            case "||", "or" -> new IrLogical(LogicalOperator.OR, left, right, context.source(node));
            case "??" -> new IrCoalesce(left, right, context.source(node));
            default -> new IrBinary(binaryOperator(operator, node, path + ".op"), left, right, context.source(node));
        };
    }

    private BinaryOperator binaryOperator(String spelling, AstNode node, String path) {
        return switch (spelling) {
            case "+" -> BinaryOperator.ADD;
            case "-" -> BinaryOperator.SUBTRACT;
            case "*" -> BinaryOperator.MULTIPLY;
            case "/" -> BinaryOperator.DIVIDE;
            case "%" -> BinaryOperator.MODULO;
            case "**" -> BinaryOperator.POWER;
            case "." -> BinaryOperator.CONCAT;
            case "<<" -> BinaryOperator.SHIFT_LEFT;
            case ">>" -> BinaryOperator.SHIFT_RIGHT;
            case "&" -> BinaryOperator.BITWISE_AND;
            case "|" -> BinaryOperator.BITWISE_OR;
            case "^" -> BinaryOperator.BITWISE_XOR;
            case "==" -> BinaryOperator.EQUAL;
            case "!=", "<>" -> BinaryOperator.NOT_EQUAL;
            case "===" -> BinaryOperator.IDENTICAL;
            case "!==" -> BinaryOperator.NOT_IDENTICAL;
            case "<=>" -> BinaryOperator.SPACESHIP;
            case "<" -> BinaryOperator.LESS;
            case "<=" -> BinaryOperator.LESS_EQUAL;
            case ">" -> BinaryOperator.GREATER;
            case ">=" -> BinaryOperator.GREATER_EQUAL;
            case "xor" -> BinaryOperator.LOGICAL_XOR;
            default -> throw context.error(node, path, "暂不支持的二元运算符: " + spelling);
        };
    }

    private IrExpression scalar(NodeScalar node, String path) {
        return switch (node) {
            case NodeScalar.Int ignored -> numbers.convert(node, path);
            case NodeScalar.Float ignored -> numbers.convert(node, path);
            case NodeScalar.DereferencableScalar ignored -> dereferencableScalar(
                    context.required(node.getDs(), node, path + ".ds"), path + ".ds");
            case NodeScalar.Constant ignored -> constant(
                    context.required(node.getC(), node, path + ".c"), path + ".c");
            default -> throw context.error(node, path, "暂不支持魔术常量、插值字符串或 heredoc/nowdoc");
        };
    }

    private IrExpression dereferencableScalar(NodeDereferencableScalar node, String path) {
        return switch (node) {
            case NodeDereferencableScalar.ConstantString ignored -> new IrLiteral(LiteralKind.STRING,
                    context.text(node.getStr(), node, path + ".str"), context.source(node));
            case NodeDereferencableScalar.LongArray ignored -> array(node, path, "array");
            case NodeDereferencableScalar.ShortArray ignored -> array(node, path, "[");
            default -> throw context.error(node, path, "无法识别的可下标访问标量结构");
        };
    }

    private IrArrayLiteral array(NodeDereferencableScalar node, String path, String expectedMarker) {
        String marker = context.text(node.getKw(), node, path + ".kw");
        if (!expectedMarker.equals(marker.toLowerCase(Locale.ROOT))) {
            throw context.error(node, path + ".kw", "数组字面量标记与结构不一致");
        }
        NodeArrayPairList arrayPairs = context.required(node.getItems(), node, path + ".items");
        if (!(arrayPairs instanceof NodeArrayPairList.ArrayPairList)) {
            throw context.error(arrayPairs, path + ".items", "无法识别的数组条目列表");
        }
        var list = context.required(arrayPairs.getItems(), arrayPairs, path + ".items.items");
        var slots = context.elements(list.getValue(), arrayPairs, path + ".items.items");
        if (slots.isEmpty()) {
            throw context.error(arrayPairs, path + ".items.items", "数组语法至少需要一个槽位包装");
        }
        var entries = new ArrayList<IrArrayEntry>();
        for (int i = 0; i < slots.size(); i++) {
            NodePossibleArrayPair slot = slots.get(i);
            String entryPath = path + ".items.items[" + i + "].pair";
            // 可空槽位只有未命名的生成变体，读取稳定字段，不依赖其哈希类名。
            NodeArrayPair pair = slot.getPair();
            if (pair == null) {
                // [] 是单个空槽，[value,] 则只有最后一个槽为空；其它空槽不能静默丢弃。
                if (i != slots.size() - 1) {
                    throw context.error(slot, entryPath, "数组构造中不允许空条目");
                }
                continue;
            }
            entries.add(arrayEntry(pair, entryPath));
        }
        return new IrArrayLiteral(entries, context.source(node));
    }

    private IrArrayEntry arrayEntry(NodeArrayPair node, String path) {
        return switch (node) {
            case NodeArrayPair.Value ignored -> new IrArrayEntry(null,
                    convert(context.required(node.getValue(), node, path + ".value"), path + ".value"),
                    context.source(node));
            case NodeArrayPair.KeyValue ignored -> new IrArrayEntry(
                    convert(context.required(node.getKey(), node, path + ".key"), path + ".key"),
                    convert(context.required(node.getValue(), node, path + ".value"), path + ".value"),
                    context.source(node));
            default -> throw context.error(node, path, "暂不支持引用数组条目、解构或无法识别的数组条目");
        };
    }

    private IrExpression constant(NodeConstant node, String path) {
        if (!(node instanceof NodeConstant.NamedConstant)) {
            throw context.error(node, path, "暂不支持类常量或动态常量引用");
        }
        NameReference name = context.name(context.required(node.getN(), node, path + ".n"), path + ".n");
        String spelling = name.spelling();
        String component = name.form() == NameForm.FULLY_QUALIFIED ? spelling.substring(1) : spelling;
        // 仅全局单段名称可识别为内置字面量；N\true、namespace\true 等仍是名称引用。
        if ((name.form() == NameForm.UNQUALIFIED || name.form() == NameForm.FULLY_QUALIFIED)
                && component.indexOf('\\') < 0) {
            switch (component.toLowerCase(Locale.ROOT)) {
                case "true", "false":
                    return new IrLiteral(LiteralKind.BOOLEAN, spelling, context.source(node));
                case "null":
                    return new IrLiteral(LiteralKind.NULL, spelling, context.source(node));
                default:
                    break;
            }
        }
        return new IrConstantReference(name, context.source(node));
    }

    private IrExpression variable(NodeVariable node, String path) {
        if (!(node instanceof NodeVariable.CallableVariable)) {
            throw context.error(node, path, "暂不支持属性或静态成员访问");
        }
        NodeCallableVariable callable = context.required(node.getCv(), node, path + ".cv");
        return switch (callable) {
            case NodeCallableVariable.SimpleVar ignored -> {
                NodeSimpleVariable simple = context.required(callable.getSv(), callable, path + ".cv.sv");
                yield new IrVariable(variableName(simple, path + ".cv.sv"), context.source(simple));
            }
            case NodeCallableVariable.FunctionCall ignored -> call(
                    context.required(callable.getCall(), callable, path + ".cv.call"), path + ".cv.call");
            case NodeCallableVariable.Index ignored -> new IrIndex(
                    dereferencable(context.required(callable.getD(), callable, path + ".cv.d"), path + ".cv.d"),
                    readIndex(callable.getOffset(), callable, path + ".cv.offset"), context.source(callable));
            case NodeCallableVariable.ConstantIndex ignored -> new IrIndex(
                    constant(context.required(callable.getC(), callable, path + ".cv.c"), path + ".cv.c"),
                    readIndex(callable.getOffset(), callable, path + ".cv.offset"), context.source(callable));
            case NodeCallableVariable.CurlyIndex ignored -> new IrIndex(
                    dereferencable(context.required(callable.getD(), callable, path + ".cv.d"), path + ".cv.d"),
                    readIndex(callable.getE(), callable, path + ".cv.e"), context.source(callable));
            default -> throw context.error(callable, path + ".cv", "暂不支持属性、方法调用或无法识别的变量结构");
        };
    }

    private IrExpression dereferencable(NodeDereferencable node, String path) {
        return switch (node) {
            case NodeDereferencable.Var ignored -> variable(
                    context.required(node.getV(), node, path + ".v"), path + ".v");
            case NodeDereferencable.Paren ignored -> convert(
                    context.required(node.getE(), node, path + ".e"), path + ".e");
            case NodeDereferencable.Scalar ignored -> dereferencableScalar(
                    context.required(node.getDs(), node, path + ".ds"), path + ".ds");
            default -> throw context.error(node, path, "无法识别的下标基础表达式");
        };
    }

    private IrExpression readIndex(@Nullable NodeExpr index, AstNode origin, String path) {
        if (index == null) throw context.error(origin, path, "读取下标时不能省略索引");
        return convert(index, path);
    }

    /** 普通赋值、复合赋值、更新及 foreach 共用的目标转换，目标链始终以简单变量为根。 */
    IrAssignmentTarget assignmentTarget(NodeVariable node, String path) {
        if (!(node instanceof NodeVariable.CallableVariable)) {
            throw context.error(node, path, "可写目标仅支持简单变量及其下标链");
        }
        NodeCallableVariable callable = context.required(node.getCv(), node, path + ".cv");
        return switch (callable) {
            case NodeCallableVariable.SimpleVar ignored -> {
                NodeSimpleVariable simple = context.required(callable.getSv(), callable, path + ".cv.sv");
                yield new IrVariableTarget(variableName(simple, path + ".cv.sv"), context.source(simple));
            }
            case NodeCallableVariable.Index ignored -> new IrIndexTarget(
                    dereferencableTarget(context.required(callable.getD(), callable, path + ".cv.d"), path + ".cv.d"),
                    callable.getOffset() == null ? null : convert(callable.getOffset(), path + ".cv.offset"),
                    context.source(callable));
            case NodeCallableVariable.CurlyIndex ignored -> new IrIndexTarget(
                    dereferencableTarget(context.required(callable.getD(), callable, path + ".cv.d"), path + ".cv.d"),
                    convert(context.required(callable.getE(), callable, path + ".cv.e"), path + ".cv.e"),
                    context.source(callable));
            default -> throw context.error(callable, path + ".cv", "可写目标仅支持简单变量及其下标链");
        };
    }

    private IrAssignmentTarget dereferencableTarget(NodeDereferencable node, String path) {
        return switch (node) {
            case NodeDereferencable.Var ignored -> assignmentTarget(
                    context.required(node.getV(), node, path + ".v"), path + ".v");
            case NodeDereferencable.Paren ignored -> expressionTarget(
                    context.required(node.getE(), node, path + ".e"), path + ".e");
            default -> throw context.error(node, path, "可写下标链必须以简单变量为根");
        };
    }

    private IrAssignmentTarget expressionTarget(NodeExpr node, String path) {
        return switch (node) {
            case NodeExpr.VariableExpr ignored -> assignmentTarget(
                    context.required(node.getV(), node, path + ".v"), path + ".v");
            case NodeExpr.ExprWithoutVariable ignored -> {
                NodeExprWithoutVariable expression = context.required(node.getEv(), node, path + ".ev");
                if (!(expression instanceof NodeExprWithoutVariable.Paren)) {
                    throw context.error(expression, path + ".ev", "可写下标链必须以简单变量为根");
                }
                yield expressionTarget(context.required(expression.getExpr(), expression, path + ".ev.expr"),
                        path + ".ev.expr");
            }
            default -> throw context.error(node, path, "无法识别的可写目标表达式包装");
        };
    }

    private String variableName(NodeSimpleVariable node, String path) {
        if (!(node instanceof NodeSimpleVariable.NamedVar)) {
            throw context.error(node, path, "暂不支持变量变量或间接变量");
        }
        return context.text(node.getVar(), node, path + ".var");
    }

    private IrCall call(NodeFunctionCall node, String path) {
        if (!(node instanceof NodeFunctionCall.Call)) {
            throw context.error(node, path, "暂不支持动态调用或静态方法调用");
        }
        NameReference name = context.name(context.required(node.getN(), node, path + ".n"), path + ".n");
        NodeArgumentList argumentList = context.required(node.getArgs(), node, path + ".args");
        if (!(argumentList instanceof NodeArgumentList.Args)) {
            throw context.error(argumentList, path + ".args", "无法识别的调用参数列表");
        }
        var list = context.required(argumentList.getArgs(), argumentList, path + ".args.args");
        var arguments = new ArrayList<IrExpression>();
        var values = context.elements(list.getValue(), argumentList, path + ".args.args");
        for (int i = 0; i < values.size(); i++) {
            NodeArgument argument = values.get(i);
            String argumentPath = path + ".args.args[" + i + "]";
            if (!(argument instanceof NodeArgument.Arg)) {
                throw context.error(argument, argumentPath, "暂不支持参数解包或无法识别的实参");
            }
            arguments.add(convert(context.required(argument.getArg(), argument, argumentPath + ".arg"),
                    argumentPath + ".arg"));
        }
        return new IrCall(name, arguments, context.source(node));
    }
}
