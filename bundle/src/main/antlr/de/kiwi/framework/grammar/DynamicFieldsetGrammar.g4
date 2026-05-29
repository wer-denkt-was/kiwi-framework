grammar DynamicFieldsetGrammar;

@header {
package de.kiwi.framework.grammar;
}

// Examples:
// foo,bar,baz --> simple fields or relations with "default" fieldset
// foo,bar,baz:short --> relation "baz" with named fieldset "short"
// foo,qux:idOnly:nameOnly --> relation "qux" with "idOnly" key- and "nameOnly" value-fieldset
// foo,bar:(first,second) --> relation "bar" with dynamic fieldset including fields "first" and "second"
// bar:(a,b:(id)) --> relation "bar" with dynamic fieldset incl. fields "a" and "b", where "b" has a dynamic fieldset incl. field "id"
// !foo -> single field "foo"
// bar:(!a) --> relation "bar" with single field "a"
// bar:!a --> relation "bar" with single field "a", short syntax

root
    : fieldset EOF
    ;

fieldset
    : field (COMMA+ field)* COMMA* #entityFieldset
    | BANG field #singleColumnFieldset
    ;

field
    : name=NAME #simpleField
    | name=NAME COLON (ref=reference) #refField
    | name=NAME COLON (key=reference) COLON (val=reference) #keyValField
    ;

reference
    : name=NAME #nameRef
    | OPEN fieldset? CLOSE #dynamicRef
    | BANG field #singleColumnRef
    | #emptyRef
    ;

BANG : '!';
COMMA : ',';
COLON : ':';
OPEN : '(';
CLOSE : ')';
NAME : NameStart NamePart*;
WS: [ \r\n\t]+ -> skip;

fragment NameStart : [a-zA-Z_];
fragment NamePart : NameStart | [0-9];
