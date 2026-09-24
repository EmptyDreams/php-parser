package top.kmar.php.model;

/** 文件或命名空间区段中直接出现的命名声明，不包括条件和嵌套声明。 */
public interface TopLevelDeclaration extends Declaration {
    NamespaceSectionId sectionId();
    String name();
    String qualifiedName();
}
