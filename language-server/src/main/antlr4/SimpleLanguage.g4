grammar SimpleLanguage;

// 语法规则
compilationUnit
    : namespaceStatement? importStatement* declaration* EOF
    ;

declaration
    : statement
    | dataDeclaration
    ;

// 命名空间声明语句
namespaceStatement
    : NAMESPACE qualifiedName SEMICOLON?
    ;

// 导入声明语句 - 格式：import com.xxx:Test 或 import com.xxx:*
importStatement
    : IMPORT qualifiedName COLON (IDENTIFIER | STAR) SEMICOLON?
    ;

// 限定名称（包名或类型名）
qualifiedName
    : IDENTIFIER (DOT IDENTIFIER)*
    ;

statement
    : varStatement
    | assignmentStatement
    | ifStatement
    | whileStatement
    | doWhileStatement
    | printStatement
    | returnStatement
    | functionDeclaration
    | block
    | expression SEMICOLON? // 允许单独的表达式语句（包括函数调用）
    ;

// 返回语句
returnStatement
    : RETURN expression SEMICOLON?
    ;

// 变量声明语句 - 支持类型推断和显式类型声明
varStatement
    : VAR IDENTIFIER (AS type)? (ASSIGN expression)? SEMICOLON?
    ;

// 数据成员声明 - 类似变量声明但可省略var
memberDeclaration
    : (VAR)? IDENTIFIER (AS type)? (ASSIGN expression)? SEMICOLON?
    ;

assignmentStatement
    : IDENTIFIER ASSIGN expression SEMICOLON?
    ;

ifStatement
    : IF LPAREN expression RPAREN block (ELSE block)?
    ;

// while语句
whileStatement
    : WHILE LPAREN expression RPAREN block
    ;

// do-while语句
doWhileStatement
    : DO block WHILE LPAREN expression RPAREN SEMICOLON?
    ;

printStatement
    : PRINT LPAREN expression RPAREN SEMICOLON?
    ;

// 函数声明 - 支持有返回值和无返回值
functionDeclaration
    : FUNC IDENTIFIER LPAREN parameterList? RPAREN (ARROW type)? block SEMICOLON?
    ;

// 参数列表 - 支持参数类型声明
parameterList
    : parameter (COMMA parameter)*
    ;

// 参数定义
parameter
    : IDENTIFIER AS type
    ;

// 数据模板声明

dataDeclaration
    : DATA IDENTIFIER LBRACE (memberDeclaration | functionDeclaration)* RBRACE SEMICOLON?
    ;

block
    : LBRACE statement* RBRACE
    ;

// 类型定义 - 支持用户自定义类型
type
    : INT
    | STRING_TYPE
    | BOOL
    | IDENTIFIER // 支持用户自定义类型
    ;

expression
    : equalityExpression
    ;

equalityExpression
    : relationalExpression ((EQ | NEQ) relationalExpression)*
    ;

relationalExpression
    : additiveExpression ((LT | LTE | GT | GTE) additiveExpression)*
    ;

additiveExpression
    : multiplicativeExpression ((ADD | SUB) multiplicativeExpression)*
    ;

multiplicativeExpression
    : unaryExpression ((STAR | DIV) unaryExpression)*
    ;

unaryExpression
    : primary
    | primary DOT IDENTIFIER // 支持成员访问
    | primary DOT IDENTIFIER LPAREN argumentList? RPAREN // 支持成员函数调用
    | IDENTIFIER LPAREN argumentList? RPAREN // 支持普通函数调用
    ;

// 函数调用被合并到unaryExpression中，避免左递归

// 参数列表
argumentList
    : expression (COMMA expression)*
    ;

primary
    : IDENTIFIER
    | NUMBER
    | STRING
    | BOOL_LITERAL
    | LPAREN expression RPAREN
    ;

// 词法规则
VAR : 'var';
IF : 'if';
ELSE : 'else';
PRINT : 'print';
FUNC : 'func';
RETURN : 'return';
AS : 'as';
ARROW : '->';
DATA : 'data'; // 数据模板关键字
DOT : '.'; // 点运算符
WHILE : 'while'; // while关键字
DO : 'do'; // do关键字
NAMESPACE : 'namespace'; // namespace关键字
IMPORT : 'import'; // import关键字
STAR : '*'; // 同时用于乘法和导入星号
COLON : ':'; // 冒号，用于导入语法

// 类型关键字
INT : 'int';
STRING_TYPE : 'string';
BOOL : 'bool';

// 布尔字面量
BOOL_LITERAL : 'true' | 'false';

ASSIGN : '=';
EQ : '==';
NEQ : '!=';
LT : '<';
LTE : '<=';
GT : '>';
GTE : '>=';
COMMA : ',';

ADD : '+';
SUB : '-';
DIV : '/';

LPAREN : '(';
RPAREN : ')';
LBRACE : '{';
RBRACE : '}';

SEMICOLON : ';';

IDENTIFIER
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

NUMBER
    : [0-9]+ ('.' [0-9]+)?
    ;

STRING
    : '"' (~["])* '"'
    ;

COMMENT
    : '//' ~[\r\n]* -> skip
    ;

WS
    : [ \t\r\n]+ -> skip
    ;
