"""Check DWARF specification/origin reference chains without debugger recursion."""
import argparse
from array import array
from bisect import bisect_left
import hashlib
import io
import json
import mmap
from pathlib import Path
import struct
import sys
from elftools.dwarf.dwarfinfo import DWARFInfo, DwarfConfig, DebugSectionDescriptor


class Section(io.RawIOBase):
    def __init__(self, data, base, size):
        self.data, self.base, self.size, self.position = data, base, size, 0

    def seek(self, offset, whence=0):
        position = offset if whence == 0 else self.position + offset if whence == 1 else self.size + offset
        if position < 0:
            raise ValueError('Negative section seek')
        self.position = position
        return position

    def tell(self):
        return self.position

    def read(self, size=-1):
        size = max(0, self.size - self.position) if size < 0 else min(size, max(0, self.size - self.position))
        value = self.data[self.base + self.position:self.base + self.position + size]
        self.position += size
        return value


def check(path):
    with path.open('rb') as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        assert struct.unpack_from('<I', data)[0] == 0xfeedfacf, 'Expected thin little-endian Mach-O64'
        cpu = struct.unpack_from('<I', data, 4)[0]
        assert cpu in {0x1000007, 0x100000c}, 'Expected x86-64 or AArch64 Mach-O'
        machine = 'AArch64' if cpu == 0x100000c else 'x64'
        sections = {}
        cursor = 32
        for _ in range(struct.unpack_from('<I', data, 16)[0]):
            command, size = struct.unpack_from('<II', data, cursor)
            assert size >= 8 and cursor + size <= len(data)
            if command == 0x19:
                for i in range(struct.unpack_from('<I', data, cursor + 64)[0]):
                    offset = cursor + 72 + i * 80
                    assert offset + 80 <= cursor + size
                    name = data[offset:offset + 16].split(b'\0')[0].decode()
                    address, length, file_offset = struct.unpack_from('<QQI', data, offset + 32)
                    if name.startswith('__debug_'):
                        assert file_offset + length <= len(data)
                        logical = '.debug_str_offsets' if name == '__debug_str_offs' else '.' + name[2:]
                        sections[logical] = DebugSectionDescriptor(Section(data, file_offset, length), logical, file_offset, length, address)
            cursor += size
        from inspect import signature
        kwargs = {name: sections.get('.' + name.removesuffix('_sec'))
                  for name in signature(DWARFInfo).parameters if name != 'config'}
        dwarf = DWARFInfo(config=DwarfConfig(little_endian=True, machine_arch=machine, default_address_size=8), **kwargs)
        assert dwarf.debug_info_sec is not None
        offsets, targets, second_targets = array('Q'), array('Q'), array('Q')
        absent = (1 << 64) - 1
        die_count = unit_count = 0
        for unit in dwarf.iter_CUs():
            unit_count += 1
            # pyelftools 0.32 translates indexed strings but omits the variable
            # width form and incorrectly sizes strx4. dsymutil uses these forms.
            unit.structs.Dwarf_dw_form['DW_FORM_strx'] = unit.structs.the_Dwarf_uleb128
            unit.structs.Dwarf_dw_form['DW_FORM_strx4'] = unit.structs.the_Dwarf_uint32
            for die in unit.iter_DIEs():
                die_count += 1
                references = []
                for key in ['DW_AT_specification', 'DW_AT_abstract_origin']:
                    attr = die.attributes.get(key)
                    if attr is None:
                        continue
                    if attr.form == 'DW_FORM_ref_addr':
                        target = attr.value
                    elif attr.form in {'DW_FORM_ref1', 'DW_FORM_ref2', 'DW_FORM_ref4', 'DW_FORM_ref8', 'DW_FORM_ref_udata'}:
                        target = unit.cu_offset + attr.value
                    else:
                        raise ValueError('Unsupported reference form: ' + attr.form)
                    references.append((key, target))
                if references:
                    assert not offsets or die.offset > offsets[-1], 'Expected ordered DIE offsets'
                    offsets.append(die.offset)
                    targets.append(references[0][1])
                    second_targets.append(references[1][1] if len(references) > 1 else absent)
            # Only the graph, not all decoded DIE objects, must survive each CU.
            unit._dielist.clear()
            unit._diemap.clear()
        colors, cycles = bytearray(len(offsets)), []
        cycle_count = maximum_depth = 0
        for origin in range(len(offsets)):
            if colors[origin]:
                continue
            path_nodes, indices, stack = [], {}, [(origin, False)]
            while stack:
                node, exiting = stack.pop()
                if exiting:
                    colors[node] = 2
                    indices.pop(node)
                    path_nodes.pop()
                    continue
                if colors[node] == 1:
                    cycle_count += 1
                    if len(cycles) < 20:
                        cycle = path_nodes[indices[node]:] + [node]
                        cycles.append(cycle)
                    continue
                if colors[node] == 2:
                    continue
                colors[node] = 1
                indices[node] = len(path_nodes)
                path_nodes.append(node)
                maximum_depth = max(maximum_depth, len(path_nodes))
                stack.append((node, True))
                for target in [second_targets[node], targets[node]]:
                    if target == absent:
                        continue
                    index = bisect_left(offsets, target)
                    if index < len(offsets) and offsets[index] == target:
                        stack.append((index, False))
        cycle_details = []
        for cycle in cycles:
            entries = []
            for node in cycle:
                offset = offsets[node]
                die = dwarf.get_DIE_from_refaddr(offset)
                name = die.attributes.get('DW_AT_name')
                entries.append({'offset': hex(offset), 'cuOffset': hex(die.cu.cu_offset), 'tag': die.tag,
                    'name': repr(name.value)[:240] if name else None,
                    'references': [(key, hex(attr.value if attr.form == 'DW_FORM_ref_addr' else die.cu.cu_offset + attr.value))
                                   for key in ['DW_AT_specification', 'DW_AT_abstract_origin']
                                   if (attr := die.attributes.get(key)) is not None]})
            cycle_details.append(entries)
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        return {'debugSha256': digest, 'units': unit_count, 'dies': die_count, 'referenceNodes': len(offsets),
                'maximumTraversalDepth': maximum_depth, 'cycleCount': cycle_count, 'cycles': cycle_details}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('debug_file', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = check(args.debug_file)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({key: value for key, value in result.items() if key != 'cycles'}, indent=2))
    sys.exit(1 if result['cycleCount'] else 0)
