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
            IrTypeReference declaredType = parameter.getType() == null ? null
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

    @Nullable IrTypeReference returnType(NodeReturnType node, String path) {
        if (node.getClass() == NodeReturnType.class) return null;
        if (!(node instanceof NodeReturnType.ReturnType)) {
            throw context.error(node, path, "无法识别的返回类型包装");
        }
        return type(context.required(node.getT(), node, path + ".t"), path + ".t");
    }

    private IrTypeReference type(NodeTypeExpr node, String path) {
        boolean nullable;
        if (node instanceof NodeTypeExpr.NullableType) {
            nullable = marker(context.required(node.getOp(), node, path + ".op"), "?", node, path + ".op");
        } else if (node instanceof NodeTypeExpr.Type) {
            nullable = false;
        } else {
            throw context.error(node, path, "无法识别的类型声明结构");
        }
        NodeType type = context.required(node.getT(), node, path + ".t");
        IrTypeName name;
        if (type instanceof NodeType.NameType) {
            name = typeName(context.name(context.required(type.getN(), type, path + ".t.n"), path + ".t.n"));
        } else if (type instanceof NodeType.ArrayType || type instanceof NodeType.CallableType) {
            String keyword = context.text(type.getKw(), type, path + ".t.kw");
            String expected = type instanceof NodeType.ArrayType ? "array" : "callable";
            if (!expected.equals(normalizedAsciiName(keyword))) {
                throw context.error(type, path + ".t.kw", "无法识别的类型关键字: " + keyword);
            }
            name = new IrBuiltinType(type instanceof NodeType.ArrayType ? BuiltinTypeKind.ARRAY
                    : BuiltinTypeKind.CALLABLE, context.source(type.getKw()));
        } else {
            throw context.error(type, path + ".t", "无法识别的类型名称结构");
        }
        return new IrTypeReference(name, nullable, context.source(node));
    }

    private IrTypeName typeName(NameReference name) {
        if (name.form() != NameForm.UNQUALIFIED && name.form() != NameForm.NAMESPACE_RELATIVE) {
            return new IrNamedType(name);
        }
        String component = name.form() == NameForm.NAMESPACE_RELATIVE
                ? name.spelling().substring(name.spelling().indexOf('\\') + 1) : name.spelling();
        String spelling = normalizedAsciiName(component);
        if (spelling == null) return new IrNamedType(name);
        // PHP 7.2 对 namespace\self／namespace\parent 也使用所属类上下文。
        if (spelling.equals("self")) return new IrSpecialType(SpecialTypeKind.SELF, name.source());
        if (spelling.equals("parent")) return new IrSpecialType(SpecialTypeKind.PARENT, name.source());
        if (name.form() != NameForm.UNQUALIFIED) return new IrNamedType(name);
        return switch (spelling) {
            case "int" -> new IrBuiltinType(BuiltinTypeKind.INTEGER, name.source());
            case "float" -> new IrBuiltinType(BuiltinTypeKind.FLOAT, name.source());
            case "string" -> new IrBuiltinType(BuiltinTypeKind.STRING, name.source());
            case "bool" -> new IrBuiltinType(BuiltinTypeKind.BOOLEAN, name.source());
            case "array" -> new IrBuiltinType(BuiltinTypeKind.ARRAY, name.source());
            case "callable" -> new IrBuiltinType(BuiltinTypeKind.CALLABLE, name.source());
            case "iterable" -> new IrBuiltinType(BuiltinTypeKind.ITERABLE, name.source());
            case "object" -> new IrBuiltinType(BuiltinTypeKind.OBJECT, name.source());
            case "void" -> new IrBuiltinType(BuiltinTypeKind.VOID, name.source());
            default -> new IrNamedType(name);
        };
    }

    /** 内置及特殊名称仅作 ASCII 大小写归一，Unicode 类名保持原样。 */
    private static @Nullable String normalizedAsciiName(String spelling) {
        for (int i = 0; i < spelling.length(); i++) {
            if (spelling.charAt(i) > 0x7f) return null;
        }
        return spelling.toLowerCase(Locale.ROOT);
    }

    boolean marker(@Nullable NodeString marker, String expected, AstNode origin, String path) {
        if (marker == null) return false;
        if (!context.text(marker, origin, path).equals(expected)) {
            throw context.error(origin, path, "预期语法标记 " + expected);
        }
        return true;
    }
}
