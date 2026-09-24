package top.kmar.php.model;

/** 类、接口或 trait 中的成员，ownerId 指向其所属类型声明。 */
public interface ClassMember extends Declaration {
    DeclarationId ownerId();
}
