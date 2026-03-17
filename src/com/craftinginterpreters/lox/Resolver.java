package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * 变量解析器（静态语义分析）
 * 在 Parser 生成语法树之后、Interpreter 执行之前运行一次。
 * 遍历整棵 AST，计算每个变量引用距离其声明作用域的"跳数"，
 * 并将结果写入 Interpreter.locals，使解释器能精确定位变量。
 *
 * 核心思路：
 *   - 用一个 Stack<Map<String,Boolean>> 模拟词法作用域链
 *   - Boolean 值：false = 已声明未定义（初始化式中禁止自引用）；true = 已就绪
 *   - 全局变量不进栈，查不到距离时留给运行时全局环境处理
 */
class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
  private final Interpreter interpreter;
  // 词法作用域栈（仅限局部块作用域，全局不入栈）
  private final Stack<Map<String, Boolean>> scopes = new Stack<>();
  // 跟踪当前是否在函数体内，用于检测顶层 return
  private FunctionType currentFunction = FunctionType.NONE;

  Resolver(Interpreter interpreter) {
    this.interpreter = interpreter;
  }

  /** 区分"不在函数内"和"在函数内"，便于扩展（方法、构造器等） */
  private enum FunctionType {
    NONE,
    FUNCTION,
    // 新增部分开始
    METHOD
    // 新增部分结束
  }

  // 新增部分开始
  private enum ClassType {
    NONE,
    CLASS
  }

  private ClassType currentClass = ClassType.NONE;
  // 新增部分结束

  // ─── 语句节点 ────────────────────────────────────────────────────────────────

  // 新增部分开始
  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    ClassType enclosingClass = currentClass;
    currentClass = ClassType.CLASS;

    declare(stmt.name);
    define(stmt.name);

    beginScope();
    scopes.peek().put("this", true);

    for (Stmt.Function method : stmt.methods) {
      FunctionType declaration = FunctionType.METHOD;
      resolveFunction(method, declaration);
    }

    endScope();

    currentClass = enclosingClass;
    return null;
  }
  // 新增部分结束

  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    beginScope();
    resolve(stmt.statements);
    endScope();
    return null;
  }

  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    // 先声明（标记为"未就绪"），再解析初始化式，最后定义（标记为"就绪"）
    // 目的：防止 var a = a; 这种在自身初始化式中引用自身的情况
    declare(stmt.name);
    if (stmt.initializer != null) {
      resolve(stmt.initializer);
    }
    define(stmt.name);
    return null;
  }

  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    // 函数名在外层作用域中立即声明并定义（允许递归）
    declare(stmt.name);
    define(stmt.name);
    resolveFunction(stmt, FunctionType.FUNCTION);
    return null;
  }

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    resolve(stmt.expression);
    return null;
  }

  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    // 静态分析：两个分支都要解析（不做控制流）
    resolve(stmt.condition);
    resolve(stmt.thenBranch);
    if (stmt.elseBranch != null) resolve(stmt.elseBranch);
    return null;
  }

  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    resolve(stmt.expression);
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    // 顶层 return 是静态错误
    if (currentFunction == FunctionType.NONE) {
      Lox.error(stmt.keyword, "Can't return from top-level code.");
    }
    if (stmt.value != null) {
      resolve(stmt.value);
    }
    return null;
  }

  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    // 静态分析：循环体只遍历一次（不做控制流）
    resolve(stmt.condition);
    resolve(stmt.body);
    return null;
  }

  // ─── 表达式节点 ──────────────────────────────────────────────────────────────

  @Override
  public Void visitVariableExpr(Expr.Variable expr) {
    // 检查是否在变量自身的初始化式中引用自身（值为 false = 已声明未定义）
    if (!scopes.isEmpty() &&
        scopes.peek().get(expr.name.lexeme) == Boolean.FALSE) {
      Lox.error(expr.name,
          "Can't read local variable in its own initializer.");
    }
    resolveLocal(expr, expr.name);
    return null;
  }

  @Override
  public Void visitAssignExpr(Expr.Assign expr) {
    resolve(expr.value);          // 先解析右值
    resolveLocal(expr, expr.name); // 再解析被赋值的变量
    return null;
  }

  @Override
  public Void visitBinaryExpr(Expr.Binary expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  // 新增部分开始
  @Override
  public Void visitGetExpr(Expr.Get expr) {
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitSetExpr(Expr.Set expr) {
    resolve(expr.value);
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitThisExpr(Expr.This expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword, "Can't use 'this' outside of a class.");
      return null;
    }
    resolveLocal(expr, expr.keyword);
    return null;
  }
  // 新增部分结束

  @Override
  public Void visitCallExpr(Expr.Call expr) {
    resolve(expr.callee);
    for (Expr argument : expr.arguments) {
      resolve(argument);
    }
    return null;
  }

  @Override
  public Void visitGroupingExpr(Expr.Grouping expr) {
    resolve(expr.expression);
    return null;
  }

  @Override
  public Void visitLiteralExpr(Expr.Literal expr) {
    return null; // 字面量无变量引用，无需处理
  }

  @Override
  public Void visitLogicalExpr(Expr.Logical expr) {
    // 静态分析不做短路，两侧都要解析
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitUnaryExpr(Expr.Unary expr) {
    resolve(expr.right);
    return null;
  }

  // ─── 辅助方法 ────────────────────────────────────────────────────────────────

  /** 解析语句列表 */
  void resolve(List<Stmt> statements) {
    for (Stmt statement : statements) {
      resolve(statement);
    }
  }

  private void resolve(Stmt stmt) {
    stmt.accept(this);
  }

  private void resolve(Expr expr) {
    expr.accept(this);
  }

  /** 开始新作用域：将空 map 压栈 */
  private void beginScope() {
    scopes.push(new HashMap<String, Boolean>());
  }

  /** 结束作用域：弹出栈顶 map */
  private void endScope() {
    scopes.pop();
  }

  /**
   * 声明变量：加入当前作用域，值为 false（未就绪）。
   * 若当前作用域已存在同名变量，报静态错误（局部重复声明）。
   */
  private void declare(Token name) {
    if (scopes.isEmpty()) return;
    Map<String, Boolean> scope = scopes.peek();
    // 新增部分开始
    if (scope.containsKey(name.lexeme)) {
      Lox.error(name,
          "Already variable with this name in this scope.");
    }
    // 新增部分结束
    scope.put(name.lexeme, false);
  }

  /** 定义变量：将当前作用域中变量值置为 true（就绪） */
  private void define(Token name) {
    if (scopes.isEmpty()) return;
    scopes.peek().put(name.lexeme, true);
  }

  /**
   * 解析局部变量引用：从最内层作用域向外查找，
   * 找到后调用 interpreter.resolve() 传入跳数。
   * 未找到则假定是全局变量，不处理。
   */
  private void resolveLocal(Expr expr, Token name) {
    for (int i = scopes.size() - 1; i >= 0; i--) {
      if (scopes.get(i).containsKey(name.lexeme)) {
        interpreter.resolve(expr, scopes.size() - 1 - i);
        return;
      }
    }
  }

  /** 解析函数体：创建新作用域，绑定形参，递归解析函数体语句 */
  private void resolveFunction(Stmt.Function function, FunctionType type) {
    FunctionType enclosingFunction = currentFunction;
    currentFunction = type;

    beginScope();
    for (Token param : function.params) {
      declare(param);
      define(param);
    }
    resolve(function.body);
    endScope();

    currentFunction = enclosingFunction;
  }
}
