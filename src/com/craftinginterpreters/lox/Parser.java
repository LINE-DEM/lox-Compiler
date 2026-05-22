package com.craftinginterpreters.lox;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.craftinginterpreters.lox.TokenType.*;

/**
 * 语法解析器（递归下降）
 * 输入：Token 列表（Scanner 输出）
 * 输出：Stmt 列表（AST 森林）
 *
 * 规则入口链：
 *   parse() → declaration() → statement() → expression()
 *                                              └→ assignment() → or() → and()
 *                                                   → equality() → comparison()
 *                                                   → term() → factor()
 *                                                   → unary() → call() → primary()
 */
class Parser {

  /** 错误标记异常，用于在解析出错时中断当前语句并同步 */
  private static class ParseError extends RuntimeException {}

  private final List<Token> tokens;
  private int current = 0; // 当前正在查看的 Token 下标

  Parser(List<Token> tokens) {
    this.tokens = tokens;
  }

  /**
   * 程序 = 语句* EOF
   * 顶层入口：循环解析每条顶层语句，返回 AST 森林（Stmt 列表）
   */
  List<Stmt> parse() {
    List<Stmt> statements = new ArrayList<>();
    while (!isAtEnd()) {
      statements.add(declaration());
    }
    return statements;
  }


  /**
   * 声明 = 函数声明 | 变量声明 | 语句
   * 顶层语句入口；出错时调用 synchronize() 恢复，返回 null 跳过本条语句
   */
  private Stmt declaration() {
    try {
      // 新增部分开始
      if (match(CLASS)) return classDeclaration();
      // 新增部分结束
      if (match(FUN)) return function("function"); // fun 关键字 → 函数声明
      if (match(VAR)) return varDeclaration();     // var 关键字 → 变量声明
      return statement();
    } catch (ParseError error) {
      synchronize(); // 错误恢复：跳到下一条语句的开头
      return null;
    }
  }

  /**
   * 语句 = for | if | print | return | while | 块 | 表达式语句
   * 根据当前 Token 分发到具体语句解析方法
   */
  private Stmt statement() {
    if (match(FOR))        return forStatement();
    if (match(IF))         return ifStatement();
    if (match(PRINT))      return printStatement();
    if (match(RETURN))     return returnStatement();
    if (match(WHILE))      return whileStatement();
    if (match(LEFT_BRACE)) return new Stmt.Block(block());
    return expressionStatement();
  }

  /**
   * if语句 = "if" "(" 条件 ")" 语句 ( "else" 语句 )?
   * else 就近匹配（dangling-else：else 属于最近的 if）
   */
  private Stmt ifStatement() {
    consume(LEFT_PAREN, "Expect '(' after 'if'.");
    Expr condition = expression();
    consume(RIGHT_PAREN, "Expect ')' after if condition.");

    Stmt thenBranch = statement();
    Stmt elseBranch = null;
    if (match(ELSE)) {
      elseBranch = statement();
    }

    return new Stmt.If(condition, thenBranch, elseBranch);
  }

  /**
   * while语句 = "while" "(" 条件 ")" 语句
   */
  private Stmt whileStatement() {
    consume(LEFT_PAREN, "Expect '(' after 'while'.");
    Expr condition = expression();
    consume(RIGHT_PAREN, "Expect ')' after condition.");
    Stmt body = statement();

    return new Stmt.While(condition, body);
  }

