package top.kmar.php.model;

/** 当前文件内的命名空间区段标识；同名区段仍有不同标识。 */
public record NamespaceSectionId(int value) {
    public NamespaceSectionId {
        if (value < 1) throw new IllegalArgumentException("命名空间区段 ID 必须从 1 开始");
    }
}
