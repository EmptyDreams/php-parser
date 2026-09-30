package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import top.kmar.php.ir.IrBlock;
import top.kmar.php.ir.IrFile;
import top.kmar.php.ir.IrNamespaceSection;
import top.kmar.php.ir.IrStatement;

import java.util.ArrayList;
import java.util.Objects;

/** 文件转换只组织区段；所有正文共用本次调用的表达式转换器，不建立声明索引。 */
final class FileConverter {
    private final ConversionContext context;
    private final StatementConverter statements;

    FileConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.statements = new StatementConverter(context, expressions);
    }

    IrFile convert(AstNode syntax) {
        var sections = new NamespaceSectionReader(context).read(syntax);
        var result = new ArrayList<IrNamespaceSection>(sections.size());
        for (var section : sections) {
            var body = new ArrayList<IrStatement>(section.statements().size());
            for (var statement : section.statements()) {
                statements.appendStatement(statement.node(), statement.path(), body);
            }
            result.add(new IrNamespaceSection(section.namespaceName(),
                    new IrBlock(body, section.bodySource()), section.source()));
        }
        return new IrFile(result, context.source(syntax));
    }
}
