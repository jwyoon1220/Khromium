parser grammar HTMLParser;

options {
    tokenVocab = HTMLLexer;
}

@header {
    package io.github.jwyoon1220.khromium.dom;
}

document: element* EOF;

element
    : OPEN_TAG TAG_IDENTIFIER attribute* CLOSE_TAG content* OPEN_TAG SLASH TAG_IDENTIFIER CLOSE_TAG # elementWithContent
    | OPEN_TAG TAG_IDENTIFIER attribute* SELF_CLOSE_TAG                        # emptyElement
    ;

attribute
    : TAG_IDENTIFIER (ATTR_EQ ATTR_VALUE)?
    ;

content
    : element
    | TEXT
    ;
