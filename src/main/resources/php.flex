package top.kmar.php;

import java.util.*;
import java_cup.runtime.*;import top.kmar.php.exceptions.LexerException;

%%

%{

    private int[] stateStack = new int[16];
    private int topStateIndex = -1;
    private char[] charBuffer = null;
    private int inlineCodeOpenCount = 0;

    private void pushState(int newState) {
        stateStack[++topStateIndex] = yystate();
        yybegin(newState);
    }

    private void replaceState(int newState) {
        yybegin(newState);
    }

    private void popState() {
        yybegin(stateStack[topStateIndex--]);
    }

    private String docLabel = null;

    private Symbol symbol(int type) {
        return new Symbol(type, yyline, -1);
    }

    private Symbol symbol(int type, Object value) {
        return new Symbol(type, yyline, -1, value);
    }

    private static final String TEXT_ESCAPE_V = String.valueOf((char) 0x0b);
    private static final String TEXT_ESCAPE_E = String.valueOf((char) 0x1b);
    private static final String TEXT_ESCAPE_F = String.valueOf((char) 0x0c);

    /** 提取当前匹配的字符串并在开头添加一个字符 */
    private String yytextPrefixWith(char c) {
        int startIndex = zzStartRead;
        int endIndexExclude = zzMarkedPos;
        int length = endIndexExclude - startIndex + 1;
        char[] buffer = charBuffer;
        if (buffer == null || buffer.length < length) {
            buffer = new char[length];
            charBuffer = buffer;
        }
        System.arraycopy(zzBuffer, startIndex, buffer, 1, length - 1);
        buffer[0] = c;
        return new String(buffer, 0, length);
    }

    /** 提取当前匹配的字符串，允许调整左右边界，为正向右偏移 */
    private String yytext(int startOffset, int endOffset) {
        int startIndex = zzStartRead + startOffset;
        int endIndexExclude = zzMarkedPos + endOffset;
        return new String(zzBuffer, startIndex, endIndexExclude - startIndex);
    }

    /** 处理 `\[0-7]{1, 3}` 形式的八进制转义 */
    private Symbol parseTextEscapeOct() {
        int length = yylength();
        int value = 0;
        for (int i = 1; i != length; ++i) {
            value = value << 3 | (yycharat(i) - '0');
        }
        if (value > 0xFF) throw new LexerException(yyline, yycolumn, "Octal escape sequence is out of range");
        return symbol(PhpSymbols.T_STR_VALUE, String.valueOf((char) value));
    }

    /** 处理 `\x[0-9a-fA-F]{1, 2}` 形式的十六进制转义 */
    private Symbol parseTextEscapeHex() {
        int length = yylength();
        int value = 0;
        for (int i = 2; i != length; ++i) {
            char c = yycharat(i);
            if (c >= '0' && c <= '9') {
                value = value << 4 | (c - '0');
            } else if (c >= 'a' && c <= 'f') {
                value = value << 4 | (c - 'a' + 10);
            } else {
                value = value << 4 | (c - 'A' + 10);
            }
        }
        return symbol(PhpSymbols.T_STR_VALUE, String.valueOf((char) value));
    }

    /** 处理 `\\u\{[0-9a-fA-F]+\}` 形式的十六进制转义 */
    private Symbol parseTextEscapeUnicode() {
        int length = yylength() - 1;
        System.out.println();
        System.out.println(yycharat(length));
        if (length < 3 || yycharat(length) != '}') throw new LexerException(yyline, yycolumn, "Invalid Unicode escape sequence");
        if (length > 8) throw new LexerException(yyline, yycolumn, "Unicode escape sequence is out of range");
        int value = 0;
        for (int i = 3; i != length; ++i) {
            char c = yycharat(i);
            if (c >= '0' && c <= '9') {
                value = value << 4 | (c - '0');
            } else if (c >= 'a' && c <= 'f') {
                value = value << 4 | (c - 'a' + 10);
            } else {
                value = value << 4 | (c - 'A' + 10);
            }
        }
        return symbol(PhpSymbols.T_STR_VALUE, String.valueOf((char) value));
    }

%}

// 单引号字符串
%x XS_SQUOTE
// 双引号字符串
%x XS_DQUOTE
// 反引号字符串
%x XS_BQUOTE
%x XS_HEAR_DOC
%x XS_NEW_DOC
// 字符串插值
%x XS_INLINE_CODE
%x XS_DOLLAR_INLINE_CODE
%x XS_INLINE_ARRAY_KEY
// 代码部分
%x XS_CODE
// 多行注释
%x XS_COMMENT

