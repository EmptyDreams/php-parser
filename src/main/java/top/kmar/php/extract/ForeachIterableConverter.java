package top.kmar.php.extract;

import top.kmar.php.*;
import top.kmar.php.ir.*;

import java.util.Objects;

/** 引用 foreach 先按 AST 分类，再转换一次；分类失败不能通过捕获转换异常来降级。 */
final class ForeachIterableConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;

    ForeachIterableConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
    }

    IrForeachIterable convert(NodeExpr node, boolean byReference, String path) {
        if (byReference && writable(node, path, false)) {
            return new IrWritableIterable(expressions.assignmentTarget(node, path), context.source(node));
        }
        return new IrExpressionIterable(expressions.convert(node, path), context.source(node));
    }

    private boolean writable(NodeExpr node, String path, boolean allowCall) {
        return switch (node) {
            case NodeExpr.VariableExpr ignored -> writable(
                    context.required(node.getV(), node, path + ".v"), path + ".v", allowCall);
            case NodeExpr.ExprWithoutVariable ignored -> {
                var expression = context.required(node.getEv(), node, path + ".ev");
                yield expression instanceof NodeExprWithoutVariable.Paren && writable(
                        context.required(expression.getExpr(), expression, path + ".ev.expr"),
                        path + ".ev.expr", allowCall);
            }
            default -> throw context.error(node, path, "无法识别的遍历来源表达式包装");
        };
    }

    private boolean writable(NodeVariable node, String path, boolean allowCall) {
        return switch (node) {
            case NodeVariable.CallableVariable ignored -> writable(
                    context.required(node.getCv(), node, path + ".cv"), path + ".cv", allowCall);
            case NodeVariable.PropertyAccess ignored -> writable(
                    context.required(node.getD(), node, path + ".d"), path + ".d");
            case NodeVariable.StaticMember ignored -> true;
            default -> throw context.error(node, path, "无法识别的遍历来源变量结构");
        };
    }

    private boolean writable(NodeCallableVariable node, String path, boolean allowCall) {
        return switch (node) {
            case NodeCallableVariable.SimpleVar ignored -> true;
            // 裸调用只产生表达式来源，调用结果后的属性／下标访问可以产生可写来源。
            case NodeCallableVariable.FunctionCall ignored -> allowCall;
            case NodeCallableVariable.MethodCall ignored -> allowCall;
            case NodeCallableVariable.Index ignored -> writable(
                    context.required(node.getD(), node, path + ".d"), path + ".d");
            case NodeCallableVariable.CurlyIndex ignored -> writable(
                    context.required(node.getD(), node, path + ".d"), path + ".d");
            case NodeCallableVariable.ConstantIndex ignored -> false;
            default -> throw context.error(node, path, "无法识别的遍历来源访问结构");
        };
    }

    private boolean writable(NodeDereferencable node, String path) {
        return switch (node) {
            case NodeDereferencable.Var ignored -> writable(
                    context.required(node.getV(), node, path + ".v"), path + ".v", true);
            case NodeDereferencable.Paren ignored -> writable(
                    context.required(node.getE(), node, path + ".e"), path + ".e", true);
            case NodeDereferencable.Scalar ignored -> false;
            default -> throw context.error(node, path, "无法识别的遍历来源访问基底");
        };
    }
}
