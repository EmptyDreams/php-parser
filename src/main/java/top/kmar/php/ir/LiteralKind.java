package top.kmar.php.ir;

/** 字面量种类；FLOAT 沿用词法分析器的分类，不通过解析原文重新判定。 */
public enum LiteralKind {
    INTEGER, FLOAT, STRING, BOOLEAN, NULL
}
