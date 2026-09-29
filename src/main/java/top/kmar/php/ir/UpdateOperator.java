package top.kmar.php.ir;

/** 自增／自减的更新方向与表达式取值时机；不提前折叠为加减运算。 */
public enum UpdateOperator {
    PRE_INCREMENT, POST_INCREMENT, PRE_DECREMENT, POST_DECREMENT
}
