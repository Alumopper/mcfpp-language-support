# MCFPP编译器开发指南

## 1. 环境搭建

### 1.1 系统要求

- Java 11+ 
- Gradle 7.0+（项目包含Gradle wrapper）
- IntelliJ IDEA或Eclipse（推荐IntelliJ IDEA）

### 1.2 项目导入

1. **克隆项目到本地**
   ```bash
   git clone https://github.com/your-repo/mcfpp.git
   ```

2. **使用IntelliJ IDEA打开项目**
   - 启动IntelliJ IDEA
   - 选择"Open"，导航到项目根目录
   - 选择`build.gradle.kts`文件
   - 点击"Open as Project"

3. **等待Gradle同步完成**
   - IntelliJ IDEA会自动检测并配置Gradle
   - 同步过程可能需要几分钟，取决于网络速度

4. **构建项目**
   ```bash
   ./gradlew build
   ```

### 1.3 开发环境配置

#### 1.3.1 ANTLR4插件

安装IntelliJ IDEA的ANTLR4插件，方便查看和编辑语法文件：

1. 打开IntelliJ IDEA的插件管理器（File > Settings > Plugins）
2. 搜索"ANTLR v4"
3. 点击"Install"安装插件
4. 重启IntelliJ IDEA

#### 1.3.2 Kotlin插件

确保安装了Kotlin插件：

1. 打开IntelliJ IDEA的插件管理器（File > Settings > Plugins）
2. 搜索"Kotlin"
3. 确保插件已安装并启用
4. 重启IntelliJ IDEA（如果需要）

#### 1.3.3 Java SDK

配置Java 11+ SDK：

1. 打开IntelliJ IDEA的项目结构（File > Project Structure）
2. 选择"Project"
3. 在"Project SDK"下拉菜单中选择Java 11+ SDK
4. 如果没有可用的SDK，点击"New..."添加一个
5. 点击"OK"保存设置

## 2. 项目结构

### 2.1 主要目录

| 目录 | 描述 |
|------|------|
| `src/main/antlr/` | ANTLR4语法文件 |
| `src/main/java/` | Java源代码 |
| `src/main/kotlin/` | Kotlin源代码 |
| `src/main/mcfpp/` | MCFPP示例文件 |
| `src/main/resources/` | 资源文件 |
| `src/test/` | 测试代码 |
| `docs/` | 文档文件 |

### 2.2 包结构

#### 2.2.1 Java包

- `top.mcfpp.antlr`：ANTLR4生成的解析器代码
- `top.mcfpp.annotations`：注解定义
- `top.mcfpp.compiletime`：编译时函数处理
- `top.mcfpp.core.lang`：核心语言元素
- `top.mcfpp.exception`：异常定义
- `top.mcfpp.io`：文件I/O操作
- `top.mcfpp.model`：模型定义，包括函数、类、命名空间等
- `top.mcfpp.type`：类型系统
- `top.mcfpp.util`：工具类

#### 2.2.2 Kotlin包

- `top.mcfpp.antlr`：ANTLR4访问者实现
- `top.mcfpp.cl`：命令行工具

## 3. 开发流程

### 3.1 添加新语法特性

1. **修改词法规则**（`mcfppLexer.g4`）
   - 添加新的关键字、运算符或常量
   - 确保词法规则不会冲突

2. **修改语法规则**（`mcfppParser.g4`）
   - 添加新的语法规则
   - 确保语法规则符合LL(*)语法

3. **生成新的解析器代码**
   ```bash
   ./gradlew generateGrammarSource
   ```

4. **实现访问者方法**
   - 在`MCFPPTypeVisitor`、`MCFPPFieldVisitor`或`MCFPPExprVisitor`中添加对应的访问方法
   - 实现语义分析和代码生成逻辑

5. **编写测试用例**
   - 在`src/test/`目录下添加测试用例
   - 测试新语法特性的正确性

6. **运行测试**
   ```bash
   ./gradlew test
   ```

