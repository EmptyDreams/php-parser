package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.IrExpression;
import top.kmar.php.ir.IrParameter;
import top.kmar.php.model.NameForm;
import top.kmar.php.model.NameReference;
import top.kmar.php.model.TypeReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 具名函数、闭包和方法共用的签名转换；默认值直接进入 IR，不经过声明层的 AST 包装。 */
final class CallableSignatureConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;

    CallableSignatureConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
    }

    List<IrParameter> parameters(NodeListNodeParameter list, String path) {
        var values = context.elements(list.getValue(), list, path);
        var result = new ArrayList<IrParameter>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var parameter = values.get(i);
            String parameterPath = path + "[" + i + "]";
            if (!(parameter instanceof NodeParameter.Param || parameter instanceof NodeParameter.ParamWithDefault)) {
                throw context.error(parameter, parameterPath, "无法识别的参数结构");
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

    @Nullable TypeReference returnType(NodeReturnType node, String path) {
        if (node.getClass() == NodeReturnType.class) return null;
        if (!(node instanceof NodeReturnType.ReturnType)) {
            throw context.error(node, path, "无法识别的返回类型包装");
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

    boolean marker(@Nullable NodeString marker, String expected, AstNode origin, String path) {
        if (marker == null) return false;
        if (!context.text(marker, origin, path).equals(expected)) {
            throw context.error(origin, path, "预期语法标记 " + expected);
        }
        return true;
    }
}
