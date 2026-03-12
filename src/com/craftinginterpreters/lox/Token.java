package com.craftinginterpreters.lox;

/**
 * 词法单元（Token）。Scanner 输出的最小语法单位，
 * 携带类型（TokenType）、原始字符串（lexeme）、字面量值和行号。
 */
public class Token {
  final TokenType type;
  final String lexeme;
  final Object literal;
  final int line; 

  Token(TokenType type, String lexeme, Object literal, int line) {
    this.type = type;
    this.lexeme = lexeme;
    this.literal = literal;
    this.line = line;
  }

  public String toString() {
    return type + " " + lexeme + " " + literal;
  }
}