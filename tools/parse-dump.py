#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Parse the whitelist dump produced by the P1 LSPosed module."""
import re
import sys
import collections
import json

path = sys.argv[1] if len(sys.argv) > 1 else 'wl-dump.log'
raw = open(path, 'rb').read()
# The module wrote with the JVM default charset; be permissive.
for enc in ('utf-8', 'gbk', 'latin-1'):
    try:
        text = raw.decode(enc)
        used = enc
        break
    except Exception:
        continue
else:
    text = raw.decode('utf-8', 'replace')
    used = 'utf-8/replace'

lines = text.splitlines()
print('decoded with %s, %d lines' % (used, len(lines)))

# ---------------------------------------------------------------- find entries
# A device entry looks like:  getXxx=..., getName="...", getFunction=Function{...}
print()
print('=' * 70)
print('1. 出现的字段名统计（getter 名 -> 次数）')
print('=' * 70)
field_counter = collections.Counter(re.findall(r'\b(get[A-Z][A-Za-z0-9]*)=', text))
for name, n in field_counter.most_common(60):
    print('  %-40s %d' % (name, n))

# ---------------------------------------------------------------- device names
print()
print('=' * 70)
print('2. 所有设备名（getName="..."）')
print('=' * 70)
names = re.findall(r'getName="([^"]*)"', text)
uniq = []
seen = set()
for n in names:
    if n not in seen:
        seen.add(n)
        uniq.append(n)
print('总出现 %d 次，去重 %d 个:' % (len(names), len(uniq)))
for n in uniq:
    print('   ', n)

# ---------------------------------------------------------------- one full DTO
print()
print('=' * 70)
print('3. 找一条完整的 WhitelistConfigDTO 记录（含 Function 的）')
print('=' * 70)
cand = None
for ln in lines:
    if 'WhitelistConfigDTO{' in ln and 'getFunction=' in ln and 'getName=' in ln:
        cand = ln
        break
if cand is None:
    for ln in lines:
        if 'WhitelistConfigDTO{' in ln and 'getName=' in ln and len(ln) > 400:
            cand = ln
            break
if cand:
    print('长度: %d 字符' % len(cand))
    print()
    # pretty split on ", get" boundaries
    parts = re.split(r', (?=get[A-Z])', cand)
    for p in parts:
        print('   ', p[:600])
else:
    print('未找到完整记录')

# ---------------------------------------------------------------- function bits
print()
print('=' * 70)
print('4. Function 里出现过的能力字段（>0 或 true 才有意义，这里列全部唯一名）')
print('=' * 70)
fn_block = None
m = re.search(r'getFunction=Function\{(.*?)\}\s*$', text, re.S | re.M)
if m:
    fn_block = m.group(1)
    names_fn = sorted(set(re.findall(r'\b(get[A-Z][A-Za-z0-9]*)=', fn_block)))
    print('Function 字段数: %d' % len(names_fn))
    for n in names_fn:
        print('   ', n)
else:
    # fall back: look at the largest Function{...} occurrence
    idx = text.find('getFunction=Function{')
    if idx >= 0:
        seg = text[idx:idx + 20000]
        names_fn = sorted(set(re.findall(r'\b(get[A-Z][A-Za-z0-9]*)=', seg)))
        print('（截断样本）Function 字段数: %d' % len(names_fn))
        for n in names_fn:
            print('   ', n)
    else:
        print('未找到 Function 块')
