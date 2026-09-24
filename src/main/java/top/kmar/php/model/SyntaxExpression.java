package top.kmar.php.model;

import java_cup.runtime.AstNode;

import java.util.Objects;

/** 尚未求值或规范化的表达式；保留的 AST 按共享只读引用使用。 */
public record SyntaxExpression(AstNode syntax, SourceInfo source) {
    public SyntaxExpression {
        Objects.requireNonNull(syntax, "syntax");
        Objects.requireNonNull(source, "source");
    }
}
