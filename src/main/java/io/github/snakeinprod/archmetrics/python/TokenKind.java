package io.github.snakeinprod.archmetrics.python;

/**
 * Python token kinds. Keywords are {@link #IDENT}: callers compare the text. Brackets and every other punctuation or
 * operator ({@code .}, {@code ==}, {@code @}) are {@link #OP}. {@link #NEWLINE}, {@link #INDENT} and {@link #DEDENT}
 * mark logical line ends and block changes (never inside brackets). Unknown characters are {@link #OTHER}.
 */
public enum TokenKind {
  IDENT, NUMBER, STRING, OP, NEWLINE, INDENT, DEDENT, OTHER
}
