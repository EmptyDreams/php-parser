package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.NameReference;
import top.kmar.php.model.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 闭包的签名、显式捕获和体均直接转为 IR，不经过含有 AST 的声明参数模型。 */
final class ClosureConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;

    ClosureConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
    }

    IrClosure convert(NodeExprWithoutVariable node, String path) {
        boolean isStatic;
        if (node instanceof NodeExprWithoutVariable.StaticClosure) {
            if (!context.text(node.getKw(), node, path + ".kw").equalsIgnoreCase("static")) {
                throw context.error(node, path + ".kw", "无法识别的 static 闭包标记");
            }
            isStatic = true;
        } else if (node instanceof NodeExprWithoutVariable.Closure) {
            isStatic = false;
        } else {
            throw context.error(node, path, "无法识别的闭包结构");
        }
        return new IrClosure(
                parameters(context.required(node.getParams(), node, path + ".params"), path + ".params"),
                returnType(context.required(node.getReturnType(), node, path + ".returnType"), path + ".returnType"),
                marker(node.getReturnsRef(), "&", node, path + ".returnsRef"), isStatic,
                captures(context.required(node.getUses(), node, path + ".uses"), path + ".uses"),
                // 只在遇到闭包时组装语句转换器，共享表达式转换器；构造器之间不相互创建。
                new StatementConverter(context, expressions).sequenceBlock(node.getStmts(), node, path + ".stmts"),
                context.source(node));
    }

    private List<IrParameter> parameters(NodeListNodeParameter list, String path) {
        var values = context.elements(list.getValue(), list, path);
        var result = new ArrayList<IrParameter>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var parameter = values.get(i);
            String parameterPath = path + "[" + i + "]";
            if (!(parameter instanceof NodeParameter.Param || parameter instanceof NodeParameter.ParamWithDefault)) {
                throw context.error(parameter, parameterPath, "无法识别的闭包参数结构");
            }
            String name = context.text(parameter.getVar(), parameter, parameterPath + ".var");
            TypeReference declaredType = parameter.getType() == null ? null
                    : type(parameter.getType(), parameterPath + ".type");
            boolean byReference = marker(parameter.getByRef(), "&", parameter, parameterPath + ".byRef");
            boolean variadic = marker(parameter.getVariadic(), "...", parameter, parameterPath + ".variadic");
            IrExpression defaultValue = parameter instanceof NodeParameter.ParamWithDefault
                    ? expressions.convert(context.required(parameter.getDefaultValue(), parameter,
                    parameterPath + ".defaultValue"), parameterPath + ".defaultValue") : null;
            result.add(new IrParameter(name, declaredType, byReference, variadic, defaultValue, context.source(parameter)));
        }
        return result;
    }

    private @Nullable TypeReference returnType(NodeReturnType node, String path) {
        if (node.getClass() == NodeReturnType.class) return null;
        if (!(node instanceof NodeReturnType.ReturnType)) {
            throw context.error(node, path, "无法识别的闭包返回类型包装");
        }
        return type(context.required(node.getT(), node, path + ".t"), path + ".t");
    }

    private TypeReference type(NodeTypeExpr node, String path) {
        boolean nullable;
        if (node instanceof NodeTypeExpr.NullableType) {
            nullable = marker(context.required(node.getOp(), node, path + ".op"), "?", node, path + ".op");
        } else if (node instanceof NodeTypeExpr.Type) {
            nullable = false;
        } else {
            throw context.error(node, path, "无法识别的类型声明结构");
        }
        NodeType type = context.required(node.getT(), node, path + ".t");
        NameReference name;
        if (type instanceof NodeType.NameType) {
            name = context.name(context.required(type.getN(), type, path + ".t.n"), path + ".t.n");
        } else if (type instanceof NodeType.ArrayType || type instanceof NodeType.CallableType) {
            String keyword = context.text(type.getKw(), type, path + ".t.kw");
            String expected = type instanceof NodeType.ArrayType ? "array" : "callable";
            if (!keyword.equalsIgnoreCase(expected)) {
                throw context.error(type, path + ".t.kw", "无法识别的类型关键字: " + keyword);
            }
            name = new NameReference(keyword, NameForm.UNQUALIFIED, context.source(type.getKw()));
        } else {
            throw context.error(type, path + ".t", "无法识别的类型名称结构");
        }
        return new TypeReference(name, nullable, context.source(node));
    }

    private List<IrClosureCapture> captures(NodeLexicalVars node, String path) {
        if (node.getClass() == NodeLexicalVars.class) return List.of();
        if (!(node instanceof NodeLexicalVars.ClosureUses)) {
            throw context.error(node, path, "无法识别的闭包捕获包装");
        }
        var list = context.required(node.getVars(), node, path + ".vars");
        var values = context.elements(list.getValue(), list, path + ".vars");
        if (values.isEmpty()) throw context.error(node, path + ".vars", "显式 use 至少需要一个捕获项");
        var result = new ArrayList<IrClosureCapture>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var capture = values.get(i);
            String capturePath = path + ".vars[" + i + "]";
            boolean byReference = switch (capture) {
                case NodeLexicalVar.ByVal ignored -> false;
                case NodeLexicalVar.ByRef ignored -> true;
                default -> throw context.error(capture, capturePath, "无法识别的闭包捕获项");
            };
            result.add(new IrClosureCapture(context.text(capture.getVar(), capture, capturePath + ".var"),
                    byReference, context.source(capture)));
        }
        return result;
    }

    private boolean marker(@Nullable NodeString marker, String expected, AstNode origin, String path) {
        if (marker == null) return false;
        if (!context.text(marker, origin, path).equals(expected)) {
            throw context.error(origin, path, "预期语法标记 " + expected);
        }
        return true;
    }
}