  /**
   * for语句 = "for" "(" 初始化? ";" 条件? ";" 增量? ")" 语句
   * 实现方式：脱糖（desugaring）——把 for 拆解重组为 while + Block，
   * 不新增 AST 节点，复用已有的 Stmt.While 和 Stmt.Block。
   *
   * 结构：
   *   {
   *     initializer;          ← 可选
   *     while (condition) {   ← condition 为空则用 true
   *       body;
   *       increment;          ← 可选，追加在 body 末尾
   *     }
   *   }
   */
  private Stmt forStatement() {
    consume(LEFT_PAREN, "Expect '(' after 'for'.");

    // 初始化子句：可以是变量声明、表达式语句或省略
    Stmt initializer;
    if (match(SEMICOLON)) {
      initializer = null;
    } else if (match(VAR)) {
      initializer = varDeclaration();
    } else {
      initializer = expressionStatement();
    }

    // 条件子句：省略则永真
    Expr condition = null;
    if (!check(SEMICOLON)) {
      condition = expression();
    }
    consume(SEMICOLON, "Expect ';' after loop condition.");

    // 增量子句：省略则不执行
    Expr increment = null;
    if (!check(RIGHT_PAREN)) {
      increment = expression();
    }
    consume(RIGHT_PAREN, "Expect ')' after for clauses.");

    Stmt body = statement();

    // 把增量追加到循环体末尾
    if (increment != null) {
      body = new Stmt.Block(Arrays.asList(body, new Stmt.Expression(increment)));
    }

    // 用 while 包裹（condition 为 null 时用 true 表示无限循环）
    if (condition == null) condition = new Expr.Literal(true);
    body = new Stmt.While(condition, body);

    // 用 Block 包裹初始化（只执行一次）
    if (initializer != null) {
      body = new Stmt.Block(Arrays.asList(initializer, body));
    }

    return body;
  }

  /**
   * print语句 = "print" 表达式 ";"
   */
  private Stmt printStatement() {
    Expr value = expression();
    consume(SEMICOLON, "Expect ';' after value.");
    return new Stmt.Print(value);
  }

  /**
   * return语句 = "return" 表达式? ";"
   * 返回值可省略（返回 nil）
   */
  private Stmt returnStatement() {
    Token keyword = previous(); // 保存 return 关键字 Token（用于运行时错误定位）
    Expr value = null;
    if (!check(SEMICOLON)) {
      value = expression();
    }
    consume(SEMICOLON, "Expect ';' after return value.");
    return new Stmt.Return(keyword, value);
  }

  /**
   * 块语句 = "{" 语句* "}"
   * 返回语句列表，由调用者包装成 Stmt.Block
   */
  private List<Stmt> block() {
    List<Stmt> statements = new ArrayList<>();
    while (!check(RIGHT_BRACE) && !isAtEnd()) {
      statements.add(declaration());
    }
    consume(RIGHT_BRACE, "Expect '}' after block.");
    return statements;
  }

  /**
   * 变量声明 = "var" 变量名 ( "=" 表达式 )? ";"
   * 初始化表达式可省略（变量值默认为 nil）
   */
  private Stmt varDeclaration() {
    Token name = consume(IDENTIFIER, "Expect variable name.");

    Expr initializer = null;
    if (match(EQUAL)) {
      initializer = expression();
    }

    consume(SEMICOLON, "Expect ';' after variable declaration.");
    return new Stmt.Var(name, initializer);
  }

  /**
   * 表达式语句 = 表达式 ";"
   * 用于赋值、函数调用等有副作用但不以关键字开头的语句
   */
  private Stmt expressionStatement() {
    Expr expr = expression();
    consume(SEMICOLON, "Expect ';' after expression.");
    return new Stmt.Expression(expr);
  }

  // 新增部分开始
  private Stmt classDeclaration() {
    Token name = consume(IDENTIFIER, "Expect class name.");
    // 新增部分开始
    Expr.Variable superclass = null;
    if (match(LESS)) {
      consume(IDENTIFIER, "Expect superclass name.");
      superclass = new Expr.Variable(previous());
    }
    // 新增部分结束
    consume(LEFT_BRACE, "Expect '{' before class body.");

    List<Stmt.Function> methods = new ArrayList<>();
    while (!check(RIGHT_BRACE) && !isAtEnd()) {
      methods.add(function("method"));
    }

    consume(RIGHT_BRACE, "Expect '}' after class body.");
    // 替换部分开始
    return new Stmt.Class(name, superclass, methods);
    // 替换部分结束
  }
  // 新增部分结束

  /**
   * 函数声明 = "fun" 函数名 "(" 参数列表 ")" "{" 语句* "}"
   * kind 参数用于错误提示区分（如将来支持 "method"）
   * 参数上限 255 个（与 JVM 字节码规范保持一致）
   */
  private Stmt.Function function(String kind) {
    Token name = consume(IDENTIFIER, "Expect " + kind + " name.");
    consume(LEFT_PAREN, "Expect '(' after " + kind + " name.");
    List<Token> parameters = new ArrayList<>();
    if (!check(RIGHT_PAREN)) {
      do {
        if (parameters.size() >= 255) {
          error(peek(), "Can't have more than 255 parameters.");
        }
        parameters.add(consume(IDENTIFIER, "Expect parameter name."));
      } while (match(COMMA));
    }
    consume(RIGHT_PAREN, "Expect ')' after parameters.");
    consume(LEFT_BRACE, "Expect '{' before " + kind + " body.");
    List<Stmt> body = block();
    return new Stmt.Function(name, parameters, body);
  }


