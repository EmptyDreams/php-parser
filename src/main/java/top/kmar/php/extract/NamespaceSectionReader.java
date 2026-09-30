package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.NodeProgram;
import top.kmar.php.NodeTopStatement;
import top.kmar.php.model.SourceInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 将 program 按原序划分为独立 namespace 区段，不解析区段里的声明或可执行结构。 */
final class NamespaceSectionReader {
    private final AstReaderContext context;

    NamespaceSectionReader(AstReaderContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    List<SyntaxNamespaceSection> read(AstNode syntax) {
        if (!(syntax instanceof NodeProgram program)) {
            throw context.error(syntax, "program", "需要 PHP program AST 根节点");
        }
        var list = context.required(program.getStmts(), program, "program.stmts");
        var statements = context.elements(list.getValue(), program, "program.stmts");
        var result = new ArrayList<SyntaxNamespaceSection>();
        SectionBuilder current = null;
        for (int i = 0; i < statements.size(); i++) {
            NodeTopStatement statement = statements.get(i);
            String path = "program.stmts[" + i + "]";
            if (statement instanceof NodeTopStatement.Namespace) {
                if (current != null) result.add(current.finish());
                current = section(namespaceName(statement, path), statement);
            } else if (statement instanceof NodeTopStatement.NamespaceBlock
                    || statement instanceof NodeTopStatement.GlobalNamespaceBlock) {
                if (current != null) result.add(current.finish());
                current = null;
                String name = statement instanceof NodeTopStatement.NamespaceBlock
                        ? namespaceName(statement, path) : "";
                var block = section(name, statement);
                var body = context.required(statement.getStmts(), statement, path + ".stmts");
                block.bodySource = context.source(body);
                var children = context.elements(body.getValue(), statement, path + ".stmts");
                for (int j = 0; j < children.size(); j++) {
                    add(block, children.get(j), path + ".stmts[" + j + "]");
                }
                result.add(block.finish());
            } else {
                // 括号区段外的连续代码独立为全局区段，不继承前一区段的导入环境。
                if (current == null) current = section("", null);
                add(current, statement, path);
            }
        }
        if (current != null) result.add(current.finish());
        if (result.isEmpty()) result.add(section("", null).finish());
        return List.copyOf(result);
    }

    private String namespaceName(NodeTopStatement statement, String path) {
        return context.namespaceName(context.required(statement.getN(), statement, path + ".n"), path + ".n");
    }

    private SectionBuilder section(String name, @Nullable AstNode origin) {
        return new SectionBuilder(name, context.source(origin), context.source(null));
    }

    private void add(SectionBuilder section, NodeTopStatement statement, String path) {
        if (statement instanceof NodeTopStatement.Namespace
                || statement instanceof NodeTopStatement.NamespaceBlock
                || statement instanceof NodeTopStatement.GlobalNamespaceBlock) {
            throw context.error(statement, path, "不支持嵌套 namespace 区段");
        }
        section.statements.add(new LocatedTopStatement(statement, path));
        section.source = context.span(section.source, context.source(statement));
        section.bodySource = context.span(section.bodySource, context.source(statement));
    }

    private static final class SectionBuilder {
        private final String name;
        private final List<LocatedTopStatement> statements = new ArrayList<>();
        private SourceInfo source;
        private SourceInfo bodySource;

        private SectionBuilder(String name, SourceInfo source, SourceInfo bodySource) {
            this.name = name;
            this.source = source;
            this.bodySource = bodySource;
        }

        private SyntaxNamespaceSection finish() {
            return new SyntaxNamespaceSection(name, statements, source, bodySource);
        }
    }
}
