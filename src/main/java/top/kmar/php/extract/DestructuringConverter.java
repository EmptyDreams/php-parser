package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import top.kmar.php.*;
import top.kmar.php.ir.*;

import java.util.ArrayList;
import java.util.Objects;

/** 在绑定位置直接解释数组槽位，不经过数组值 IR，也不执行取值或赋值。 */
final class DestructuringConverter {
    enum Style { LIST, SHORT_ARRAY }

    private final ConversionContext context;
    private final ExpressionConverter expressions;

    DestructuringConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
    }

    IrDestructuringPattern convert(NodeArrayPairList node, Style style, String path) {
        if (!(node instanceof NodeArrayPairList.ArrayPairList)) {
            throw context.error(node, path, "无法识别的解构条目列表");
        }
        var list = context.required(node.getItems(), node, path + ".items");
        var slots = context.elements(list.getValue(), list, path + ".items");
        if (slots.isEmpty()) throw context.error(node, path + ".items", "解构语法至少需要一个槽位包装");
        int size = slots.size();
        // 可空槽位使用稳定字段；只去掉尾逗号对应的一个槽，不删除真正的跳过项。
        if (slots.getLast().getPair() == null) size--;
        var result = new ArrayList<IrDestructuringSlot>(size);
        Boolean keyed = null;
        NodePossibleArrayPair firstHole = null;
        String firstHolePath = null;
        for (int i = 0; i < size; i++) {
            NodePossibleArrayPair slot = slots.get(i);
            String itemPath = path + ".items[" + i + "].pair";
            NodeArrayPair pair = slot.getPair();
            if (pair == null) {
                if (Boolean.TRUE.equals(keyed)) {
                    throw context.error(slot, itemPath, "带键解构不允许跳过项");
                }
                if (firstHole == null) {
                    firstHole = slot;
                    firstHolePath = itemPath;
                }
                result.add(new IrDestructuringSlot(null, null, context.source(slot)));
                continue;
            }
            boolean hasKey = switch (pair) {
                case NodeArrayPair.Value ignored -> false;
                case NodeArrayPair.ListValue ignored -> false;
                case NodeArrayPair.KeyValue ignored -> true;
                case NodeArrayPair.KeyListValue ignored -> true;
                case NodeArrayPair.RefValue ignored ->
                        throw context.error(pair, itemPath, "PHP 7.2 不支持引用解构项");
                case NodeArrayPair.KeyRefValue ignored ->
                        throw context.error(pair, itemPath, "PHP 7.2 不支持引用解构项");
                default -> throw context.error(pair, itemPath, "无法识别的解构条目");
            };
            if (keyed != null && keyed != hasKey) {
                throw context.error(pair, itemPath, "解构不能混用带键项与无键项");
            }
            if (hasKey && firstHole != null) {
                throw context.error(firstHole, firstHolePath, "带键解构不允许跳过项");
            }
            keyed = hasKey;
            IrExpression key = hasKey ? expressions.convert(
                    context.required(pair.getKey(), pair, itemPath + ".key"), itemPath + ".key") : null;
            IrBindingTarget target;
            if (pair instanceof NodeArrayPair.ListValue || pair instanceof NodeArrayPair.KeyListValue) {
                requireStyle(style, Style.LIST, pair, itemPath);
                target = convert(context.required(pair.getItems(), pair, itemPath + ".items"),
                        style, itemPath + ".items");
            } else {
                target = target(context.required(pair.getValue(), pair, itemPath + ".value"),
                        style, itemPath + ".value");
            }
            result.add(new IrDestructuringSlot(key, target, context.source(pair)));
        }
        if (keyed == null) throw context.error(node, path, "解构至少需要一个非跳过项");
        return new IrDestructuringPattern(result, context.source(node));
    }

    private IrBindingTarget target(NodeExpr node, Style style, String path) {
        if (node instanceof NodeExpr.VariableExpr) {
            return expressions.assignmentTarget(context.required(node.getV(), node, path + ".v"), path + ".v");
        }
        if (!(node instanceof NodeExpr.ExprWithoutVariable)) {
            throw context.error(node, path, "无法识别的解构目标表达式包装");
        }
        var expression = context.required(node.getEv(), node, path + ".ev");
        path += ".ev";
        if (expression instanceof NodeExprWithoutVariable.Paren) {
            return target(context.required(expression.getExpr(), expression, path + ".expr"), style, path + ".expr");
        }
        if (expression instanceof NodeExprWithoutVariable.Scalar) {
            var scalar = context.required(expression.getScalar(), expression, path + ".scalar");
            if (scalar instanceof NodeScalar.DereferencableScalar) {
                var array = context.required(scalar.getDs(), scalar, path + ".scalar.ds");
                String arrayPath = path + ".scalar.ds";
                if (array instanceof NodeDereferencableScalar.ShortArray) {
                    if (!context.text(array.getKw(), array, arrayPath + ".kw").equals("[")) {
                        throw context.error(array, arrayPath + ".kw", "短数组解构标记与结构不一致");
                    }
                    requireStyle(style, Style.SHORT_ARRAY, array, arrayPath);
                    return convert(context.required(array.getItems(), array, arrayPath + ".items"),
                            style, arrayPath + ".items");
                }
                if (array instanceof NodeDereferencableScalar.LongArray) {
                    throw context.error(array, arrayPath, "array(...) 不能作为解构模式");
                }
            }
        }
        throw context.error(expression, path, "解构目标必须是可写位置或嵌套解构模式");
    }

    private void requireStyle(Style expected, Style actual, AstNode node, String path) {
        if (expected != actual) throw context.error(node, path, "嵌套解构不能混用 list() 与 []");
    }
}
