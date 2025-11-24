package top.kmar.php;

import java.io.Reader;
import java.util.*;
import java_cup.runtime.*;
import java_cup.runtime.symbol.Location;
import java_cup.runtime.symbol.complex.*;
import top.kmar.php.exceptions.LexerException;

%%

%{

    private int[] stateStack = new int[16];
    private int topStateIndex = -1;
    private char[] charBuffer = null;

    private SymbolFactory factory;

    public PhpLexer(Reader reader, SymbolFactory factory) {
        this(reader);
        this.factory = factory;
    }

    private void pushState(int newState) {
        stateStack[++topStateIndex] = yystate();
        yybegin(newState);
    }

    private void popState() {
        yybegin(stateStack[topStateIndex--]);
    }

    /* 字符串缓冲区 */

    private StringBuilder stringBuffer = null;

    private void pushStringBuffer() {
        stringBuffer = new StringBuilder();
    }

    private String popStringBuffer() {
        var text = stringBuffer.toString();
        stringBuffer = null;
        return text;
    }

    private void writeToBuffer(String text) {
        stringBuffer.append(text);
    }

    private void writeToBuffer(char c) {
        stringBuffer.append(c);
    }

    private void cpyTextToBuffer() {
        stringBuffer.append(zzBuffer, zzStartRead, zzMarkedPos - zzStartRead);
    }

    /** DOC 存储 */
    private String docLabel = null;

    /* 坐标缓冲区 */

    private final int[] posBuffer = new int[2];

    private void pushPos() {
        posBuffer[0] = yyline + 1;
        posBuffer[1] = yycolumn + 1;
    }

    private Location popPos() {
        int startLine = posBuffer[0];
        int startColumn = posBuffer[1];
        int endLine = yyline + 1;
        int endColumn = yycolumn + 1 + yylength();
        return ComplexLocation.of(startLine, startColumn, endLine, endColumn);
    }

    private Symbol symbol(int type) {
        return factory.newSymbol(type, location());
    }

    private Symbol symbol(int type, Object value) {
        return factory.newSymbol(type, location(), value);
    }

    private Symbol symbol(int type, Location location, Object value) {
        return factory.newSymbol(type, location, value);
    }

    private Symbol symbol(int type, long value) {
        return factory.newSymbol(type, location(), value);
    }

    private Symbol symbol(int type, double value) {
        return factory.newSymbol(type, location(), value);
    }

    private Symbol symbol(int type, boolean value) {
        return factory.newSymbol(type, location(), value);
    }

    private ComplexLocation location() {
        var line = yyline + 1;
        var startColumn = yycolumn + 1;
        return ComplexLocation.of(
            line,
            startColumn,
            line,
            startColumn + yylength()
        );
    }

    private Location locationOffsetWith(String text) {
        int startLine = yyline + 1;
        int startColumn = yycolumn + 1;
        int endLine = yyline + 1;
        int endColumn = startColumn;
        for (int i = 0; i != text.length(); ++i) {
            char c = text.charAt(i);
            if (c == '\n') {
                ++endLine;
                endColumn = 0;
            } else {
                ++endColumn;
            }
        }
        return ComplexLocation.of(startLine, startColumn, endLine, endColumn);
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

    private String yytext(int startOffset) {
        int startIndex = zzStartRead + startOffset;
        return String.valueOf(zzBuffer, startIndex, zzMarkedPos - startIndex);
    }

    private String yytext(int startOffset, int endOffset) {
        int startIndex = zzStartRead + startOffset;
        return String.valueOf(zzBuffer, startIndex, zzMarkedPos - startIndex + endOffset);
    }

    private boolean yyStartsWith(String prefix, int startOffset) {
        int length = prefix.length();
        if (zzMarkedPos - zzStartRead < length) return false;
        int startIndex = zzStartRead + startOffset;
        for (int i = 0; i != length; ++i) {
            if (prefix.charAt(i) != zzBuffer[startIndex + i]) return false;
        }
        return true;
    }

    /** 处理 `\[0-7]{1, 3}` 形式的八进制转义 */
    private Symbol parseTextEscapeOct() {
        int length = yylength();
        int value = 0;
        for (int i = 1; i != length; ++i) {
            value = value << 3 | (yycharat(i) - '0');
        }
        if (value > 0xFF) throw new LexerException(yyline, yycolumn, "Octal escape sequence is out of range");
        return symbol(PhpSymbols.T_STRING_VALUE, String.valueOf((char) value));
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
        return symbol(PhpSymbols.T_STRING_VALUE, String.valueOf((char) value));
    }

    /** 处理 `\\u\{[0-9a-fA-F]+\}` 形式的十六进制转义 */
    private Symbol parseTextEscapeUnicode() {
        int length = yylength() - 1;
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
        return symbol(PhpSymbols.T_STRING_VALUE, String.valueOf((char) value));
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
%x XS_INLINE_CALL
// 代码部分
%x XS_CODE

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
// 浮点数
DNUM = ([0-9]*"."[0-9]+)|([0-9]+"."[0-9]*)

%%

<YYINITIAL, XS_CODE> {
    [ \t\n]+ { }
}

<YYINITIAL> {
    "<?php" {
        pushState(XS_CODE);
        return symbol(PhpSymbols.T_CODE_BLOCK);
    }
}

<XS_CODE> {
    [\"] {
        pushState(XS_DQUOTE);
        return symbol(PhpSymbols.T_DOUBLE_QUOTE);
    }
    ['] {
        pushState(XS_SQUOTE);
        pushPos();
        pushStringBuffer();
    }
    [`] {
        pushState(XS_BQUOTE);
        return symbol(PhpSymbols.T_BACK_QUOTE);
    }

    (#|\/\/)[^\n]* { /* return symbol(PhpSymbols.T_LINE_COMMENT, yytext()); */ }
    "/*" [^*]* "*"+ ([^*/] [^*]* "*"+)* "/" { }

    "(" { return symbol(PhpSymbols.T_OPEN_PAREN); }
    ")" { return symbol(PhpSymbols.T_CLOSE_PAREN); }
    "[" { return symbol(PhpSymbols.T_OPEN_SQUARE); }
    "]" { return symbol(PhpSymbols.T_CLOSE_SQUARE); }
    "{" { return symbol(PhpSymbols.T_OPEN_CURLY); }
    "}" { return symbol(PhpSymbols.T_CLOSE_CURLY); }

    ";" { return symbol(PhpSymbols.T_SEMI); }
    "," { return symbol(PhpSymbols.T_COMMA); }
    "." { return symbol(PhpSymbols.T_DOT); }
    "=>" { return symbol(PhpSymbols.T_DOUBLE_ARROW); }
    "->" { return symbol(PhpSymbols.T_SINGLE_ARROW); }
    "..." { return symbol(PhpSymbols.T_ELLIPSIS); }
    "::" { return symbol(PhpSymbols.T_SCOPE_RESOLUTION); }
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
    "**=" { return symbol(PhpSymbols.T_POW_ASSIGN); }
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
    "??=" { return symbol(PhpSymbols.T_COALESCE_ASSIGN); }
    "!" { return symbol(PhpSymbols.T_BOOL_NOT); }
    "&&" { return symbol(PhpSymbols.T_BOOL_AND); }
    "||" { return symbol(PhpSymbols.T_BOOL_OR); }
    "==" { return symbol(PhpSymbols.T_DOUBLE_EQ); }
    ==|<> { return symbol(PhpSymbols.T_NOT_DOUBLE_EQ); }
    "===" { return symbol(PhpSymbols.T_TRIPLE_EQ); }
    "!==" { return symbol(PhpSymbols.T_NOT_TRIPLE_EQ); }
    "<=>" { return symbol(PhpSymbols.T_SPACESHIP); }
    "<" { return symbol(PhpSymbols.T_LT); }
    ">" { return symbol(PhpSymbols.T_GT); }
    "<=" { return symbol(PhpSymbols.T_LT_EQ); }
    ">=" { return symbol(PhpSymbols.T_GT_EQ); }
    "?" { return symbol(PhpSymbols.T_QUESTION); }
    ":" { return symbol(PhpSymbols.T_COLON); }
    "**" { return symbol(PhpSymbols.T_POW); }
    "??" { return symbol(PhpSymbols.T_COALESCE); }
    "$" { return symbol(PhpSymbols.T_DOLLAR); }
    "@" { return symbol(PhpSymbols.T_AT); }

    "null" { return symbol(PhpSymbols.T_NULL); }
    "true" { return symbol(PhpSymbols.T_TRUE); }
    "false" { return symbol(PhpSymbols.T_FALSE); }
    "echo" { return symbol(PhpSymbols.T_ECHO); }
    "print" { return symbol(PhpSymbols.T_PRINT); }
    "return" { return symbol(PhpSymbols.T_RETURN); }
    "throw" { return symbol(PhpSymbols.T_THROW); }
    "if" { return symbol(PhpSymbols.T_IF); }
    else[ \t\n]*if { return symbol(PhpSymbols.T_ELSEIF); }
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
//    "enum" { return symbol(PhpSymbols.T_ENUM); }
    "trait" { return symbol(PhpSymbols.T_TRAIT); }
    "interface" { return symbol(PhpSymbols.T_INTERFACE); }
    "extends" { return symbol(PhpSymbols.T_EXTENDS); }
    "implements" { return symbol(PhpSymbols.T_IMPLEMENTS); }
    "insteadof" { return symbol(PhpSymbols.T_INSTEADOF); }
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
    yield[ \t\n]from { return symbol(PhpSymbols.T_YIELD_FROM); }
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
    "declare" { return symbol(PhpSymbols.T_DECLARE); }
    "self" { return symbol(PhpSymbols.T_SELF); }
    "parent" { return symbol(PhpSymbols.T_PARENT); }


    "<<<'"({LABEL})"'"\n {
        var len = yylength();
        docLabel = yytext(4, -2);
        pushState(XS_NEW_DOC);
        pushStringBuffer();
        pushPos();
    }
    "<<<"({LABEL})\n {
        var len = yylength();
        docLabel = yytext(3, -1);
        pushState(XS_HEAR_DOC);
        return symbol(PhpSymbols.T_HEREDOC_START);
    }

    \$({LABEL}) { return symbol(PhpSymbols.T_VAR_NAME, yytext(1)); }
    ({LABEL}) { return symbol(PhpSymbols.T_NAME, yytext()); }
    (\\{LABEL}\\?)|(\\{LABEL})+\\?|(({LABEL}\\)+{LABEL}\\?)|({LABEL}\\) {
        return symbol(PhpSymbols.T_QUALIFIED_NAME, yytext());
    }

    [^] { throw new RuntimeException(yytext()); }
}

<XS_CODE, XS_INLINE_CODE, XS_DOLLAR_INLINE_CODE> {
    "+" { return symbol(PhpSymbols.T_PLUS); }
    "-" { return symbol(PhpSymbols.T_MINUS); }

    // 十六进制整数
    0[xX][0-9a-fA-F]+     {
        return symbol(PhpSymbols.T_INT_VALUE, Long.parseLong(yytext(2), 16));
    }
    // 二进制整数
    0[bB][01]+            {
        return symbol(PhpSymbols.T_INT_VALUE, Long.parseLong(yytext(2), 2));
    }
    // 八进制整数
    0[oO]?[0-7]+          {
        char sec = yycharat(1);
        int offset = sec == 'o' || sec == 'O' ? 2 : 1;
        return symbol(PhpSymbols.T_INT_VALUE, Long.parseLong(yytext(offset), 8));
    }
    // 十进制整数
    [1-9][0-9]*|0         {
        return symbol(PhpSymbols.T_INT_VALUE, Long.parseLong(yytext()));
    }

    // 浮点数
    ({DNUM})|(({DNUM}|[0-9]+)[eE][+-]?[0-9]+)  {
        return symbol(PhpSymbols.T_FLOAT_VALUE, Double.parseDouble(yytext()));
    }
}

<XS_SQUOTE> {
    ['] {
        popState();
        return factory.newSymbol(PhpSymbols.T_SINGLE_STRING, popPos(), popStringBuffer());
    }
    \\' {
        writeToBuffer('\'');
    }
    \\\\ {
        writeToBuffer('\\');
    }
    [^\\']+ {
        cpyTextToBuffer();
    }

}

<XS_NEW_DOC> {
    \n?[^\n]+ {
        char first = yycharat(0);
        int offset = first == '\n' ? 1 : 0;
        var len = yylength();
        var labelLen = docLabel.length();
        switch (len - labelLen - offset) {
            case 0:
                if (yyStartsWith(docLabel, offset)) {
                    popState();
                    return symbol(PhpSymbols.T_NEWDOC, popPos(), popStringBuffer());
                } else {
                    cpyTextToBuffer();
                }
                break;
            case 1:
                if (yyStartsWith(docLabel, offset) && yycharat(labelLen + offset) == ';') {
                    yypushback(1);
                    popState();
                    return symbol(PhpSymbols.T_NEWDOC, popPos(), popStringBuffer());
                } else {
                    cpyTextToBuffer();
                }
                break;
            default:
                cpyTextToBuffer();
                break;
        }
    }
    \n {
        writeToBuffer('\n');
    }
}

<XS_DQUOTE, XS_BQUOTE> {
    [\"] {
        popState();
        return symbol(PhpSymbols.T_DOUBLE_QUOTE);
    }
    ([^\\\"\$\{]|\\[^nrtvef\\$\"0-7xu]|\\x[^0-9a-fA-F]|\\u[^{]|\{[^$]|\$[^a-zA-Z_\x80-\xFF{])+ {
        return symbol(PhpSymbols.T_STRING_VALUE, yytext());
    }
}

<XS_DQUOTE, XS_HEAR_DOC, XS_BQUOTE> {
    \\n { return symbol(PhpSymbols.T_STRING_VALUE, "\n"); }
    \\r { return symbol(PhpSymbols.T_STRING_VALUE, "\r"); }
    \\t { return symbol(PhpSymbols.T_STRING_VALUE, "\t"); }
    \\v { return symbol(PhpSymbols.T_STRING_VALUE, TEXT_ESCAPE_V); }
    \\e { return symbol(PhpSymbols.T_STRING_VALUE, TEXT_ESCAPE_E); }
    \\f { return symbol(PhpSymbols.T_STRING_VALUE, TEXT_ESCAPE_F); }
    \\\\ { return symbol(PhpSymbols.T_STRING_VALUE, "\\\\"); }
    \\$ { return symbol(PhpSymbols.T_STRING_VALUE, "$"); }
    \\\" { return symbol(PhpSymbols.T_STRING_VALUE, "\""); }
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
        return symbol(PhpSymbols.T_VAR_NAME, yytext(1));
    }
    "$"({LABEL})"->" {
        pushState(XS_INLINE_CALL);
        yypushback(2);
        return symbol(PhpSymbols.T_VAR_NAME, yytext(1));
    }
    "$"({LABEL}) { return symbol(PhpSymbols.T_VAR_NAME, yytext(1)); }
}

<XS_HEAR_DOC> {
    \n?([^\\\"\$\n\{]|\\[^nrtvef\\$\"0-7xu]|\\x[^0-9a-fA-F]|\\u[^{]|\{[^$]|\$[^a-zA-Z_\x80-\xFF{])+ {
        char first = yycharat(0);
        int offset = first == '\n' ? 1 : 0;
        var len = yylength();
        var labelLen = docLabel.length();
        switch (len - labelLen - offset) {
            case 0: {
                if (yyStartsWith(docLabel, offset)) {
                    return symbol(PhpSymbols.T_HEREDOC_END);
                } else {
                    return symbol(PhpSymbols.T_STRING_VALUE, yytext());
                }
            }
            case 1:
                if (yyStartsWith(docLabel, offset) && yycharat(labelLen + offset) == ';') {
                    yypushback(1);
                    popState();
                    return symbol(PhpSymbols.T_HEREDOC_END);
                } else {
                    return symbol(PhpSymbols.T_STRING_VALUE, yytext());
                }
            default:
                return symbol(PhpSymbols.T_STRING_VALUE, yytext());
        }
    }
    \n {
        return symbol(PhpSymbols.T_STRING_VALUE, "\n");
    }
}

<XS_INLINE_CODE> {
    \$({LABEL}) { return symbol(PhpSymbols.T_VAR_NAME, yytext(1)); }
}

<XS_DOLLAR_INLINE_CODE, XS_INLINE_CODE> {
    ({LABEL}) { return symbol(PhpSymbols.T_VAR_NAME, yytext()); }
    "}" {
        popState();
        return symbol(PhpSymbols.T_CLOSE_CURLY);
    }
    "[" { return symbol(PhpSymbols.T_OPEN_SQUARE); }
    "]" { return symbol(PhpSymbols.T_CLOSE_SQUARE); }
    \$ { return symbol(PhpSymbols.T_DOLLAR); }
    "'" {
        pushState(XS_SQUOTE);
        pushStringBuffer();
        pushPos();
    }
    "\"" {
        pushState(XS_DQUOTE);
        return symbol(PhpSymbols.T_DOUBLE_QUOTE);
    }
    "`" {
        pushState(XS_BQUOTE);
        return symbol(PhpSymbols.T_BACK_QUOTE);
    }
    "->" { return symbol(PhpSymbols.T_SINGLE_ARROW); }
    [^] { throw new LexerException(yyline + 1, yycolumn + 1, "Unexpected character '" + yycharat(0) + "'"); }
}

<XS_INLINE_ARRAY_KEY> {
    "[" { return symbol(PhpSymbols.T_OPEN_SQUARE); }
    "]" {
        popState();
        return symbol(PhpSymbols.T_CLOSE_SQUARE);
    }
    "0" { return symbol(PhpSymbols.T_INT_VALUE, 0); }
    -?[1-9][0-9]* { return symbol(PhpSymbols.T_INT_VALUE, Long.parseLong(yytext())); }
    ({LABEL}|-?0(a-zA-Z0-9_\x80-\xFF)*) { return symbol(PhpSymbols.T_STRING_VALUE, yytext()); }
    [^] { throw new LexerException(yyline + 1, yycolumn + 1, "Unexpected character '" + yycharat(0) + "'"); }
}

<XS_INLINE_CALL> {
    "->" {
        return symbol(PhpSymbols.T_SINGLE_ARROW);
    }
    ({LABEL}) {
        popState();
        return symbol(PhpSymbols.T_NAME, yytext());
    }
    [^] { throw new LexerException(yyline + 1, yycolumn + 1, "Unexpected character '" + yycharat(0) + "'"); }
}

<YYINITIAL> {
    [^] { throw new LexerException(yyline + 1, yycolumn + 1, "Unexpected character '" + yycharat(0) + "'"); }
}

<<EOF>> {
    if (topStateIndex < 1) {
        return symbol(PhpSymbols.EOF);
    }
    throw new LexerException(yyline + 1, yycolumn + 1, "Unexpected end of file in state[" + yystate() + ']');
}