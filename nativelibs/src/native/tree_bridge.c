#include "xnotes_native.h"
#include "tree_sitter/api.h"
#include <stdlib.h>

#define XNOTES_LANGUAGE(name, symbol) const TSLanguage *symbol(void);
#include "languages.inc"
#undef XNOTES_LANGUAGE

static const struct {
    const char *name;
    const TSLanguage *(*load)(void);
} languages[] = {
#define XNOTES_LANGUAGE(name, symbol) {name, symbol},
#include "languages.inc"
#undef XNOTES_LANGUAGE
};

int xn_ts_count(void) { return sizeof(languages) / sizeof(languages[0]); }

const char *xn_ts_name(int index) {
    return index >= 0 && index < xn_ts_count() ? languages[index].name : NULL;
}

char *xn_ts_parse(int index, const void *bytes, int length) {
    if (!xn_ts_name(index) || length < 0 || (!bytes && length)) return NULL;
    TSParser *parser = ts_parser_new();
    if (!parser) return NULL;
    char *expression = NULL;
    if (ts_parser_set_language(parser, languages[index].load())) {
        TSTree *tree = ts_parser_parse_string(parser, NULL, bytes ? bytes : "", length);
        if (tree) {
            expression = ts_node_string(ts_tree_root_node(tree));
            ts_tree_delete(tree);
        }
    }
    ts_parser_delete(parser);
    return expression;
}

void xn_ts_free(char *expression) { free(expression); }
