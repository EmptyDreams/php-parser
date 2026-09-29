package top.kmar.php.extract;

import java_cup.runtime.AstNode;
import top.kmar.php.*;
import top.kmar.php.ir.*;
import top.kmar.php.model.SourceInfo;
import top.kmar.php.model.SourceRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static top.kmar.php.extract.StringLiteralDecoder.Mode;

/** 字符串语法适配器；只有文本解码使用共享缓冲区，递归表达式转换发生在发布文本快照之后。 */
final class StringConverter {
    private final ConversionContext context;
    private final ExpressionConverter expressions;
    private final StringLiteralDecoder decoder;

    StringConverter(ConversionContext context, ExpressionConverter expressions) {
        this.context = Objects.requireNonNull(context, "context");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
        this.decoder = new StringLiteralDecoder(context);
    }

    IrStringLiteral literal(NodeDereferencableScalar node, String path) {
        var token = context.required(node.getStr(), node, path + ".str");
        String text = context.text(token, node, path + ".str");
        int quoteIndex = text.charAt(0) == 'b' || text.charAt(0) == 'B' ? 1 : 0;
        if (text.length() < quoteIndex + 2) throw context.error(token, path + ".str", "字符串缺少成对引号");
        char quote = text.charAt(quoteIndex);
        int end = text.length() - 1;
        if ((quote != '\'' && quote != '"') || text.charAt(end) != quote) {
            throw context.error(token, path + ".str", "无法识别的字符串定界符");
        }
        for (int i = quoteIndex + 1; i < end; i++) {
            char character = text.charAt(i);
            if (character == '\\') {
                if (++i == end) throw context.error(token, path + ".str", "字符串结束引号被转义");
            } else if (character == quote) {
                throw context.error(token, path + ".str", "字符串内部存在未转义的定界符");
            } else if (quote == '"' && i + 1 < end
                    && (character == '$' && (text.charAt(i + 1) == '{' || labelStart(text.codePointAt(i + 1)))
                    || character == '{' && text.charAt(i + 1) == '$')) {
                throw context.error(token, path + ".str", "常量字符串节点不能包含未转义的插值");
            }
        }
        var fragment = new StringLiteralDecoder.Fragment(text, quoteIndex + 1, end, token, path + ".str");
        return new IrStringLiteral(decoder.decode(List.of(fragment),
                quote == '\'' ? Mode.SINGLE_QUOTED : Mode.DOUBLE_QUOTED, 0), context.source(node));
    }

    IrExpression scalar(NodeScalar node, String path) {
        return switch (node) {
            case NodeScalar.MagicConst ignored -> magic(node, path);
            case NodeScalar.InterpolatedString ignored -> content(node, path, Mode.DOUBLE_QUOTED, false);
            case NodeScalar.Heredoc ignored -> content(node, path, heredocMode(node, path), true);
            default -> throw context.error(node, path, "无法识别的字符串或魔术常量结构");
        };
    }

    private IrMagicConstant magic(NodeScalar node, String path) {
        String spelling = context.text(node.getKw(), node, path + ".kw");
        for (int i = 0; i < spelling.length(); i++) {
            if (spelling.charAt(i) > 0x7f) {
                throw context.error(node, path + ".kw", "魔术常量标记必须使用 ASCII 拼写");
            }
        }
        MagicConstantKind kind = switch (spelling.toUpperCase(Locale.ROOT)) {
            case "__LINE__" -> MagicConstantKind.LINE;
            case "__FILE__" -> MagicConstantKind.FILE;
            case "__DIR__" -> MagicConstantKind.DIR;
            case "__CLASS__" -> MagicConstantKind.CLASS;
            case "__TRAIT__" -> MagicConstantKind.TRAIT;
            case "__FUNCTION__" -> MagicConstantKind.FUNCTION;
            case "__METHOD__" -> MagicConstantKind.METHOD;
            case "__NAMESPACE__" -> MagicConstantKind.NAMESPACE;
            default -> throw context.error(node, path + ".kw", "无法识别的魔术常量: " + spelling);
        };
        return new IrMagicConstant(kind, context.source(node));
    }

