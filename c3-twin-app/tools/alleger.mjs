// Lightens the car for the head unit without touching what the eye reads:
// paint, glass, chrome, lamps and plates keep every triangle; only the
// interior, tyres, rims, brake discs and black trim are simplified.
// Usage: node tools/alleger.mjs <in.glb> <out.glb>
import { NodeIO } from '@gltf-transform/core';
import { ALL_EXTENSIONS } from '@gltf-transform/extensions';
import { weld, simplifyPrimitive, draco } from '@gltf-transform/functions';
import { MeshoptSimplifier } from 'meshoptimizer';
import draco3d from 'draco3dgltf';

// material name prefix -> [ratio, error]; anything not listed is kept as is
const RULES = [
  [/^interior/, 0.25, 0.002],
  [/^tire/, 0.35, 0.0008],
  [/^rim/, 0.6, 0.0004],
  [/^brakedisk/, 0.3, 0.002],
  [/^black/, 0.5, 0.0003],
];

const [, , input, output] = process.argv;
const headUnit = process.argv.includes('--head-unit');
await MeshoptSimplifier.ready;
const io = new NodeIO().registerExtensions(ALL_EXTENSIONS).registerDependencies({
  'draco3d.decoder': await draco3d.createDecoderModule(),
  'draco3d.encoder': await draco3d.createEncoderModule(),
});
const doc = await io.read(input);
await doc.transform(weld());

let before = 0, after = 0;
for (const mesh of doc.getRoot().listMeshes()) for (const prim of mesh.listPrimitives()) {
  const tris = () => prim.getIndices().getCount() / 3;
  before += tris();
  const name = prim.getMaterial()?.getName() || '';
  const rule = headUnit
    ? (/^(Plate|C3_Paint|.*glass)/.test(name) ? null : [null, 0.12, 0.001])
    : RULES.find(([re]) => re.test(name));
  if (rule) simplifyPrimitive(prim, { simplifier: MeshoptSimplifier, ratio: rule[1], error: rule[2] });
  after += tris();
}
// finer normals than the default: the glossy paint shows every quantization step
await doc.transform(draco({ quantizePosition: 16, quantizeNormal: 12, quantizeTexcoord: 14 }));
await io.write(output, doc);
console.log(`triangles ${before} -> ${after}`);
