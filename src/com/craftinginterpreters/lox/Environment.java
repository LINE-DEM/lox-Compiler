package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.Map;

/**
 * 变量环境（作用域）。用 Map 存储变量名→值的绑定关系。
 * 通过 enclosing 链支持嵌套作用域：get/assign 会沿链向外查找，
 * define 在当前作用域新增变量。
 */
class Environment {
  final Environment enclosing;
  private final Map<String, Object> values = new HashMap<>();

  Environment() {
    enclosing = null;
  }

  Environment(Environment enclosing) {
    this.enclosing = enclosing;
  }

  void define(String name, Object value) {
    values.put(name, value);
  }

  // 新增部分开始
  /** 沿 enclosing 链向上走 distance 步，返回目标环境 */
  Environment ancestor(int distance) {
    Environment environment = this;
    for (int i = 0; i < distance; i++) {
      environment = environment.enclosing;
    }
    return environment;
  }

  /** 直接在距离为 distance 的祖先环境中读取变量（无需动态查找） */
  Object getAt(int distance, String name) {
    return ancestor(distance).values.get(name);
  }

  /** 直接在距离为 distance 的祖先环境中写入变量 */
  void assignAt(int distance, Token name, Object value) {
    ancestor(distance).values.put(name.lexeme, value);
  }
  // 新增部分结束

  Object get(Token name) {
    if (values.containsKey(name.lexeme)) {
      return values.get(name.lexeme);
    }

    if (enclosing != null) return enclosing.get(name);

    throw new RuntimeError(name,
        "Undefined variable '" + name.lexeme + "'.");
  }

  void assign(Token name, Object value) {
    if (values.containsKey(name.lexeme)) {
      values.put(name.lexeme, value);
      return;
    }

    if (enclosing != null) {
      enclosing.assign(name, value);
      return;
    }

    throw new RuntimeError(name,
        "Undefined variable '" + name.lexeme + "'.");
  }
}
