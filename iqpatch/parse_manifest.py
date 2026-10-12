import re

txt = open('iq-manifest.txt', encoding='utf-8', errors='replace').read().split('\n')
MODE = {0: 'standard', 1: 'singleTop', 2: 'singleTask', 3: 'singleInstance'}

cur = None
rows = []
for line in txt:
    s = line.strip()
    if s.startswith('E: activity'):
        cur = {'name': None, 'mode': 0, 'clear': None, 'retain': None}
        rows.append(cur)
    elif cur is not None and s.startswith('A: '):
        m = re.search(r'name\(0x[0-9a-f]+\)="([^"]+)"', s)
        if m and cur['name'] is None and '.' in m.group(1):
            cur['name'] = m.group(1)
        m = re.search(r'launchMode\(0x[0-9a-f]+\)=(\d+)', s)
        if m:
            cur['mode'] = int(m.group(1))
        m = re.search(r'clearTaskOnLaunch\(0x[0-9a-f]+\)=(\w+)', s)
        if m:
            cur['clear'] = m.group(1)
        m = re.search(r'alwaysRetainTaskState\(0x[0-9a-f]+\)=(\w+)', s)
        if m:
            cur['retain'] = m.group(1)

named = [r for r in rows if r['name']]
print('activity 总数 =', len(named))
from collections import Counter
print('launchMode 分布:', Counter(MODE.get(r['mode'], r['mode']) for r in named))
print('clearTaskOnLaunch=true 的:', [r['name'] for r in named if r['clear'] == 'true'] or '无')
print('alwaysRetainTaskState=true 的:', [r['name'] for r in named if r['retain'] == 'true'] or '无')
print()
print('--- 非 standard 的 Activity（会被系统做栈重排）---')
for r in named:
    if r['mode'] != 0:
        print(' %-14s %s' % (MODE[r['mode']], r['name']))
print()
print('--- standard 且名字像二级页的（可用来做验证实验）---')
for r in named:
    if r['mode'] == 0 and re.search(r'Search|Detail|Setting|Mine|History|Album|Web|About', r['name'] or ''):
        print('  ', r['name'])