### 3.2 添加新类型

1. **创建类型类**
   - 继承`MCFPPType`类
   - 实现类型的基本操作：`canCastTo`、`canExplicitCast`、`cast`等

2. **注册类型**
   - 在`MCFPPType`类中添加类型注册
   - 在语法文件中添加类型关键字

3. **实现类型转换**
   - 在`Var`类及其子类中添加类型转换逻辑
   - 确保类型转换的正确性和安全性

4. **编写测试用例**
   - 测试新类型的基本操作
   - 测试类型转换

### 3.3 添加新函数

1. **创建函数类**
   - 继承`Function`类
   - 实现函数的执行逻辑

2. **注册函数**
   - 在访问者类中添加函数声明和调用的处理
   - 确保函数可以正确解析和执行

3. **实现函数调用**
   - 在`MCFPPExprVisitor`中添加函数调用的处理
   - 处理参数传递和返回值

4. **编写测试用例**
   - 测试函数的声明和调用
   - 测试函数参数和返回值

## 4. 代码规范

### 4.1 Java代码规范

- **命名约定**
  - 类名：PascalCase
  - 方法名：camelCase
  - 变量名：camelCase
  - 常量名：UPPER_CASE_WITH_UNDERSCORES

- **缩进**：4个空格
- **换行**：每行不超过120个字符
- **注释**：使用Javadoc注释

### 4.2 Kotlin代码规范

- **命名约定**
  - 类名：PascalCase
  - 方法名：camelCase
  - 变量名：camelCase
  - 常量名：UPPER_CASE_WITH_UNDERSCORES

- **缩进**：4个空格
- **换行**：每行不超过120个字符
- **注释**：使用KDoc注释

### 4.3 ANTLR4语法文件规范

- **命名约定**
  - 词法规则：UPPER_CASE
  - 语法规则：lowerCase
  - 文件名：小写字母，以`.g4`结尾

- **缩进**：2个空格
- **换行**：每行不超过120个字符
- **注释**：使用`//`或`/* */`注释

## 5. 测试和调试

### 5.1 单元测试

项目使用JUnit进行单元测试，测试文件位于`src/test/`目录下。

**运行所有测试**：
```bash
./gradlew test
```

**运行特定测试类**：
```bash
./gradlew test --tests "top.mcfpp.test.MyTestClass"
```

**运行特定测试方法**：
```bash
./gradlew test --tests "top.mcfpp.test.MyTestClass.myTestMethod"
```

### 5.2 集成测试

使用项目中的示例文件进行集成测试：

```bash
./gradlew run --args="example.mcfpp"
```

### 5.3 调试

#### 5.3.1 在IntelliJ IDEA中调试

1. **设置断点**
   - 在代码行左侧单击，设置断点
   - 断点会显示为红色圆点

2. **运行调试**
   - 打开`LineCompiler`类
   - 点击主方法左侧的绿色箭头
   - 选择"Debug 'LineCompiler.main()'"

3. **调试控制台**
   - 调试控制台会显示变量值、调用栈和输出
   - 使用"Step Over"、"Step Into"、"Step Out"等按钮控制执行流程
   - 使用"Resume Program"按钮继续执行直到下一个断点

#### 5.3.2 命令行调试

使用`--debug`选项启用调试模式：

```bash
./gradlew run --args="example.mcfpp --debug"
```

## 6. 构建和发布

### 6.1 构建项目

**构建项目**：
```bash
./gradlew build
```

**构建并运行测试**：
```bash
./gradlew build test
```

**构建发布版本**：
```bash
./gradlew assembleRelease
```

### 6.2 生成文档

**生成JavaDoc**：
```bash
./gradlew javadoc
```

**生成KDoc**：
```bash
./gradlew dokkaHtml
```

### 6.3 发布到Maven仓库

1. **配置Maven仓库信息**
   - 修改`build.gradle.kts`中的发布配置
   - 添加Maven仓库的URL、用户名和密码

