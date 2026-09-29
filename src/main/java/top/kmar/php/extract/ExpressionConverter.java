package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.NameReference;

import java.util.ArrayList;
import java.util.List;
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
            case NodeExprWithoutVariable.New ignored -> newExpression(
                    context.required(node.getNewExpr(), node, path + ".newExpr"), path + ".newExpr");
            case NodeExprWithoutVariable.Clone ignored -> new IrClone(
                    convert(context.required(node.getExpr(), node, path + ".expr"), path + ".expr"),
                    context.source(node));
            case NodeExprWithoutVariable.Instanceof ignored -> instanceOf(node, path);
            case NodeExprWithoutVariable.Binary ignored -> binary(node, path);
            case NodeExprWithoutVariable.Unary ignored -> unary(node, path);
            case NodeExprWithoutVariable.Cast ignored -> cast(node, path);
            case NodeExprWithoutVariable.InternalFunction ignored -> internalFunction(
                    context.required(node.getFunc(), node, path + ".func"), path + ".func");
            case NodeExprWithoutVariable.Print ignored -> new IrPrint(
                    convert(context.required(node.getExpr(), node, path + ".expr"), path + ".expr"),
                    context.source(node));
            case NodeExprWithoutVariable.Exit ignored -> exit(node, path);
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
            // 此已知变体也有 op/expr 字段，但不属于一元正负号，不能落入字段识别分支。
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

    private IrExpression unary(NodeExprWithoutVariable node, String path) {
        String spelling = context.text(node.getOp(), node, path + ".op");
        if (spelling.equals("@")) {
            return new IrErrorSuppress(
                    convert(context.required(node.getExpr(), node, path + ".expr"), path + ".expr"),
                    context.source(node));
        }
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

    private IrCast cast(NodeExprWithoutVariable node, String path) {
        String spelling = context.text(node.getOp(), node, path + ".op");
        int start = 1;
        int end = spelling.length() - 1;
        if (end < start || spelling.charAt(0) != '(' || spelling.charAt(end) != ')') {
            throw context.error(node, path + ".op", "无法识别的类型转换标记: " + spelling);
        }
        // 只接受词法定义中的括号及两侧空格/tab，不删除类型名称内部或其它种类的空白。
        while (start < end && isCastSpace(spelling.charAt(start))) start++;
        while (end > start && isCastSpace(spelling.charAt(end - 1))) end--;
        CastKind kind = switch (spelling.substring(start, end).toLowerCase(Locale.ROOT)) {
            case "int", "integer" -> CastKind.INTEGER;
            case "real", "double", "float" -> CastKind.FLOAT;
            case "string", "binary" -> CastKind.STRING;
            case "array" -> CastKind.ARRAY;
            case "object" -> CastKind.OBJECT;
            case "bool", "boolean" -> CastKind.BOOLEAN;
            case "unset" -> CastKind.UNSET;
            default -> throw context.error(node, path + ".op", "无法识别的类型转换标记: " + spelling);
        };
        return new IrCast(kind,
                convert(context.required(node.getExpr(), node, path + ".expr"), path + ".expr"),
                context.source(node));
    }

    private static boolean isCastSpace(char character) {
        return character == ' ' || character == '\t';
    }

    private IrExpression internalFunction(NodeInternalFunctionsInYacc node, String path) {
        if (node instanceof NodeInternalFunctionsInYacc.Isset) {
            var list = context.required(node.getVars(), node, path + ".vars");
            var values = context.elements(list.getValue(), list, path + ".vars");
            if (values.isEmpty()) throw context.error(node, path + ".vars", "isset 至少需要一个表达式");
            var expressions = new ArrayList<IrExpression>(values.size());
            for (int i = 0; i < values.size(); i++) {
                expressions.add(convert(values.get(i), path + ".vars[" + i + "]"));
            }
            return new IrIsset(expressions, context.source(node));
        }
        if (!(node instanceof NodeInternalFunctionsInYacc.IncludeOrEval)) {
            throw context.error(node, path, "无法识别的内置语言结构");
        }
        String operator = context.text(node.getOp(), node, path + ".op").toLowerCase(Locale.ROOT);
        if (!List.of("empty", "include", "include_once", "require", "require_once", "eval").contains(operator)) {
            throw context.error(node, path + ".op", "无法识别的内置语言结构标记: " + operator);
        }
        IrExpression expression = convert(context.required(node.getExpr(), node, path + ".expr"), path + ".expr");
        return switch (operator) {
            case "empty" -> new IrEmptyCheck(expression, context.source(node));
            case "eval" -> new IrEval(expression, context.source(node));
            case "include" -> new IrInclude(IncludeKind.INCLUDE, expression, context.source(node));
            case "include_once" -> new IrInclude(IncludeKind.INCLUDE_ONCE, expression, context.source(node));
            case "require" -> new IrInclude(IncludeKind.REQUIRE, expression, context.source(node));
            case "require_once" -> new IrInclude(IncludeKind.REQUIRE_ONCE, expression, context.source(node));
            default -> throw new AssertionError("已验证的内置标记: " + operator);
        };
    }

    private IrExit exit(NodeExprWithoutVariable node, String path) {
        NodeExitExpr argument = context.required(node.getArg(), node, path + ".arg");
        if (argument.getClass() == NodeExitExpr.class) return new IrExit(null, context.source(node));
        if (!(argument instanceof NodeExitExpr.ExitArgs)) {
            throw context.error(argument, path + ".arg", "无法识别的 exit 参数包装");
        }
        return new IrExit(argument.getExpr() == null ? null : convert(argument.getExpr(), path + ".arg.expr"),
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
        if (node instanceof NodeConstant.ClassConstant) {
            IrClassReference clazz = classReference(
                    context.required(node.getClazz(), node, path + ".clazz"), path + ".clazz");
            String member = context.identifier(context.required(node.getMember(), node, path + ".member"),
                    path + ".member");
            return member.equalsIgnoreCase("class") ? new IrClassName(clazz, context.source(node))
                    : new IrClassConstantReference(clazz, member, context.source(node));
        }
        if (!(node instanceof NodeConstant.NamedConstant)) {
            throw context.error(node, path, "暂不支持动态类常量引用或无法识别的常量结构");
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
        return switch (node) {
            case NodeVariable.CallableVariable ignored -> callableVariable(
                    context.required(node.getCv(), node, path + ".cv"), path + ".cv");
            case NodeVariable.PropertyAccess ignored -> new IrPropertyAccess(
                    dereferencable(context.required(node.getD(), node, path + ".d"), path + ".d"),
                    propertyName(context.required(node.getProp(), node, path + ".prop"), path + ".prop"),
                    context.source(node));
            case NodeVariable.StaticMember ignored -> staticProperty(
                    context.required(node.getSm(), node, path + ".sm"), path + ".sm");
            default -> throw context.error(node, path, "无法识别的变量结构");
        };
    }

    private IrExpression callableVariable(NodeCallableVariable node, String path) {
        return switch (node) {
            case NodeCallableVariable.SimpleVar ignored -> {
                NodeSimpleVariable simple = context.required(node.getSv(), node, path + ".sv");
                yield new IrVariable(variableName(simple, path + ".sv"), context.source(simple));
            }
            case NodeCallableVariable.FunctionCall ignored -> call(
                    context.required(node.getCall(), node, path + ".call"), path + ".call");
            case NodeCallableVariable.MethodCall ignored -> new IrMethodCall(
                    dereferencable(context.required(node.getD(), node, path + ".d"), path + ".d"),
                    propertyName(context.required(node.getProp(), node, path + ".prop"), path + ".prop"),
                    arguments(context.required(node.getArgs(), node, path + ".args"), path + ".args"),
                    context.source(node));
            case NodeCallableVariable.Index ignored -> new IrIndex(
                    dereferencable(context.required(node.getD(), node, path + ".d"), path + ".d"),
                    readIndex(node.getOffset(), node, path + ".offset"), context.source(node));
            case NodeCallableVariable.ConstantIndex ignored -> new IrIndex(
                    constant(context.required(node.getC(), node, path + ".c"), path + ".c"),
                    readIndex(node.getOffset(), node, path + ".offset"), context.source(node));
            case NodeCallableVariable.CurlyIndex ignored -> new IrIndex(
                    dereferencable(context.required(node.getD(), node, path + ".d"), path + ".d"),
                    readIndex(node.getE(), node, path + ".e"), context.source(node));
            default -> throw context.error(node, path, "无法识别的可调用变量结构");
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
            default -> throw context.error(node, path, "无法识别的访问基底表达式");
        };
    }

    private IrExpression readIndex(@Nullable NodeExpr index, AstNode origin, String path) {
        if (index == null) throw context.error(origin, path, "读取下标时不能省略索引");
        return convert(index, path);
    }

    /** 普通赋值、复合赋值、更新及 foreach 共用；调用结果只能作访问基底，不能独立赋值。 */
    IrAssignmentTarget assignmentTarget(NodeVariable node, String path) {
        return target(node, path, true);
    }

    /** 删除沿目标链禁止追加，但不限制下标、实参中独立表达式的写操作。 */
    IrAssignmentTarget unsetTarget(NodeVariable node, String path) {
        return target(node, path, false);
    }

    private IrAssignmentTarget target(NodeVariable node, String path, boolean allowAppend) {
        IrWriteBase base = variableWriteBase(node, path, allowAppend);
        if (base instanceof IrAssignmentTarget target) return target;
        throw context.error(node, path, "调用结果不能独立作为可写目标");
    }

    private IrWriteBase variableWriteBase(NodeVariable node, String path, boolean allowAppend) {
        return switch (node) {
            case NodeVariable.CallableVariable ignored -> callableWriteBase(
                    context.required(node.getCv(), node, path + ".cv"), path + ".cv", allowAppend);
            case NodeVariable.PropertyAccess ignored -> new IrPropertyTarget(
                    dereferencableWriteBase(context.required(node.getD(), node, path + ".d"), path + ".d", allowAppend),
                    propertyName(context.required(node.getProp(), node, path + ".prop"), path + ".prop"),
                    context.source(node));
            case NodeVariable.StaticMember ignored -> staticPropertyTarget(
                    context.required(node.getSm(), node, path + ".sm"), path + ".sm");
            default -> throw context.error(node, path, "无法识别的可写访问结构");
        };
    }

    private IrWriteBase callableWriteBase(NodeCallableVariable node, String path, boolean allowAppend) {
        return switch (node) {
            case NodeCallableVariable.FunctionCall ignored -> expressionWriteBase(callableVariable(node, path));
            case NodeCallableVariable.MethodCall ignored -> expressionWriteBase(callableVariable(node, path));
            case NodeCallableVariable.SimpleVar ignored -> {
                NodeSimpleVariable simple = context.required(node.getSv(), node, path + ".sv");
                yield new IrVariableTarget(variableName(simple, path + ".sv"), context.source(simple));
            }
            case NodeCallableVariable.Index ignored -> new IrIndexTarget(
                    dereferencableWriteBase(context.required(node.getD(), node, path + ".d"), path + ".d", allowAppend),
                    targetIndex(node.getOffset(), node, path + ".offset", allowAppend),
                    context.source(node));
            case NodeCallableVariable.CurlyIndex ignored -> new IrIndexTarget(
                    dereferencableWriteBase(context.required(node.getD(), node, path + ".d"), path + ".d", allowAppend),
                    convert(context.required(node.getE(), node, path + ".e"), path + ".e"),
                    context.source(node));
            default -> throw context.error(node, path, "暂不支持的写入基底");
        };
    }

    private @Nullable IrExpression targetIndex(@Nullable NodeExpr index, AstNode origin, String path,
                                              boolean allowAppend) {
        if (index == null) {
            if (!allowAppend) throw context.error(origin, path, "删除目标的下标不能省略索引");
            return null;
        }
        return convert(index, path);
    }

    private IrExpressionWriteBase expressionWriteBase(IrExpression expression) {
        // 此处只接收已按读取规则转换的调用，其接收者、实参不会继承外层写上下文。
        return new IrExpressionWriteBase(expression, expression.source());
    }

    private IrWriteBase dereferencableWriteBase(NodeDereferencable node, String path, boolean allowAppend) {
        return switch (node) {
            case NodeDereferencable.Var ignored -> variableWriteBase(
                    context.required(node.getV(), node, path + ".v"), path + ".v", allowAppend);
            case NodeDereferencable.Paren ignored -> expressionWriteBase(
                    context.required(node.getE(), node, path + ".e"), path + ".e", allowAppend);
            default -> throw context.error(node, path, "暂不支持的写入基底");
        };
    }

    private IrWriteBase expressionWriteBase(NodeExpr node, String path, boolean allowAppend) {
        return switch (node) {
            case NodeExpr.VariableExpr ignored -> variableWriteBase(
                    context.required(node.getV(), node, path + ".v"), path + ".v", allowAppend);
            case NodeExpr.ExprWithoutVariable ignored -> {
                NodeExprWithoutVariable expression = context.required(node.getEv(), node, path + ".ev");
                if (!(expression instanceof NodeExprWithoutVariable.Paren)) {
                    throw context.error(expression, path + ".ev", "暂不支持的写入基底");
                }
                yield expressionWriteBase(context.required(expression.getExpr(), expression, path + ".ev.expr"),
                        path + ".ev.expr", allowAppend);
            }
            default -> throw context.error(node, path, "无法识别的可写基底表达式包装");
        };
    }

    private IrStaticPropertyAccess staticProperty(NodeStaticMember node, String path) {
        requireStaticProperty(node, path);
        return new IrStaticPropertyAccess(
                classReference(context.required(node.getClazz(), node, path + ".clazz"), path + ".clazz"),
                variableName(context.required(node.getVar(), node, path + ".var"), path + ".var"),
                context.source(node));
    }

    private IrStaticPropertyTarget staticPropertyTarget(NodeStaticMember node, String path) {
        requireStaticProperty(node, path);
        return new IrStaticPropertyTarget(
                classReference(context.required(node.getClazz(), node, path + ".clazz"), path + ".clazz"),
                variableName(context.required(node.getVar(), node, path + ".var"), path + ".var"),
                context.source(node));
    }

    private void requireStaticProperty(NodeStaticMember node, String path) {
        if (!(node instanceof NodeStaticMember.StaticProperty)) {
            throw context.error(node, path, "暂不支持动态类名或无法识别的静态属性结构");
        }
    }

    private String variableName(NodeSimpleVariable node, String path) {
        if (!(node instanceof NodeSimpleVariable.NamedVar)) {
            throw context.error(node, path, "暂不支持变量变量或间接变量");
        }
        return context.text(node.getVar(), node, path + ".var");
    }

    private String propertyName(NodePropertyName node, String path) {
        if (!(node instanceof NodePropertyName.Name)) {
            throw context.error(node, path, "暂不支持动态属性／方法名或无法识别的成员名称");
        }
        return context.text(node.getName(), node, path + ".name");
    }

    private String memberName(NodeMemberName node, String path) {
        if (!(node instanceof NodeMemberName.IdentifierName)) {
            throw context.error(node, path, "暂不支持动态静态方法名或无法识别的成员名称");
        }
        return context.identifier(context.required(node.getIdent(), node, path + ".ident"), path + ".ident");
    }

    private IrClassReference classReference(NodeClassNameReference node, String path) {
        if (!(node instanceof NodeClassNameReference.ClassName)) {
            throw context.error(node, path, "暂不支持动态类名或无法识别的类引用");
        }
        return classReference(context.required(node.getClazz(), node, path + ".clazz"), path + ".clazz");
    }

    private IrClassReference classReference(NodeClassName node, String path) {
        if (node instanceof NodeClassName.StaticClass) {
            if (!context.text(node.getKw(), node, path + ".kw").equalsIgnoreCase("static")) {
                throw context.error(node, path + ".kw", "静态类引用标记与结构不一致");
            }
            return new IrSpecialClassReference(SpecialClassKind.STATIC, context.source(node));
        }
        if (!(node instanceof NodeClassName.NamedClass)) {
            throw context.error(node, path, "无法识别的类名称结构");
        }
        NameReference name = context.name(context.required(node.getN(), node, path + ".n"), path + ".n");
        if (name.form() == NameForm.UNQUALIFIED) {
            SpecialClassKind kind = switch (name.spelling().toLowerCase(Locale.ROOT)) {
                case "self" -> SpecialClassKind.SELF;
                case "parent" -> SpecialClassKind.PARENT;
                case "static" -> SpecialClassKind.STATIC;
                default -> null;
            };
            if (kind != null) return new IrSpecialClassReference(kind, context.source(node));
        }
        return new IrNamedClassReference(name, context.source(node));
    }

    private IrNew newExpression(NodeNewExpr node, String path) {
        if (!(node instanceof NodeNewExpr.New)) {
            throw context.error(node, path, "暂不支持匿名类或无法识别的实例化结构");
        }
        return new IrNew(
                classReference(context.required(node.getClazz(), node, path + ".clazz"), path + ".clazz"),
                node.getCtorArgs() == null ? List.of() : arguments(node.getCtorArgs(), path + ".ctorArgs"),
                context.source(node));
    }

    private IrInstanceOf instanceOf(NodeExprWithoutVariable node, String path) {
        if (!context.text(node.getOp(), node, path + ".op").equalsIgnoreCase("instanceof")) {
            throw context.error(node, path + ".op", "instanceof 运算符与结构不一致");
        }
        return new IrInstanceOf(
                convert(context.required(node.getLeft(), node, path + ".left"), path + ".left"),
                classReference(context.required(node.getClassRef(), node, path + ".classRef"), path + ".classRef"),
                context.source(node));
    }

    private IrExpression call(NodeFunctionCall node, String path) {
        return switch (node) {
            case NodeFunctionCall.Call ignored -> new IrCall(
                    context.name(context.required(node.getN(), node, path + ".n"), path + ".n"),
                    arguments(context.required(node.getArgs(), node, path + ".args"), path + ".args"),
                    context.source(node));
            case NodeFunctionCall.StaticCall ignored -> new IrStaticCall(
                    classReference(context.required(node.getClazz(), node, path + ".clazz"), path + ".clazz"),
                    memberName(context.required(node.getMember(), node, path + ".member"), path + ".member"),
                    arguments(context.required(node.getArgs(), node, path + ".args"), path + ".args"),
                    context.source(node));
            default -> throw context.error(node, path, "暂不支持动态调用或无法识别的调用结构");
        };
    }

    private List<IrExpression> arguments(NodeArgumentList argumentList, String path) {
        if (!(argumentList instanceof NodeArgumentList.Args)) {
            throw context.error(argumentList, path, "无法识别的调用参数列表");
        }
        var list = context.required(argumentList.getArgs(), argumentList, path + ".args");
        var arguments = new ArrayList<IrExpression>();
        var values = context.elements(list.getValue(), argumentList, path + ".args");
        for (int i = 0; i < values.size(); i++) {
            NodeArgument argument = values.get(i);
            String argumentPath = path + ".args[" + i + "]";
            if (!(argument instanceof NodeArgument.Arg)) {
                throw context.error(argument, argumentPath, "暂不支持参数解包或无法识别的实参");
            }
            arguments.add(convert(context.required(argument.getArg(), argument, argumentPath + ".arg"),
                    argumentPath + ".arg"));
        }
        return arguments;
    }
}
