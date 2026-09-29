package top.kmar.php.extract;

import top.kmar.php.*;
import top.kmar.php.ir.*;

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
        var signatures = new CallableSignatureConverter(context, expressions);
        return new IrClosure(
                signatures.parameters(context.required(node.getParams(), node, path + ".params"), path + ".params"),
                signatures.returnType(context.required(node.getReturnType(), node, path + ".returnType"), path + ".returnType"),
                signatures.marker(node.getReturnsRef(), "&", node, path + ".returnsRef"), isStatic,
                captures(context.required(node.getUses(), node, path + ".uses"), path + ".uses"),
                // 只在遇到闭包时组装语句转换器，共享表达式转换器；构造器之间不相互创建。
                new StatementConverter(context, expressions).sequenceBlock(node.getStmts(), node, path + ".stmts"),
                context.source(node));
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
}
