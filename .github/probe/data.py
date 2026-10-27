# Internal helper: prints the worldgen data the mod depends on (structure sets, structures, start pools)
# and a block histogram of every start template, so the detectors can be written against real 26.2 data.
import gzip, json, os, struct, sys, glob, collections

ROOT = sys.argv[1]
D = os.path.join(ROOT, 'data/minecraft')

def read(f):
    t = f.read(1)[0]
    if t == 0:
        return None, None
    n = struct.unpack('>H', f.read(2))[0]
    name = f.read(n).decode()
    return name, payload(f, t)

def payload(f, t):
    if t == 1: return struct.unpack('>b', f.read(1))[0]
    if t == 2: return struct.unpack('>h', f.read(2))[0]
    if t == 3: return struct.unpack('>i', f.read(4))[0]
    if t == 4: return struct.unpack('>q', f.read(8))[0]
    if t == 5: return struct.unpack('>f', f.read(4))[0]
    if t == 6: return struct.unpack('>d', f.read(8))[0]
    if t == 7: n = struct.unpack('>i', f.read(4))[0]; return f.read(n)
    if t == 8: n = struct.unpack('>H', f.read(2))[0]; return f.read(n).decode('utf-8', 'replace')
    if t == 9:
        et = f.read(1)[0]; n = struct.unpack('>i', f.read(4))[0]
        return [payload(f, et) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            k, v = read(f)
            if k is None: return d
            d[k] = v
    if t == 11: n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack('>%di' % n, f.read(4 * n)))
    if t == 12: n = struct.unpack('>i', f.read(4))[0]; return list(struct.unpack('>%dq' % n, f.read(8 * n)))
    raise ValueError(t)

def short(s):
    return s.replace('minecraft:', '')

def template(loc):
    try:
        return template0(loc)
    except Exception as e:
        return '  %s: ERROR %r' % (loc, e)

def template0(loc):
    loc = short(loc)
    p = os.path.join(D, 'structure', loc + '.nbt')
    if not os.path.exists(p):
        return '  %s: MISSING' % loc
    with gzip.open(p) as f:
        _, root = read(f)
    pal = root.get('palette') or root.get('palettes', [[]])[0]
    names = [short(x['Name']) for x in pal]
    hist = collections.Counter()
    jig = []
    for b in root['blocks']:
        n = names[b['state']]
        if n in ('air', 'structure_void'):
            continue
        if n == 'jigsaw':
            nbt = b.get('nbt', {})
            jig.append('%s@%s->%s' % (short(nbt.get('name', '?')), ','.join(map(str, b['pos'])), short(nbt.get('target', '?'))))
            continue
        hist[n] += 1
    top = ' '.join('%s:%d' % kv for kv in hist.most_common(40))
    pals = len(root.get('palettes', [])) if 'palettes' in root else 1
    return '  %s size=%s solid=%d palettes=%d\n    %s\n    jigsaw %s' % (loc, root['size'], sum(hist.values()), pals, top, ' '.join(jig[:12]))

def compact(path):
    with open(path) as f:
        return json.dumps(json.load(f), separators=(',', ':'))

print('== structure_set')
for p in sorted(glob.glob(os.path.join(D, 'worldgen/structure_set/*.json'))):
    print(os.path.basename(p), compact(p))
print('== structure')
pools = set()
for p in sorted(glob.glob(os.path.join(D, 'worldgen/structure/*.json'))):
    with open(p) as f:
        s = json.load(f)
    s.pop('spawn_overrides', None)
    print(os.path.basename(p), json.dumps(s, separators=(',', ':')))
    if 'start_pool' in s:
        pools.add(short(s['start_pool']))
print('== has_structure tags')
for p in sorted(glob.glob(os.path.join(D, 'tags/worldgen/biome/has_structure/*.json'))):
    print(os.path.basename(p), compact(p))
for p in sorted(glob.glob(os.path.join(D, 'tags/worldgen/biome/is_*.json'))):
    print(os.path.basename(p), compact(p))
print('== start pools')
procs = set()
for pool in sorted(pools):
    p = os.path.join(D, 'worldgen/template_pool', pool + '.json')
    try:
        with open(p) as f:
            j = json.load(f)
    except Exception as e:
        print('POOL', pool, 'ERROR', e)
        continue
    print('POOL', pool, 'fallback', j.get('fallback'))
    for e in j['elements']:
        el = e['element']
        locs = []
        if 'location' in el:
            locs = [el['location']]
        elif 'elements' in el:
            locs = [x.get('location', '?') for x in el['elements']]
        pr = el.get('processors')
        if isinstance(pr, str):
            procs.add(short(pr))
        print(' weight', e['weight'], el.get('element_type'), 'proj', el.get('projection'), 'proc', pr if isinstance(pr, str) else ('inline' if pr else None))
        for loc in locs:
            print(template(loc))
print('== processors')
for pr in sorted(procs):
    p = os.path.join(D, 'worldgen/processor_list', pr + '.json')
    if os.path.exists(p):
        print(pr, compact(p)[:1500])
for d in ['shipwreck', 'igloo', 'ruined_portal', 'underwater_ruin']:
    print('== templates', d)
    for p in sorted(glob.glob(os.path.join(D, 'structure', d, '**/*.nbt'), recursive=True)):
        rel = os.path.relpath(p, os.path.join(D, 'structure'))[:-4]
        print(template(rel))
