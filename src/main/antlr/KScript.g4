grammar KScript;

@header {
    package io.github.jwyoon1220.khromium.js;
}

program: statement* EOF;

statement
    : varDecl
    | funcDecl
    | ifStmt
    | whileStmt
    | returnStmt
    | exprStmt
    | block
    ;

block: LBRACE statement* RBRACE;

varDecl: VAR IDENTIFIER (ASSIGN expression)? SEMI;

funcDecl: FUNCTION IDENTIFIER LPAREN (IDENTIFIER (COMMA IDENTIFIER)*)? RPAREN block;

ifStmt: IF LPAREN expression RPAREN statement (ELSE statement)?;

whileStmt: WHILE LPAREN expression RPAREN statement;

returnStmt: RETURN expression? SEMI;

exprStmt: expression SEMI?;

expression
    : expression (MULT | DIV) expression #mulDivExpr
    | expression (PLUS | MINUS) expression #addSubExpr
    | expression (LT | GT | LTE | GTE) expression #relationalExpr
    | expression (EQ | NEQ) expression #equalityExpr
    | IDENTIFIER ASSIGN expression #assignExpr
    | expression DOT IDENTIFIER ASSIGN expression #propAssignExpr
    | expression DOT IDENTIFIER (LPAREN arguments? RPAREN)? #memberDotExpr
    | IDENTIFIER LPAREN arguments? RPAREN #callExpr
    | NEW IDENTIFIER LPAREN arguments? RPAREN #newExpr
    | primary #primaryExpr
    ;

arguments: expression (COMMA expression)*;

primary
    : NUMBER
    | STRING
    | TRUE
    | FALSE
    | NULL
    | IDENTIFIER
    | LPAREN expression RPAREN
    ;

// Lexer Rules
VAR: 'var' | 'let' | 'const';
IF: 'if';
ELSE: 'else';
WHILE: 'while';
FUNCTION: 'function';
RETURN: 'return';
TRUE: 'true';
FALSE: 'false';
NULL: 'null';
NEW: 'new';

LPAREN: '(';
RPAREN: ')';
LBRACE: '{';
RBRACE: '}';
LBRACK: '[';
RBRACK: ']';
SEMI: ';';
COMMA: ',';
DOT: '.';
ASSIGN: '=';

EQ: '==' | '===';
NEQ: '!=' | '!==';
LT: '<';
GT: '>';
LTE: '<=';
GTE: '>=';

PLUS: '+';
MINUS: '-';
MULT: '*';
DIV: '/';

NUMBER: [0-9]+ ('.' [0-9]+)?;
STRING: '"' ~["]* '"' | '\'' ~[']* '\'';
IDENTIFIER: [a-zA-Z_$] [a-zA-Z0-9_$]*;

WS: [ \t\r\n]+ -> skip;
COMMENT: '//' ~[\r\n]* -> skip;
MULTILINE_COMMENT: '/*' .*? '*/' -> skip;
