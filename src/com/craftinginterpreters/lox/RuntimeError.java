package com.craftinginterpreters.lox;

/**
 * 运行时错误。Interpreter 执行过程中遇到类型错误、未定义变量等问题时抛出，
 * 携带出错位置的 Token 以便报告准确的行号。
 */
class RuntimeError extends RuntimeException {
  final Token token;

  RuntimeError(Token token, String message) {
    super(message);
    this.token = token;
  }
}