package top.kmar.php.extract;

import top.kmar.php.NodeConstDecl;
import top.kmar.php.NodeTopStatement;
import top.kmar.php.ir.IrConstantDeclaration;
import top.kmar.php.ir.IrHaltCompiler;
import top.kmar.php.ir.IrImport;
import top.kmar.php.ir.IrUse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** 文件级导入、常量和停止解析标记；不执行名称绑定、常量求值或运行时退出。 */
final class TopLevelStatementConverter {
    private static final Pattern HALT_MARKER = Pattern.compile(
            "__halt_compiler[ \\n\\r\\t]*\\([ \\n\\r\\t]*\\)[ \\n\\r\\t]*;", Pattern.CASE_INSENSITIVE);

    private final ConversionContext context;
    private final ExpressionConverter expressions;

    TopLevelStatementConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
    }

    IrUse use(NodeTopStatement node, String path) {
        var imports = new ImportReader<>(context, (kind, target, declaredAlias, source) ->
                new IrImport(kind, target, declaredAlias != null ? declaredAlias
                        : target.substring(target.lastIndexOf('\\') + 1), source));
        return new IrUse(imports.read(node, path), context.source(node));
    }

    List<IrConstantDeclaration> constants(NodeTopStatement.Const node, String path) {
        var list = context.required(node.getConsts(), node, path + ".consts");
        var values = context.elements(list.getValue(), list, path + ".consts");
        if (values.isEmpty()) throw context.error(list, path + ".consts", "常量声明至少需要一项");
        var result = new ArrayList<IrConstantDeclaration>(values.size());
        for (int i = 0; i < values.size(); i++) {
            var constant = values.get(i);
            String itemPath = path + ".consts[" + i + "]";
            if (!(constant instanceof NodeConstDecl.ConstDecl)) {
                throw context.error(constant, itemPath, "无法识别的顶层常量声明结构");
            }
            result.add(new IrConstantDeclaration(
                    context.text(constant.getName(), constant, itemPath + ".name"),
                    expressions.convert(context.required(constant.getValue(), constant, itemPath + ".value"),
                            itemPath + ".value"), context.source(constant)));
        }
        return result;
    }

    IrHaltCompiler halt(NodeTopStatement.HaltCompiler node, String path) {
        String marker = context.text(node.getHalt(), node, path + ".halt");
        if (!HALT_MARKER.matcher(marker).matches()) {
            throw context.error(node, path + ".halt", "无法识别的 __halt_compiler 标记");
        }
        return new IrHaltCompiler(context.source(node));
    }
}