  // 优先级从低到高：赋值 → or → and → 相等 → 比较 → 加减 → 乘除 → 一元 → 调用 → 基本量

  /**
   * 表达式入口（最低优先级）
   * 表达式 = 赋值
   */
  private Expr expression() {
    return assignment();
  }

  /**
   * 赋值 = 或运算 | 变量名 "=" 赋值（右结合）
   *
   * 技巧：先解析左侧为任意表达式，再检查是否跟着 "="。
   * 若是，则验证左侧必须是合法的赋值目标（Variable），
   * 否则报错——这样一次扫描就能区分普通表达式和赋值。
   */
  private Expr assignment() {
    Expr expr = or(); // 先尝试解析成普通表达式

    if (match(EQUAL)) {
      Token equals = previous();
      Expr value = assignment(); // 右结合：递归解析右侧

      if (expr instanceof Expr.Variable) {
        Token name = ((Expr.Variable) expr).name;
        return new Expr.Assign(name, value);
      // 新增部分开始
      } else if (expr instanceof Expr.Get) {
        Expr.Get get = (Expr.Get) expr;
        return new Expr.Set(get.object, get.name, value);
      // 新增部分结束
      }

      error(equals, "Invalid assignment target."); // 左侧不是变量，报错
    }

    return expr;
  }

  /**
   * 或运算 = 与运算 ( "or" 与运算 )*
   * 短路求值：左侧为真时直接返回，不计算右侧
   */
  private Expr or() {
    Expr expr = and();

    while (match(OR)) {
      Token operator = previous();
      Expr right = and();
      expr = new Expr.Logical(expr, operator, right);
    }

    return expr;
  }

  /**
   * 与运算 = 相等判断 ( "and" 相等判断 )*
   * 短路求值：左侧为假时直接返回，不计算右侧
   */
  private Expr and() {
    Expr expr = equality();

    while (match(AND)) {
      Token operator = previous();
      Expr right = equality();
      expr = new Expr.Logical(expr, operator, right);
    }

    return expr;
  }

