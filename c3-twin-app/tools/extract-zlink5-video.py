#!/usr/bin/env python3
"""Extract Zlink 5 video messages from an Ethernet/IPv4 loopback pcap.
Usage: python3 tools/extract-zlink5-video.py OUTPUT_DIRECTORY PCAP...
Only TCP traffic destined for localhost:1888 is examined.
"""
import collections
import json
from pathlib import Path
import struct
import sys

output = Path(sys.argv[1])
output.mkdir(parents=True, exist_ok=True)
flows = collections.defaultdict(list)
for path in sys.argv[2:]:
    with open(path, 'rb') as source:
        header = source.read(24)
        endian = '<' if header[:4] in (b'\xd4\xc3\xb2\xa1', b'\x4d\x3c\xb2\xa1') else '>'
        if len(header) != 24 or struct.unpack(endian+'I', header[20:24])[0] != 1:
            raise ValueError('Expected Ethernet pcap')
        while record := source.read(16):
            if len(record) != 16: break
            sec, usec, length, original = struct.unpack(endian+'IIII', record)
            packet = source.read(length)
            if len(packet) != length: break
            if packet[12:14] != b'\x08\x00' or len(packet) < 54: continue
            ip = packet[14:]
            ihl = (ip[0] & 15) * 4
            if ip[9] != 6 or ip[16:20] != b'\x7f\0\0\1': continue
            tcp = ip[ihl:struct.unpack('>H', ip[2:4])[0]]
            if len(tcp) < 20: continue
            src, dest, seq = struct.unpack('>HHI', tcp[:8])
            if dest != 1888: continue
            payload = tcp[(tcp[12] >> 4)*4:]
            if payload: flows[src].append((seq, payload))

report = []
for port, segments in flows.items():
    chunks = []
    data = bytearray()
    expected = None
    for seq, payload in sorted(segments, key=lambda item: item[0]):
        if expected is None or seq > expected:
            if data: chunks.append(bytes(data))
            data = bytearray(payload)
            expected = seq + len(payload)
        else:
            overlap = expected - seq
            if overlap < len(payload):
                data.extend(payload[overlap:])
                expected = seq + len(payload)
    if data: chunks.append(bytes(data))
    frames = []
    messages = collections.Counter()
    metadata = collections.Counter()
    with (output / f'{port}.h264').open('wb') as video:
        for chunk in chunks:
            offset = 0
            while True:
                offset = chunk.find(b'\xff\xff\xff\x10', offset)
                if offset < 0 or offset + 12 > len(chunk): break
                size, kind = struct.unpack_from('>II', chunk, offset+4)
                if size > 16*1024*1024:
                    offset += 4
                    continue
                if offset+12+size > len(chunk): break
                payload = chunk[offset+12:offset+12+size]
                messages[kind] += 1
                if kind == 0x302 and len(payload) > 20:
                    meta = struct.unpack('>IIIII', payload[:20])
                    metadata[meta] += 1
                    frames.append(payload[20:])
                    video.write(payload[20:])
                offset += 12 + size
    report.append({'port':port, 'chunks':len(chunks), 'messages':dict(messages), 'frames':len(frames),
                   'metadata':[(list(k),v) for k,v in metadata.items()],
                   'firstVideoBytes':frames[0][:16].hex() if frames else None})
(output/'summary.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
