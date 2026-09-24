package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import top.kmar.php.*;
import top.kmar.php.model.ImportDeclaration;
import top.kmar.php.model.ImportKind;

import java.util.ArrayList;
import java.util.List;

/** 只读取 namespace 的 use，不处理 trait 使用或闭包捕获。 */
final class ImportReader {
    private final ExtractionContext context;

    ImportReader(ExtractionContext context) {
        this.context = context;
    }

    List<ImportDeclaration> read(NodeTopStatement statement) {
        var result = new ArrayList<ImportDeclaration>();
        if (statement instanceof NodeTopStatement.Use || statement instanceof NodeTopStatement.UseTyped) {
            ImportKind kind = statement instanceof NodeTopStatement.UseTyped
                    ? kind(context.required(statement.getType(), statement, "use.type")) : ImportKind.CLASS;
            var uses = context.required(statement.getUses(), statement, "use.uses");
            for (var use : nonEmpty(uses.getValue(), uses, "use.uses")) {
                if (use instanceof NodeUseDeclaration.UseDeclAbsolute) {
                    context.text(use.getKw(), use, "use.kw");
                } else if (!(use instanceof NodeUseDeclaration.UseDecl)) {
                    throw context.error(use, "use", "无法识别的导入结构");
                }
                result.add(item(kind, "", context.required(use.getUse(), use, "use.item"), use));
            }
        } else if (statement instanceof NodeTopStatement.UseGroup) {
            ImportKind kind = kind(context.required(statement.getType(), statement, "use.type"));
            var group = context.required(statement.getUse(), statement, "use.group");
            if (!(group instanceof NodeGroupUseDeclaration.GroupUse)
                    && !(group instanceof NodeGroupUseDeclaration.GroupUseAbsolute)) {
                throw context.error(group, "use.group", "无法识别的分组导入结构");
            }
            String prefix = context.namespaceName(context.required(group.getPrefix(), group, "use.group.prefix"));
            var uses = context.required(group.getUses(), group, "use.group.uses");
            for (var use : nonEmpty(uses.getValue(), uses, "use.group.uses")) {
                result.add(item(kind, prefix, use, use));
            }
        } else if (statement instanceof NodeTopStatement.UseMixedGroup) {
            var group = context.required(statement.getMixedUse(), statement, "use.mixedGroup");
            if (!(group instanceof NodeMixedGroupUseDeclaration.MixedGroupUse)
                    && !(group instanceof NodeMixedGroupUseDeclaration.MixedGroupUseAbsolute)) {
                throw context.error(group, "use.mixedGroup", "无法识别的混合分组导入结构");
            }
            String prefix = context.namespaceName(context.required(group.getPrefix(), group, "use.mixedGroup.prefix"));
            var uses = context.required(group.getUses(), group, "use.mixedGroup.uses");
            for (var use : nonEmpty(uses.getValue(), uses, "use.mixedGroup.uses")) {
                ImportKind kind;
                if (use instanceof NodeInlineUseDeclaration.InlineUseTyped) {
                    kind = kind(context.required(use.getType(), use, "use.item.type"));
                } else if (use instanceof NodeInlineUseDeclaration.InlineUse) {
                    kind = ImportKind.CLASS;
                } else {
                    throw context.error(use, "use.item", "无法识别的混合导入项");
                }
                result.add(item(kind, prefix, context.required(use.getUse(), use, "use.item"), use));
            }
        } else {
            throw context.error(statement, "use", "无法识别的导入语句");
        }
        return result;
    }

    private ImportDeclaration item(ImportKind kind, String prefix, NodeUnprefixedUseDeclaration node,
                                   AstNode origin) {
        String alias;
        if (node instanceof NodeUnprefixedUseDeclaration.UseElemAs) {
            alias = context.text(node.getAlias(), node, "use.item.alias");
        } else if (node instanceof NodeUnprefixedUseDeclaration.UseElem) {
            alias = null;
        } else {
            throw context.error(node, "use.item", "无法识别的导入项");
        }
        String suffix = context.namespaceName(context.required(node.getN(), node, "use.item.name"));
        // PHP 导入目标始终是全限定名称，不受当前 namespace 影响。
        return new ImportDeclaration(kind, context.qualifiedName(prefix, suffix), alias, context.source(origin));
    }

    private ImportKind kind(NodeUseType node) {
        context.text(node.getKw(), node, "use.type.kw");
        if (node instanceof NodeUseType.UseFunction) return ImportKind.FUNCTION;
        if (node instanceof NodeUseType.UseConst) return ImportKind.CONST;
        throw context.error(node, "use.type", "无法识别的导入种类");
    }

    private <T> List<T> nonEmpty(List<T> values, AstNode node, String path) {
        context.elements(values, node, path);
        if (values.isEmpty()) throw context.error(node, path, "导入列表至少需要一项");
        return values;
    }
}
