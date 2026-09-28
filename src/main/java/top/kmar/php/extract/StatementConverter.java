package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.*;
import top.kmar.php.model.SyntaxBody;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 只在适配层理解语句包装和两种 if 文法，输出不再包含 CUP 节点。 */
final class StatementConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;

    StatementConverter(ConversionContext context) {
        this.context = context;
        expressions = new ExpressionConverter(context);
    }

    IrBlock body(SyntaxBody body) {
        return new IrBlock(statements(body.statements(), null, "body.statements"), body.source());
    }

    private List<IrStatement> statements(List<? extends AstNode> nodes, @Nullable AstNode origin, String path) {
        context.required(nodes, origin, path);
        var result = new ArrayList<IrStatement>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            String itemPath = path + "[" + i + "]";
            result.add(statement(context.required(nodes.get(i), origin, itemPath), itemPath));
        }
        return result;
    }

    private IrStatement statement(AstNode node, String path) {
        if (node instanceof NodeInnerStatement.Statement inner) {
            return statement(context.required(inner.getStmt(), inner, path + ".stmt"), path + ".stmt");
        }
        if (node instanceof NodeTopStatement.Statement top) {
            return statement(context.required(top.getStmt(), top, path + ".stmt"), path + ".stmt");
        }
        // 分号产生式无字段、无生成变体，精确基类是解析器表示空语句的方式。
        if (node.getClass() == NodeStatement.class) return new IrEmpty(context.source(node));
        return switch (node) {
            case NodeStatement.Block block -> innerBlock(block.getStmts(), block, path + ".stmts");
            case NodeStatement.ExpressionStatement expr -> new IrExpressionStatement(
                    expressions.convert(context.required(expr.getExpression(), expr, path + ".expression"),
                            path + ".expression"), context.source(expr));
            case NodeStatement.Return ret -> new IrReturn(ret.getValue() == null ? null
                    : expressions.convert(ret.getValue(), path + ".value"), context.source(ret));
            case NodeStatement.Echo echo -> echo(echo, path);
            case NodeStatement.If stmt -> standardIf(
                    context.required(stmt.getIfStmt(), stmt, path + ".ifStmt"), path + ".ifStmt");
            case NodeStatement.AltIf stmt -> alternateIf(
                    context.required(stmt.getAltIfStmt(), stmt, path + ".altIfStmt"), path + ".altIfStmt");
            default -> throw context.error(node, path, "不支持的语句或声明结构：" + node.getNodeName());
        };
    }

    private IrEcho echo(NodeStatement.Echo node, String path) {
        var list = context.required(node.getExprs(), node, path + ".exprs");
        var values = context.elements(list.getValue(), list, path + ".exprs");
        if (values.isEmpty()) throw context.error(node, path + ".exprs", "echo 至少需要一个表达式");
        var converted = new ArrayList<IrExpression>(values.size());
        for (int i = 0; i < values.size(); i++) {
            converted.add(expressions.convert(values.get(i), path + ".exprs[" + i + "]"));
        }
        return new IrEcho(converted, context.source(node));
    }

    /** CUP 1.1.0 的 * 空产生式返回空列表；包装和 value 均不可缺失。 */
    private IrBlock innerBlock(@Nullable NodeListNodeInnerStatement list, @Nullable AstNode origin, String path) {
        context.required(list, origin, path);
        return new IrBlock(statements(context.required(list.getValue(), list, path), list, path),
                context.source(origin));
    }

    private IrBlock statementBlock(NodeStatement node, String path) {
        IrStatement converted = statement(node, path);
        return converted instanceof IrBlock block ? block : new IrBlock(List.of(converted), converted.source());
    }

    private IrIf standardIf(NodeIfStmt node, String path) {
        // 无 else 的产生式使用 %prec，只依赖稳定的字段契约，不依赖其生成变体名。
        var current = context.required(node.getStmt(), node, path + ".stmt");
        if (!node.hasStmt()) throw context.error(node, path, "无法识别的 if 结构");
        var pending = new ArrayDeque<Map.Entry<NodeIfStmtWithoutElse, String>>();
        String branchPath = path + ".stmt";
        while (true) {
            pending.addFirst(Map.entry(current, branchPath));
            if (current instanceof NodeIfStmtWithoutElse.IfElem) break;
            if (!(current instanceof NodeIfStmtWithoutElse.ElseIfElem)) {
                throw context.error(current, branchPath, "无法识别的 if 分支结构");
            }
            current = context.required(current.getChain(), current, branchPath + ".chain");
            branchPath += ".chain";
        }
        var branches = new ArrayList<IrIfBranch>(pending.size());
        for (var entry : pending) {
            var branch = entry.getKey();
            String p = entry.getValue();
            branches.add(new IrIfBranch(
                    expressions.convert(context.required(branch.getCond(), branch, p + ".cond"), p + ".cond"),
                    statementBlock(context.required(branch.getBody(), branch, p + ".body"), p + ".body"),
                    context.source(branch)));
        }
        IrBlock elseBlock = null;
        if (node instanceof NodeIfStmt.IfElse || node.hasElseStmt()) {
            elseBlock = statementBlock(context.required(node.getElseStmt(), node, path + ".elseStmt"),
                    path + ".elseStmt");
        } else if (node.getElseStmt() != null) {
            throw context.error(node, path + ".elseStmt", "无法识别的 else 结构");
        }
        return new IrIf(branches, elseBlock, context.source(node));
    }

    private IrIf alternateIf(NodeAltIfStmt node, String path) {
        if (!(node instanceof NodeAltIfStmt.AltIf || node instanceof NodeAltIfStmt.AltIfElse)) {
            throw context.error(node, path, "无法识别的冒号式 if 结构");
        }
        var current = context.required(node.getStmt(), node, path + ".stmt");
        var pending = new ArrayDeque<Map.Entry<NodeAltIfStmtWithoutElse, String>>();
        String branchPath = path + ".stmt";
        while (true) {
            pending.addFirst(Map.entry(current, branchPath));
            if (current instanceof NodeAltIfStmtWithoutElse.AltIfElem) break;
            if (!(current instanceof NodeAltIfStmtWithoutElse.AltElseIfElem)) {
                throw context.error(current, branchPath, "无法识别的冒号式 if 分支结构");
            }
            current = context.required(current.getChain(), current, branchPath + ".chain");
            branchPath += ".chain";
        }
        var branches = new ArrayList<IrIfBranch>(pending.size());
        for (var entry : pending) {
            var branch = entry.getKey();
            String p = entry.getValue();
            branches.add(new IrIfBranch(
                    expressions.convert(context.required(branch.getCond(), branch, p + ".cond"), p + ".cond"),
                    innerBlock(context.required(branch.getStmts(), branch, p + ".stmts"),
                            branch.getStmts(), p + ".stmts"), context.source(branch)));
        }
        // 通过已知产生式区别于没有 else，不因空语句序列丢弃显式 else。
        IrBlock elseBlock = node instanceof NodeAltIfStmt.AltIfElse
                ? innerBlock(context.required(node.getStmts(), node, path + ".stmts"),
                        node.getStmts(), path + ".stmts") : null;
        return new IrIf(branches, elseBlock, context.source(node));
    }
}
