/* -----------------------------------------------------------------------------
 * PHP 7.2 词法分析器
 *
 * 翻译自 php-src PHP-7.2.34 的 Zend/zend_language_scanner.l（re2c），
 * 并按照“纯 PHP、不支持内联 HTML”的目标做了三处调整：
 *
 * 1. 文件必须以 <?php 开头（后跟空白或换行），否则报错；
 *    原 INITIAL 状态的 T_INLINE_HTML 机制整体移除。
 * 2. "?>" 按 zendlex()（zend_compile.c:1714）的语义处理：等价于 ';'（隐式分号），
 *    随后进入 SKIP_HTML 状态丢弃所有内容，直到下一个 "<?php" 或 EOF。
 * 3. "__halt_compiler ();" 作为单一 token 返回（词法层已要求 "();" 完整出现），
 *    之后按 zend_stop_lexing() 语义丢弃剩余内容。
 *
 * 其余规则（状态机、最长匹配语义、关键字/标签冲突、heredoc/nowdoc、
 * 字符串插值、数字进制、注释中 "?>" 提前结束等）均与 zend 一致。
 *
 * 已知的语义保留差异（语义层的处理属于后处理阶段）：
 * - 字符串转义序列不做解码，token 保留原始文本；
 * - 整数写法按 64 位有符号整数范围区分 T_LNUMBER / T_DNUMBER，不解析实际数值；
 * - 无法识别的字符按 zend 语义警告后跳过（zend 为 E_COMPILE_WARNING）。
 * -------------------------------------------------------------------------- */

package top.kmar.php;

import java_cup.runtime.Symbol;
import java_cup.runtime.symbol.Location;
import java_cup.runtime.symbol.complex.ComplexLocation;
import java_cup.runtime.symbol.complex.ComplexSymbolFactory;

import java.util.ArrayDeque;

%%

%class PhpLexer
%public
%unicode
%line
%column
%cup

/* 状态：SCRIPTING=ST_IN_SCRIPTING，SKIP_HTML=去掉 HTML 后的 INITIAL，
 * DQ/BQ=双引号/反引号，HEREDOC/HEREDOC_BEGIN=heredoc 正文/行首，
 * NOWDOC/NOWDOC_BEGIN 同理，其余三个与 zend 同名，HALT=halt_compiler 之后 */
%state SCRIPTING, SKIP_HTML, DQ, BQ, HEREDOC, HEREDOC_BEGIN, NOWDOC, NOWDOC_BEGIN, LOOKING_PROPERTY, LOOKING_VARNAME, VAR_OFFSET, HALT

