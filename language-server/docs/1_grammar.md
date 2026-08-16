# MCFPP语法文档

## 1. 词法规则

### 1.1 基本字符集

MCFPP支持Unicode字符集，标识符可以包含字母、数字和下划线，但必须以字母或下划线开头。

### 1.2 关键字

以下是MCFPP语言的关键字：

| 关键字 | 描述 |
|--------|------|
| `this` | 指向当前对象 |
| `super` | 指向父类对象 |
| `if` | 条件语句 |
| `else` | 条件语句的否定分支 |
| `while` | 循环语句 |
| `for` | 循环语句 |
| `do` | 循环语句 |
| `try` | 尝试执行语句 |
| `store` | 存储语句 |
| `as` | 类型转换 |
| `from` | 导入语句 |
| `execute` | 执行上下文 |
| `break` | 跳出循环 |
| `continue` | 继续循环 |
| `return` | 返回语句 |
| `static` | 静态成员 |
| `extends` | 继承 |
| `native` | 原生函数 |
| `concrete` | 具体实现 |
| `final` | 最终类或方法 |
| `public` | 公共访问修饰符 |
| `protected` | 受保护访问修饰符 |
| `private` | 私有访问修饰符 |
| `override` | 重写方法 |
| `abstract` | 抽象类或方法 |
| `impl` | 实现接口 |
| `const` | 常量 |
| `dynamic` | 动态类型 |
| `import` | 导入语句 |
| `inline` | 内联函数 |
| `object` | 对象 |
| `interface` | 接口 |
| `data` | 数据模板 |
| `func` | 函数 |
| `enum` | 枚举 |
| `operator` | 运算符重载 |
| `typealias` | 类型别名 |
| `constructor` | 构造函数 |
| `global` | 全局 |
| `var` | 变量声明 |
| `get` | 属性getter |
| `set` | 属性setter |
| `namespace` | 命名空间 |
| `vec` | 向量类型 |
| `int` | 整数类型 |
| `entity` | 实体类型 |
| `bool` | 布尔类型 |
| `byte` | 字节类型 |
| `short` | 短整型 |
| `long` | 长整型 |
| `float` | 浮点型 |
| `double` | 双精度浮点型 |
| `selector` | 选择器类型 |
| `string` | 字符串类型 |
| `text` | 文本类型 |
| `nbt` | NBT类型 |
| `any` | 任意类型 |
| `void` | 空类型 |
| `list` | 列表类型 |
| `map` | 映射类型 |
| `dict` | 字典类型 |
| `Type` | 类型类型 |
| `ByteArray` | 字节数组类型 |
| `IntArray` | 整数数组类型 |
| `LongArray` | 长整数数组类型 |
| `true` | 布尔值真 |
| `false` | 布尔值假 |
| `null` | 空值 |

### 1.3 运算符

#### 1.3.1 算术运算符

| 运算符 | 描述 |
|--------|------|
| `+` | 加法 |
| `-` | 减法 |
| `*` | 乘法 |
| `/` | 除法 |
| `%` | 取模 |
| `++` | 自增 |
| `--` | 自减 |

#### 1.3.2 赋值运算符

| 运算符 | 描述 |
|--------|------|
| `=` | 赋值 |
| `+=` | 加法赋值 |
| `-=` | 减法赋值 |
| `*=` | 乘法赋值 |
| `/=` | 除法赋值 |
| `%=` | 取模赋值 |

#### 1.3.3 比较运算符

| 运算符 | 描述 |
|--------|------|
| `==` | 等于 |
| `!=` | 不等于 |
| `~=` | 波浪等于 |
| `<` | 小于 |
| `>` | 大于 |
| `<=` | 小于等于 |
| `>=` | 大于等于 |

#### 1.3.4 逻辑运算符

| 运算符 | 描述 |
|--------|------|
| `&&` | 逻辑与 |
| `||` | 逻辑或 |
| `!` | 逻辑非 |

#### 1.3.5 位运算符

| 运算符 | 描述 |
|--------|------|
| `&` | 位与 |
| `|` | 位或 |

#### 1.3.6 其他运算符

| 运算符 | 描述 |
|--------|------|
| `.` | 成员访问 |
| `,` | 分隔符 |
| `(` | 左括号 |
| `)` | 右括号 |
| `[` | 左方括号 |
| `]` | 右方括号 |
| `{` | 左大括号 |
| `}` | 右大括号 |
| `:` | 冒号 |
| `;` | 分号 |
| `->` | 箭头 |
| `=>` | 双箭头 |
| `..` | 范围 |
| `::` | 作用域解析 |
| `#` | 注释 |
| `@` | 注解 |
| `?` | 可空类型 |
| `<` | 泛型开始 |
| `>` | 泛型结束 |
| `|` | 联合类型 |

### 1.4 常量

#### 1.4.1 数值常量

MCFPP支持多种数值常量：