%cupdebug
%unicode
%line
%ignorecase
%cupsym PhpSymbols
%cup
%class PhpLexer
%public

// 标识符
LABEL = [a-zA-Z_\x80-\xFF][a-zA-Z0-9_\x80-\xFF]*

%%

<YYINITIAL, XS_CODE, XS_DOLLAR_INLINE_CODE> {
    [ \t\r\n]+ { }
}

<YYINITIAL> {
    "<?php" {
        pushState(XS_CODE);
        return symbol(PhpSymbols.T_CODE_BLOCK, "S");
    }
}

<XS_CODE, XS_INLINE_CODE> {
    [\"] {
        pushState(XS_DQUOTE);
        return symbol(PhpSymbols.T_DOUBLE_QUOTE, "S");
    }
    ['] {
        pushState(XS_SQUOTE);
        return symbol(PhpSymbols.T_SINGLE_QUOTE, "S");
    }
    [`] {
        pushState(XS_BQUOTE);
        return symbol(PhpSymbols.T_BACK_QUOTE, "S");
    }

    (#|\/\/)[^\r\n]* { return symbol(PhpSymbols.T_LINE_COMMENT, yytext()); }
    "/*" {
        pushState(XS_COMMENT);
        return symbol(PhpSymbols.T_OPEN_BLOCK_COMMENT);
    }

    "(" { return symbol(PhpSymbols.T_OPEN_PAREN); }
    ")" { return symbol(PhpSymbols.T_CLOSE_PAREN); }
    "[" { return symbol(PhpSymbols.T_OPEN_SQUARE); }
    "]" { return symbol(PhpSymbols.T_CLOSE_SQUARE); }

    ";" { return symbol(PhpSymbols.T_SEMI); }
    "," { return symbol(PhpSymbols.T_COMMA); }
    "." { return symbol(PhpSymbols.T_DOT); }
    "=>" { return symbol(PhpSymbols.T_DOUBLE_ARROW); }
    "->" { return symbol(PhpSymbols.T_SINGLE_ARROW); }
    "..." { return symbol(PhpSymbols.T_ELLIPSIS); }
    "::" { return symbol(PhpSymbols.T_SCOPE_RESOLUTION); }
    "+" { return symbol(PhpSymbols.T_PLUS); }
    "-" { return symbol(PhpSymbols.T_MINUS); }
    "*" { return symbol(PhpSymbols.T_MUL); }
    "/" { return symbol(PhpSymbols.T_DIV); }
    "%" { return symbol(PhpSymbols.T_MOD); }
    "++" { return symbol(PhpSymbols.T_INC); }
    "--" { return symbol(PhpSymbols.T_DEC); }
    "=" { return symbol(PhpSymbols.T_ASSIGN); }
    "+=" { return symbol(PhpSymbols.T_PLUS_ASSIGN); }
    ".=" { return symbol(PhpSymbols.T_DOT_ASSIGN); }
    "-=" { return symbol(PhpSymbols.T_MINUS_ASSIGN); }
    "*=" { return symbol(PhpSymbols.T_MUL_ASSIGN); }
    "/=" { return symbol(PhpSymbols.T_DIV_ASSIGN); }
    "%=" { return symbol(PhpSymbols.T_MOD_ASSIGN); }
    "&" { return symbol(PhpSymbols.T_BIT_AND); }
    "|" { return symbol(PhpSymbols.T_BIT_OR); }
    "^" { return symbol(PhpSymbols.T_BIT_XOR); }
    "~" { return symbol(PhpSymbols.T_BIT_NOT); }
    "<<" { return symbol(PhpSymbols.T_LEFT_SHIFT); }
    ">>" { return symbol(PhpSymbols.T_RIGHT_SHIFT); }
    "&=" { return symbol(PhpSymbols.T_BIT_AND_ASSIGN); }
    "|=" { return symbol(PhpSymbols.T_BIT_OR_ASSIGN); }
    "^=" { return symbol(PhpSymbols.T_BIT_XOR_ASSIGN); }
    "<<=" { return symbol(PhpSymbols.T_LEFT_SHIFT_ASSIGN); }
    ">>=" { return symbol(PhpSymbols.T_RIGHT_SHIFT_ASSIGN); }
    "!" { return symbol(PhpSymbols.T_BOOL_NOT); }
    "&&" { return symbol(PhpSymbols.T_BOOL_AND); }
    "||" { return symbol(PhpSymbols.T_BOOL_OR); }
    "==" { return symbol(PhpSymbols.T_DOUBLE_EQ); }
    ==|<> { return symbol(PhpSymbols.T_NOT_DOUBLE_EQ); }
    "===" { return symbol(PhpSymbols.T_TRIPLE_EQ); }
    "!==" { return symbol(PhpSymbols.T_NOT_TRIPLE_EQ); }
    "<" { return symbol(PhpSymbols.T_LT); }
    ">" { return symbol(PhpSymbols.T_GT); }
    "<=" { return symbol(PhpSymbols.T_LT_EQ); }
    ">=" { return symbol(PhpSymbols.T_GT_EQ); }
    "?" { return symbol(PhpSymbols.T_QUESTION); }
    ":" { return symbol(PhpSymbols.T_COLON); }
    "**" { return symbol(PhpSymbols.T_POW); }
    "??" { return symbol(PhpSymbols.T_COALESCE); }
    "?:" { return symbol(PhpSymbols.T_ELVIS); }
    "@" { return symbol(PhpSymbols.T_AT); }

    "null" { return symbol(PhpSymbols.T_NULL); }
    "true" { return symbol(PhpSymbols.T_TRUE); }
    "false" { return symbol(PhpSymbols.T_FALSE); }
    "echo" { return symbol(PhpSymbols.T_ECHO); }
    "print" { return symbol(PhpSymbols.T_PRINT); }
    "return" { return symbol(PhpSymbols.T_RETURN); }
    "throw" { return symbol(PhpSymbols.T_THROW); }
    "if" { return symbol(PhpSymbols.T_IF); }
    else[ \t\r\n]*if { return symbol(PhpSymbols.T_ELSEIF); }
    "else" { return symbol(PhpSymbols.T_ELSE); }
    "for" { return symbol(PhpSymbols.T_FOR); }
    "foreach" { return symbol(PhpSymbols.T_FOREACH); }
    "while" { return symbol(PhpSymbols.T_WHILE); }
    "do" { return symbol(PhpSymbols.T_DO); }
    "endif" { return symbol(PhpSymbols.T_END_IF); }
    "endfor" { return symbol(PhpSymbols.T_END_FOR); }
    "endforeach" { return symbol(PhpSymbols.T_END_FOREACH); }
    "endwhile" { return symbol(PhpSymbols.T_END_WHILE); }
    "break" { return symbol(PhpSymbols.T_BREAK); }
    "continue" { return symbol(PhpSymbols.T_CONTINUE); }
    "goto" { return symbol(PhpSymbols.T_GOTO); }
    "switch" { return symbol(PhpSymbols.T_SWITCH); }
    "case" { return symbol(PhpSymbols.T_CASE); }
    "endswitch" { return symbol(PhpSymbols.T_END_SWITCH); }
    "default" { return symbol(PhpSymbols.T_DEFAULT); }
    "try" { return symbol(PhpSymbols.T_TRY); }
    "catch" { return symbol(PhpSymbols.T_CATCH); }
    "finally" { return symbol(PhpSymbols.T_FINALLY); }
    "function" { return symbol(PhpSymbols.T_FUNCTION); }
    "class" { return symbol(PhpSymbols.T_CLASS); }
    "enum" { return symbol(PhpSymbols.T_ENUM); }
    "trait" { return symbol(PhpSymbols.T_TRAIT); }
    "interface" { return symbol(PhpSymbols.T_INTERFACE); }
    "extends" { return symbol(PhpSymbols.T_EXTENDS); }
    "implements" { return symbol(PhpSymbols.T_IMPLEMENTS); }
    "static" { return symbol(PhpSymbols.T_STATIC); }
    "abstract" { return symbol(PhpSymbols.T_ABSTRACT); }
    "final" { return symbol(PhpSymbols.T_FINAL); }
    "private" { return symbol(PhpSymbols.T_PRIVATE); }
    "protected" { return symbol(PhpSymbols.T_PROTECTED); }
    "public" { return symbol(PhpSymbols.T_PUBLIC); }
    "const" { return symbol(PhpSymbols.T_CONST); }
    "new" { return symbol(PhpSymbols.T_NEW); }
    "instanceof" { return symbol(PhpSymbols.T_INSTANCEOF); }
    "mixed" { return symbol(PhpSymbols.T_MIXED); }
    "callable" { return symbol(PhpSymbols.T_CALLABLE); }
    "int" { return symbol(PhpSymbols.T_INT); }
    "float" { return symbol(PhpSymbols.T_FLOAT); }
    "bool" { return symbol(PhpSymbols.T_BOOL); }
    "string" { return symbol(PhpSymbols.T_STRING); }
    "array" { return symbol(PhpSymbols.T_ARRAY); }
    "object" { return symbol(PhpSymbols.T_OBJECT); }
    "iterable" { return symbol(PhpSymbols.T_ITERABLE); }
    "resource" { return symbol(PhpSymbols.T_RESOURCE); }
    "void" { return symbol(PhpSymbols.T_VOID); }
    "yield" { return symbol(PhpSymbols.T_YIELD); }
    yield[ \t\r\n]from { return symbol(PhpSymbols.T_YIELD_FROM); }
    "var" { return symbol(PhpSymbols.T_VAR); }
    "global" { return symbol(PhpSymbols.T_GLOBAL); }
    "list" { return symbol(PhpSymbols.T_LIST); }
    "clone" { return symbol(PhpSymbols.T_CLONE); }
    "use" { return symbol(PhpSymbols.T_USE); }
    "namespace" { return symbol(PhpSymbols.T_NAMESPACE); }
    "as" { return symbol(PhpSymbols.T_AS); }
    "require" { return symbol(PhpSymbols.T_REQUIRE); }
    "require_once" { return symbol(PhpSymbols.T_REQUIRE_ONCE); }
    "include" { return symbol(PhpSymbols.T_INCLUDE); }
    "include_once" { return symbol(PhpSymbols.T_INCLUDE_ONCE); }
    "eval" { return symbol(PhpSymbols.T_EVAL); }
    "exit" { return symbol(PhpSymbols.T_EXIT); }
    "die" { return symbol(PhpSymbols.T_DIE); }
    "declare" { return symbol(PhpSymbols.T_DECLARE); }
    "self" { return symbol(PhpSymbols.T_SELF); }
    "parent" { return symbol(PhpSymbols.T_PARENT); }

    \$({LABEL}) { return symbol(PhpSymbols.T_VAR_NAME, yytext()); }
    ({LABEL}) { return symbol(PhpSymbols.T_SIMPLE_NAME, yytext()); }
    (\\{LABEL}\\?)|(\\{LABEL})+\\?|(({LABEL}\\)+{LABEL}\\?)|({LABEL}\\) {
        return symbol(PhpSymbols.T_QUALIFIED_NAME, yytext());
    }
    [+-]?(([0-9]+(_[0-9]+)*)|(0b[01]+(_[01]+)*)|(0x[0-9a-fA-F]+(_[0-9a-fA-F])*)) {
        return symbol(PhpSymbols.T_INT_VALUE, yytext());
    }
    [+-]?([0-9]+\.[0-9]*|\.[0-9]+) { return symbol(PhpSymbols.T_FLOAT_VALUE, yytext()); }
    [+-]?[0-9]+e[+-]?[0-9]+ { return symbol(PhpSymbols.T_FLOAT_E_VALUE, yytext()); }
}

<XS_CODE> {
    "{" { return symbol(PhpSymbols.T_OPEN_CURLY); }
    "}" { return symbol(PhpSymbols.T_CLOSE_CURLY); }
}

<XS_INLINE_CODE> {
    "{" {
        ++inlineCodeOpenCount;
        return symbol(PhpSymbols.T_OPEN_CURLY);
    }
    "}" {
        if (--inlineCodeOpenCount == 0) popState();
        return symbol(PhpSymbols.T_CLOSE_CURLY);
    }
}

<XS_SQUOTE> {
    ['] {
        popState();
        return symbol(PhpSymbols.T_SINGLE_QUOTE, "E");
    }
    (\\') { return symbol(PhpSymbols.T_STR_VALUE, "\\'"); }
    \\\\ { return symbol(PhpSymbols.T_STR_VALUE, "\\\\"); }
    ([^'\\]|\\[^'])+ { return symbol(PhpSymbols.T_STR_VALUE, yytext()); }
}

<XS_DQUOTE> {
    [\"] {
        popState();
        return symbol(PhpSymbols.T_DOUBLE_QUOTE, "E");
    }
    ([^\\\"\$]|\\[^nrtvef\\$\"0-7xu]|\\x[^0-9a-fA-F]|\\u[^{]|\{[^$]|\$[^a-zA-Z_\x80-\xFF{])+ {
        return symbol(PhpSymbols.T_STR_VALUE, yytext());
    }
}

<XS_DQUOTE, XS_HEAR_DOC, XS_BQUOTE> {
    \\n { return symbol(PhpSymbols.T_STR_VALUE, "\n"); }
    \\r { return symbol(PhpSymbols.T_STR_VALUE, "\r"); }
    \\t { return symbol(PhpSymbols.T_STR_VALUE, "\t"); }
    \\v { return symbol(PhpSymbols.T_STR_VALUE, TEXT_ESCAPE_V); }
    \\e { return symbol(PhpSymbols.T_STR_VALUE, TEXT_ESCAPE_E); }
    \\f { return symbol(PhpSymbols.T_STR_VALUE, TEXT_ESCAPE_F); }
    \\\\ { return symbol(PhpSymbols.T_STR_VALUE, "\\\\"); }
    \\$ { return symbol(PhpSymbols.T_STR_VALUE, "$"); }
    \\\" { return symbol(PhpSymbols.T_STR_VALUE, "\""); }
    \\[0-7]{1, 3} { return parseTextEscapeOct(); }
    \\x[0-9a-fA-F]{1, 2} { return parseTextEscapeHex(); }
    \\u\{[^}]*\}? { return parseTextEscapeUnicode(); }
    "${" {
        pushState(XS_DOLLAR_INLINE_CODE);
        return symbol(PhpSymbols.T_OPEN_DOLLAR_CURLY);
    }
    "{$" {
        pushState(XS_INLINE_CODE);
        yypushback(1);
        return symbol(PhpSymbols.T_OPEN_CURLY);
    }
    "$"({LABEL})"[" {
        pushState(XS_INLINE_ARRAY_KEY);
        yypushback(1);
        return symbol(PhpSymbols.T_VAR_NAME, yytext());
    }
    "$"({LABEL}) { return symbol(PhpSymbols.T_VAR_NAME, yytext()); }
}

<XS_DOLLAR_INLINE_CODE> {
    "}" {
        popState();
        return symbol(PhpSymbols.T_CLOSE_CURLY);
    }
    ({LABEL}) { return symbol(PhpSymbols.T_VAR_NAME, yytextPrefixWith('$')); }
    "[" { return symbol(PhpSymbols.T_OPEN_SQUARE); }
    "]" { return symbol(PhpSymbols.T_CLOSE_SQUARE); }
    [+-]?(([0-9]+(_[0-9]+)*)|(0b[01]+(_[01]+)*)|(0x[0-9a-fA-F]+(_[0-9a-fA-F])*)) {
        return symbol(PhpSymbols.T_INT_VALUE, yytext());
    }
    \$({LABEL}) { return symbol(PhpSymbols.T_VAR_NAME, yytext()); }
    "'" {
        pushState(XS_SQUOTE);
        return symbol(PhpSymbols.T_SINGLE_QUOTE, "S");
    }
    "\"" {
        pushState(XS_DQUOTE);
        return symbol(PhpSymbols.T_DOUBLE_QUOTE, "S");
    }
    "`" {
        pushState(XS_BQUOTE);
        return symbol(PhpSymbols.T_BACK_QUOTE, "S");
    }
}

<XS_INLINE_ARRAY_KEY> {
    "[" { return symbol(PhpSymbols.T_OPEN_SQUARE); }
    "]" {
        popState();
    }
    -?0 { return symbol(PhpSymbols.T_INT_VALUE, "0"); }
    -?[1-9][0-9]* { return symbol(PhpSymbols.T_INT_VALUE, yytext()); }
    ({LABEL}) { return symbol(PhpSymbols.T_STR_VALUE, yytext()); }
}

<XS_COMMENT> {
    "*/" {
        popState();
        return symbol(PhpSymbols.T_CLOSE_BLOCK_COMMENT);
    }
    ([^*]|\*[^/])+ { return symbol(PhpSymbols.T_BLOCK_COMMENT_CONTENT, yytext()); }
}

<XS_SQUOTE, XS_DQUOTE, XS_BQUOTE><<EOF>> {
    throw new LexerException(yyline, yycolumn, "Unexpected EOF");
}