# MCFPP编译器架构

## 1. 编译器概述

MCFPP编译器是一个将MCFPP源代码转换为Minecraft命令的工具，它使用ANTLR4进行词法和语法分析，然后通过访问者模式遍历抽象语法树（AST），生成中间表示，最后生成目标代码。

## 2. 核心组件

### 2.1 LineCompiler

**位置**：`src/main/kotlin/top/mcfpp/cl/LineCompiler.kt`

**功能**：
- 命令行编译入口
- 处理单行输入和多行输入
- 维护括号匹配状态
- 调用ANTLR4解析器和访问者

**主要方法**：
- `compile(line: String)`：编译一行代码

**工作流程**：
1. 处理未匹配的括号
2. 创建ANTLR4词法分析器和语法分析器
3. 调用`mcfppParser.compilationUnit()`生成AST
4. 调用`MCFPPFieldVisitor().visit(unit)`遍历AST
5. 输出生成的命令

### 2.2 MCFPPTypeVisitor

**位置**：`src/main/kotlin/top/mcfpp/antlr/MCFPPTypeVisitor.kt`

**功能**：
- 处理类型声明
- 构建类型系统
- 处理命名空间和导入

**主要方法**：
- `visitCompilationUnit(ctx)`：遍历整个编译单元
- `visitImportDeclaration(ctx)`：处理导入声明
- `visitInterfaceDeclaration(ctx)`：处理接口声明
- `visitTemplateDeclaration(ctx)`：处理数据模板声明
- `visitObjectTemplateDeclaration(ctx)`：处理对象模板声明
- `visitEnumDeclaration(ctx)`：处理枚举声明

**工作流程**：
1. 处理命名空间声明
2. 处理导入声明
3. 遍历类型声明
4. 构建类型系统
5. 注册类型到命名空间

### 2.3 MCFPPFieldVisitor

**位置**：`src/main/kotlin/top/mcfpp/antlr/MCFPPFieldVisitor.kt`

**功能**：
- 处理函数声明
- 处理变量声明
- 处理类成员
- 构建符号表

**主要方法**：
- `visitFunctionDeclaration(ctx)`：处理函数声明
- `visitExtensionFunctionDeclaration(ctx)`：处理扩展函数声明
- `visitTemplateDeclaration(ctx)`：处理模板声明
- `visitTemplateBody(ctx)`：处理模板体
- `visitTemplateMemberDeclaration(ctx)`：处理模板成员声明

**工作流程**：
1. 遍历类型声明
2. 处理函数声明
3. 处理模板声明
4. 处理模板成员
5. 构建符号表

### 2.4 MCFPPExprVisitor

**位置**：`src/main/kotlin/top/mcfpp/antlr/MCFPPExprVisitor.kt`

**功能**：
- 处理表达式
- 生成中间代码
- 执行类型检查

**主要方法**：
- `visitExpression(ctx)`：处理表达式
- `visitCommonBinaryOperatorExpression(ctx)`：处理二元运算符表达式
- `visitConditionalOrExpression(ctx)`：处理条件或表达式
- `visitConditionalAndExpression(ctx)`：处理条件与表达式
- `visitEqualityExpression(ctx)`：处理相等表达式
- `visitRelationalExpression(ctx)`：处理关系表达式
- `visitAdditiveExpression(ctx)`：处理加法表达式
- `visitMultiplicativeExpression(ctx)`：处理乘法表达式

**工作流程**：
1. 递归处理表达式
2. 执行类型检查和转换
3. 生成中间代码
4. 返回表达式结果

## 3. 编译流程

MCFPP编译器的编译流程主要包括以下几个阶段：

### 3.1 词法分析

**输入**：MCFPP源代码
**输出**：词法单元流
**组件**：`mcfppLexer.g4`

**功能**：
- 将源代码转换为词法单元流
- 识别关键字、标识符、运算符等
- 处理注释和空白字符

### 3.2 语法分析

**输入**：词法单元流
**输出**：抽象语法树（AST）
**组件**：`mcfppParser.g4`

