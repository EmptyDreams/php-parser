package top.kmar.php;

import java_cup.runtime.AstNode;
import java_cup.runtime.Symbol;
import java_cup.runtime.symbol.complex.ComplexSymbolFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 解析入口：解析给定 PHP 文件并打印语法树。
 *
 * <pre>用法：Main &lt;file.php&gt;</pre>
 */
public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("用法: Main <file.php>");
            System.exit(2);
            return;
        }
        AstNode tree = parse(Path.of(args[0]));
        System.out.print(tree.toTreeString(false));
    }

    /** 解析 PHP 源码文本，返回语法树根节点（program） */
    public static AstNode parse(String source) {
        return parse(new java.io.StringReader(source));
    }

    /** 解析 PHP 文件（UTF-8） */
    public static AstNode parse(Path file) throws IOException {
        return parse(Files.newBufferedReader(file, StandardCharsets.UTF_8));
    }

    /** 解析字符流 */
    public static AstNode parse(java.io.Reader reader) {
        ComplexSymbolFactory symbolFactory = new ComplexSymbolFactory(
                PhpSymbols.TERMINAL_NAMES, PhpSymbols.NON_TERMINAL_NAMES);
        PhpLexer lexer = new PhpLexer(reader, symbolFactory);
        PhpParser parser = new PhpParser(lexer, symbolFactory);
        try {
            Symbol result = parser.parse();
            return result.value();
        } catch (PhpLexerException e) {
            throw e;
        } catch (Exception e) {
            throw new PhpParseException("解析失败: " + e.getMessage(), e);
        }
    }
}
