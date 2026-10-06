#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Minimal DEX parser: extract class names, method names, and strings from APK.
No external deps. Used to defeat R8 obfuscation when picking hook anchors.
"""
import io
import json
import os
import struct
import sys
import zipfile

NO_INDEX = 0xFFFFFFFF

TYPE_DESCRIPTOR = 0x0000
TYPE_STRING = 0x0001
TYPE_METHOD = 0x0002
TYPE_FIELD = 0x0003
TYPE_TYPE = 0x0004

KIND_NAMES = {0: "header", 1: "string", 2: "type", 3: "proto", 4: "field",
              5: "method", 6: "class", 7: "map", 8: "type_list", 9: "annotation_set",
              10: "annotation_set_ref", 11: "annotation", 12: "class_data",
              13: "code", 14: "string_data", 15: "debug_info", 16: "annotation",
              17: "encoded_array", 18: "annotations_directory"}


def read_uleb128(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


def read_uleb128p1(data, off):
    v, off = read_uleb128(data, off)
    return v - 1, off


def decode_mutf8(raw):
    """Decode DEX MUTF-8 (CESU-8 style) to a Python str."""
    out = []
    i = 0
    n = len(raw)
    while i < n:
        b = raw[i]
        if b == 0:
            break
        if b < 0x80:
            out.append(chr(b))
            i += 1
        elif (b & 0xE0) == 0xC0 and i + 1 < n:
            out.append(chr(((b & 0x1F) << 6) | (raw[i + 1] & 0x3F)))
            i += 2
        elif (b & 0xF0) == 0xE0 and i + 2 < n:
            cp = ((b & 0x0F) << 12) | ((raw[i + 1] & 0x3F) << 6) | (raw[i + 2] & 0x3F)
            out.append(chr(cp))
            i += 3
        else:
            out.append('?')
            i += 1
    return ''.join(out)


class Dex:
    def __init__(self, data, label):
        self.data = data
        self.label = label
        if data[:4] != b'dex\n':
            raise ValueError('not a dex: %r' % data[:4])
        self.string_ids_size, self.string_ids_off = struct.unpack_from('<II', data, 56)
        self.type_ids_size, self.type_ids_off = struct.unpack_from('<II', data, 64)
        self.proto_ids_size, self.proto_ids_off = struct.unpack_from('<II', data, 72)
        self.field_ids_size, self.field_ids_off = struct.unpack_from('<II', data, 80)
        self.method_ids_size, self.method_ids_off = struct.unpack_from('<II', data, 88)
        self.class_defs_size, self.class_defs_off = struct.unpack_from('<II', data, 96)
        self._strings = None
        self._types = None

    # ---------- string / type tables ----------
    def _load_strings(self):
        if self._strings is not None:
            return
        d = self.data
        out = []
        for i in range(self.string_ids_size):
            off = struct.unpack_from('<I', d, self.string_ids_off + i * 4)[0]
            _, p = read_uleb128(d, off)      # utf16 size, ignore
            end = d.index(b'\x00', p)
            out.append(decode_mutf8(d[p:end]))
        self._strings = out

    @property
    def strings(self):
        self._load_strings()
        return self._strings

    def _load_types(self):
        if self._types is not None:
            return
        s = self.strings
        d = self.data
        out = []
        for i in range(self.type_ids_size):
            idx = struct.unpack_from('<I', d, self.type_ids_off + i * 4)[0]
            out.append(s[idx] if idx < len(s) else '?')
        self._types = out

    @property
    def types(self):
        self._load_types()
        return self._types

    def method_name(self, idx):
        d = self.data
        name_idx = struct.unpack_from('<I', d, self.method_ids_off + idx * 8 + 4)[0]
        return self.strings[name_idx]

    def method_class(self, idx):
        d = self.data
        class_idx = struct.unpack_from('<H', d, self.method_ids_off + idx * 8)[0]
        return self.types[class_idx]

    def field_name(self, idx):
        d = self.data
        name_idx = struct.unpack_from('<I', d, self.field_ids_off + idx * 8 + 4)[0]
        return self.strings[name_idx]

    def field_class(self, idx):
        d = self.data
        class_idx = struct.unpack_from('<H', d, self.field_ids_off + idx * 8)[0]
        return self.types[class_idx]

    # ---------- map + class defs ----------
    def read_map(self):
        """Return list of (type_code, size, offset)."""
        d = self.data
        map_off = struct.unpack_from('<I', d, 52)[0]
        size = struct.unpack_from('<I', d, map_off)[0]
        items = []
        p = map_off + 4
        for _ in range(size):
            type_code, unused, sz, off = struct.unpack_from('<HHII', d, p)
            items.append((type_code, sz, off))
            p += 12
        return items

    def read_class_defs(self):
        d = self.data
        out = []
        for i in range(self.class_defs_size):
            base = self.class_defs_off + i * 32
            class_idx, access, super_idx, interfaces_off, source_idx, \
                annotations_off, class_data_off, static_values_off = struct.unpack_from('<IIIIIIII', d, base)
            out.append({
                'class_idx': class_idx,
                'access': access,
                'super_idx': super_idx,
                'interfaces_off': interfaces_off,
                'source_idx': source_idx,
                'class_data_off': class_data_off,
                'static_values_off': static_values_off,
            })
        return out

    def parse_class_data(self, off):
        """Return (direct_methods[], virtual_methods[], fields[]) as raw idx tuples."""
        d = self.data
        p = off
        static_fields_size, p = read_uleb128(d, p)
        instance_fields_size, p = read_uleb128(d, p)
        direct_methods_size, p = read_uleb128(d, p)
        virtual_methods_size, p = read_uleb128(d, p)

        fields = []
        for _ in range(static_fields_size + instance_fields_size):
            _, p = read_uleb128(d, p)   # field_idx_diff
            _, p = read_uleb128(d, p)   # access_flags
            fields.append(1)

        methods = []
        for kind_size in (direct_methods_size, virtual_methods_size):
            method_idx = 0
            for _ in range(kind_size):
                diff, p = read_uleb128(d, p)
                method_idx += diff
                access, p = read_uleb128(d, p)
                code_off, p = read_uleb128(d, p)
                methods.append((method_idx, access, code_off))
        return methods


def looks_like_dex(name):
    base = os.path.basename(name)
    return base.endswith('.dex')


def iter_dex_blobs(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        for info in z.infolist():
            if looks_like_dex(info.filename):
                yield info.filename, z.read(info)
        # also handle nested apk (e.g. assets/*.apk) minimally
        for info in z.infolist():
            if info.filename.endswith('.apk'):
                try:
                    inner = io.BytesIO(z.read(info))
                    with zipfile.ZipFile(inner) as z2:
                        for i2 in z2.infolist():
                            if looks_like_dex(i2.filename):
                                yield info.filename + '!' + i2.filename, z2.read(i2)
                except Exception:
                    pass


def _safe(s):
    """Strip lone surrogates (legal in MUTF-8, illegal in UTF-8)."""
    return s.encode('utf-8', 'replace').decode('utf-8', 'replace')


def main():
    if len(sys.argv) < 3:
        print('usage: parse_apk.py <apk> <outdir>')
        sys.exit(2)
    apk_path, outdir = sys.argv[1], sys.argv[2]
    os.makedirs(outdir, exist_ok=True)

    all_classes = []
    all_methods = []      # (class, method)
    all_strings = set()
    per_dex = []

    for label, blob in iter_dex_blobs(apk_path):
        try:
            dex = Dex(blob, label)
        except Exception as e:
            print('skip %s: %s' % (label, e))
            continue

        classes = []
        methods = []
        for cd in dex.read_class_defs():
            cname = dex.types[cd['class_idx']]
            classes.append(cname)
            if cd['class_data_off'] == 0:
                continue
            try:
                ms = dex.parse_class_data(cd['class_data_off'])
            except Exception:
                continue
            for (midx, access, code_off) in ms:
                try:
                    cls = dex.method_class(midx)
                    name = dex.method_name(midx)
                except Exception:
                    continue
                if cls == cname:
                    methods.append((cname, name))

        all_classes.extend(classes)
        all_methods.extend(methods)
        all_strings.update(dex.strings)
        per_dex.append({
            'dex': label,
            'classes': len(classes),
            'methods': len(methods),
            'strings': dex.string_ids_size,
        })
        print('%-16s classes=%-7d methods=%-7d strings=%d' % (label, len(classes), len(methods), dex.string_ids_size))

    # dedupe preserving order
    seen = set()
    classes_u = []
    for c in all_classes:
        if c not in seen:
            seen.add(c)
            classes_u.append(c)

    with open(os.path.join(outdir, 'classes.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(classes_u))

    with open(os.path.join(outdir, 'methods.txt'), 'w', encoding='utf-8') as f:
        for c, m in all_methods:
            f.write('%s\t%s\n' % (c, m))

    with open(os.path.join(outdir, 'strings.txt'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(_safe(s) for s in sorted(all_strings)))

    with open(os.path.join(outdir, 'summary.json'), 'w', encoding='utf-8') as f:
        json.dump({'apk': apk_path, 'perDex': per_dex,
                   'uniqueClasses': len(classes_u),
                   'uniqueStrings': len(all_strings)}, f, indent=2)

    print('---')
    print('unique classes : %d' % len(classes_u))
    print('unique strings : %d' % len(all_strings))
    print('outdir         : %s' % outdir)


if __name__ == '__main__':
    main()