**功能**：
- 将词法单元流转换为抽象语法树
- 检查语法规则
- 构建程序结构

### 3.3 语义分析

**输入**：抽象语法树
**输出**：符号表、类型信息
**组件**：`MCFPPTypeVisitor`、`MCFPPFieldVisitor`

**功能**：
- 处理命名空间和导入
- 构建类型系统
- 处理函数声明和变量声明
- 构建符号表
- 执行类型检查

### 3.4 中间代码生成

**输入**：抽象语法树、符号表、类型信息
**输出**：中间表示
**组件**：`MCFPPExprVisitor`

**功能**：
- 处理表达式
- 生成中间代码
- 优化代码

### 3.5 目标代码生成

**输入**：中间表示
**输出**：Minecraft命令文件
**组件**：`Function`类及其子类

**功能**：
- 生成Minecraft命令
- 生成函数文件
- 生成数据文件
- 生成标签文件
- 构建包结构

## 4. 数据结构

### 4.1 命名空间

**类**：`Namespace`
**位置**：`src/main/java/top/mcfpp/model/Namespace.java`

**功能**：
- 组织代码，避免名称冲突
- 管理类型、函数和变量

**主要字段**：
- `name`：命名空间名称
- `scope`：命名空间的作用域

### 4.2 作用域

**接口**：`IScope`
**实现类**：`GlobalScope`、`NamespaceScope`、`FunctionScope`等

**功能**：
- 管理符号表
- 处理名称解析
- 处理作用域嵌套

**主要方法**：
- `hasVar(name)`：检查变量是否存在
- `getVar(name)`：获取变量
- `addVar(var)`：添加变量
- `hasFunction(name)`：检查函数是否存在
- `getFunction(name)`：获取函数
- `addFunction(func)`：添加函数

### 4.3 类型系统

**基类**：`MCFPPType`
**位置**：`src/main/java/top/mcfpp/type/MCFPPType.java`

**子类**：
- `MCFPPBaseType`：基本类型
- `MCFPPDataTemplateType`：数据模板类型
- `MCFPPEnumType`：枚举类型
- `MCFPPTypeAliasType`：类型别名

**功能**：
- 表示类型信息
- 处理类型转换
- 处理类型比较

### 4.4 函数

**基类**：`Function`
**位置**：`src/main/java/top/mcfpp/model/function/Function.java`

**子类**：
- `InlineFunction`：内联函数
- `NativeFunction`：原生函数
- `CompileTimeFunction`：编译时函数
- `GenericFunction`：泛型函数
- `ExtensionFunction`：扩展函数

**功能**：
- 表示函数
- 处理函数调用
- 生成函数代码

**主要字段**：
- `identifier`：函数名称
- `namespace`：命名空间
- `returnType`：返回类型
- `readOnlyParams`：只读参数
- `normalParams`：普通参数
- `commands`：命令列表

### 4.5 变量

**基类**：`Var<T>`
**位置**：`src/main/java/top/mcfpp/core/lang/Var.java`

**子类**：
- `IntVar`：整数变量
- `StringVar`：字符串变量
- `BoolVar`：布尔变量
- `SelectorVar`：选择器变量
- `ObjectVar`：对象变量

**功能**：
- 表示变量
- 处理变量赋值
- 处理变量访问

**主要字段**：
- `identifier`：变量名称
- `type`：变量类型
- `value`：变量值
- `isConst`：是否为常量
- `isStatic`：是否为静态变量

## 5. 组件协作

### 5.1 编译流程协作

1. **LineCompiler** 启动编译过程，创建ANTLR4解析器
2. **MCFPPTypeVisitor** 首先遍历AST，构建类型系统
3. **MCFPPFieldVisitor** 遍历AST，构建符号表和函数结构
4. **MCFPPExprVisitor** 处理表达式，生成中间代码
5. **Function** 类生成目标代码

### 5.2 类型检查协作

1. **MCFPPTypeVisitor** 构建类型系统
2. **MCFPPFieldVisitor** 在声明时进行类型检查
3. **MCFPPExprVisitor** 在表达式求值时进行类型检查
4. **Var** 类处理类型转换

