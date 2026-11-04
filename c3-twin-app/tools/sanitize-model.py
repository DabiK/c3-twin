#!/usr/bin/env python3
"""Remove embedded plate textures and metadata from the project's GLB files.
Geometry and Draco streams are preserved byte-for-byte. Image bytes are zeroed,
not merely hidden from the renderer. Refuses models with other image resources.
"""
import json
from pathlib import Path
import struct
import sys

for name in sys.argv[1:]:
    path = Path(name)
    raw = path.read_bytes()
    magic, version, length = struct.unpack_from('<III', raw)
    assert magic == 0x46546C67 and version == 2 and length == len(raw)
    json_length, kind = struct.unpack_from('<II', raw, 12)
    assert kind == 0x4E4F534A
    doc = json.loads(raw[20:20 + json_length])
    offset = 20 + json_length
    bin_length, kind = struct.unpack_from('<II', raw, offset)
    assert kind == 0x004E4942 and offset + 8 + bin_length == len(raw)
    binary = bytearray(raw[offset + 8:])
    images = doc.get('images', [])
    for image in images:
        assert image.get('name', '').lower() == 'plate', 'Unrecognized texture: review before publishing'
        view = doc['bufferViews'][image['bufferView']]
        start = view.get('byteOffset', 0)
        binary[start:start + view['byteLength']] = bytes(view['byteLength'])
    for material in doc.get('materials', []):
        pbr = material.get('pbrMetallicRoughness', {})
        if 'baseColorTexture' in pbr:
            pbr.pop('baseColorTexture')
            pbr['baseColorFactor'] = [0.85, 0.85, 0.85, 1]
    doc.pop('images', None)
    doc.pop('textures', None)
    doc.pop('samplers', None)
    def clean(value):
        if isinstance(value, dict):
            value.pop('extras', None)
            for child in value.values(): clean(child)
        elif isinstance(value, list):
            for child in value: clean(child)
    clean(doc)
    doc['asset'] = {'version': '2.0', 'generator': 'C3 Twin public model'}
    encoded = json.dumps(doc, separators=(',', ':')).encode()
    encoded += b' ' * (-len(encoded) % 4)
    result = (struct.pack('<III', magic, version, 28 + len(encoded) + len(binary))
              + struct.pack('<II', len(encoded), 0x4E4F534A) + encoded
              + struct.pack('<II', len(binary), 0x004E4942) + binary)
    path.write_bytes(result)
    print(f'{path.name}: {len(images)} plate texture(s) removed; geometry preserved')
