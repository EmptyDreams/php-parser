package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.*;
import top.kmar.php.ir.*;
import top.kmar.php.model.NameReference;
import top.kmar.php.model.SyntaxBody;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 只在适配层理解语句包装和普通／冒号文法，输出不再包含 CUP 节点。 */
final class StatementConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;

    StatementConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
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
            case NodeStatement.Unset unset -> unset(unset, path);
            case NodeStatement.Global stmt -> global(stmt, path);
            case NodeStatement.Static stmt -> staticVariables(stmt, path);
            case NodeStatement.Declare stmt -> declare(stmt, path);
            case NodeStatement.Goto stmt -> new IrGoto(
                    context.text(stmt.getLabel(), stmt, path + ".label"), context.source(stmt));
            case NodeStatement.Label stmt -> new IrLabel(
                    context.text(stmt.getLabel(), stmt, path + ".label"), context.source(stmt));
            case NodeStatement.If stmt -> standardIf(
                    context.required(stmt.getIfStmt(), stmt, path + ".ifStmt"), path + ".ifStmt");
            case NodeStatement.AltIf stmt -> alternateIf(
                    context.required(stmt.getAltIfStmt(), stmt, path + ".altIfStmt"), path + ".altIfStmt");
            case NodeStatement.While stmt -> new IrWhile(
                    expressions.convert(context.required(stmt.getCond(), stmt, path + ".cond"), path + ".cond"),
                    whileBody(context.required(stmt.getWhileBody(), stmt, path + ".whileBody"), path + ".whileBody"),
                    context.source(stmt));
            case NodeStatement.DoWhile stmt -> new IrDoWhile(
                    statementBlock(context.required(stmt.getBody(), stmt, path + ".body"), path + ".body"),
                    expressions.convert(context.required(stmt.getCond(), stmt, path + ".cond"), path + ".cond"),
                    context.source(stmt));
            case NodeStatement.For stmt -> new IrFor(
                    expressionList(stmt.getForInit(), stmt, path + ".forInit"),
                    expressionList(stmt.getForCond(), stmt, path + ".forCond"),
                    expressionList(stmt.getForStep(), stmt, path + ".forStep"),
                    forBody(context.required(stmt.getForBody(), stmt, path + ".forBody"), path + ".forBody"),
                    context.source(stmt));
            case NodeStatement.Foreach stmt -> foreach(stmt, path);
            case NodeStatement.ForeachKV stmt -> foreach(stmt, path);
            case NodeStatement.Break stmt -> new IrBreak(stmt.getLevels() == null ? null
                    : expressions.convert(stmt.getLevels(), path + ".levels"), context.source(stmt));
            case NodeStatement.Continue stmt -> new IrContinue(stmt.getLevels() == null ? null
                    : expressions.convert(stmt.getLevels(), path + ".levels"), context.source(stmt));
            case NodeStatement.Switch stmt -> new IrSwitch(
                    expressions.convert(context.required(stmt.getCond(), stmt, path + ".cond"), path + ".cond"),
                    switchCases(context.required(stmt.getCases(), stmt, path + ".cases"), path + ".cases"),
                    context.source(stmt));
            case NodeStatement.Try stmt -> new IrTry(
                    sequenceBlock(stmt.getStmts(), stmt, path + ".stmts"),
                    catches(context.required(stmt.getCatches(), stmt, path + ".catches"), path + ".catches"),
                    finallyBlock(context.required(stmt.getFinallyBlock(), stmt, path + ".finallyBlock"),
                            path + ".finallyBlock"), context.source(stmt));
            case NodeStatement.Throw stmt -> new IrThrow(
                    expressions.convert(context.required(stmt.getExpr(), stmt, path + ".expr"), path + ".expr"),
                    context.source(stmt));
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

    private IrUnset unset(NodeStatement.Unset node, String path) {
        var list = context.required(node.getUnsetVars(), node, path + ".unsetVars");
        var values = context.elements(list.getValue(), list, path + ".unsetVars");
        if (values.isEmpty()) throw context.error(node, path + ".unsetVars", "unset 至少需要一个目标");
        var targets = new ArrayList<IrAssignmentTarget>(values.size());
        for (int i = 0; i < values.size(); i++) {
            targets.add(expressions.unsetTarget(values.get(i), path + ".unsetVars[" + i + "]"));
        }
        return new IrUnset(targets, context.source(node));
    }

    private IrGlobal global(NodeStatement.Global node, String path) {
        var list = context.required(node.getGlobalVars(), node, path + ".globalVars");
        var values = context.elements(list.getValue(), list, path + ".globalVars");
        if (values.isEmpty()) throw context.error(node, path + ".globalVars", "global 至少需要一个变量");
        var variables = new ArrayList<IrVariableTarget>(values.size());
        for (int i = 0; i < values.size(); i++) {
            variables.add(expressions.simpleVariableTarget(values.get(i), path + ".globalVars[" + i + "]"));
        }
        return new IrGlobal(variables, context.source(node));
    }

    private IrStaticVariables staticVariables(NodeStatement.Static node, String path) {
        var list = context.required(node.getStaticVars(), node, path + ".staticVars");
        var values = context.elements(list.getValue(), list, path + ".staticVars");
        if (values.isEmpty()) throw context.error(node, path + ".staticVars", "static 至少需要一个变量");
        var variables = new ArrayList<IrStaticVariable>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var variable = values.get(i);
            String variablePath = path + ".staticVars[" + i + "]";
            if (!(variable instanceof NodeStaticVar.StaticVar || variable instanceof NodeStaticVar.StaticVarWithDefault)) {
                throw context.error(variable, variablePath, "无法识别的局部 static 变量结构");
            }
            String name = context.text(variable.getVar(), variable, variablePath + ".var");
            IrExpression initializer = variable instanceof NodeStaticVar.StaticVarWithDefault
                    ? expressions.convert(context.required(variable.getDefaultValue(), variable,
                    variablePath + ".defaultValue"), variablePath + ".defaultValue") : null;
            variables.add(new IrStaticVariable(name, initializer, context.source(variable)));
        }
        return new IrStaticVariables(variables, context.source(node));
    }

    private IrDeclare declare(NodeStatement.Declare node, String path) {
        var list = context.required(node.getDirectives(), node, path + ".directives");
        var values = context.elements(list.getValue(), list, path + ".directives");
        if (values.isEmpty()) throw context.error(node, path + ".directives", "declare 至少需要一个指令");
        var directives = new ArrayList<IrDeclareDirective>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var directive = values.get(i);
            String directivePath = path + ".directives[" + i + "]";
            if (!(directive instanceof NodeConstDecl.ConstDecl)) {
                throw context.error(directive, directivePath, "无法识别的 declare 指令结构");
            }
            directives.add(new IrDeclareDirective(
                    context.text(directive.getName(), directive, directivePath + ".name"),
                    expressions.convert(context.required(directive.getValue(), directive, directivePath + ".value"),
                            directivePath + ".value"), context.source(directive)));
        }
        return new IrDeclare(directives,
                declareBody(context.required(node.getDeclareBody(), node, path + ".declareBody"), path + ".declareBody"),
                context.source(node));
    }

    private @Nullable IrBlock declareBody(NodeDeclareStatement node, String path) {
        return switch (node) {
            case NodeDeclareStatement.Body body -> {
                var statement = context.required(body.getBody(), body, path + ".body");
                // 直接分号没有局部主体，不能与显式空块合并，也不吸收后续语句。
                yield statement.getClass() == NodeStatement.class ? null : statementBlock(statement, path + ".body");
            }
            case NodeDeclareStatement.AltBody body -> innerBlock(body.getStmts(), body, path + ".stmts");
            default -> throw context.error(node, path, "无法识别的 declare 主体结构");
        };
    }

    /** CUP 1.1.0 的 * 空产生式返回空列表；包装和 value 均不可缺失，块来源取所属包装。 */
    IrBlock innerBlock(@Nullable NodeListNodeInnerStatement list, @Nullable AstNode origin, String path) {
        context.required(list, origin, path);
        return new IrBlock(statements(context.required(list.getValue(), list, path), list, path),
                context.source(origin));
    }

    private IrBlock statementBlock(NodeStatement node, String path) {
        IrStatement converted = statement(node, path);
        return converted instanceof IrBlock block ? block : new IrBlock(List.of(converted), converted.source());
    }

    /** 分支和 try 的体使用列表自身范围，不把整个链节点的累计范围当成块范围。 */
    IrBlock sequenceBlock(@Nullable NodeListNodeInnerStatement list, AstNode origin, String path) {
        context.required(list, origin, path);
        return innerBlock(list, list, path);
    }

    private List<IrSwitchCase> switchCases(NodeSwitchCaseList node, String path) {
        if (!(node instanceof NodeSwitchCaseList.Cases || node instanceof NodeSwitchCaseList.CasesWithSemi
                || node instanceof NodeSwitchCaseList.AltCases || node instanceof NodeSwitchCaseList.AltCasesWithSemi)) {
            throw context.error(node, path, "无法识别的 switch 分支包装");
        }
        var current = context.required(node.getCases(), node, path + ".cases");
        var pending = new ArrayDeque<Map.Entry<NodeCaseList, String>>();
        String branchPath = path + ".cases";
        // case_list 是左递归链；只有精确基类表示空产生式，未知子类不能当作结束。
        while (current.getClass() != NodeCaseList.class) {
            if (!(current instanceof NodeCaseList.Case || current instanceof NodeCaseList.DefaultCase)) {
                throw context.error(current, branchPath, "无法识别的 switch 分支结构");
            }
            pending.addFirst(Map.entry(current, branchPath));
            current = context.required(current.getCases(), current, branchPath + ".cases");
            branchPath += ".cases";
        }
        var result = new ArrayList<IrSwitchCase>(pending.size());
        for (var entry : pending) {
            var branch = entry.getKey();
            String p = entry.getValue();
            IrExpression condition = null;
            if (branch instanceof NodeCaseList.Case) {
                condition = expressions.convert(context.required(branch.getCond(), branch, p + ".cond"),
                        p + ".cond");
            } else if (!context.text(branch.getKw(), branch, p + ".kw").equalsIgnoreCase("default")) {
                throw context.error(branch, p + ".kw", "无法识别的 default 标记");
            }
            caseSeparator(context.required(branch.getSep(), branch, p + ".sep"), p + ".sep");
            result.add(new IrSwitchCase(condition, sequenceBlock(branch.getStmts(), branch, p + ".stmts"),
                    context.source(branch)));
        }
        return result;
    }

    private void caseSeparator(NodeCaseSeparator node, String path) {
        switch (node) {
            case NodeCaseSeparator.Colon separator -> {
                if (!context.text(separator.getColon(), separator, path + ".colon").equals(":")) {
                    throw context.error(separator, path + ".colon", "无法识别的 case 冒号分隔符");
                }
            }
            case NodeCaseSeparator.Semi separator -> {
                if (!context.text(separator.getSemicolon(), separator, path + ".semicolon").equals(";")) {
                    throw context.error(separator, path + ".semicolon", "无法识别的 case 分号分隔符");
                }
            }
            default -> throw context.error(node, path, "无法识别的 case 分隔符结构");
        }
    }

    private List<IrCatch> catches(NodeCatchList node, String path) {
        var pending = new ArrayDeque<Map.Entry<NodeCatchList.CatchItem, String>>();
        while (node.getClass() != NodeCatchList.class) {
            if (!(node instanceof NodeCatchList.CatchItem item)) {
                throw context.error(node, path, "无法识别的 catch 结构");
            }
            pending.addFirst(Map.entry(item, path));
            node = context.required(item.getCatches(), item, path + ".catches");
            path += ".catches";
        }
        var result = new ArrayList<IrCatch>(pending.size());
        for (var entry : pending) {
            var item = entry.getKey();
            String p = entry.getValue();
            var list = context.required(item.getExceptions(), item, p + ".exceptions");
            var names = context.elements(list.getValue(), list, p + ".exceptions");
            if (names.isEmpty()) throw context.error(item, p + ".exceptions", "catch 至少需要一个异常类型");
            var types = new ArrayList<NameReference>(names.size());
            for (int i = 0; i < names.size(); i++) {
                types.add(context.name(names.get(i), p + ".exceptions[" + i + "]"));
            }
            // T_VARIABLE 已由词法去掉开头的 $，此处保留名称而不再次截取。
            result.add(new IrCatch(types, context.text(item.getVar(), item, p + ".var"),
                    sequenceBlock(item.getStmts(), item, p + ".stmts"), context.source(item)));
        }
        return result;
    }

    private @Nullable IrBlock finallyBlock(NodeFinallyStatement node, String path) {
        if (node.getClass() == NodeFinallyStatement.class) return null;
        if (node instanceof NodeFinallyStatement.Finally block) {
            return innerBlock(block.getStmts(), block, path + ".stmts");
        }
        throw context.error(node, path, "无法识别的 finally 结构");
    }

    /** 三组 for 列表独立转换；空列表合法，但缺少列表包装或列表元素并不合法。 */
    private List<IrExpression> expressionList(@Nullable NodeListNodeExpr list, AstNode origin, String path) {
        context.required(list, origin, path);
        var nodes = context.elements(list.getValue(), list, path);
        var result = new ArrayList<IrExpression>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            result.add(expressions.convert(nodes.get(i), path + "[" + i + "]"));
        }
        return result;
    }

    private IrBlock whileBody(NodeWhileStatement node, String path) {
        return switch (node) {
            case NodeWhileStatement.Body body -> statementBlock(
                    context.required(body.getBody(), body, path + ".body"), path + ".body");
            case NodeWhileStatement.AltBody body -> innerBlock(body.getStmts(), body, path + ".stmts");
            default -> throw context.error(node, path, "无法识别的 while 循环体");
        };
    }

    private IrBlock forBody(NodeForStatement node, String path) {
        return switch (node) {
            case NodeForStatement.Body body -> statementBlock(
                    context.required(body.getBody(), body, path + ".body"), path + ".body");
            case NodeForStatement.AltBody body -> innerBlock(body.getStmts(), body, path + ".stmts");
            default -> throw context.error(node, path, "无法识别的 for 循环体");
        };
    }

    private IrBlock foreachBody(NodeForeachStatement node, String path) {
        return switch (node) {
            case NodeForeachStatement.Body body -> statementBlock(
                    context.required(body.getBody(), body, path + ".body"), path + ".body");
            case NodeForeachStatement.AltBody body -> innerBlock(body.getStmts(), body, path + ".stmts");
            default -> throw context.error(node, path, "无法识别的 foreach 循环体");
        };
    }

    private IrForeach foreach(NodeStatement node, String path) {
        IrAssignmentTarget key = null;
        boolean hasKey = node instanceof NodeStatement.ForeachKV;
        if (hasKey) {
            var keyNode = context.required(node.getKey(), node, path + ".key");
            if (!(keyNode instanceof NodeForeachVariable.Var)) {
                throw context.error(keyNode, path + ".key", "foreach 键必须是非引用的单个可写目标");
            }
            key = expressions.assignmentTarget(context.required(keyNode.getV(), keyNode, path + ".key.v"),
                    path + ".key.v");
        }
        String valuePath = path + (hasKey ? ".valueVar" : ".var");
        var valueNode = context.required(hasKey ? node.getValueVar() : node.getVar(), node, valuePath);
        boolean byReference = valueNode instanceof NodeForeachVariable.Ref;
        if (byReference && !context.text(valueNode.getAmp(), valueNode, valuePath + ".amp").equals("&")) {
            throw context.error(valueNode, valuePath + ".amp", "foreach 引用标记与结构不一致");
        }
        IrBindingTarget value = switch (valueNode) {
            case NodeForeachVariable.Var ignored ->
                    expressions.assignmentTarget(context.required(valueNode.getV(), valueNode, valuePath + ".v"),
                            valuePath + ".v");
            case NodeForeachVariable.Ref ignored ->
                    expressions.assignmentTarget(context.required(valueNode.getV(), valueNode, valuePath + ".v"),
                            valuePath + ".v");
            case NodeForeachVariable.List ignored -> new DestructuringConverter(context, expressions).convert(
                    context.required(valueNode.getItems(), valueNode, valuePath + ".items"),
                    DestructuringConverter.Style.LIST, valuePath + ".items");
            case NodeForeachVariable.ShortList ignored -> new DestructuringConverter(context, expressions).convert(
                    context.required(valueNode.getItems(), valueNode, valuePath + ".items"),
                    DestructuringConverter.Style.SHORT_ARRAY, valuePath + ".items");
            default -> throw context.error(valueNode, valuePath, "无法识别的 foreach 值目标");
        };
        return new IrForeach(new ForeachIterableConverter(context, expressions).convert(
                context.required(node.getIterable(), node, path + ".iterable"), byReference, path + ".iterable"),
                key, value, byReference,
                foreachBody(context.required(node.getForeachBody(), node, path + ".foreachBody"),
                        path + ".foreachBody"), context.source(node));
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