- **整数常量**：如`123`、`0x1A`（十六进制）、`0123`（八进制）
- **长整数常量**：如`123L`、`0x1AL`
- **浮点数常量**：如`123.45`、`123.45f`、`123.45d`
- **字节常量**：如`123B`
- **短整数常量**：如`123S`

#### 1.4.2 布尔常量

布尔常量只有两个值：`true`和`false`。

#### 1.4.3 字符串常量

字符串常量可以用单引号或双引号括起来，支持转义字符：

- 单行字符串：如`"hello"`、`'world'`
- 多行字符串：如`"""line1
line2"""`

#### 1.4.4 NBT常量

NBT常量用于表示Minecraft的NBT数据：

- **NBT字节**：如`1B`、`255B`
- **NBT短整数**：如`1S`、`32767S`
- **NBT整数**：如`1`、`2147483647`
- **NBT长整数**：如`1L`、`9223372036854775807L`
- **NBT浮点数**：如`1.0f`、`3.14159f`
- **NBT双精度浮点数**：如`1.0d`、`3.14159d`
- **NBT字节数组**：如`[B;1,2,3]`
- **NBT整数数组**：如`[I;1,2,3]`
- **NBT长整数数组**：如`[L;1L,2L,3L]`
- **NBT列表**：如`[1,2,3]`
- **NBT复合**：如`{name:"value",number:123}`

#### 1.4.5 坐标常量

坐标常量用于表示Minecraft中的坐标：

- **绝对坐标**：如`100 200 300`
- **相对坐标**：如`~1 ~2 ~3`
- **局部坐标**：如`^1 ^2 ^3`

#### 1.4.6 选择器常量

选择器常量用于选择Minecraft中的实体：

- `@a`：所有玩家
- `@r`：随机玩家
- `@p`：最近的玩家
- `@s`：当前实体
- `@e`：所有实体

### 1.5 注释

MCFPP支持三种类型的注释：

- **单行注释**：以`#`开头，直到行尾
- **块注释**：以`##`开头，以`##`结尾，可以跨多行
- **文档注释**：以`###`开头，以`###`结尾，用于生成文档

## 2. 语法规则

### 2.1 程序结构

一个MCFPP程序由以下部分组成：

1. 命名空间声明（可选）
2. 导入声明（可选）
3. 类型别名声明（可选）
4. 顶级语句（可选）
5. 类型声明（可选）

### 2.2 命名空间

命名空间用于组织代码，避免名称冲突：

```mcfpp
namespace com.example.mymod
```

### 2.3 导入

导入用于引入其他命名空间中的类型和函数：

```mcfpp
import com.example.mymod:MyClass
import com.example.mymod:* as mymod
```

### 2.4 类型别名

类型别名用于为现有类型创建新的名称：

```mcfpp
typealias int as Integer
typealias list<string> as StringList
```

### 2.5 变量声明

变量声明使用`var`关键字：

```mcfpp
var x as int = 10
var y as string = "hello"
var z as bool = true
```

### 2.6 函数声明

函数声明使用`func`关键字：

```mcfpp
func add(a as int, b as int) -> int {
    return a + b
}
```

### 2.7 类和数据模板

MCFPP支持类和数据模板：

```mcfpp
data Person {
    var name as string
    var age as int
    
    constructor(name as string, age as int) {
        this.name = name
        this.age = age
    }
    
    func sayHello() {
        print("Hello, my name is " + name)
    }
}
```

### 2.8 接口

接口用于定义类必须实现的方法：

```mcfpp
interface Animal {
    func makeSound()
}

data Dog implements Animal {
    func makeSound() {
        print("Woof!")
    }
}
```

### 2.9 枚举

枚举用于定义一组命名的常量：

```mcfpp
enum Direction {
    UP
    DOWN
    LEFT
    RIGHT
}
```

### 2.10 条件语句

条件语句使用`if`和`else`关键字：

```mcfpp
if (x > 0) {
    print("x is positive")
} else if (x < 0) {
    print("x is negative")
} else {
    print("x is zero")
}
```

### 2.11 循环语句

MCFPP支持多种循环语句：

#### 2.11.1 While循环

```mcfpp
var i as int = 0
while (i < 10) {
    print(i)
    i++
}
```

#### 2.11.2 Do-While循环

```mcfpp
var i as int = 0
do {
    print(i)
    i++
} while (i < 10)
```

#### 2.11.3 For循环

```mcfpp
for (var i as int = 0; i < 10; i++) {
    print(i)
}
```

#### 2.11.4 For-Each循环

```mcfpp
var list as list<int> = [1, 2, 3, 4, 5]
for (var item in list) {
    print(item)
}
```

### 2.12 异常处理

MCFPP支持简单的异常处理：

```mcfpp
try {
    // 可能抛出异常的代码
} store (e) {
    // 处理异常
    print("Error: " + e)
}
```

