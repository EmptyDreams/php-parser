package top.kmar.php.model;

import java_cup.runtime.AstNode;

import java.util.List;
import java.util.Objects;

/** 按原顺序保留的语句序列；列表为只读快照，AST 节点仍是共享的只读引用。 */
public record SyntaxBody(List<AstNode> statements, SourceInfo source) {
    public SyntaxBody {
        statements = List.copyOf(statements);
        Objects.requireNonNull(source, "source");
    }
}
