# SPDX-License-Identifier: GPL-2.0-only
import os
import traceback

import gdb

try:
    symbol = gdb.lookup_global_symbol('hello.DeclarationFiles$DataOnly', gdb.SYMBOL_STRUCT_DOMAIN)
    assert symbol is not None
    assert symbol.symtab is not None
    assert os.path.basename(symbol.symtab.filename) == 'DeclarationFiles.java', symbol.symtab.filename
    # This is a header-only file table, not a table borrowed from a class with code.
    assert not list(symbol.symtab.linetable())
    names = {field.name for field in symbol.type.fields()}
    assert {'value', 'other'} <= names, names
    print('Code-less type-unit declaration source and fields verified')
    gdb.execute('quit 0')
except Exception:
    traceback.print_exc()
    gdb.execute('quit 1')