### 2.13 执行上下文

执行上下文用于修改命令的执行环境：

```mcfpp
execute(as = @a, at = @s) {
    // 在所有玩家的位置执行命令
    print("Hello from " + @s.name)
}
```

### 2.14 原生命令

原生命令用于直接执行Minecraft命令：

```mcfpp
/say Hello, world!
/give @p diamond 1
/tp @p 100 200 300
```

## 3. 类型系统

### 3.1 基本类型

| 类型 | 描述 |
|------|------|
| `int` | 32位整数 |
| `long` | 64位整数 |
| `byte` | 8位整数 |
| `short` | 16位整数 |
| `float` | 32位浮点数 |
| `double` | 64位浮点数 |
| `bool` | 布尔值 |
| `string` | 字符串 |
| `text` | JSON文本 |
| `selector` | 实体选择器 |
| `entity` | 实体 |
| `nbt` | NBT数据 |
| `any` | 任意类型 |

### 3.2 复合类型

| 类型 | 描述 | 语法 |
|------|------|------|
| 列表 | 有序集合 | `list<T>` |
| 映射 | 键值对集合 | `map<K, V>` |
| 字典 | 动态键值对集合 | `dict` |
| 字节数组 | 字节数组 | `ByteArray` |
| 整数数组 | 整数数组 | `IntArray` |
| 长整数数组 | 长整数数组 | `LongArray` |

### 3.3 向量类型

向量类型用于表示三维坐标：

```mcfpp
var pos as vec3 = 100 200 300
var velocity as vec3 = 1.5 0 -1.5
```

### 3.4 联合类型

联合类型用于表示一个值可以是多种类型中的一种：

```mcfpp
var x as (int | string) = 10
x = "hello" // 合法
```

### 3.5 可空类型

可空类型用于表示一个值可以是`null`：

```mcfpp
var x as int? = null
var y as string? = "hello"
```

### 3.6 类型转换

类型转换使用`as`关键字：

```mcfpp
var x as int = 10
var y as float = x as float
var z as string = x as string
```

## 4. 高级特性

### 4.1 泛型

MCFPP支持泛型：

```mcfpp
func <T> getFirstElement(list as list<T>) -> T {
    return list[0]
}
```

### 4.2 扩展函数

扩展函数用于为现有类型添加新的方法：

```mcfpp
func string.reverse() -> string {
    // 实现字符串反转
}

var s as string = "hello"
var reversed = s.reverse() // "olleh"
```

### 4.3 内联函数

内联函数用于提高性能：

```mcfpp
inline func add(a as int, b as int) -> int {
    return a + b
}
```

### 4.4 原生函数

原生函数用于调用Java代码：

```mcfpp
func sqrt(x as double) -> double = java.lang.Math.sqrt
```

### 4.5 编译时函数

编译时函数在编译阶段执行：

```mcfpp
const func factorial(n as int) -> int {
    if (n <= 1) {
        return 1
    }
    return n * factorial(n - 1)
}

var result as int = factorial(5) // 编译时计算为120
```

### 4.6 运算符重载

MCFPP支持运算符重载：

```mcfpp
data Vector {
    var x as double
    var y as double
    
    operator +(other as Vector) -> Vector {
        return Vector(x + other.x, y + other.y)
    }
}

var v1 as Vector = Vector(1.0, 2.0)
var v2 as Vector = Vector(3.0, 4.0)
var v3 as Vector = v1 + v2 // Vector(4.0, 6.0)
```

### 4.7 属性访问器

属性访问器用于控制属性的访问：

```mcfpp
data Person {
    var _name as string
    
    var name {
        get {
            return _name
        }
        set {
            if (value.length > 0) {
                _name = value
            }
        }
    }
}
```

## 5. 代码示例

以下是一个完整的MCFPP程序示例：

```mcfpp
###
这是一个简单的MCFPP程序示例
###

namespace com.example.mymod

// 导入语句
import com.example.utils:Logger

// 类型别名
typealias int as Integer

// 数据模板
data Person {
    var name as string
    var age as Integer
    
    constructor(name as string, age as Integer) {
        this.name = name
        this.age = age
    }
    
    func sayHello() {
        Logger.log("Hello, my name is " + name + " and I am " + age + " years old")
    }
}

// 扩展函数
func string.toUpperCase() -> string {
    // 实现字符串转大写
}

// 主函数
func main() {
    var person as Person = Person("Alice", 30)
    person.sayHello()
    
    var message as string = "hello, world!"
    Logger.log(message.toUpperCase())
    
    // 循环
    for (var i as Integer = 0; i < 5; i++) {
        Logger.log("Count: " + i)
    }
    
    // 条件语句
    if (person.age >= 18) {
        Logger.log(person.name + " is an adult")
    } else {
        Logger.log(person.name + " is a minor")
    }
    
    // 原生命令
    /say Program completed successfully!
}
```