    private Mode heredocMode(NodeScalar node, String path) {
        String opening = context.text(node.getH(), node, path + ".h");
        String closing = context.text(node.getE(), node, path + ".e");
        int start = opening.charAt(0) == 'b' || opening.charAt(0) == 'B' ? 1 : 0;
        if (!opening.startsWith("<<<", start)) throw context.error(node, path + ".h", "无效的 heredoc 起始标记");
        start += 3;
        int end = opening.length();
        if (opening.endsWith("\r\n")) end -= 2;
        else if (opening.endsWith("\r") || opening.endsWith("\n")) end--;
        else throw context.error(node, path + ".h", "heredoc 起始标记必须以换行结束");
        while (start < end && (opening.charAt(start) == ' ' || opening.charAt(start) == '\t')) start++;
        Mode mode = Mode.HEREDOC;
        if (start < end && (opening.charAt(start) == '\'' || opening.charAt(start) == '"')) {
            char quote = opening.charAt(start++);
            if (start >= end || opening.charAt(end - 1) != quote) {
                throw context.error(node, path + ".h", "heredoc 标签引号不匹配");
            }
            end--;
            if (quote == '\'') mode = Mode.RAW;
        }
        if (start >= end || !labelStart(opening.codePointAt(start))) {
            throw context.error(node, path + ".h", "heredoc 标签不能为空或以非法字符开头");
        }
        for (int i = start; i < end;) {
            int character = opening.codePointAt(i);
            if (!labelStart(character) && !(character >= '0' && character <= '9')) {
                throw context.error(node, path + ".h", "heredoc 标签包含非法字符");
            }
            i += Character.charCount(character);
        }
        if (!closing.equals(opening.substring(start, end))) {
            throw context.error(node, path + ".e", "heredoc 结束标签与起始标签不匹配");
        }
        return mode;
    }

    private IrExpression content(NodeScalar node, String path, Mode mode, boolean heredoc) {
        NodeEncapsList list = context.required(node.getList(), node, path + ".list");
        if (!(list instanceof NodeEncapsList.Parts)) {
            throw context.error(list, path + ".list", "无法识别的字符串片段列表");
        }
        var wrapper = context.required(list.getParts(), list, path + ".list.parts");
        var parts = context.elements(wrapper.getValue(), wrapper, path + ".list.parts");
        var result = new ArrayList<IrStringPart>();
        boolean interpolated = false;
        for (int i = 0; i < parts.size();) {
            NodeEncapsPart part = parts.get(i);
            String partPath = path + ".list.parts[" + i + "]";
            if (part instanceof NodeEncapsPart.Variable) {
                if (mode == Mode.RAW) throw context.error(part, partPath + ".var", "nowdoc 不能包含插值节点");
                NodeEncapsVar variable = context.required(part.getVar(), part, partPath + ".var");
                result.add(new IrStringInterpolation(interpolation(variable, partPath + ".var"),
                        context.source(variable)));
                interpolated = true;
                if (heredoc && i == parts.size() - 1) {
                    throw context.error(part, partPath, "heredoc 正文必须以换行结束");
                }
                i++;
            } else if (part instanceof NodeEncapsPart.Text) {
                int runStart = i;
                var fragments = new ArrayList<StringLiteralDecoder.Fragment>();
                do {
                    part = parts.get(i);
                    String textPath = path + ".list.parts[" + i + "].text";
                    var token = context.required(part.getText(), part, textPath);
                    // 文本允许为空；不能复用要求非空名称的 context.text。
                    String text = context.required(token.getValue(), token, textPath);
                    fragments.add(new StringLiteralDecoder.Fragment(text, token, textPath));
                    i++;
                } while (i < parts.size() && parts.get(i) instanceof NodeEncapsPart.Text);
                int trim = heredoc && i == parts.size() ? trailingNewline(fragments) : 0;
                if (heredoc && i == parts.size() && trim == 0 && runStart != 0) {
                    var end = fragments.getLast();
                    throw context.error(end.origin(), end.path(), "heredoc 正文必须以换行结束");
                }
                ByteString bytes = decoder.decode(fragments, mode, trim);
                if (bytes.size() != 0) result.add(new IrStringText(bytes, textSource(fragments)));
            } else throw context.error(part, partPath, "无法识别的字符串片段");
        }
        if (!interpolated) {
            ByteString bytes = result.isEmpty() ? decoder.decode(List.of(), Mode.RAW, 0)
                    : ((IrStringText) result.getFirst()).value();
            return new IrStringLiteral(bytes, context.source(node));
        }
        return new IrStringTemplate(result, context.source(node));
    }

    private int trailingNewline(List<StringLiteralDecoder.Fragment> fragments) {
        int last = -1;
        int previous = -1;
        for (int i = fragments.size() - 1; i >= 0; i--) {
            var fragment = fragments.get(i);
            for (int j = fragment.end() - 1; j >= fragment.start(); j--) {
                if (last < 0) last = fragment.text().charAt(j);
                else {
                    previous = fragment.text().charAt(j);
                    break;
                }
            }
            if (previous >= 0) break;
        }
        if (last == '\r') return 1;
        if (last == '\n') return previous == '\r' ? 2 : 1;
        if (last < 0) return 0;
        var end = fragments.getLast();
        throw context.error(end.origin(), end.path(), "heredoc 正文必须以换行结束");
    }