### 5.3 代码生成协作

1. **MCFPPExprVisitor** 生成中间代码
2. **Function** 类收集命令
3. **Function** 类生成Minecraft命令文件
4. **Namespace** 类组织生成的文件

## 6. 编译优化

### 6.1 常量折叠

在编译时计算常量表达式，减少运行时计算。

**实现**：
- `CompileTimeFunction` 类在编译时执行函数
- 常量表达式直接计算结果

### 6.2 死代码消除

移除不会执行的代码。

**实现**：
- 条件语句优化
- 循环优化

### 6.3 命令合并

合并多个相似的命令，减少命令数量。

**实现**：
- 命令批量处理
- 变量复用

### 6.4 变量优化

优化变量的使用，减少变量数量。

**实现**：
- 变量生命周期分析
- 变量复用

## 7. 错误处理

### 7.1 语法错误

由ANTLR4自动检测，提供详细的错误信息。

### 7.2 语义错误

由编译器检测，包括：
- 类型不匹配
- 未定义的变量或函数
- 重复声明
- 访问权限错误

**实现**：
- `LogProcessor` 类处理错误信息
- 错误信息包含行号和列号
- 提供友好的错误提示

### 7.3 运行时错误

生成的命令在Minecraft中执行时可能出现的错误，如：
- 无效的坐标
- 无效的选择器
- 无效的NBT数据

**处理**：
- 生成调试信息
- 提供错误定位
- 优化生成的命令

## 8. 扩展性

### 8.1 添加新类型

1. 创建新的类型类，继承`MCFPPType`
2. 实现类型的基本操作
3. 在语法文件中添加类型关键字
4. 在`MCFPPType`类中注册新类型

### 8.2 添加新函数

1. 创建新的函数类，继承`Function`
2. 实现函数的执行逻辑
3. 在语法文件中添加函数声明规则
4. 在访问者类中添加函数处理逻辑

### 8.3 添加新语法特性

1. 修改`mcfppLexer.g4`添加词法规则
2. 修改`mcfppParser.g4`添加语法规则
3. 运行`gradle generateGrammarSource`生成新的解析器代码
4. 在访问者类中添加对应的处理逻辑

## 9. 性能考虑

### 9.1 编译时间

- **增量编译**：只编译修改的文件
- **缓存机制**：缓存编译结果
- **并行编译**：并行编译多个文件
- **高效算法**：使用高效的算法处理语法分析和语义分析

### 9.2 运行时性能

- **命令优化**：生成高效的命令
- **变量复用**：减少变量数量
- **惰性求值**：延迟计算，只在需要时执行
- **高效数据结构**：使用高效的数据结构存储中间结果

## 10. 测试和调试

### 10.1 单元测试

使用JUnit进行单元测试，测试文件位于`src/test/`目录下。

### 10.2 集成测试

使用项目中的示例文件进行集成测试，验证整个编译流程。

### 10.3 调试支持

- **调试模式**：支持调试模式，输出详细的调试信息
- **断点支持**：支持在IDE中设置断点调试
- **命令跟踪**：跟踪生成的命令

## 11. 未来改进

### 11.1 编译器优化

- 提高编译速度
- 优化生成的命令
- 增强错误提示

### 11.2 语言特性

- 支持更多的Minecraft特性
- 增强类型系统
- 支持更多的函数式编程特性

### 11.3 工具链

- 开发IDE插件
- 提供调试工具
- 增强文档

## 12. 总结

MCFPP编译器是一个功能强大的工具，它将MCFPP源代码转换为Minecraft命令。编译器采用了模块化的设计，使用ANTLR4进行词法和语法分析，通过访问者模式遍历AST，生成中间表示，最后生成目标代码。编译器支持多种高级特性，如面向对象编程、函数式编程、泛型、扩展函数等。通过这份文档，希望新开发者能够快速了解MCFPP编译器的架构和工作原理，为项目的发展做出贡献。