%{
    /** 符号工厂，由构造器注入（与 CUP fork 的推荐用法一致） */
    private ComplexSymbolFactory symbolFactory;

    /** 状态栈，等价于 zend 的 yy_push_state / yy_pop_state */
    private final ArrayDeque<Integer> stateStack = new ArrayDeque<>();

    /** heredoc/nowdoc 结束标签栈 */
    private final ArrayDeque<String> heredocLabels = new ArrayDeque<>();

    public PhpLexer(java.io.Reader in, ComplexSymbolFactory sf) {
        this(in);
        this.symbolFactory = sf;
    }

    public PhpLexer(java.io.InputStream in) {
        this(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8),
                new ComplexSymbolFactory(PhpSymbols.TERMINAL_NAMES, PhpSymbols.NON_TERMINAL_NAMES));
    }

    /** 等价 yy_push_state：保存当前状态并切换 */
    private void pushState(int newState) {
        stateStack.push(yystate());
        yybegin(newState);
    }

    /** 等价 yy_pop_state */
    private void popState() {
        yybegin(stateStack.pop());
    }

    /** token 位置：1-based 行列，起点包含、终点不包含。
     *  token 内部的换行符会被正确计入结束行列（jflex 不会为本次匹配更新计数）。 */
    private ComplexLocation loc() {
        String s = yytext();
        int len = s.length();
        int line = yyline + 1;
        int col = yycolumn + 1;
        int lineCount = 0;
        int lastNewline = -1;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                lineCount++;
                lastNewline = i;
            } else if (c == '\r') {
                if (i + 1 < len && s.charAt(i + 1) == '\n') continue;
                lineCount++;
                lastNewline = i;
            }
        }
        int endLine = line + lineCount;
        int endCol = lineCount == 0 ? col + len : len - lastNewline;
        return ComplexLocation.of(line, col, endLine, endCol);
    }

    /** token 工厂：所有 token 的值统一为原始文本 */
    private Symbol tk(int id) {
        return symbolFactory.newSymbol(id, loc(), yytext());
    }

    /** 带指定文本的 token（用于需要裁剪/替换文本的场景） */
    private Symbol symbol(int id, String text) {
        return symbolFactory.newSymbol(id, loc(), text);
    }

    /** heredoc / nowdoc 结束标签判定。
     *  当前匹配形如 LABEL (";")? NEWLINE：与栈顶标签一致则结束（回退 ";"/换行）；
     *  否则整行是普通文本，继续在下一行行首检查标签。
     *  返回 true 表示已作为 T_END_HEREDOC 返回。 */
    private boolean finishHeredoc(boolean nowdoc) {
        String label = heredocLabels.peek();
        String text = yytext();
        if (text.length() > label.length() && text.startsWith(label)) {
            char next = text.charAt(label.length());
            if (next == ';' || next == '\r' || next == '\n') {
                // 回退 ";" 和换行；";" 由 SCRIPTING 状态按普通分号处理（与 zend 一致）
                yypushback(yylength() - label.length());
                heredocLabels.pop();
                yybegin(SCRIPTING);
                return true;
            }
        }
        yybegin(nowdoc ? NOWDOC_BEGIN : HEREDOC_BEGIN);
        return false;
    }

    /** 行首一旦消费正文或开始插值，就不能再把同行后续内容识别成结束标签。 */
    private void leaveHeredocBeginning() {
        if (yystate() == HEREDOC_BEGIN) yybegin(HEREDOC);
    }

    /** 文本 token 保留原文；行首状态严格跟随实际消费的换行。 */
    private Symbol encapsedToken() {
        int state = yystate();
        if (state == HEREDOC || state == HEREDOC_BEGIN || state == NOWDOC || state == NOWDOC_BEGIN) {
            String text = yytext();
            boolean lineStart = text.endsWith("\r") || text.endsWith("\n");
            boolean nowdoc = state == NOWDOC || state == NOWDOC_BEGIN;
            yybegin(nowdoc ? (lineStart ? NOWDOC_BEGIN : NOWDOC)
                    : (lineStart ? HEREDOC_BEGIN : HEREDOC));
        }
        return tk(PhpSymbols.T_ENCAPSED_AND_WHITESPACE);
    }

    /** 候选字符串只扫描到首个未转义的引号；插值交由相应词法状态继续处理。 */
    private boolean containsInterpolation(String text) {
        int quote = text.charAt(0) == '"' ? 0 : 1;
        for (int i = quote + 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '$') {
                int next = text.codePointAt(i + 1);
                if (next == '{' || isLabelStart(next)) return true;
            } else if (c == '{' && text.charAt(i + 1) == '$') {
                return true;
            }
        }
        return false;
    }

    private boolean isLabelStart(int c) {
        return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'
                || c >= 0x80 && c <= 0xffff;
    }

    /** TOKENS 单字符符号 -> 终结符常量 */
    private Symbol singleCharToken(char c) {
        int id;
        switch (c) {
            case ';': id = PhpSymbols.SEMI; break;
            case ':': id = PhpSymbols.COLON; break;
            case ',': id = PhpSymbols.COMMA; break;
            case '.': id = PhpSymbols.DOT; break;
            case '[': id = PhpSymbols.LBRACK; break;
            case ']': id = PhpSymbols.RBRACK; break;
            case '(': id = PhpSymbols.LPAREN; break;
            case ')': id = PhpSymbols.RPAREN; break;
            case '|': id = PhpSymbols.PIPE; break;
            case '^': id = PhpSymbols.CARET; break;
            case '&': id = PhpSymbols.AMP; break;
            case '+': id = PhpSymbols.PLUS; break;
            case '-': id = PhpSymbols.MINUS; break;
            case '*': id = PhpSymbols.STAR; break;
            case '/': id = PhpSymbols.SLASH; break;
            case '=': id = PhpSymbols.EQUAL; break;
            case '%': id = PhpSymbols.PERCENT; break;
            case '!': id = PhpSymbols.BANG; break;
            case '~': id = PhpSymbols.TILDE; break;
            case '$': id = PhpSymbols.DOLLAR; break;
            case '<': id = PhpSymbols.LT; break;
            case '>': id = PhpSymbols.GT; break;
            case '?': id = PhpSymbols.QUESTION; break;
            case '@': id = PhpSymbols.AT; break;
            default: throw new IllegalStateException("非 TOKENS 字符: " + c);
        }
        return tk(id);
    }

    /** 整数写法按 64 位有符号范围分类；只比较有效位数和字符，保留原始文本。 */
    private Symbol numberToken(int radix) {
        String text = yytext();
        int start = radix == 10 ? 0 : 2;
        if (radix == 10 && text.length() > 1 && text.charAt(0) == '0') {
            radix = 8;
            // LNUM 也匹配 8/9；即使长度已经超限，仍须检查整个八进制字面量。
            for (int i = 1; i < text.length(); i++) {
                if (text.charAt(i) > '7') {
                    throw new PhpLexerException("Invalid numeric literal (非法八进制): " + text, loc());
                }
            }
        }
        while (start < text.length() && text.charAt(start) == '0') {
            start++;
        }
        int digits = text.length() - start;
        boolean fitsInteger = switch (radix) {
            case 2 -> digits <= 63;
            case 8 -> digits <= 21;
            // 十六进制的第 16 位只能使用 3 个有效二进制位。
            case 16 -> digits < 16 || (digits == 16 && text.charAt(start) <= '7');
            // 十进制分支没有前缀或前导零，可直接比较完整原文。
            case 10 -> digits < 19 || (digits == 19 && text.compareTo("9223372036854775807") <= 0);
            default -> throw new IllegalArgumentException("Unsupported numeric radix: " + radix);
        };
        return symbol(fitsInteger ? PhpSymbols.T_LNUMBER : PhpSymbols.T_DNUMBER, text);
    }

    private void warn(String message) {
        System.err.println("PHP lexer 警告 " + loc() + ": " + message);
    }