    private SourceInfo textSource(List<StringLiteralDecoder.Fragment> fragments) {
        var first = context.source(fragments.getFirst().origin());
        SourceRange range = first.range();
        for (var fragment : fragments) {
            var next = context.source(fragment.origin()).range();
            if (range == null || next == null) return new SourceInfo(first.sourceId(), null);
            var start = before(next.startLine(), next.startColumn(), range.startLine(), range.startColumn())
                    ? next : range;
            var end = before(next.endLine(), next.endColumn(), range.endLine(), range.endColumn()) ? range : next;
            range = new SourceRange(start.startLine(), start.startColumn(), end.endLine(), end.endColumn());
        }
        return new SourceInfo(first.sourceId(), range);
    }

    private static boolean before(int line, int column, int otherLine, int otherColumn) {
        return line < otherLine || line == otherLine && column < otherColumn;
    }

    private IrExpression interpolation(NodeEncapsVar node, String path) {
        return switch (node) {
            case NodeEncapsVar.Var ignored -> namedVariable(node.getVar(), node, path + ".var", context.source(node));
            case NodeEncapsVar.Index ignored -> new IrIndex(
                    namedVariable(node.getVar(), node, path + ".var", context.source(node.getVar())),
                    offset(context.required(node.getOffset(), node, path + ".offset"), path + ".offset"),
                    context.source(node));
            case NodeEncapsVar.Property ignored -> new IrPropertyAccess(
                    namedVariable(node.getVar(), node, path + ".var", context.source(node.getVar())),
                    new IrFixedName(context.text(node.getProp(), node, path + ".prop"), context.source(node.getProp())),
                    context.source(node));
            case NodeEncapsVar.IndirectVar ignored -> new IrVariable(new IrComputedName(
                    expressions.convert(context.required(node.getE(), node, path + ".e"), path + ".e"),
                    context.source(node)), context.source(node));
            case NodeEncapsVar.NamedIndirectVar ignored -> namedVariable(
                    node.getName(), node, path + ".name", context.source(node));
            case NodeEncapsVar.NamedIndirectVarIndex ignored -> new IrIndex(
                    namedVariable(node.getName(), node, path + ".name", context.source(node.getName())),
                    expressions.convert(context.required(node.getIndex(), node, path + ".index"), path + ".index"),
                    context.source(node));
            case NodeEncapsVar.CurlyVar ignored -> expressions.variable(
                    context.required(node.getV(), node, path + ".v"), path + ".v");
            default -> throw context.error(node, path, "无法识别的插值变量结构");
        };
    }

    private IrVariable namedVariable(NodeString token, AstNode origin, String path, SourceInfo source) {
        return new IrVariable(new IrFixedName(context.text(token, origin, path), context.source(token)), source);
    }

    private IrExpression offset(NodeEncapsVarOffset node, String path) {
        return switch (node) {
            case NodeEncapsVarOffset.StringOffset ignored -> utf8(
                    context.text(node.getS(), node, path + ".s"), node.getS(), path + ".s", context.source(node));
            case NodeEncapsVarOffset.VariableOffset ignored -> namedVariable(
                    node.getV(), node, path + ".v", context.source(node));
            case NodeEncapsVarOffset.NumericOffset ignored -> numericOffset(node, path, false);
            case NodeEncapsVarOffset.NegativeNumericOffset ignored -> numericOffset(node, path, true);
            default -> throw context.error(node, path, "无法识别的字符串插值下标");
        };
    }

    private IrExpression numericOffset(NodeEncapsVarOffset node, String path, boolean negative) {
        String text = context.text(node.getN(), node, path + ".n");
        int start = 0;
        int radix = 10;
        if (text.length() > 1 && text.charAt(0) == '0') {
            if (text.charAt(1) == 'x' || text.charAt(1) == 'X') { start = 2; radix = 16; }
            else if (text.charAt(1) == 'b' || text.charAt(1) == 'B') { start = 2; radix = 2; }
        }
        if (start == text.length()) throw context.error(node, path + ".n", "插值数字下标缺少数字");
        for (int i = start; i < text.length(); i++) {
            int digit = StringLiteralDecoder.hex(text.charAt(i));
            if (digit < 0 || digit >= radix) throw context.error(node, path + ".n", "非法插值数字下标");
        }
        boolean canonical = radix == 10 && (text.length() == 1 || text.charAt(0) != '0');
        if (canonical && (text.length() < 19 || text.length() == 19 && text.compareTo("9223372036854775807") <= 0)) {
            long value = Long.parseLong(text);
            if (!negative || value != 0) return new IrIntegerLiteral(negative ? -value : value, context.source(node));
        }
        return utf8(negative ? "-" + text : text, node.getN(), path + ".n", context.source(node));
    }

    private IrStringLiteral utf8(String text, AstNode origin, String path, SourceInfo source) {
        return new IrStringLiteral(decoder.decode(List.of(new StringLiteralDecoder.Fragment(text, origin, path)),
                Mode.RAW, 0), source);
    }

    /** 与词法 LABEL 的 BMP 范围一致，不扩展标识符语言。 */
    private static boolean labelStart(int character) {
        return character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
                || character == '_' || character >= 0x80 && character <= 0xffff;
    }
}
