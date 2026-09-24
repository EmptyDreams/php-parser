package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
import top.kmar.php.NodeProgram;
import top.kmar.php.NodeTopStatement;
import top.kmar.php.model.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 将已有 PHP program AST 提取为声明模型，不重新解析或修改 AST。
 * 只收集区段直接包含的命名声明及其类型成员；可执行内容仍保留 AST。
 * 每次调用独立分配 ID，返回集合只读，保留的 AST 引用也应按只读方式使用。
 */
public final class DeclarationExtractor {
    private DeclarationExtractor() {}

    /** 提取没有外部源码标识的语法树。 */
    public static PhpFile extract(AstNode syntax) {
        return extract(syntax, null);
    }

    /** sourceId 是调用方提供的不透明标识，不要求是文件路径；null 表示未提供。 */
    public static PhpFile extract(AstNode syntax, @Nullable String sourceId) {
        var context = new ExtractionContext(sourceId);
        if (!(syntax instanceof NodeProgram program)) {
            throw context.error(syntax, "program", "需要 PHP program AST 根节点");
        }
        var statements = context.required(program.getStmts(), program, "program.stmts");
        var worker = new Worker(context);
        List<NamespaceSection> sections = worker.read(context.elements(statements.getValue(), program, "program.stmts"));
        return new PhpFile(context.source(syntax), syntax, sections, DeclarationIndex.from(sections));
    }

    private static final class Worker {
        private final ExtractionContext context;
        private final DeclarationReader declarations;
        private final ImportReader imports;
        private int nextSectionId = 1;

        private Worker(ExtractionContext context) {
            this.context = context;
            declarations = new DeclarationReader(context);
            imports = new ImportReader(context);
        }

        private List<NamespaceSection> read(List<NodeTopStatement> statements) {
            var result = new ArrayList<NamespaceSection>();
            SectionBuilder current = null;
            for (var statement : statements) {
                if (statement instanceof NodeTopStatement.Namespace) {
                    if (current != null) result.add(current.finish());
                    current = section(context.namespaceName(context.required(statement.getN(), statement, "namespace.name")), statement);
                } else if (statement instanceof NodeTopStatement.NamespaceBlock
                        || statement instanceof NodeTopStatement.GlobalNamespaceBlock) {
                    if (current != null) result.add(current.finish());
                    current = null;
                    String name = statement instanceof NodeTopStatement.NamespaceBlock
                            ? context.namespaceName(context.required(statement.getN(), statement, "namespace.name")) : "";
                    var block = section(name, statement);
                    var body = context.required(statement.getStmts(), statement, "namespace.stmts");
                    block.bodySource = context.source(body);
                    for (var child : context.elements(body.getValue(), statement, "namespace.stmts")) {
                        add(block, child);
                    }
                    result.add(block.finish());
                } else {
                    // 括号区段以外的连续代码独立成全局区段，不继承前一区段的 imports。
                    if (current == null) current = section("", null);
                    add(current, statement);
                }
            }
            if (current != null) result.add(current.finish());
            if (result.isEmpty()) result.add(section("", null).finish());
            return List.copyOf(result);
        }

        private SectionBuilder section(String name, AstNode origin) {
            return new SectionBuilder(new NamespaceSectionId(nextSectionId++), name, context.source(origin), context.source(null));
        }

        private void add(SectionBuilder section, NodeTopStatement statement) {
            if (statement instanceof NodeTopStatement.Namespace
                    || statement instanceof NodeTopStatement.NamespaceBlock
                    || statement instanceof NodeTopStatement.GlobalNamespaceBlock) {
                throw context.error(statement, "namespace.stmts", "不支持嵌套 namespace 区段");
            }
            if (statement instanceof NodeTopStatement.FunctionDecl || statement instanceof NodeTopStatement.ClassDecl
                    || statement instanceof NodeTopStatement.InterfaceDecl || statement instanceof NodeTopStatement.TraitDecl
                    || statement instanceof NodeTopStatement.Const) {
                section.declarations.addAll(declarations.read(statement, section.id, section.name));
            } else if (statement instanceof NodeTopStatement.Use || statement instanceof NodeTopStatement.UseTyped
                    || statement instanceof NodeTopStatement.UseGroup || statement instanceof NodeTopStatement.UseMixedGroup) {
                section.imports.addAll(imports.read(statement));
            } else if (statement instanceof NodeTopStatement.Statement) {
                context.required(statement.getStmt(), statement, "statement.stmt");
            } else if (statement instanceof NodeTopStatement.HaltCompiler) {
                context.text(statement.getHalt(), statement, "halt_compiler.halt");
            } else {
                throw context.error(statement, "top_statement", "无法识别的顶层结构");
            }
            section.statements.add(statement);
            section.source = context.span(section.source, context.source(statement));
            section.bodySource = context.span(section.bodySource, context.source(statement));
        }
    }

    private static final class SectionBuilder {
        private final NamespaceSectionId id;
        private final String name;
        private final List<AstNode> statements = new ArrayList<>();
        private final List<ImportDeclaration> imports = new ArrayList<>();
        private final List<TopLevelDeclaration> declarations = new ArrayList<>();
        private SourceInfo source;
        private SourceInfo bodySource;

        private SectionBuilder(NamespaceSectionId id, String name, SourceInfo source, SourceInfo bodySource) {
            this.id = id;
            this.name = name;
            this.source = source;
            this.bodySource = bodySource;
        }

        private NamespaceSection finish() {
            return new NamespaceSection(id, name, imports, declarations, new SyntaxBody(statements, bodySource), source);
        }
    }
}
