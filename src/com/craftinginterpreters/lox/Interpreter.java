package com.craftinginterpreters.lox;
import java.util.ArrayList;
import java.util.List;

/**
 * 解释器（树遍历求值）
 * 输入：Stmt/Expr AST 树；输出：执行副作用（打印、变量赋值、函数调用等）
 *
 * 实现方式：Visitor 模式——每种 AST 节点对应一个 visit 方法。
 * 遍历顺序：后序（先求子节点的值，再用父节点的运算符合并）。
 *
 * 两类 Visitor：
 *   Expr.Visitor<Object> → visitXxxExpr：表达式求值，返回 Object（运行时值）
 *   Stmt.Visitor<Void>   → visitXxxStmt：语句执行，产生副作用，返回 null
 */
class Interpreter implements Expr.Visitor<Object>,
                             Stmt.Visitor<Void> {


  /** 全局环境，生命周期与解释器相同 */
  final Environment globals = new Environment();
  /** 当前活跃环境（随块/函数调用切换） */
  private Environment environment = globals;

  Interpreter() {
    // 注册内置函数 clock()：返回 Unix 时间戳（秒），用于性能计时
    globals.define("clock", new LoxCallable() {
      @Override
      public int arity() { return 0; }

      @Override
      public Object call(Interpreter interpreter, List<Object> arguments) {
        return (double) System.currentTimeMillis() / 1000.0;
      }

      @Override
      public String toString() { return "<native fn>"; }
    });
  }



  /**
   * 顶层入口：依次执行每条顶层语句。
   * 捕获 RuntimeError 并交由 Lox.runtimeError() 报告（不中断后续语句）。
   */
  void interpret(List<Stmt> statements) {
    try {
      for (Stmt statement : statements) {
        execute(statement);
      }
    } catch (RuntimeError error) {
      Lox.runtimeError(error);
    }
  }




  /**
   * var 语句：求值初始化表达式（可选），在当前环境中定义变量。
   * 若无初始化，变量值为 nil。
   */
  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    Object value = null;
    if (stmt.initializer != null) {
      value = evaluate(stmt.initializer);
    }
    environment.define(stmt.name.lexeme, value);
    return null;
  }

  /**
   * 块语句：为块创建新的子环境，执行完后恢复外层环境。
   * 块内声明的变量在块结束后不可见。
   */
  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    executeBlock(stmt.statements, new Environment(environment));
    return null;
  }

  /**
   * 切换到指定环境执行语句列表，执行完毕后（无论是否异常）恢复原环境。
   * finally 保证环境栈不会因 return/异常而泄漏。
   */
  void executeBlock(List<Stmt> statements, Environment environment) {
    Environment previous = this.environment;
    try {
      this.environment = environment;
      for (Stmt statement : statements) {
        execute(statement);
      }
    } finally {
      this.environment = previous;
    }
  }



  /**
   * if 语句：求值条件，根据真假执行对应分支。
   * elseBranch 为 null 时表示没有 else 子句。
   */
  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    if (isTruthy(evaluate(stmt.condition))) {
      execute(stmt.thenBranch);
    } else if (stmt.elseBranch != null) {
      execute(stmt.elseBranch);
    }
    return null;
  }

  /**
   * while 语句：反复求值条件，条件为真则执行循环体。
   * for 循环已在 Parser 中脱糖为 while，此处不需要单独处理。
   */
  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    while (isTruthy(evaluate(stmt.condition))) {
      execute(stmt.body);
    }
    return null;
  }



  /**
   * fun 声明：将函数定义包装成 LoxFunction 对象，注册到当前环境。
   * 闭包：捕获声明时的 environment（而非调用时的），实现词法作用域。
   */
  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    LoxFunction function = new LoxFunction(stmt, environment);
    environment.define(stmt.name.lexeme, function);
    return null;
  }

  /**
   * return 语句：通过抛出 Return 异常（携带返回值）来展开调用栈。
   * 用异常而非普通返回值，是因为 return 可能出现在深层嵌套中。
   */
  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    Object value = null;
    if (stmt.value != null) value = evaluate(stmt.value);
    throw new Return(value); // 用异常展开调用栈，由 LoxFunction.call() 捕获
  }



  /**
   * 表达式语句：求值表达式（取副作用），丢弃返回值。
   * 例：x = 5;   add(1, 2);
   */
  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    evaluate(stmt.expression);
    return null;
  }

  /**
   * print 语句：求值表达式并打印字符串化结果到标准输出。
   */
  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    Object value = evaluate(stmt.expression);
    System.out.println(stringify(value));
    return null;
  }





  /**
   * 字面量：直接返回 AST 节点中存储的运行时值（Double / String / Boolean / null）
   */
  @Override
  public Object visitLiteralExpr(Expr.Literal expr) {
    return expr.value;
  }

  /**
   * 变量引用：在当前环境链中查找变量值。
   * 若变量未定义，Environment.get() 会抛出 RuntimeError。
   */
  @Override
  public Object visitVariableExpr(Expr.Variable expr) {
    return environment.get(expr.name);
  }

  /**
   * 括号分组：直接求值内部表达式，分组仅影响解析优先级，运行时无额外语义。
   */
  @Override
  public Object visitGroupingExpr(Expr.Grouping expr) {
    return evaluate(expr.expression);
  }

  /**
   * 赋值表达式：求值右侧，写入环境，并将值作为表达式结果返回。
   * 返回值使 a = b = 1 这种链式赋值成为可能。
   */
  @Override
  public Object visitAssignExpr(Expr.Assign expr) {
    Object value = evaluate(expr.value);
    environment.assign(expr.name, value);
    return value;
  }



  /**
   * 逻辑运算（or / and）：短路求值——不一定计算右侧。
   *   or：左侧为真 → 直接返回左值（不再求右侧）
   *   and：左侧为假 → 直接返回左值（不再求右侧）
   *
   * 注意：返回的是操作数的原始值（非 Boolean），与 Ruby/Python 行为一致。
   * 例：nil or "yes" → "yes"；1 and 2 → 2
   */
  @Override
  public Object visitLogicalExpr(Expr.Logical expr) {
    Object left = evaluate(expr.left);

    if (expr.operator.type == TokenType.OR) {
      if (isTruthy(left)) return left;  // 短路：or 左侧真则不看右侧
    } else {
      if (!isTruthy(left)) return left; // 短路：and 左侧假则不看右侧
    }

    return evaluate(expr.right);
  }



  /**
   * 一元运算：
   *   !value  → 对 isTruthy 结果取反（Boolean）
   *   -value  → 数值取负（必须是 Double，否则运行时报错）
   */
  @Override
  public Object visitUnaryExpr(Expr.Unary expr) {
    Object right = evaluate(expr.right);

    switch (expr.operator.type) {
      case BANG:
        return !isTruthy(right);
      case MINUS:
        checkNumberOperand(expr.operator, right);
        return -(double) right;
      default:
        return null; // 不可达
    }
  }



  /**
   * 二元运算：先求值左右两侧，再按运算符执行。
   *
   * 特殊处理：
   *   +  → 支持数字加法 和 字符串拼接（运行时类型判断）
   *   其余算术/比较运算符 → 只接受 Double，否则抛 RuntimeError
   *   == / != → 使用 isEqual()，null 安全，不做隐式类型转换
   */
  @Override
  public Object visitBinaryExpr(Expr.Binary expr) {
    Object left = evaluate(expr.left);
    Object right = evaluate(expr.right);

    switch (expr.operator.type) {
      case GREATER:
        checkNumberOperands(expr.operator, left, right);
        return (double) left > (double) right;
      case GREATER_EQUAL:
        checkNumberOperands(expr.operator, left, right);
        return (double) left >= (double) right;
      case LESS:
        checkNumberOperands(expr.operator, left, right);
        return (double) left < (double) right;
      case LESS_EQUAL:
        checkNumberOperands(expr.operator, left, right);
        return (double) left <= (double) right;
      case MINUS:
        checkNumberOperands(expr.operator, left, right);
        return (double) left - (double) right;
      case PLUS:
        if (left instanceof Double && right instanceof Double)
          return (double) left + (double) right;
        if (left instanceof String && right instanceof String)
          return (String) left + (String) right;
        throw new RuntimeError(expr.operator,
            "Operands must be two numbers or two strings.");
      case SLASH:
        checkNumberOperands(expr.operator, left, right);
        return (double) left / (double) right;
      case STAR:
        checkNumberOperands(expr.operator, left, right);
        return (double) left * (double) right;
      case BANG_EQUAL:  return !isEqual(left, right);
      case EQUAL_EQUAL: return isEqual(left, right);
    }

    return null; // 不可达
  }



  /**
   * 函数调用表达式：
   *   1. 求值被调用者（callee）
   *   2. 依次求值所有参数
   *   3. 检查 callee 是否可调用（LoxCallable）
   *   4. 检查参数数量（arity）
   *   5. 调用 function.call()，返回结果
   */
  @Override
  public Object visitCallExpr(Expr.Call expr) {
    Object callee = evaluate(expr.callee);

    List<Object> arguments = new ArrayList<>();
    for (Expr argument : expr.arguments) {
      arguments.add(evaluate(argument));
    }

    if (!(callee instanceof LoxCallable)) {
      throw new RuntimeError(expr.paren, "Can only call functions and classes.");
    }

    LoxCallable function = (LoxCallable) callee;
    if (arguments.size() != function.arity()) {
      throw new RuntimeError(expr.paren,
          "Expected " + function.arity() + " arguments but got " +
          arguments.size() + ".");
    }

    return function.call(this, arguments);
  }




  /** 分发给对应的 visitXxxExpr 方法（Visitor 模式入口） */
  private Object evaluate(Expr expr) {
    return expr.accept(this);
  }

  /** 分发给对应的 visitXxxStmt 方法（Visitor 模式入口） */
  private void execute(Stmt stmt) {
    stmt.accept(this);
  }

  /**
   * Lox 的真值规则（与大多数动态语言一致）：
   *   nil   → false
   *   false → false
   *   其他  → true（包括 0、""、空列表等）
   */
  private boolean isTruthy(Object object) {
    if (object == null) return false;
    if (object instanceof Boolean) return (boolean) object;
    return true;
  }

  /**
   * 相等判断（null 安全，不做隐式类型转换）：
   *   nil == nil → true；nil == 其他 → false；其他用 .equals()
   */
  private boolean isEqual(Object a, Object b) {
    if (a == null && b == null) return true;
    if (a == null) return false;
    return a.equals(b);
  }

  /**
   * 将运行时值转换为可打印字符串：
   *   nil    → "nil"
   *   整数浮点（如 1.0）→ 去掉 ".0" 后缀，显示为 "1"
   *   其他   → 调用 toString()
   */
  private String stringify(Object object) {
    if (object == null) return "nil";

    if (object instanceof Double) {
      String text = object.toString();
      if (text.endsWith(".0")) {
        text = text.substring(0, text.length() - 2);
      }
      return text;
    }

    return object.toString();
  }

  /** 检查一元运算数必须为数字 */
  private void checkNumberOperand(Token operator, Object operand) {
    if (operand instanceof Double) return;
    throw new RuntimeError(operator, "Operand must be a number.");
  }

  /** 检查二元运算两侧必须均为数字 */
  private void checkNumberOperands(Token operator, Object left, Object right) {
    if (left instanceof Double && right instanceof Double) return;
    throw new RuntimeError(operator, "Operands must be numbers.");
  }


}
