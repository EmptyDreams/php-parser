package top.kmar.php.model;

/** 已提取声明的共同身份与来源。 */
public interface Declaration {
    DeclarationId id();
    SourceInfo source();
}