  /**
   * 相等判断 = 大小比较 ( ("!=" | "==") 大小比较 )*
   */
  private Expr equality() {
    Expr expr = comparison();

    while (match(BANG_EQUAL, EQUAL_EQUAL)) {
      Token operator = previous();
      Expr right = comparison();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  /**
   * 大小比较 = 加减运算 ( (">" | ">=" | "<" | "<=") 加减运算 )*
   */
  private Expr comparison() {
    Expr expr = term();

    while (match(GREATER, GREATER_EQUAL, LESS, LESS_EQUAL)) {
      Token operator = previous();
      Expr right = term();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  /**
   * 加减运算 = 乘除运算 ( ("+" | "-") 乘除运算 )*
   * "+" 同时支持字符串拼接（运行时判断）
   */
  private Expr term() {
    Expr expr = factor();

    while (match(MINUS, PLUS)) {
      Token operator = previous();
      Expr right = factor();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  /**
   * 乘除运算 = 一元运算 ( ("*" | "/") 一元运算 )*
   */
  private Expr factor() {
    Expr expr = unary();

    while (match(SLASH, STAR)) {
      Token operator = previous();
      Expr right = unary();
      expr = new Expr.Binary(expr, operator, right);
    }

    return expr;
  }

  /**
   * 一元运算 = ("-" | "!") 一元运算 | 函数调用
   * 递归处理，支持 --x、!!b 等连续一元运算
   */
  private Expr unary() {
    if (match(BANG, MINUS)) {
      Token operator = previous();
      Expr right = unary();
      return new Expr.Unary(operator, right);
    }

    return call();
  }

  /**
   * 函数调用 = 基本量 ( "(" 参数列表 ")" )*
   * 支持链式调用：getFunc()(1, 2)
   */
  private Expr call() {
    Expr expr = primary();

    while (true) {
      if (match(LEFT_PAREN)) {
        expr = finishCall(expr);
      // 新增部分开始
      } else if (match(DOT)) {
        Token name = consume(IDENTIFIER, "Expect property name after '.'.");
        expr = new Expr.Get(expr, name);
      // 新增部分结束
      } else {
        break;
      }
    }

    return expr;
  }

  /**
   * 解析调用参数列表并构建 Call 节点
   * 参数上限 255 个（与 JVM 字节码规范保持一致）
   */
  private Expr finishCall(Expr callee) {
    List<Expr> arguments = new ArrayList<>();
    if (!check(RIGHT_PAREN)) {
      do {
        if (arguments.size() >= 255) {
          error(peek(), "Can't have more than 255 arguments.");
        }
        arguments.add(expression());
      } while (match(COMMA));
    }

    Token paren = consume(RIGHT_PAREN, "Expect ')' after arguments.");

    return new Expr.Call(callee, paren, arguments);
  }

  /**
   * 基本量 = false | true | nil | 数字 | 字符串 | 变量名 | "(" 表达式 ")"
   * 不可再拆分的原子表达式，优先级最高（树中最靠近叶子）
   */
  private Expr primary() {
    if (match(FALSE)) return new Expr.Literal(false);
    if (match(TRUE))  return new Expr.Literal(true);
    if (match(NIL))   return new Expr.Literal(null);

    if (match(NUMBER, STRING)) {
      return new Expr.Literal(previous().literal);
    }

    // 新增部分开始
    if (match(SUPER)) {
      Token keyword = previous();
      consume(DOT, "Expect '.' after 'super'.");
      Token method = consume(IDENTIFIER,
          "Expect superclass method name.");
      return new Expr.Super(keyword, method);
    }
    // 新增部分结束

    // 新增部分开始
    if (match(THIS)) return new Expr.This(previous());
    // 新增部分结束

    if (match(IDENTIFIER)) {
      return new Expr.Variable(previous()); // 变量引用
    }

    if (match(LEFT_PAREN)) {
      Expr expr = expression();
      consume(RIGHT_PAREN, "Expect ')' after expression.");
      return new Expr.Grouping(expr); // 括号分组，仅改变优先级，不产生新节点语义
    }

    throw error(peek(), "Expect expression.");
  }



  /** 消费指定类型的 Token，失败则抛出解析错误 */
  private Token consume(TokenType type, String message) {
    if (check(type)) return advance();
    throw error(peek(), message);
  }

  /** 报告解析错误并返回 ParseError（不抛出，让调用方决定是否抛） */
  private ParseError error(Token token, String message) {
    Lox.error(token, message);
    return new ParseError();
  }

  /**
   * 错误恢复：丢弃 Token 直到遇到语句边界（";" 或语句关键字）
   * 目的：一次解析尽量多地报告错误，而不是遇到第一个错误就停止
   */
  private void synchronize() {
    advance();

    while (!isAtEnd()) {
      if (previous().type == SEMICOLON) return;

      switch (peek().type) {
        case CLASS:
        case FUN:
        case VAR:
        case FOR:
        case IF:
        case WHILE:
        case PRINT:
        case RETURN:
          return;
      }

      advance();
    }
  }

  /** 若当前 Token 匹配任一给定类型，则消费并返回 true；否则返回 false */
  private boolean match(TokenType... types) {
    for (TokenType type : types) {
      if (check(type)) {
        advance();
        return true;
      }
    }
    return false;
  }

  /** 检查当前 Token 类型（不消费） */
  private boolean check(TokenType type) {
    if (isAtEnd()) return false;
    return peek().type == type;
  }

  /** 返回当前 Token 并将指针后移一位 */
  private Token advance() {
    if (!isAtEnd()) current++;
    return previous();
  }

  /** 是否已到达 Token 列表末尾（EOF） */
  private boolean isAtEnd() {
    return peek().type == EOF;
  }

  /** 返回当前位置的 Token（不消费） */
  private Token peek() {
    return tokens.get(current);
  }

  /** 返回上一个已消费的 Token */
  private Token previous() {
    return tokens.get(current - 1);
  }


}