2. **发布项目**
   ```bash
   ./gradlew publish
   ```

## 7. 常见问题

### 7.1 Gradle同步失败

**问题**：IntelliJ IDEA无法同步Gradle项目。

**解决方案**：
1. 检查网络连接
2. 确保Java SDK版本正确
3. 清除Gradle缓存
   ```bash
   ./gradlew cleanBuildCache
   ```
4. 重新同步项目

### 7.2 ANTLR4生成的代码有错误

**问题**：修改语法文件后，生成的解析器代码有错误。

**解决方案**：
1. 检查语法文件中的语法错误
2. 确保词法规则和语法规则不会冲突
3. 重新生成解析器代码
   ```bash
   ./gradlew generateGrammarSource
   ```

### 7.3 测试失败

**问题**：运行测试时失败。

**解决方案**：
1. 查看测试失败信息
2. 检查代码中的错误
3. 修复错误后重新运行测试
4. 如果测试是因为环境问题失败，检查测试环境配置

## 8. 代码贡献

### 8.1 分支管理

- **main**：主分支，用于发布稳定版本
- **develop**：开发分支，用于集成新功能
- **feature/xxx**：功能分支，用于开发新功能
- **bugfix/xxx**：修复分支，用于修复bug

### 8.2 提交规范

- **提交信息**：清晰、简洁，描述更改的内容和原因
- **提交粒度**：每个提交只包含一个功能或修复
- **测试**：确保所有测试通过后再提交

### 8.3 Pull Request流程

1. **创建分支**
   ```bash
   git checkout -b feature/my-new-feature
   ```

2. **开发功能**
   - 编写代码
   - 编写测试用例
   - 确保所有测试通过

3. **提交代码**
   ```bash
   git add .
   git commit -m "Add my new feature"
   git push origin feature/my-new-feature
   ```

4. **创建Pull Request**
   - 访问GitHub仓库
   - 点击"Pull Requests"
   - 点击"New Pull Request"
   - 选择源分支和目标分支
   - 填写Pull Request描述
   - 点击"Create Pull Request"

5. **代码审查**
   - 等待其他开发者审查代码
   - 根据审查意见进行修改
   - 重新提交修改

6. **合并Pull Request**
   - 审查通过后，合并Pull Request到目标分支
   - 删除功能分支

## 9. 学习资源

### 9.1 ANTLR4

- [ANTLR4官方文档](https://www.antlr.org/documentation.html)
- [The Definitive ANTLR 4 Reference](https://pragprog.com/titles/tpantlr2/the-definitive-antlr-4-reference/)
- [ANTLR4教程](https://github.com/antlr/antlr4/blob/master/doc/tutorials.md)

### 9.2 Kotlin

- [Kotlin官方文档](https://kotlinlang.org/docs/home.html)
- [Kotlin编程实战](https://www.manning.com/books/kotlin-in-action)
- [Kotlin教程](https://kotlinlang.org/docs/tutorials.html)

### 9.3 编译器设计

- [Compilers: Principles, Techniques, and Tools](https://www.pearson.com/store/p/compilers-principles-techniques-and-tools-third-edition/P100000000237/9780134747749)
- [Modern Compiler Implementation in Java](https://www.cambridge.org/us/academic/subjects/computer-science/software-design-and-implementation/modern-compiler-implementation-java-2nd-edition)
- [编译器设计](https://book.douban.com/subject/26370657/)

## 10. 联系方式

- **项目主页**：https://github.com/your-repo/mcfpp
- **问题反馈**：https://github.com/your-repo/mcfpp/issues
- **讨论区**：https://github.com/your-repo/mcfpp/discussions
- **邮件列表**：mcfpp-dev@your-domain.com

## 11. 结语

MCFPP编译器是一个功能强大的工具，它为Minecraft命令开发带来了现代化的编程体验。通过本开发指南，希望新开发者能够快速上手MCFPP编译器的工作