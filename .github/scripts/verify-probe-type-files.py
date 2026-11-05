import json
import sys
from pathlib import Path

from elftools.elf.elffile import ELFFile

path = Path(sys.argv[1])
source_name = sys.argv[2] if len(sys.argv) > 2 else 'DeclarationProbe.java'
result = {'input': str(path), 'units': 0, 'declarations': 0, 'typeUnitDeclarations': 0,
          'errors': [], 'dataOnlyMembers': []}
with path.open('rb') as stream:
    dwarf = ELFFile(stream).get_dwarf_info()
    for cu in dwarf.iter_CUs():
        result['units'] += 1
        top = cu.get_top_DIE()
        line = dwarf.line_program_for_CU(cu)
        for die in cu.iter_DIEs():
            attr = die.attributes.get('DW_AT_decl_file')
            if not attr:
                continue
            result['declarations'] += 1
            if cu.header.unit_type == 'DW_UT_type':
                result['typeUnitDeclarations'] += 1
            index = attr.value
            if not line or index >= len(line.header.file_entry):
                result['errors'].append({'die': die.offset, 'fileIndex': index,
                                         'unit': cu.cu_offset})
                continue
            name = line.header.file_entry[index].name.decode('utf8', 'replace')
            if name == source_name and die.tag == 'DW_TAG_member':
                member = die.attributes.get('DW_AT_name')
                if member and member.value in (b'value', b'other'):
                    result['dataOnlyMembers'].append({'member': member.value.decode(), 'file': name,
                        'unitType': cu.header.unit_type, 'die': die.offset})

assert result['declarations'] > 0
assert result['typeUnitDeclarations'] > 0
assert not result['errors'], result['errors'][:10]
assert {x['member'] for x in result['dataOnlyMembers']} == {'value', 'other'}
print(json.dumps(result, indent=2))
