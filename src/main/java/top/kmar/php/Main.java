package top.kmar.php;


import java_cup.runtime.symbol.complex.ComplexSymbolFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class Main {

    public static void main(String[] args) throws IOException {
        var file = new File("D:\\Workspace\\jvm\\php-parser\\src\\test\\resources\\test.php");
        var path = file.toPath();
        System.out.println(Files.readString(path));
        System.out.println("=================================");
        try (
            var bufferedReader = Files.newBufferedReader(path);
            var reader = new CodeReader(bufferedReader);
        ) {
            var factory = new ComplexSymbolFactory(PhpSymbols.TERMINAL_NAMES, PhpSymbols.NON_TERMINAL_NAMES);
            var lexer = new PhpLexer(reader, factory);
            do {
                var token = lexer.next_token();
                System.out.println(PhpSymbols.TERMINAL_NAMES[token.sym] + ": " + token);
                System.out.println("----------------------------");
            } while (!lexer.yyatEOF());
        }
    }

}