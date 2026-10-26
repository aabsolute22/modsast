# Prints the size of each structure template and the block layout of its lowest layers.
import gzip, struct, sys

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
    if t == 8: n = struct.unpack('>H', f.read(2))[0]; return f.read(n).decode()
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

for path in sys.argv[1:]:
    with gzip.open(path) as f:
        _, root = read(f)
    size = root['size']
    pal = [p['Name'].replace('minecraft:', '') for p in root['palette']]
    print('###', path.split('/')[-1], 'size', size, 'blocks', len(root['blocks']))
    if 'base_floor' not in path and 'second_floor_1' not in path and 'third_floor_1' not in path:
        continue
    grid = {}
    for b in root['blocks']:
        x, y, z = b['pos']
        grid[(x, y, z)] = pal[b['state']]
    names = sorted(set(grid.values()))
    code = {n: chr(ord('a') + i) for i, n in enumerate(names)}
    print('legend', code)
    for y in range(min(3, size[1])):
        print('y =', y)
        for z in range(size[2]):
            print(''.join(code.get(grid.get((x, y, z)), '?') for x in range(size[0])))
