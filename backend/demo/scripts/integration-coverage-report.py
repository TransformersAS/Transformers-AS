#!/usr/bin/env python3
"""Report every missed source line and integration test counts from a fresh Maven run."""
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
report=root/'target/site/jacoco/jacoco.xml'
r=ET.parse(report).getroot()
line=next(c for c in r.findall('counter') if c.get('type')=='LINE')
covered,missed=int(line.get('covered')),int(line.get('missed'))
print(f'LINE covered={covered} missed={missed} total={covered+missed} coverage={100*covered/(covered+missed):.6f}%')
counts=dict(tests=0,failures=0,errors=0,skipped=0)
reports=list((root/'target/surefire-integration-reports').glob('TEST-*.xml'))
for p in reports:
    suite=ET.parse(p).getroot()
    for key in counts: counts[key]+=int(suite.get(key,0))
print(f'Integration reports: {len(reports)} classes; {counts}')
expected={v.removesuffix('.java').replace('/', '.') for v in
          (root/'src/test/integration-tests.includes').read_text().splitlines() if v and not v.startswith('#')}
actual={ET.parse(p).getroot().get('name') for p in reports}
print(f'Complete allowlisted suite: {actual == expected}')
if actual - expected:
    raise SystemExit(f'Unexpected test classes: {sorted(actual - expected)}')
rows=[]
for package in r.findall('package'):
    for source in package.findall('sourcefile'):
        lines=[l.get('nr') for l in source.findall('line') if int(l.get('mi','0'))>0 and int(l.get('ci','0'))==0]
        if lines: rows.append((len(lines), package.get('name')+'/'+source.get('name'), lines))
for count,path,lines in sorted(rows,key=lambda v:(-v[0],v[1])):
    print(f'{count:3} {path}: {",".join(lines)}')
