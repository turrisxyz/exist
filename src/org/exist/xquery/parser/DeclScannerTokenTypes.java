// $ANTLR 2.7.4: "DeclScanner.g" -> "DeclScanner.java"$

	package org.exist.xquery.parser;
	
	import org.exist.xquery.XPathException;

public interface DeclScannerTokenTypes {
	int EOF = 1;
	int NULL_TREE_LOOKAHEAD = 3;
	int QNAME = 4;
	int PREDICATE = 5;
	int FLWOR = 6;
	int PARENTHESIZED = 7;
	int ABSOLUTE_SLASH = 8;
	int ABSOLUTE_DSLASH = 9;
	int WILDCARD = 10;
	int PREFIX_WILDCARD = 11;
	int FUNCTION = 12;
	int UNARY_MINUS = 13;
	int UNARY_PLUS = 14;
	int XPOINTER = 15;
	int XPOINTER_ID = 16;
	int VARIABLE_REF = 17;
	int VARIABLE_BINDING = 18;
	int ELEMENT = 19;
	int ATTRIBUTE = 20;
	int TEXT = 21;
	int VERSION_DECL = 22;
	int NAMESPACE_DECL = 23;
	int DEF_NAMESPACE_DECL = 24;
	int DEF_COLLATION_DECL = 25;
	int DEF_FUNCTION_NS_DECL = 26;
	int GLOBAL_VAR = 27;
	int FUNCTION_DECL = 28;
	int PROLOG = 29;
	int ATOMIC_TYPE = 30;
	int MODULE = 31;
	int ORDER_BY = 32;
	int POSITIONAL_VAR = 33;
	int BEFORE = 34;
	int AFTER = 35;
	int MODULE_DECL = 36;
	int ATTRIBUTE_TEST = 37;
	int COMP_ELEM_CONSTRUCTOR = 38;
	int COMP_ATTR_CONSTRUCTOR = 39;
	int COMP_TEXT_CONSTRUCTOR = 40;
	int COMP_COMMENT_CONSTRUCTOR = 41;
	int COMP_PI_CONSTRUCTOR = 42;
	int COMP_NS_CONSTRUCTOR = 43;
	int COMP_DOC_CONSTRUCTOR = 44;
	int LITERAL_xpointer = 45;
	int LPAREN = 46;
	int RPAREN = 47;
	int NCNAME = 48;
	int LITERAL_xquery = 49;
	int LITERAL_version = 50;
	int SEMICOLON = 51;
	int LITERAL_module = 52;
	int LITERAL_namespace = 53;
	int EQ = 54;
	int STRING_LITERAL = 55;
	int LITERAL_import = 56;
	int LITERAL_declare = 57;
	int LITERAL_default = 58;
	int LITERAL_xmlspace = 59;
	int LITERAL_ordering = 60;
	int LITERAL_construction = 61;
	int LITERAL_function = 62;
	int LITERAL_variable = 63;
	int LITERAL_encoding = 64;
	int LITERAL_collation = 65;
	int LITERAL_element = 66;
	int LITERAL_preserve = 67;
	int LITERAL_strip = 68;
	int LITERAL_ordered = 69;
	int LITERAL_unordered = 70;
	int DOLLAR = 71;
	int LCURLY = 72;
	int RCURLY = 73;
	int LITERAL_at = 74;
	int LITERAL_as = 75;
	int COMMA = 76;
	int LITERAL_empty = 77;
	int QUESTION = 78;
	int STAR = 79;
	int PLUS = 80;
	int LITERAL_item = 81;
	int LITERAL_for = 82;
	int LITERAL_let = 83;
	int LITERAL_some = 84;
	int LITERAL_every = 85;
	int LITERAL_if = 86;
	int LITERAL_where = 87;
	int LITERAL_return = 88;
	int LITERAL_in = 89;
	int COLON = 90;
	int LITERAL_order = 91;
	int LITERAL_by = 92;
	int LITERAL_ascending = 93;
	int LITERAL_descending = 94;
	int LITERAL_greatest = 95;
	int LITERAL_least = 96;
	int LITERAL_satisfies = 97;
	int LITERAL_typeswitch = 98;
	int LITERAL_case = 99;
	int LITERAL_then = 100;
	int LITERAL_else = 101;
	int LITERAL_or = 102;
	int LITERAL_and = 103;
	int LITERAL_instance = 104;
	int LITERAL_of = 105;
	int LITERAL_castable = 106;
	int LITERAL_cast = 107;
	int LT = 108;
	int GT = 109;
	int LITERAL_eq = 110;
	int LITERAL_ne = 111;
	int LITERAL_lt = 112;
	int LITERAL_le = 113;
	int LITERAL_gt = 114;
	int LITERAL_ge = 115;
	int NEQ = 116;
	int GTEQ = 117;
	int LTEQ = 118;
	int LITERAL_is = 119;
	int LITERAL_isnot = 120;
	int ANDEQ = 121;
	int OREQ = 122;
	int LITERAL_to = 123;
	int MINUS = 124;
	int LITERAL_div = 125;
	int LITERAL_idiv = 126;
	int LITERAL_mod = 127;
	int LITERAL_union = 128;
	int UNION = 129;
	int LITERAL_intersect = 130;
	int LITERAL_except = 131;
	int SLASH = 132;
	int DSLASH = 133;
	int LITERAL_text = 134;
	int LITERAL_node = 135;
	int LITERAL_attribute = 136;
	int LITERAL_comment = 137;
	// "processing-instruction" = 138
	// "document-node" = 139
	int LITERAL_document = 140;
	int SELF = 141;
	int XML_COMMENT = 142;
	int XML_PI = 143;
	int LPPAREN = 144;
	int RPPAREN = 145;
	int AT = 146;
	int PARENT = 147;
	int LITERAL_child = 148;
	int LITERAL_self = 149;
	int LITERAL_descendant = 150;
	// "descendant-or-self" = 151
	// "following-sibling" = 152
	int LITERAL_following = 153;
	int LITERAL_parent = 154;
	int LITERAL_ancestor = 155;
	// "ancestor-or-self" = 156
	// "preceding-sibling" = 157
	int DOUBLE_LITERAL = 158;
	int DECIMAL_LITERAL = 159;
	int INTEGER_LITERAL = 160;
	int END_TAG_START = 161;
	int QUOT = 162;
	int APOS = 163;
	int ATTRIBUTE_CONTENT = 164;
	int ELEMENT_CONTENT = 165;
	int XML_COMMENT_END = 166;
	int XML_PI_END = 167;
	int XML_CDATA = 168;
	int LITERAL_collection = 169;
	int LITERAL_preceding = 170;
	int XML_PI_START = 171;
	int XML_CDATA_START = 172;
	int XML_CDATA_END = 173;
	int LETTER = 174;
	int DIGITS = 175;
	int HEX_DIGITS = 176;
	int NMSTART = 177;
	int NMCHAR = 178;
	int WS = 179;
	int EXPR_COMMENT = 180;
	int PRAGMA = 181;
	int PRAGMA_CONTENT = 182;
	int PRAGMA_QNAME = 183;
	int PREDEFINED_ENTITY_REF = 184;
	int CHAR_REF = 185;
	int NEXT_TOKEN = 186;
	int CHAR = 187;
	int BASECHAR = 188;
	int IDEOGRAPHIC = 189;
	int COMBINING_CHAR = 190;
	int DIGIT = 191;
	int EXTENDER = 192;
}