%}

/* ---------------- 宏（与 zend_language_scanner.l 对应） ---------------- */

NEWLINE        = \r\n|[\r\n]
LNUM           = [0-9]+
HNUM           = "0"[xX][0-9a-fA-F]+
BNUM           = "0"[bB][01]+
DNUM           = ([0-9]*\.[0-9]+)|([0-9]+\.[0-9]*)
EXPONENT_DNUM  = ({LNUM}|{DNUM})[eE][+-]?{LNUM}
WHITESPACE     = [ \n\r\t]+
TABS           = [ \t]*
LABEL          = [a-zA-Z_\x80-\uffff][a-zA-Z0-9_\x80-\uffff]*

/* 单字符 TOKENS（zend 的单字符操作符类，见下方 TOKENS 定义），{ } ` 单独处理 */
TOKENS         = [;:,\.\[\]()|\^&+\-*\/=%!~\$<>?@]

/* $ 和 { 留给公共插值规则或单字符文本规则，避免 $$name、{{$name} 等重叠触发被吞掉。
 * 转义不能跨过换行，以便 heredoc 总能在下一行检查结束标签。 */
DQ_TXT   = ([^$\r\n{\\\"]|\\[^\r\n])
BQ_TXT   = ([^$\r\n{\\`]|\\[^\r\n])
HDOC_TXT = ([^$\r\n{\\]|\\[^\r\n])

/* 行注释：内容里 "?>" 会提前结束注释（zend 语义），\?* 处理结尾连续问号 */
LINE_COMMENT_TAIL = ([^?\r\n]|"?"[^>\r\n])*\?*

/* 先匹配到首个未转义引号，再检查插值；不能让候选跨过其它表达式中的引号。 */
CONST_DQ_STR   = [bB]?\"([^\"\\]|\\[^])*\"

/* heredoc 起始：<<< [空白] (LABEL | 'LABEL' | [lL][aA][bB][eE][lL]) 换行 */
HEREDOC_OPEN   = [bB]?"<<<"{TABS}({LABEL}|[']{LABEL}[']|\"{LABEL}\"){NEWLINE}

%eofval{
    return symbolFactory.newSymbol(PhpSymbols.EOF, Location.NO_LOCATION);
%eofval}

%%

/* ============ 文件头：必须是 <?php（后跟空白/换行），不支持 HTML ============ */

<YYINITIAL> {
  "<?"[pP][hH][pP]([ 	]|{NEWLINE})    { yybegin(SCRIPTING); }
  [^]  { throw new PhpLexerException(
             "纯 PHP 模式要求文件以 <?php 开头，实际内容: \"" + yytext() + "\"", loc()); }
}

/* ============ ?> ：隐式分号 + 丢弃到下一个 <?php 为止 ============ */

<SCRIPTING> "?>"{NEWLINE}? {
    yybegin(SKIP_HTML);
    return symbol(PhpSymbols.SEMI, "?>");
}

<SKIP_HTML> {
  "<?"[pP][hH][pP]([ 	]|{NEWLINE})    { yybegin(SCRIPTING); }
  [^<]+                       { }
  "<"                         { }
}

/* ============ __halt_compiler：要求 " ();"，之后丢弃全部内容 ============ */

<SCRIPTING> __[hH][aA][lL][tT]_[cC][oO][mM][pP][iI][lL][eE][rR]{WHITESPACE}*"("{WHITESPACE}*")"{WHITESPACE}*";" {
    yybegin(HALT);
    return symbol(PhpSymbols.T_HALT_COMPILER, yytext());
}

<HALT> [^]+  { }

/* ============ ST_IN_SCRIPTING ============ */

<SCRIPTING> {

  /* -------- 关键字（声明顺序保证同长度时关键字优先于 LABEL） -------- */
  [eE][xX][iI][tT]                       { return tk(PhpSymbols.T_EXIT); }
  [dD][iI][eE]                        { return tk(PhpSymbols.T_EXIT); }
  [fF][uU][nN][cC][tT][iI][oO][nN]                   { return tk(PhpSymbols.T_FUNCTION); }
  [cC][oO][nN][sS][tT]                      { return tk(PhpSymbols.T_CONST); }
  [rR][eE][tT][uU][rR][nN]                     { return tk(PhpSymbols.T_RETURN); }

  /* "yield from"：后随一个非标签字符（匹配后回退 1 字符，等价 zend 的 yyless） */
  [yY][iI][eE][lL][dD][ \t\r\n]+[fF][rR][oO][mM][^a-zA-Z0-9_\x80-\uffff] {
                               yypushback(1); return tk(PhpSymbols.T_YIELD_FROM); }
  [yY][iI][eE][lL][dD]                      { return tk(PhpSymbols.T_YIELD); }

  [tT][rR][yY]                        { return tk(PhpSymbols.T_TRY); }
  [cC][aA][tT][cC][hH]                      { return tk(PhpSymbols.T_CATCH); }
  [fF][iI][nN][aA][lL][lL][yY]                    { return tk(PhpSymbols.T_FINALLY); }
  [tT][hH][rR][oO][wW]                      { return tk(PhpSymbols.T_THROW); }
  [iI][fF]                         { return tk(PhpSymbols.T_IF); }
  [eE][lL][sS][eE][iI][fF]                     { return tk(PhpSymbols.T_ELSEIF); }
  [eE][nN][dD][iI][fF]                      { return tk(PhpSymbols.T_ENDIF); }
  [eE][lL][sS][eE]                       { return tk(PhpSymbols.T_ELSE); }
  [wW][hH][iI][lL][eE]                      { return tk(PhpSymbols.T_WHILE); }
  [eE][nN][dD][wW][hH][iI][lL][eE]                   { return tk(PhpSymbols.T_ENDWHILE); }
  [dD][oO]                         { return tk(PhpSymbols.T_DO); }
  [fF][oO][rR]                        { return tk(PhpSymbols.T_FOR); }
  [eE][nN][dD][fF][oO][rR]                     { return tk(PhpSymbols.T_ENDFOR); }
  [fF][oO][rR][eE][aA][cC][hH]                    { return tk(PhpSymbols.T_FOREACH); }
  [eE][nN][dD][fF][oO][rR][eE][aA][cC][hH]                 { return tk(PhpSymbols.T_ENDFOREACH); }
  [dD][eE][cC][lL][aA][rR][eE]                    { return tk(PhpSymbols.T_DECLARE); }
  [eE][nN][dD][dD][eE][cC][lL][aA][rR][eE]                 { return tk(PhpSymbols.T_ENDDECLARE); }
  [iI][nN][sS][tT][aA][nN][cC][eE][oO][fF]                 { return tk(PhpSymbols.T_INSTANCEOF); }
  [aA][sS]                         { return tk(PhpSymbols.T_AS); }
  [sS][wW][iI][tT][cC][hH]                     { return tk(PhpSymbols.T_SWITCH); }
  [eE][nN][dD][sS][wW][iI][tT][cC][hH]                  { return tk(PhpSymbols.T_ENDSWITCH); }
  [cC][aA][sS][eE]                       { return tk(PhpSymbols.T_CASE); }
  [dD][eE][fF][aA][uU][lL][tT]                    { return tk(PhpSymbols.T_DEFAULT); }
  [bB][rR][eE][aA][kK]                      { return tk(PhpSymbols.T_BREAK); }
  [cC][oO][nN][tT][iI][nN][uU][eE]                   { return tk(PhpSymbols.T_CONTINUE); }
  [gG][oO][tT][oO]                       { return tk(PhpSymbols.T_GOTO); }
  [eE][cC][hH][oO]                       { return tk(PhpSymbols.T_ECHO); }
  [pP][rR][iI][nN][tT]                      { return tk(PhpSymbols.T_PRINT); }
  [cC][lL][aA][sS][sS]                      { return tk(PhpSymbols.T_CLASS); }
  [iI][nN][tT][eE][rR][fF][aA][cC][eE]                  { return tk(PhpSymbols.T_INTERFACE); }
  [tT][rR][aA][iI][tT]                      { return tk(PhpSymbols.T_TRAIT); }
  [eE][xX][tT][eE][nN][dD][sS]                    { return tk(PhpSymbols.T_EXTENDS); }
  [iI][mM][pP][lL][eE][mM][eE][nN][tT][sS]                 { return tk(PhpSymbols.T_IMPLEMENTS); }
  [nN][eE][wW]                        { return tk(PhpSymbols.T_NEW); }
  [cC][lL][oO][nN][eE]                      { return tk(PhpSymbols.T_CLONE); }
  [vV][aA][rR]                        { return tk(PhpSymbols.T_VAR); }
  [eE][vV][aA][lL]                       { return tk(PhpSymbols.T_EVAL); }
  [iI][nN][cC][lL][uU][dD][eE]                    { return tk(PhpSymbols.T_INCLUDE); }
  [iI][nN][cC][lL][uU][dD][eE]_[oO][nN][cC][eE]               { return tk(PhpSymbols.T_INCLUDE_ONCE); }
  [rR][eE][qQ][uU][iI][rR][eE]                    { return tk(PhpSymbols.T_REQUIRE); }
  [rR][eE][qQ][uU][iI][rR][eE]_[oO][nN][cC][eE]               { return tk(PhpSymbols.T_REQUIRE_ONCE); }
  [nN][aA][mM][eE][sS][pP][aA][cC][eE]                  { return tk(PhpSymbols.T_NAMESPACE); }
  [uU][sS][eE]                        { return tk(PhpSymbols.T_USE); }
  [iI][nN][sS][tT][eE][aA][dD][oO][fF]                  { return tk(PhpSymbols.T_INSTEADOF); }
  [gG][lL][oO][bB][aA][lL]                     { return tk(PhpSymbols.T_GLOBAL); }
  [iI][sS][sS][eE][tT]                      { return tk(PhpSymbols.T_ISSET); }
  [eE][mM][pP][tT][yY]                      { return tk(PhpSymbols.T_EMPTY); }
  [sS][tT][aA][tT][iI][cC]                     { return tk(PhpSymbols.T_STATIC); }
  [aA][bB][sS][tT][rR][aA][cC][tT]                   { return tk(PhpSymbols.T_ABSTRACT); }
  [fF][iI][nN][aA][lL]                      { return tk(PhpSymbols.T_FINAL); }
  [pP][rR][iI][vV][aA][tT][eE]                    { return tk(PhpSymbols.T_PRIVATE); }
  [pP][rR][oO][tT][eE][cC][tT][eE][dD]                  { return tk(PhpSymbols.T_PROTECTED); }
  [pP][uU][bB][lL][iI][cC]                     { return tk(PhpSymbols.T_PUBLIC); }
  [uU][nN][sS][eE][tT]                      { return tk(PhpSymbols.T_UNSET); }
  [lL][iI][sS][tT]                       { return tk(PhpSymbols.T_LIST); }
  [aA][rR][rR][aA][yY]                      { return tk(PhpSymbols.T_ARRAY); }
  [cC][aA][lL][lL][aA][bB][lL][eE]                   { return tk(PhpSymbols.T_CALLABLE); }

  /* 逻辑运算符（re2c 全局大小写不敏感，与 zend 生成的 DFA 一致） */
  [oO][rR]                         { return tk(PhpSymbols.T_LOGICAL_OR); }
  [aA][nN][dD]                        { return tk(PhpSymbols.T_LOGICAL_AND); }
  [xX][oO][rR]                        { return tk(PhpSymbols.T_LOGICAL_XOR); }

  /* 魔术常量（大小写不敏感） */
  __[cC][lL][aA][sS][sS]__                  { return tk(PhpSymbols.T_CLASS_C); }
  __[tT][rR][aA][iI][tT]__                  { return tk(PhpSymbols.T_TRAIT_C); }
  __[fF][uU][nN][cC][tT][iI][oO][nN]__               { return tk(PhpSymbols.T_FUNC_C); }
  __[mM][eE][tT][hH][oO][dD]__                 { return tk(PhpSymbols.T_METHOD_C); }
  __[lL][iI][nN][eE]__                   { return tk(PhpSymbols.T_LINE); }
  __[fF][iI][lL][eE]__                   { return tk(PhpSymbols.T_FILE); }
  __[dD][iI][rR]__                    { return tk(PhpSymbols.T_DIR); }
  __[nN][aA][mM][eE][sS][pP][aA][cC][eE]__              { return tk(PhpSymbols.T_NS_C); }

  /* -------- 强制转换（大小写不敏感，允许内部空白） -------- */
  "("{TABS}([iI][nN][tT]|[iI][nN][tT][eE][gG][eE][rR]){TABS}")"          { return tk(PhpSymbols.T_INT_CAST); }
  "("{TABS}([rR][eE][aA][lL]|[dD][oO][uU][bB][lL][eE]|[fF][lL][oO][aA][tT]){TABS}")"  { return tk(PhpSymbols.T_DOUBLE_CAST); }
  "("{TABS}([sS][tT][rR][iI][nN][gG]|[bB][iI][nN][aA][rR][yY]){TABS}")"        { return tk(PhpSymbols.T_STRING_CAST); }
  "("{TABS}[aA][rR][rR][aA][yY]{TABS}")"                    { return tk(PhpSymbols.T_ARRAY_CAST); }
  "("{TABS}[oO][bB][jJ][eE][cC][tT]{TABS}")"                   { return tk(PhpSymbols.T_OBJECT_CAST); }
  "("{TABS}([bB][oO][oO][lL]|[bB][oO][oO][lL][eE][aA][nN]){TABS}")"         { return tk(PhpSymbols.T_BOOL_CAST); }
  "("{TABS}[uU][nN][sS][eE][tT]{TABS}")"                    { return tk(PhpSymbols.T_UNSET_CAST); }

  /* -------- 操作符 -------- */
  "->"      { pushState(LOOKING_PROPERTY); return tk(PhpSymbols.T_OBJECT_OPERATOR); }
  "::"      { return tk(PhpSymbols.T_PAAMAYIM_NEKUDOTAYIM); }
  "\\"      { return tk(PhpSymbols.T_NS_SEPARATOR); }
  "..."     { return tk(PhpSymbols.T_ELLIPSIS); }
  "??"      { return tk(PhpSymbols.T_COALESCE); }
  "=>"      { return tk(PhpSymbols.T_DOUBLE_ARROW); }
  "++"      { return tk(PhpSymbols.T_INC); }
  "--"      { return tk(PhpSymbols.T_DEC); }
  "==="     { return tk(PhpSymbols.T_IS_IDENTICAL); }
  "!=="     { return tk(PhpSymbols.T_IS_NOT_IDENTICAL); }
  "=="      { return tk(PhpSymbols.T_IS_EQUAL); }
  "!="|"<>" { return tk(PhpSymbols.T_IS_NOT_EQUAL); }
  "<=>"     { return tk(PhpSymbols.T_SPACESHIP); }
  "<="      { return tk(PhpSymbols.T_IS_SMALLER_OR_EQUAL); }
  ">="      { return tk(PhpSymbols.T_IS_GREATER_OR_EQUAL); }
  "+="      { return tk(PhpSymbols.T_PLUS_EQUAL); }
  "-="      { return tk(PhpSymbols.T_MINUS_EQUAL); }
  "*="      { return tk(PhpSymbols.T_MUL_EQUAL); }
  "**"      { return tk(PhpSymbols.T_POW); }
  "**="     { return tk(PhpSymbols.T_POW_EQUAL); }
  "/="      { return tk(PhpSymbols.T_DIV_EQUAL); }
  ".="      { return tk(PhpSymbols.T_CONCAT_EQUAL); }
  "%="      { return tk(PhpSymbols.T_MOD_EQUAL); }
  "<<="     { return tk(PhpSymbols.T_SL_EQUAL); }
  ">>="     { return tk(PhpSymbols.T_SR_EQUAL); }
  "&="      { return tk(PhpSymbols.T_AND_EQUAL); }
  "|="      { return tk(PhpSymbols.T_OR_EQUAL); }
  "^="      { return tk(PhpSymbols.T_XOR_EQUAL); }
  "||"      { return tk(PhpSymbols.T_BOOLEAN_OR); }
  "&&"      { return tk(PhpSymbols.T_BOOLEAN_AND); }
  "<<"      { return tk(PhpSymbols.T_SL); }
  ">>"      { return tk(PhpSymbols.T_SR); }

  /* -------- 字符串 -------- */
  {CONST_DQ_STR}  {
      String text = yytext();
      if (!containsInterpolation(text)) return tk(PhpSymbols.T_CONSTANT_ENCAPSED_STRING);
      int openingLength = text.charAt(0) == '"' ? 1 : 2;
      yypushback(yylength() - openingLength);
      yybegin(DQ);
      return tk(PhpSymbols.DQUOTE);
  }
  [bB]?\"         { yybegin(DQ); return tk(PhpSymbols.DQUOTE); }
  [bB]?[']([^'\\]|\\[^])*['] {
                  return symbol(PhpSymbols.T_CONSTANT_ENCAPSED_STRING, yytext()); }
  [bB]?[']        { /* 未闭合的单引号串：返回空内容 token，由解析器报错（zend 语义） */
                  return symbol(PhpSymbols.T_ENCAPSED_AND_WHITESPACE, ""); }
  [`]             { yybegin(BQ); return tk(PhpSymbols.BACKTICK); }
  {HEREDOC_OPEN}  {
      String text = yytext();
      int start = text.indexOf('<') + 3;
      while (text.charAt(start) == ' ' || text.charAt(start) == '\t') start++;
      char quote = text.charAt(start);
      boolean nowdoc = quote == '\'';
      if (quote == '\'' || quote == '"') start++;
      int end = text.length();
      if (text.charAt(end - 2) == '\r') end -= 2; else end -= 1;
      if (quote == '\'' || quote == '"') end--;
      heredocLabels.push(text.substring(start, end));
      yybegin(nowdoc ? NOWDOC_BEGIN : HEREDOC_BEGIN);
      return symbol(PhpSymbols.T_START_HEREDOC, yytext());
  }

  /* -------- 注释：行注释有三种结束（换行 / "?>" / EOF） -------- */
  ("//"|"#"){LINE_COMMENT_TAIL}[\r\n]  { }
  ("//"|"#"){LINE_COMMENT_TAIL}"?>"    { yybegin(SKIP_HTML);
                                         return symbol(PhpSymbols.SEMI, "?>"); }
  ("//"|"#"){LINE_COMMENT_TAIL}        { }
  "/*"([^*]|\*+[^*\/])*\*+"/"           { }
  "/*"([^*]|\*+[^*\/])*\**              { warn("未闭合的块注释"); }

  /* -------- 数字 -------- */
  {HNUM}          { return numberToken(16); }
  {BNUM}          { return numberToken(2); }
  {LNUM}          { return numberToken(10); }
  {DNUM}|{EXPONENT_DNUM} { return symbol(PhpSymbols.T_DNUMBER, yytext()); }

  /* -------- 变量与花括号（带状态栈） -------- */
  "$"{LABEL}      { return symbol(PhpSymbols.T_VARIABLE, yytext().substring(1)); }
  "{"            { pushState(SCRIPTING); return tk(PhpSymbols.LBRACE); }
  "}"            { if (!stateStack.isEmpty()) popState();
                   return tk(PhpSymbols.RBRACE); }

  /* -------- 其它单字符 -------- */
  {TOKENS}        { return singleCharToken(yytext().charAt(0)); }
  {LABEL}         { return symbol(PhpSymbols.T_STRING, yytext()); }
  {WHITESPACE}+   { }

  /* 无法识别的字符：zend 为警告后跳过；返回 T_ERROR 强制解析失败 */
  [^]             { return tk(PhpSymbols.T_ERROR); }
}

/* ============ ST_LOOKING_FOR_PROPERTY："$obj->" 之后 ============ */

<LOOKING_PROPERTY> {
  "->"      { return tk(PhpSymbols.T_OBJECT_OPERATOR); }
  {LABEL}   { popState(); return symbol(PhpSymbols.T_STRING, yytext()); }
  {WHITESPACE}+ { }
  [^]       { yypushback(yylength()); popState(); }
}

/* ============ ST_LOOKING_FOR_VARNAME："${" 之后 ============ */

<LOOKING_VARNAME> {
  /* 标签后必须紧跟 [ 或 }（匹配后回退该字符），否则回退到普通词法 */
  {LABEL}("["|"}") {
      yypushback(1);
      popState(); pushState(SCRIPTING);
      return symbol(PhpSymbols.T_STRING_VARNAME, yytext());
  }
  [^]       { yypushback(yylength()); popState(); pushState(SCRIPTING); }
}

/* ============ ST_VAR_OFFSET：字符串内的 "$a[" 之后 ============ */

<VAR_OFFSET> {
  "]"       { popState(); return tk(PhpSymbols.RBRACK); }
  {LNUM}|{HNUM}|{BNUM} { return symbol(PhpSymbols.T_NUM_STRING, yytext()); }
  "$"{LABEL}  { return symbol(PhpSymbols.T_VARIABLE, yytext().substring(1)); }
  {LABEL}   { return symbol(PhpSymbols.T_STRING, yytext()); }
  {TOKENS}|[{}\"`] { return singleCharToken(yytext().charAt(0)); }
  [ \n\r\t\\'#] {
      /* 非法偏移：回退并返回空 T_ENCAPSED 让解析器报错（zend 语义） */
      yypushback(yylength()); popState();
      return symbol(PhpSymbols.T_ENCAPSED_AND_WHITESPACE, "");
  }
}

/* ============ 双引号 / 反引号 / heredoc 公共插值规则 ============ */

<DQ, BQ, HEREDOC, HEREDOC_BEGIN> {
  /* "$var->prop"：-> 后必须是标签起始字符（匹配后回退 3 字符等价 zend 的 yyless） */
  "$"{LABEL}"->"[a-zA-Z_\x80-\uffff] {
      yypushback(3);
      leaveHeredocBeginning();
      pushState(LOOKING_PROPERTY);
      return symbol(PhpSymbols.T_VARIABLE, yytext().substring(1));
  }
  /* "$var["：进入偏移状态 */
  "$"{LABEL}"[" {
      yypushback(1);
      leaveHeredocBeginning();
      pushState(VAR_OFFSET);
      return symbol(PhpSymbols.T_VARIABLE, yytext().substring(1));
  }
  "$"{LABEL}  { leaveHeredocBeginning();
                return symbol(PhpSymbols.T_VARIABLE, yytext().substring(1)); }
  "${"        { leaveHeredocBeginning(); pushState(LOOKING_VARNAME);
                return tk(PhpSymbols.T_DOLLAR_OPEN_CURLY_BRACES); }
  "{$"        { yypushback(1); leaveHeredocBeginning(); pushState(SCRIPTING);
                return tk(PhpSymbols.T_CURLY_OPEN); }
}

<DQ> {
  \"          { yybegin(SCRIPTING); return tk(PhpSymbols.DQUOTE); }
  {DQ_TXT}+   { return encapsedToken(); }
  "$"|"{"     { return encapsedToken(); }
  {NEWLINE}   { return encapsedToken(); }
}

<BQ> {
  [`]         { yybegin(SCRIPTING); return tk(PhpSymbols.BACKTICK); }
  {BQ_TXT}+   { return encapsedToken(); }
  "$"|"{"     { return encapsedToken(); }
  {NEWLINE}   { return encapsedToken(); }
}

/* ============ heredoc / nowdoc 正文 ============ */

/* 行首：结束标签判定 */
<HEREDOC_BEGIN> {
  {LABEL}(";")?{NEWLINE} { if (finishHeredoc(false)) return tk(PhpSymbols.T_END_HEREDOC);
                          else return encapsedToken(); }
}
<NOWDOC_BEGIN> {
  {LABEL}(";")?{NEWLINE} { if (finishHeredoc(true)) return tk(PhpSymbols.T_END_HEREDOC);
                          else return encapsedToken(); }
}

/* heredoc 正文有插值（公共规则已覆盖 $/${/{$）。
 * 换行原文保留在文本 token 内，后处理负责去掉结束标签前的最后一个换行。 */
<HEREDOC, HEREDOC_BEGIN> {
  {HDOC_TXT}*{NEWLINE}  { return encapsedToken(); }
  {HDOC_TXT}+           { return encapsedToken(); }
  "$"|"{"               { return encapsedToken(); }
}
/* nowdoc 无插值，纯文本整行（含换行）到行尾 */
<NOWDOC, NOWDOC_BEGIN> {
  [^\r\n]*{NEWLINE}     { return encapsedToken(); }
  [^\r\n]+              { return encapsedToken(); }
}

/* 兜底：上述规则未覆盖的单字符（如 EOF 前的孤立反斜杠）按文本处理 */
<DQ, BQ, HEREDOC, HEREDOC_BEGIN, NOWDOC, NOWDOC_BEGIN> [^] {
    return encapsedToken();
}
