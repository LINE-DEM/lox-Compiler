# lox-Compiler

一个基于 Java 实现的 Lox 语言解释器学习项目，整体路线参考《Crafting Interpreters》中的 `jlox`。项目目前已经覆盖从词法扫描、递归下降解析、静态作用域解析到树遍历解释执行的完整链路，适合作为编译原理与解释器实现的练手仓库。

仓库中的源码带有较多中文注释，除了核心实现外，还保留了若干案例解析和学习笔记，便于对照代码理解每一步的设计动机。

## 项目目标

- 手写一个可运行的 Lox 解释器，而不是依赖生成器或大型框架
- 用清晰的代码结构拆分扫描、解析、语义分析和执行阶段
- 通过中文注释和配套笔记，把“能跑”提升为“能看懂、能继续扩展”

## 当前能力

当前实现已经支持以下语言特性：

- 词法扫描：标识符、数字、字符串、关键字、运算符、注释
- 表达式求值：一元/二元运算、比较、逻辑运算、分组
- 语句执行：`print`、表达式语句、变量声明、块作用域
- 控制流：`if / else`、`while`、`for`
- 函数能力：函数声明、参数传递、`return`、闭包
- 面向对象：`class`、方法、`this`、`super`、单继承
- 运行方式：脚本文件执行和 REPL 交互模式
- 内置函数：`clock()`

其中 `for` 循环在语法阶段会被脱糖为 `while`，变量查找则通过 `Resolver` 预先计算作用域距离，再交给解释器按词法作用域执行。

## 核心执行流程

项目的主执行链路如下：

1. `Scanner` 将源码切分为 `Token` 序列
2. `Parser` 使用递归下降法把 `Token` 解析为 AST
3. `Resolver` 做静态作用域分析，记录变量绑定距离
4. `Interpreter` 以 Visitor 模式遍历 AST 并执行程序

入口类位于 `src/com/craftinginterpreters/lox/Lox.java`，会根据命令行参数决定进入脚本模式还是 REPL 模式。

## 目录结构

```text
src/
  com/craftinginterpreters/lox/
    Lox.java           程序入口
    Scanner.java       词法扫描
    Parser.java        语法分析（递归下降）
    Resolver.java      静态作用域解析
    Interpreter.java   树遍历解释执行
    Expr.java          表达式 AST
    Stmt.java          语句 AST
  com/craftinginterpreters/tool/
    GenerateAst.java   AST 代码生成工具

out/                   编译输出目录
bin/                   编译输出目录
*.md                   学习笔记、案例解析与补充说明
```

`GenerateAst.java` 保留了书中的 AST 生成工具。随着项目功能扩展，当前 `Expr.java` 和 `Stmt.java` 已经在生成结果基础上继续演进，因此它更适合作为理解 AST 生成思路的辅助工具。

## 开发环境

- JDK 21 已验证可编译运行
- Windows PowerShell 可直接执行以下命令

理论上使用支持 Java 的其他环境也可以运行，只要保留相同的包结构即可。

## 快速开始

### 1. 编译

```powershell
$files = Get-ChildItem -Recurse -Path .\src -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 -d .\out $files
```

### 2. 启动 REPL

```powershell
java -cp .\out com.craftinginterpreters.lox.Lox
```

启动后可直接输入 Lox 代码，例如：

```lox
print 1 + 2;

fun add(a, b) {
  return a + b;
}

print add(3, 4);

class A {
  method() {
    return "A";
  }
}

class B < A {
  method() {
    return super.method() + "B";
  }
}

print B().method();
```

### 3. 运行脚本文件

```powershell
java -cp .\out com.craftinginterpreters.lox.Lox .\your-script.lox
```

## 项目亮点

- 分层明确：扫描、解析、绑定、执行职责分离
- 注释友好：适合把源码当学习材料逐个文件阅读
- 特性完整：已经覆盖函数、闭包、类、继承等核心语言机制
- 易于扩展：后续可以继续增加数组、模块、错误恢复增强或字节码后端

## 适合谁

- 正在学习《Crafting Interpreters》并希望对照代码理解实现细节的人
- 想通过一个小而完整的项目熟悉解释器架构的人
- 想练习 Visitor、递归下降解析、词法作用域和闭包实现的人

## 配套资料

仓库根目录下已有多份中文资料，可配合阅读：

- `案例解析.md`
- `案例解析2.md`
- `第11章_解析与绑定_overview.md`
- `汇编_每一步一张栈图.md`
- `中英对照.md`

这些文档更偏学习笔记和专题拆解，`README.md` 则用于帮助新读者快速理解项目全貌。
