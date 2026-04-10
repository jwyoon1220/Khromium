lexer grammar HTMLLexer;

@header {
    package io.github.jwyoon1220.khromium.dom;
}

// Basic text outside tags
TEXT: ~[<]+;
OPEN_TAG: '<' -> mode(TAG);
WS: [ \t\r\n]+ -> skip;

// Inside a tag
mode TAG;
CLOSE_TAG: '>' -> mode(DEFAULT_MODE);
SELF_CLOSE_TAG: '/>' -> mode(DEFAULT_MODE);

TAG_IDENTIFIER: [a-zA-Z0-9_\-]+;
ATTR_EQ: '=';
ATTR_VALUE: '"' ~["]* '"' | '\'' ~[']* '\'';
SLASH: '/';
TAG_WS: [ \t\r\n]+ -> skip;
