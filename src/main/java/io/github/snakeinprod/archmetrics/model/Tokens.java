package io.github.snakeinprod.archmetrics.model;

import java.util.ArrayList;
import java.util.List;

/** A small Java lexer: identifiers, literals, operators and punctuation with their line; whitespace and comments dropped. */
public final class Tokens {

  private Tokens() {
  }

  /** @param line 1-based line of the token's first character */
  public record Token(String text, int line) {
  }

  private static final String[] OPERATORS = {">>>=", "<<=", ">>=", ">>>", "...", "->", "::", "++", "--", "&&", "||", "==", "!=", "<=",
    ">=", "+=", "-=", "*=", "/=", "&=", "|=", "^=", "%=", "<<", ">>"};

  public static List<Token> of(String src) {
    List<Token> out = new ArrayList<>();
    int line = 1;
    int i = 0;
    int n = src.length();
    while (i < n) {
      char c = src.charAt(i);
      if (c == '\n') {
        line++;
        i++;
      } else if (Character.isWhitespace(c)) {
        i++;
      } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
        while (i < n && src.charAt(i) != '\n') {
          i++;
        }
      } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
        i += 2;
        while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
          if (src.charAt(i) == '\n') {
            line++;
          }
          i++;
        }
        i += 2;
      } else if (src.startsWith("\"\"\"", i)) {
        int start = i;
        int startLine = line;
        i += 3;
        while (i < n && !src.startsWith("\"\"\"", i)) {
          if (src.charAt(i) == '\\') {
            i++;
          } else if (src.charAt(i) == '\n') {
            line++;
          }
          i++;
        }
        i = Math.min(n, i + 3);
        out.add(new Token(src.substring(start, i), startLine));
      } else if (c == '"' || c == '\'') {
        int start = i++;
        while (i < n && src.charAt(i) != c && src.charAt(i) != '\n') {
          if (src.charAt(i) == '\\') {
            i++;
          }
          i++;
        }
        i = Math.min(n, i + 1);
        out.add(new Token(src.substring(start, i), line));
      } else if (Character.isJavaIdentifierStart(c)) {
        int start = i;
        while (i < n && Character.isJavaIdentifierPart(src.charAt(i))) {
          i++;
        }
        out.add(new Token(src.substring(start, i), line));
      } else if (Character.isDigit(c) || c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1))) {
        int start = i;
        while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '.' || src.charAt(i) == '_'
          || (src.charAt(i) == '+' || src.charAt(i) == '-') && (src.charAt(i - 1) == 'e' || src.charAt(i - 1) == 'E')
            && !src.substring(start, i).startsWith("0x"))) {
          i++;
        }
        out.add(new Token(src.substring(start, i), line));
      } else {
        String op = String.valueOf(c);
        for (String o : OPERATORS) {
          if (src.startsWith(o, i)) {
            op = o;
            break;
          }
        }
        out.add(new Token(op, line));
        i += op.length();
      }
    }
    return out;
  }
}
