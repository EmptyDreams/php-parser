package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import org.jetbrains.annotations.Nullable;
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
        var worker = new Worker(context);
        List<NamespaceSection> sections = worker.read(new NamespaceSectionReader(context).read(syntax));
        return new PhpFile(context.source(syntax), syntax, sections, DeclarationIndex.from(sections));
    }

    private static final class Worker {
        private final ExtractionContext context;
        private final DeclarationReader declarations;
        private final ImportReader<ImportDeclaration> imports;
        private int nextSectionId = 1;

        private Worker(ExtractionContext context) {
            this.context = context;
            declarations = new DeclarationReader(context);
            imports = new ImportReader<>(context, ImportDeclaration::new);
        }

        private List<NamespaceSection> read(List<SyntaxNamespaceSection> sections) {
            var result = new ArrayList<NamespaceSection>();
            for (var section : sections) {
                var builder = new SectionBuilder(new NamespaceSectionId(nextSectionId++), section.namespaceName(),
                        section.source(), section.bodySource());
                for (var statement : section.statements()) {
                    add(builder, statement.node(), statement.path());
                }
                result.add(builder.finish());
            }
            return List.copyOf(result);
        }

        private void add(SectionBuilder section, NodeTopStatement statement, String path) {
            if (statement instanceof NodeTopStatement.FunctionDecl || statement instanceof NodeTopStatement.ClassDecl
                    || statement instanceof NodeTopStatement.InterfaceDecl || statement instanceof NodeTopStatement.TraitDecl
                    || statement instanceof NodeTopStatement.Const) {
                section.declarations.addAll(declarations.read(statement, section.id, section.name));
            } else if (statement instanceof NodeTopStatement.Use || statement instanceof NodeTopStatement.UseTyped
                    || statement instanceof NodeTopStatement.UseGroup || statement instanceof NodeTopStatement.UseMixedGroup) {
                section.imports.addAll(imports.read(statement, path));
            } else if (statement instanceof NodeTopStatement.Statement) {
                context.required(statement.getStmt(), statement, path + ".stmt");
            } else if (statement instanceof NodeTopStatement.HaltCompiler) {
                context.text(statement.getHalt(), statement, path + ".halt");
            } else {
                throw context.error(statement, path, "无法识别的顶层结构");
            }
            section.statements.add(statement);
        }
    }

    private static final class SectionBuilder {
        private final NamespaceSectionId id;
        private final String name;
        private final List<AstNode> statements = new ArrayList<>();
        private final List<ImportDeclaration> imports = new ArrayList<>();
        private final List<TopLevelDeclaration> declarations = new ArrayList<>();
        private final SourceInfo source;
        private final SourceInfo bodySource;

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
