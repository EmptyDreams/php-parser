package top.kmar.php.extract;

import java_cup.runtime.AstNode;
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
            case NodeScalar.DereferencableScalar ignored -> stringLiteral(
                    context.required(node.getDs(), node, path + ".ds"), path + ".ds");
            case NodeScalar.Constant ignored -> constant(
                    context.required(node.getC(), node, path + ".c"), path + ".c");
            default -> throw context.error(node, path, "暂不支持魔术常量、插值字符串或 heredoc/nowdoc");
        };
    }

    private IrLiteral stringLiteral(NodeDereferencableScalar node, String path) {
        if (!(node instanceof NodeDereferencableScalar.ConstantString)) {
            throw context.error(node, path, "暂不支持数组字面量或无法识别的标量结构");
        }
        return new IrLiteral(LiteralKind.STRING,
                context.text(node.getStr(), node, path + ".str"), context.source(node));
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
            default -> throw context.error(callable, path + ".cv", "暂不支持下标、属性或方法调用");
        };
    }

    private IrVariableTarget assignmentTarget(NodeVariable node, String path) {
        if (!(node instanceof NodeVariable.CallableVariable)) {
            throw context.error(node, path, "赋值目标仅支持简单命名变量");
        }
        NodeCallableVariable callable = context.required(node.getCv(), node, path + ".cv");
        if (!(callable instanceof NodeCallableVariable.SimpleVar)) {
            throw context.error(callable, path, "赋值目标仅支持简单命名变量");
        }
        NodeSimpleVariable simple = context.required(callable.getSv(), callable, path + ".cv.sv");
        return new IrVariableTarget(variableName(simple, path + ".cv.sv"), context.source(simple));
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
