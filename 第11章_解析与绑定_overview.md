# 第 11 章：解析与绑定 — 高层视角

---

## 一、本章要解决的核心问题

### 那个 Bug

```
var a = "global";
{
  fun showA() { print a; }

  showA();       // 期望：global
  var a = "block";
  showA();       // 期望：global  ← 实际却输出 block ！
}
```

**一个变量、一条 print、却在两个时刻打印出不同的值。**
原因不是赋值，而是环境查找的时机出了问题。

---

## 二、问题根源：可变环境 + 闭包 = 漏洞

### 当前实现的心智模型

```
代码块 = 一个可变的 HashMap
变量声明 = 往这个 HashMap 里插入条目
闭包    = 持有这个 HashMap 的引用（不是快照！）
```

### 时序图：为什么出错

```
时刻 1：声明 showA
        block_env = { showA: <fn> }
        showA.closure ──→ block_env

时刻 2：调用 showA()，查找 a
        showA_env（空）
            ↓
        block_env { showA: <fn> }      ← 没有 a
            ↓
        global_env { a: "global" }     ← 找到！打印 global ✓

时刻 3：声明 var a = "block"
        block_env = { showA: <fn>, a: "block" }  ← 同一个对象被修改！

时刻 4：再次调用 showA()，查找 a
        showA_env（空）
            ↓
        block_env { showA: <fn>, a: "block" }  ← 找到！打印 block ✗
```

**症结：** 闭包持有的是 `block_env` 的**引用**，而不是它被创建时的**快照**。
后来往同一个对象里塞进去的变量，闭包也能看见——违反了词法作用域的语义。

---

## 三、解决思路：把"动态查找"变成"静态计算"

### 静态作用域的本质

> 变量指向哪个声明，**只看代码文本**，与运行时无关。

既然答案在编译时就已确定，为什么要每次运行时都重新查找？

```
旧方式（动态）：每次执行变量表达式 → 沿环境链逐层查找名称
新方式（静态）：解析阶段一次性算出"距离" → 运行时直接定位，不再查名称
```

### 距离的含义

```
环境链：  当前env → 父env → 祖父env → ... → global
距离 0  ：变量就在当前环境
距离 1  ：变量在父环境
距离 2  ：变量在祖父环境
距离 null：全局变量（不在局部作用域栈中）
```

---

## 四、解决方案全景图

```
源码
 │
 ▼
Scanner ──────────────────────→ Tokens
 │
 ▼
Parser ───────────────────────→ AST（语法树）
 │
 ▼                              ← 本章新增这一遍 ↓
Resolver（静态语义分析遍）
 │   遍历 AST，对每个变量引用
 │   计算"距离值"写入 interpreter.locals
 │
 ▼
Interpreter ─────────────────→ 执行
    读取 locals 中的距离值
    用 getAt(distance) 精确定位
    不再动态搜索
```

---

## 五、三个核心组件的变化

### 5.1 新增：Resolver.java（解析遍）

```
职责：静态遍历 AST，建立"表达式节点 → 作用域距离"的映射

工具：
  scopes: Stack<Map<String, Boolean>>
    ├── 每层 = 一个词法块作用域
    ├── key  = 变量名
    └── value = false（已声明未定义） | true（已就绪）

关键操作：
  beginScope() / endScope()  ← 进出代码块时压栈/弹栈
  declare(name)              ← 标记 false（防止初始化式自引用）
  define(name)               ← 标记 true
  resolveLocal(expr, name)   ← 从内到外找，算距离，调用 interpreter.resolve()
```

**Resolver 与 Interpreter 的类比：**

| | Resolver（静态） | Interpreter（动态） |
|--|--|--|
| 遍历方式 | Visitor | Visitor |
| if 语句 | 两个分支都走 | 只走一个分支 |
| 循环 | 只遍历一次 | 重复执行 |
| 副作用 | 无 | 有（print、赋值…）|
| 函数体 | 立即遍历 | 调用时才执行 |

---

### 5.2 修改：Interpreter.java

```
新增字段：
  Map<Expr, Integer> locals   ← 存储 Resolver 写入的距离值

新增方法：
  resolve(expr, depth)        ← 供 Resolver 调用，写入 locals

修改方法：
  visitVariableExpr           ← 查 locals，有距离就用 getAt，否则走全局
  visitAssignExpr             ← 查 locals，有距离就用 assignAt，否则走全局
```

---

### 5.3 修改：Environment.java

```
新增方法：
  ancestor(distance)          ← 沿 enclosing 链向上走 distance 步
  getAt(distance, name)       ← 直接读取指定层的变量
  assignAt(distance, name, v) ← 直接写入指定层的变量
```

```
旧：get(name)        → while (env != null) { 找 name … }   O(n) 动态
新：getAt(dist,name) → ancestor(dist).values.get(name)     O(1) 精确
```

---

## 六、声明的两阶段设计：为什么要 declare → define？

```java
var a = a;   // 这应该报错
```

```
时刻 1：declare("a")   → scopes 中 a = false（已声明，未就绪）
时刻 2：解析初始化式 a → 发现 a 在当前作用域且值为 false → 报错！
时刻 3：define("a")    → a = true（就绪，可使用）
```

**对比函数声明：**

```
visitFunctionStmt:
  declare(name) + define(name)   ← 立即就绪
  resolveFunction(body)          ← 再解析体

为什么函数可以立即 define？
  函数名只在调用时才被使用，声明时不会立即求值函数体。
  允许递归：fun fact(n) { return fact(n-1); }  ← fact 先定义再解析体，合法。
```

---

## 七、静态错误检测：顺手做的两件好事

### 7.1 顶层 return

```
currentFunction: NONE → FUNCTION（进入函数时）→ 恢复（离开函数时）

visitReturnStmt:
  if currentFunction == NONE → 编译错误
```

### 7.2 局部作用域重复声明

```
declare():
  if scope.containsKey(name) → 编译错误

注：全局作用域不检查（全局变量允许重定义，是动态特性）
```

---

## 八、一句话总结

```
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  问题：闭包持有可变环境的引用，后来的声明会"穿透"进去         │
│                                                                 │
│  洞察：词法作用域是静态的，变量解析结果在编译时就已确定       │
│                                                                 │
│  方案：在执行前增加一个解析遍，把"运行时动态查找名称"         │
│        替换为"编译时计算距离 + 运行时精确定位"               │
│                                                                 │
│  收益：① 修复闭包 bug   ② 提升性能   ③ 顺手检测更多错误     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 九、与后续章节的关联

| 方向 | 说明 |
|--|--|
| 第 12 章（类） | `this` 和 `super` 也需要解析，`FunctionType` 会扩展为 `METHOD`、`INITIALIZER` |
| clox（C 实现） | 把"距离"直接变成字节码中的槽位索引，彻底消除运行时名称查找，性能再上一个台阶 |
| 类型系统 | 这一遍的位置（parse 后、execute 前）正是放类型检查器的地方 |
