// Home screen of the head unit: the C3 twin in 3D with speed, lamps and alerts.
// The Android app pushes the car's state with twin.set({...}); in a browser the
// page also listens to postMessage so a host page can drive it.
import * as THREE from 'three';
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { DRACOLoader } from 'three/examples/jsm/loaders/DRACOLoader.js';
import { RoomEnvironment } from 'three/examples/jsm/environments/RoomEnvironment.js';
import { describe } from './alertes.js';

const native = window.Android || null;
let lightTheme = false;
try { lightTheme = localStorage.getItem('c3.theme') === 'light'; } catch (_) {}
function applyTheme() {
  document.body.classList.toggle('light', lightTheme);
  document.getElementById('themeLabel').textContent = lightTheme ? 'Sombre' : 'Clair';
  document.getElementById('btnTheme').setAttribute('aria-label', lightTheme ? 'Activer le thème sombre' : 'Activer le thème clair');
}
applyTheme();
document.getElementById('btnTheme').addEventListener('click', () => {
  lightTheme = !lightTheme;
  applyTheme();
  try { localStorage.setItem('c3.theme', lightTheme ? 'light' : 'dark'); } catch (_) {}
});
// ?mode=side: the narrow panel beside CarPlay, only the car, the speed, the lamps and the warnings
const SIDE = new URLSearchParams(location.search).get('mode') === 'side';
if (SIDE) document.body.classList.add('side');
let fitScale = 1;                                    // a narrow screen needs the camera further back
const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------
const state = {
  doors: { FL: false, FR: false, RL: false, RR: false },
  hatch: false,
  bonnet: false,
  windows: { FL: 0, FR: 0, RL: 0, RR: 0 },
  lights: {
    beam: 'off',                                     // 'off' | 'position' | 'low' | 'high'
    position: null, lowBeam: null, turnPhase: false,
    fogFront: false, fogRear: false,
    brake: false, reverse: false,
    turnLeft: false, turnRight: false, hazard: false,
  },
  handbrake: false,
  beltDriver: false,                                 // true = driver belt NOT fastened
  speed: 0,                                          // km/h
  steer: 0,                                          // road wheel degrees, + = left
  sources: { car: false, gps: false, experimental: false, configured: false },
  warnings: [],                                      // [{ code, proto: 'psa' | 'raise', detail }]
  trip: {                                            // the car's trip computer; null = not said
    odometer: null, range: null, instant: null, fuel: null, outsideTemp: null,
    trip1: { km: null, avgKmh: null, avgL100: null },
    trip2: { km: null, avgKmh: null, avgL100: null },
  },
};

function merge(dst, src) {
  for (const k of Object.keys(src)) {
    if (src[k] && typeof src[k] === 'object' && !Array.isArray(src[k]) && dst[k] && typeof dst[k] === 'object') merge(dst[k], src[k]);
    else if (k in dst) dst[k] = src[k];
  }
}

// while the demo runs it owns the state: the car's (or the emulator's) own updates wait
function apply(patch) {
  merge(state, patch); refreshUi();
  const diagnostic = $('signalStatus');
  if (diagnostic) {
    diagnostic.hidden = !state.sources.experimental;
    diagnostic.textContent = !state.sources.car ? 'MTCH expérimental · aucun signal récent · Signaux pour analyser' :
      state.sources.configured ? 'MTCH expérimental · profil enregistré · champs non associés inconnus' : 'MTCH expérimental · analyse requise dans Signaux';
  }
}
window.twin = {
  set(patch) { if (!demoTimer) apply(patch); },
  get() { return JSON.parse(JSON.stringify(state)); },
  demo(on) { setDemo(on); },
  // for debugging the camera: where it looks and why
  view() { tmp.copy(camera.position).sub(controls.target); sph.setFromVector3(tmp);
    return { az: Math.round(THREE.MathUtils.radToDeg(sph.theta)), el: Math.round(90 - THREE.MathUtils.radToDeg(sph.phi)), focus: focusView, manual: manualView, touched: touchedUntil > performance.now() }; },
};
window.addEventListener('message', (e) => {
  const d = e.data;
  if (!d || typeof d !== 'object') return;
  if ('__demo' in d) setDemo(!!d.__demo);
  if ('__view' in d) nextView();
  if (d.patch) window.twin.set(d.patch);
});

// ---------------------------------------------------------------------------
// Scene
// ---------------------------------------------------------------------------
const light = !!native;                              // the head unit's GPU gets the cheaper settings
const renderer = new THREE.WebGLRenderer({ antialias: !light, alpha: true, powerPreference: 'high-performance' });
renderer.setClearColor(0x000000, 0);                 // the CSS backdrop shows through
renderer.setPixelRatio(light ? 1 : Math.min(window.devicePixelRatio, 2));
renderer.setSize(window.innerWidth, window.innerHeight);
renderer.shadowMap.enabled = !light;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
renderer.outputColorSpace = THREE.SRGBColorSpace;
$('scene').appendChild(renderer.domElement);

const scene = new THREE.Scene();

const camera = new THREE.PerspectiveCamera(34, window.innerWidth / window.innerHeight, 0.05, 120);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
controls.dampingFactor = 0.08;
controls.enablePan = false;
controls.minDistance = 3.2;
controls.maxDistance = 11;
controls.maxPolarAngle = Math.PI * 0.48;

const pmrem = new THREE.PMREMGenerator(renderer);
scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
scene.add(new THREE.HemisphereLight(0xffffff, 0x15181d, 1.0));

const key = new THREE.DirectionalLight(0xffffff, 2.5);
key.position.set(2.6, 5.5, 3.6);
key.castShadow = true;
key.shadow.mapSize.set(light ? 1024 : 2048, light ? 1024 : 2048);
Object.assign(key.shadow.camera, { near: 0.5, far: 18, left: -3.2, right: 3.2, top: 3.2, bottom: -3.2 });
key.shadow.bias = -0.0008;
key.shadow.normalBias = 0.02;
scene.add(key, key.target);
const rim = new THREE.DirectionalLight(0xdfe8ff, 1.1);
rim.position.set(-3.5, 2.6, -3.2);
scene.add(rim);

// the floor: only the car's shadow, a soft pool of light and a slow ring of marks around it
const ground = new THREE.Mesh(new THREE.CircleGeometry(9, 96), new THREE.ShadowMaterial({ opacity: 0.55 }));
ground.rotation.x = -Math.PI / 2;
ground.receiveShadow = true;
scene.add(ground);

function canvasTexture(size, draw) {
  const c = document.createElement('canvas');
  c.width = c.height = size;
  draw(c.getContext('2d'), size);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  return t;
}
const floorGlow = new THREE.Mesh(new THREE.CircleGeometry(4.6, 96), new THREE.MeshBasicMaterial({
  transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  map: canvasTexture(512, (g, n) => {
    const r = g.createRadialGradient(n / 2, n / 2, 0, n / 2, n / 2, n / 2);
    r.addColorStop(0, 'rgba(201,160,124,0.32)');
    r.addColorStop(0.45, 'rgba(120,140,170,0.12)');
    r.addColorStop(1, 'rgba(0,0,0,0)');
    g.fillStyle = r; g.fillRect(0, 0, n, n);
  }),
}));
floorGlow.rotation.x = -Math.PI / 2;
floorGlow.position.y = 0.003;
scene.add(floorGlow);

const floorRings = new THREE.Mesh(new THREE.CircleGeometry(4.2, 128), new THREE.MeshBasicMaterial({
  transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, opacity: 0.9,
  map: canvasTexture(1024, (g, n) => {
    const c = n / 2;
    g.lineCap = 'round';
    // a few partial rings and tick marks, brighter on one side so the turn reads
    [[0.62, 0.10, 2], [0.74, 0.07, 1.5], [0.86, 0.05, 1], [0.97, 0.04, 1]].forEach(([rr, a, w], i) => {
      for (let k = 0; k < 6; k++) {
        const start = (k / 6) * Math.PI * 2 + i * 0.4;
        g.strokeStyle = 'rgba(180,200,230,' + (a * (k % 2 ? 0.5 : 1)) + ')';
        g.lineWidth = w * 2;
        g.beginPath(); g.arc(c, c, rr * c, start, start + Math.PI / 4.2); g.stroke();
      }
    });
    for (let k = 0; k < 120; k++) {
      const a = (k / 120) * Math.PI * 2, r0 = 0.9 * c, r1 = (k % 10 ? 0.92 : 0.95) * c;
      g.strokeStyle = 'rgba(180,200,230,' + (k % 10 ? 0.06 : 0.14) + ')';
      g.lineWidth = 2;
      g.beginPath(); g.moveTo(c + Math.cos(a) * r0, c + Math.sin(a) * r0); g.lineTo(c + Math.cos(a) * r1, c + Math.sin(a) * r1); g.stroke();
    }
  }),
}));
floorRings.rotation.x = -Math.PI / 2;
floorRings.position.y = 0.004;
scene.add(floorRings);

// ---------------------------------------------------------------------------
// The car
// ---------------------------------------------------------------------------
// rest pose in the file is closed; open poses in glTF space (Y up, front = +Z, left = +X)
const HINGES = {
  Door_FL: { axis: 'y', open: -1.15 }, Door_FR: { axis: 'y', open: 1.15 },
  Door_RL: { axis: 'y', open: -1.15 }, Door_RR: { axis: 'y', open: 1.15 },
  Hatch: { axis: 'x', open: 1.2 },
};
const WINDOW_TRAVEL = { FL: 0.44, FR: 0.44, RL: 0.40, RR: 0.40 };
const WHEEL_RADIUS = 0.31;
const LAMPS = ['Headlight', 'HighBeam', 'FogLight', 'Taillight', 'BrakeLight', 'Reverse', 'RearFog', 'Turn_L', 'Turn_R'];

const parts = { hinges: {}, windows: {}, wheels: {} };
const lampMats = {};
const anim = { hinge: {}, window: {} };
const center = new THREE.Vector3(0, 0.75, 0);
let radius = 6.2;
let model = null;

const draco = new DRACOLoader();
draco.setDecoderPath('draco/');
// WebAssembly decoder where the WebView has it; DRACOLoader falls back to the plain JS one otherwise
const loader = new GLTFLoader();
loader.setDRACOLoader(draco);
function onCar(gltf) {
  model = gltf.scene;
  model.traverse((o) => {
    if (!o.isMesh) return;
    o.castShadow = true;
    o.receiveShadow = true;
    for (const m of Array.isArray(o.material) ? o.material : [o.material]) {
      // Refraction requires another full scene render on the head unit.
      if (light && m.transmission > 0) m.transmission = 0;
      m.envMapIntensity = 0.9;
      if (/^((clear|green|dark)glass|mirror)/.test(m.name)) m.roughness = Math.max(m.roughness, 0.14);
      // on the dark background clear glass vanishes: give it a little body and reflection
      if (/^(green|dark)glass/.test(m.name)) { m.opacity = Math.max(m.opacity, 0.42); m.envMapIntensity = 1.6; }
      if (LAMPS.includes(m.name)) {
        m.userData.offOpacity = m.opacity;
        m.emissiveIntensity = 0;
        o.renderOrder = m.name.startsWith('Turn_') ? 2 : 1;
        if (m.name.startsWith('Turn_')) {
          m.color.setHex(0xff6600);
          m.envMapIntensity = 0;
        }
        lampMats[m.name] = m;
      }
    }
  });
  scene.add(model);
  for (const name of Object.keys(HINGES)) {
    const o = model.getObjectByName(name);
    if (o) { parts.hinges[name] = o; anim.hinge[name] = 0; }
  }
  for (const k of Object.keys(WINDOW_TRAVEL)) {
    const o = model.getObjectByName('Window_' + k);
    if (o) { parts.windows[k] = { obj: o, y0: o.position.y }; anim.window[k] = 0; }
  }
  for (const k of ['FL', 'FR', 'RL', 'RR']) {
    const o = model.getObjectByName('Wheel_' + k);
    if (o) { o.rotation.order = 'YXZ'; parts.wheels[k] = o; }
  }
  const box = new THREE.Box3().setFromObject(model);
  carBox.copy(box);
  for (const m of Object.values(lampMats)) lampColors.set(m, m.emissive.clone());
  box.getCenter(center);
  center.y *= 0.9;
  const size = box.getSize(new THREE.Vector3());
  radius = Math.max(size.x, size.y, size.z) * 1.95;
  key.target.position.copy(center);
  key.target.updateMatrixWorld();
  controls.target.copy(center);
  placeCamera(VIEWS.home, 1);
  $('loader').classList.add('hidden');
  refreshUi();
  native && native.ready && native.ready();
}
const onCarError = (err) => { $('loader').textContent = 'Modèle introuvable : ' + (err && err.message ? err.message : err); };

// hosts that refuse .glb files get the model as base64 text (car-mobile.glb.txt)
const modelUrl = new URLSearchParams(location.search).get('model') || 'car-mobile.glb';
fetch(modelUrl)
  .then((r) => { if (!r.ok) throw new Error(r.status); return r; })
  .catch(() => fetch(modelUrl + '.txt').then((r) => r.text()).then((b64) => {
    const bin = atob(b64.trim());
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return { arrayBuffer: () => bytes.buffer };
  }))
  .then((r) => r.arrayBuffer())
  .then((buf) => loader.parse(buf, '', onCar, onCarError))
  .catch(onCarError);

// ---------------------------------------------------------------------------
// Camera: it follows what happens (open door, reverse) unless the driver
// turned it by hand, in which case it waits a moment before taking over again.
// Azimuth 0 = in front of the car, 90 = its left side, 180 = behind.
// ---------------------------------------------------------------------------
const VIEWS = {
  home: { az: 38, el: 20, d: 1 },
  left: { az: 80, el: 24, d: 0.95 },
  right: { az: -80, el: 24, d: 0.95 },
  rear: { az: 160, el: 26, d: 1 },
  reverse: { az: 180, el: 20, d: 1.05 },
  top: { az: 30, el: 62, d: 1.05 },
};
const MANUAL_ORDER = ['home', 'left', 'rear', 'right', 'top'];
let manualView = null;
let manualUntil = 0;
let touchedUntil = 0;

controls.addEventListener('start', () => { touchedUntil = Infinity; });
controls.addEventListener('end', () => { touchedUntil = performance.now() + 10000; });

function nextView() {
  const i = MANUAL_ORDER.indexOf(manualView);
  manualView = MANUAL_ORDER[(i + 1) % MANUAL_ORDER.length];
  manualUntil = performance.now() + 20000;
  touchedUntil = 0;
}

// a new warning, or a tap on its card, turns the camera to its zone for a while
let focusView = null;
let focusUntil = 0;
function focusZone(zone, ms) {
  const z = ZONES[zone];
  if (!z) return;
  focusView = z.view;
  focusUntil = performance.now() + ms;
  manualView = null;
  touchedUntil = 0;
}

function autoView() {
  if (manualView && performance.now() < manualUntil) return VIEWS[manualView];
  manualView = null;
  if (focusView && performance.now() < focusUntil) return focusView;
  focusView = null;
  const d = state.doors;
  if (state.lights.reverse) return VIEWS.reverse;
  if (d.FL || d.RL) return VIEWS.left;
  if (d.FR || d.RR) return VIEWS.right;
  if (state.hatch) return VIEWS.rear;
  return VIEWS.home;
}

// ---------------------------------------------------------------------------
// Warning zones: where each kind of warning points on the car. Anchors are
// fractions of the car's box: x of the half width (+ = left), y of the height
// from the ground, z of the half length (+ = front). Wheels use the wheels.
// ---------------------------------------------------------------------------
const carBox = new THREE.Box3();
const lampColors = new Map();
const FRONT_LAMPS = [[0.66, 0.5, 0.97], [-0.66, 0.5, 0.97]];
const REAR_LAMPS = [[0.8, 0.6, -0.97], [-0.8, 0.6, -0.97]];
const ALL_WHEELS = ['FL', 'FR', 'RL', 'RR'];
const V_FRONT = { az: 14, el: 12, d: 0.85 };
const V_REAR = { az: 166, el: 14, d: 0.85 };
const V_SIDE = { az: 68, el: 16, d: 1 };
const V_ENGINE = { az: 25, el: 32, d: 0.9 };
const ZONES = {
  moteur: { at: [[0, 0.66, 0.62]], view: V_ENGINE },
  capot: { at: [[0, 0.66, 0.62]], view: V_ENGINE },
  batterie: { at: [[0.42, 0.62, 0.66]], view: V_ENGINE },
  feux_av: { at: FRONT_LAMPS, view: V_FRONT, lamps: ['Headlight', 'HighBeam', 'FogLight'] },
  croisement: { at: FRONT_LAMPS, view: V_FRONT, lamps: ['Headlight'] },
  route: { at: FRONT_LAMPS, view: V_FRONT, lamps: ['HighBeam'] },
  feux_ar: { at: REAR_LAMPS, view: V_REAR, lamps: ['Taillight', 'BrakeLight', 'Reverse', 'RearFog'] },
  stop: { at: REAR_LAMPS, view: V_REAR, lamps: ['BrakeLight', 'Taillight'] },
  recul: { at: REAR_LAMPS, view: V_REAR, lamps: ['Reverse'] },
  feux: { at: FRONT_LAMPS.concat(REAR_LAMPS), view: V_SIDE, lamps: LAMPS },
  position: { at: FRONT_LAMPS.concat(REAR_LAMPS), view: V_SIDE, lamps: ['Headlight', 'Taillight'] },
  antibrouillard: { at: FRONT_LAMPS.concat(REAR_LAMPS), view: V_SIDE, lamps: ['FogLight', 'RearFog'] },
  clignotants: { at: FRONT_LAMPS.concat(REAR_LAMPS), view: V_SIDE, lamps: ['Turn_L', 'Turn_R'] },
  pneus: { wheels: ALL_WHEELS, view: V_SIDE },
  freins: { wheels: ALL_WHEELS, view: V_SIDE },
  suspension: { wheels: ALL_WHEELS, view: V_SIDE },
  direction: { wheels: ['FL', 'FR'], view: { az: 30, el: 14, d: 0.9 } },
  boite: { at: [[0, 0.28, 0.4]], view: { az: 50, el: 12, d: 0.9 } },
  echappement: { at: [[0.35, 0.14, -0.97]], view: { az: 160, el: 10, d: 0.9 } },
  habitacle: { at: [[0.3, 0.55, 0.08]], view: { az: 75, el: 38, d: 0.9 } },
  carburant: { at: [[-0.98, 0.6, -0.55]], view: { az: -115, el: 16, d: 0.9 } },
  pare_brise: { at: [[0, 0.82, 0.32]], view: { az: 20, el: 36, d: 0.9 } },
  portes: { at: [[0.98, 0.55, 0.25], [-0.98, 0.55, 0.25]], view: VIEWS.left },
  coffre: { at: [[0, 0.62, -0.98]], view: VIEWS.rear },
  toit: { at: [[0, 1.0, -0.1]], view: VIEWS.top },
};
const LEVEL_ORDER = { stop: 0, service: 1, info: 2 };
const LEVEL_COLOR = { stop: '#ef4444', service: '#f59e0b', info: '#60a5fa' };
const LAMP_ALERT = new THREE.Color(0xff3b1f);

// warnings the overlay already says from the live state, or that say nothing
const SKIP = new Set([0x0000, 0x00F0, 0x00F1, 0x0062]);
function redundant(w) {
  if (w.proto !== 'psa') return false;
  if (SKIP.has(w.code)) return true;
  const d = state.doors;
  if ((w.code === 0x000B || w.code === 0x00DE) && (d.FL || d.FR || d.RL || d.RR || state.hatch)) return true;
  return (w.code === 0x00D9 || w.code === 0x0061) && state.handbrake;
}

const markerTextures = {};
function markerMaterial(level) {
  if (!markerTextures[level]) {
    const c = document.createElement('canvas');
    c.width = c.height = 128;
    const g = c.getContext('2d');
    const r = g.createRadialGradient(64, 64, 0, 64, 64, 64);
    r.addColorStop(0, '#ffffff');
    r.addColorStop(0.18, LEVEL_COLOR[level]);
    r.addColorStop(0.45, LEVEL_COLOR[level] + '55');
    r.addColorStop(1, LEVEL_COLOR[level] + '00');
    g.fillStyle = r; g.fillRect(0, 0, 128, 128);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.SRGBColorSpace;
    markerTextures[level] = t;
  }
  return new THREE.SpriteMaterial({ map: markerTextures[level], depthTest: true, depthWrite: false, transparent: true, fog: false });
}

const markers = new THREE.Group();
markers.renderOrder = 10;
scene.add(markers);
let alertLamps = new Set();
const seenWarnings = new Set();

function anchorPoints(zone) {
  const z = ZONES[zone];
  const pts = [];
  if (!z || carBox.isEmpty()) return pts;
  const c = carBox.getCenter(new THREE.Vector3());
  const s = carBox.getSize(new THREE.Vector3());
  for (const [x, y, zz] of z.at || []) pts.push(new THREE.Vector3(c.x + x * s.x / 2, carBox.min.y + y * s.y, c.z + zz * s.z / 2));
  for (const k of z.wheels || []) {
    const w = parts.wheels[k];
    if (!w) continue;
    const p = w.getWorldPosition(new THREE.Vector3());
    p.x += Math.sign(p.x - c.x) * 0.22;
    p.y += 0.12;
    pts.push(p);
  }
  return pts;
}

/** The car's warnings, described, most serious first. */
function currentWarnings() {
  return (state.warnings || [])
    .filter((w) => w && typeof w.code === 'number' && !redundant(w))
    .map((w) => Object.assign({ key: (w.proto || 'psa') + ':' + w.code }, describe(w)))
    .sort((a, b) => LEVEL_ORDER[a.level] - LEVEL_ORDER[b.level]);
}

function refreshWarnings(list) {
  for (const s of markers.children) s.material.dispose();
  markers.clear();
  alertLamps = new Set();
  for (const w of list) {
    for (const p of anchorPoints(w.zone)) {
      const s = new THREE.Sprite(markerMaterial(w.level));
      s.position.copy(p);
      s.userData.phase = Math.random() * Math.PI * 2;
      markers.add(s);
    }
    for (const n of (ZONES[w.zone] && ZONES[w.zone].lamps) || []) alertLamps.add(n);
  }
  // turn to a new serious warning once
  const fresh = list.find((w) => !seenWarnings.has(w.key) && w.level !== 'info' && w.zone);
  for (const w of list) seenWarnings.add(w.key);
  for (const k of [...seenWarnings]) if (!list.some((w) => w.key === k)) seenWarnings.delete(k);
  if (fresh && model) focusZone(fresh.zone, 12000);
}

const sph = new THREE.Spherical();
const tmp = new THREE.Vector3();
function placeCamera(v, k) {
  tmp.copy(camera.position).sub(controls.target);
  sph.setFromVector3(tmp);
  const wantTheta = THREE.MathUtils.degToRad(v.az);
  let dTheta = wantTheta - sph.theta;
  dTheta = Math.atan2(Math.sin(dTheta), Math.cos(dTheta));
  if (k >= 1 || !isFinite(sph.theta)) { sph.theta = wantTheta; sph.phi = THREE.MathUtils.degToRad(90 - v.el); sph.radius = radius * v.d * fitScale; }
  else {
    sph.theta += dTheta * k;
    sph.phi += (THREE.MathUtils.degToRad(90 - v.el) - sph.phi) * k;
    sph.radius += (radius * v.d * fitScale - sph.radius) * k;
  }
  camera.position.copy(controls.target).add(tmp.setFromSpherical(sph));
  camera.lookAt(controls.target);
}

// the car sits between the left column and the right edge, above the trip cards
function frame() {
  const w = window.innerWidth, h = window.innerHeight;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  const aspect = w / h;
  fitScale = aspect < 1.2 ? Math.min(5, Math.max(1, 0.95 / aspect)) : 1;
  if (!SIDE && h <= 480 && w > h) fitScale *= 1.18;
  controls.maxDistance = 11 * Math.max(1, fitScale);
  if (SIDE) camera.setViewOffset(w, h, 0, -h * 0.02, w, h);
  else if (w > h) camera.setViewOffset(w, h, -w * 0.115, h * (h <= 480 ? 0.17 : 0.12), w, h);
  else camera.clearViewOffset();
  camera.updateProjectionMatrix();
}
frame();
window.addEventListener('resize', frame);

// ---------------------------------------------------------------------------
// Overlay
// ---------------------------------------------------------------------------
const SIDE_NAMES = { FL: 'avant gauche', FR: 'avant droite', RL: 'arrière gauche', RR: 'arrière droite' };

function refreshUi() {
  const L = state.lights;
  $('speed').textContent = Math.round(state.speed);
  $('gearTop').innerHTML = L.reverse ? '<b class="rev">R</b> &nbsp;MARCHE ARRIÈRE' : '';
  const temp = state.trip && state.trip.outsideTemp;
  $('temp').textContent = temp == null ? '' : Math.round(temp) + ' °C';
  $('lpPos').classList.toggle('on', L.position ?? (L.beam !== 'off'));
  $('lpLow').classList.toggle('on', L.lowBeam ?? (L.beam === 'low' || L.beam === 'high'));
  $('lpHigh').classList.toggle('on', L.beam === 'high');
  $('lpFogF').classList.toggle('on', L.fogFront);
  $('lpFogR').classList.toggle('on', L.fogRear);
  $('lpHand').classList.toggle('on', state.handbrake);
  $('srcCan').classList.toggle('on', state.sources.car);
  $('srcGps').classList.toggle('on', state.sources.gps);

  // [text, css class, zone]: the car's own warnings first, then what the live state says
  const warnings = currentWarnings();
  refreshWarnings(warnings);
  const alerts = warnings.map((w) => [w.text, { stop: 'red', service: '', info: 'info' }[w.level], w.zone]);
  if (state.handbrake && state.speed > 5) alerts.unshift(['Frein à main serré', 'red', 'freins']);
  if (state.beltDriver && state.speed > 10) alerts.unshift(['Ceinture conducteur non bouclée', 'red', 'habitacle']);
  for (const k of ['FL', 'FR', 'RL', 'RR']) if (state.doors[k]) alerts.push(['Portière ' + SIDE_NAMES[k] + ' ouverte', '', null]);
  if (state.hatch) alerts.push(['Coffre ouvert', '', null]);
  if (state.bonnet) alerts.push(['Capot ouvert', '', 'capot']);
  const box = $('alerts');
  box.innerHTML = '';
  const shown = alerts.slice(0, alerts.length > 3 ? 2 : 3);
  for (const [text, cls, zone] of shown) {
    const el = document.createElement('div');
    el.className = 'alert ' + cls;
    el.textContent = text;
    if (zone) el.addEventListener('click', () => focusZone(zone, 20000));
    box.appendChild(el);
  }
  if (alerts.length > shown.length) {
    const more = document.createElement('div');
    more.className = 'alert info';
    more.textContent = '+ ' + (alerts.length - shown.length) + ' autres alertes';
    box.appendChild(more);
  }
  buildCallouts(warnings);
  refreshTrip();
}

// ---------------------------------------------------------------------------
// Labels pinned to the car, Tesla-style: a title, a value, a thin line down to
// the part. Status of the openings, then the car's two most serious warnings.
// ---------------------------------------------------------------------------
const calloutBox = $('callouts');
const calloutEls = new Map();
let callouts = [];
const SHORT_SIDE = { FL: 'AV G', FR: 'AV D', RL: 'AR G', RR: 'AR D' };

function boxPoint(x, y, z) {
  const c = carBox.getCenter(new THREE.Vector3());
  const s = carBox.getSize(new THREE.Vector3());
  return new THREE.Vector3(c.x + x * s.x / 2, carBox.min.y + y * s.y, c.z + z * s.z / 2);
}

function buildCallouts(warnings) {
  if (carBox.isEmpty() || SIDE) return;
  const open = Object.keys(SHORT_SIDE).filter((k) => state.doors[k]);
  const list = [
    { key: 'hatch', title: 'Coffre', value: state.hatch == null ? 'Inconnu' : state.hatch ? 'Ouvert' : 'Fermé', cls: state.hatch ? 'open' : '', at: [boxPoint(0, 0.74, -0.97)] },
    { key: 'bonnet', title: 'Capot', value: state.bonnet == null ? 'Inconnu' : state.bonnet ? 'Ouvert' : 'Fermé', cls: state.bonnet ? 'open' : '', at: [boxPoint(0, 0.62, 0.72)] },
    { key: 'doors', title: 'Portières', value: open.length ? open.map((k) => SHORT_SIDE[k]).join(' · ') + (open.length > 1 ? ' ouvertes' : ' ouverte') : Object.values(state.doors).some(v => v == null) ? 'Inconnues' : 'Fermées',
      cls: open.length ? 'open' : '', at: [boxPoint(0.98, 0.48, 0.12), boxPoint(-0.98, 0.48, 0.12)] },
  ];
  for (const w of warnings.filter((x) => x.zone && x.level !== 'info').slice(0, 2)) {
    list.push({ key: 'w:' + w.key, title: w.level === 'stop' ? 'Arrêt immédiat' : 'Alerte', value: w.text, cls: w.level === 'stop' ? 'stop' : 'warn', at: anchorPoints(w.zone) });
  }
  callouts = list;
  for (const [k, el] of calloutEls) if (!list.some((c) => c.key === k)) { el.remove(); calloutEls.delete(k); }
  for (const c of list) {
    let el = calloutEls.get(c.key);
    if (!el) {
      el = document.createElement('div');
      el.innerHTML = '<span class="t"></span><span class="v"></span><span class="ln"></span><span class="dot"></span>';
      calloutBox.appendChild(el);
      calloutEls.set(c.key, el);
    }
    el.className = 'co ' + c.cls;
    el.children[0].textContent = c.title;
    el.children[1].textContent = c.value;
  }
}

const proj = new THREE.Vector3();
const corner = new THREE.Vector3();
function placeCallouts() {
  if (!callouts.length || carBox.isEmpty()) return;
  const w = window.innerWidth, h = window.innerHeight;
  const toScreen = (p) => { proj.copy(p).project(camera); return [(proj.x + 1) / 2 * w, (1 - proj.y) / 2 * h]; };
  // the labels line up in the empty space above the car's roof, as on a Tesla
  let roof = Infinity;
  for (let i = 0; i < 8; i++) {
    corner.set(i & 1 ? carBox.max.x : carBox.min.x, i & 2 ? carBox.max.y : carBox.min.y, i & 4 ? carBox.max.z : carBox.min.z);
    roof = Math.min(roof, toScreen(corner)[1]);
  }
  const cam = camera.position.clone().sub(controls.target).setY(0).normalize();
  const c = carBox.getCenter(new THREE.Vector3());
  const shown = [];
  for (const co of callouts) {
    const el = calloutEls.get(co.key);
    if (!el) continue;
    // the anchor on the side the camera sees best
    let best = null, bestDot = -Infinity;
    for (const p of co.at) {
      const d = p.clone().sub(c).setY(0).normalize().dot(cam);
      if (d > bestDot) { bestDot = d; best = p; }
    }
    if (!best || bestDot < -0.25) { el.style.opacity = '0'; continue; }
    const [x, y] = toScreen(best);
    const labelWidth = Math.max(el.children[0].offsetWidth, el.children[1].offsetWidth);
    shown.push({ el, x: Math.max(16, Math.min(x, w - labelWidth - 16)), y });
  }
  // each label takes the lowest row where it overlaps no other label
  shown.sort((a, b) => a.x - b.x);
  const rows = [];                                   // per row, the right edge of its last label
  shown.forEach((s) => {
    const width = Math.max(s.el.children[0].offsetWidth, s.el.children[1].offsetWidth);
    let row = rows.findIndex((right) => s.x > right + 18);
    if (row < 0) { row = rows.length; rows.push(0); }
    rows[row] = s.x + width;
    const labelBottom = Math.max(h * 0.12 + row * 52, roof - 18 - row * 52);
    s.el.children[2].style.height = Math.max(12, s.y - labelBottom - 10) + 'px';
    s.el.style.opacity = '1';
    s.el.style.transform = 'translate(' + Math.round(s.x) + 'px,' + Math.round(Math.max(w < h ? Math.max(260, h * 0.28) : 74, Math.min(s.y - s.el.offsetHeight + 4, h - 210))) + 'px)';
  });
}

// ---------------------------------------------------------------------------
// Distances. The trip is counted here from the speed; the total comes from the
// car when it says it, otherwise from the figure the driver typed in, moved on
// by every kilometre counted since.
// ---------------------------------------------------------------------------
const store = {
  get(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* private mode: kept for this run only */ } },
};
let distKm = store.get('c3.dist') || 0;               // every km this screen has counted, ever
const tripNow = { km: 0, startedAt: 0, movingMs: 0 };
const fmt = (v, d = 1) => Number(v).toLocaleString('fr-FR', { minimumFractionDigits: d, maximumFractionDigits: d });
const known = (v) => v !== null && v !== undefined && !Number.isNaN(v);

function countDistance(dt) {
  if (state.speed <= 0.5) return;
  const km = state.speed * dt / 3600;
  tripNow.km += km;
  tripNow.movingMs += dt * 1000;
  if (!tripNow.startedAt) tripNow.startedAt = Date.now();
  distKm += km;
}
setInterval(() => store.set('c3.dist', distKm), 10000);

function odometer() {
  const car = state.trip && state.trip.odometer;
  if (known(car) && car > 0) return { km: car, from: 'car' };
  const set = store.get('c3.odo');
  if (set && known(set.km)) return { km: set.km + (distKm - set.dist), from: 'estimate' };
  return null;
}

function refreshTrip() {
  const T = state.trip || {};
  const t1 = T.trip1 || {};
  const unit = (v, u) => v + '<small>' + u + '</small>';
  $('nowKm').innerHTML = unit(fmt(tripNow.km), 'KM');
  const min = tripNow.startedAt ? Math.round((Date.now() - tripNow.startedAt) / 60000) : 0;
  const dur = min < 60 ? min + ' min' : Math.floor(min / 60) + ' h ' + String(min % 60).padStart(2, '0');
  const avg = tripNow.movingMs > 60000 ? ' · ' + Math.round(tripNow.km / (tripNow.movingMs / 3600000)) + ' km/h' : '';
  $('nowSub').textContent = tripNow.startedAt ? dur + avg : 'À l’arrêt';
  $('tRange').innerHTML = known(T.range) ? unit(fmt(T.range, 0), 'KM') : '–';
  $('tInst').textContent = known(T.instant) && T.instant > 0 ? fmt(T.instant) + ' L/100 en ce moment' : (known(T.range) ? 'Estimée par la voiture' : 'Pas encore reçue');
  $('tAvgL').innerHTML = known(t1.avgL100) ? unit(fmt(t1.avgL100), 'L/100') : '–';
  const parts = [];
  if (known(t1.km)) parts.push(fmt(t1.km) + ' km');
  if (known(t1.avgKmh)) parts.push(fmt(t1.avgKmh, 0) + ' km/h');
  $('tTrip').textContent = parts.length ? parts.join(' · ') : 'Ordinateur de bord';
  const o = odometer();
  $('odoKm').innerHTML = o ? unit(fmt(Math.floor(o.km), 0), 'KM') : '–';
  $('odoTag').textContent = !o ? 'Touchez pour le régler' : o.from === 'car' ? 'Compteur de la voiture' : 'Estimé · touchez pour corriger';
}
setInterval(refreshTrip, 1000);

$('cardOdo').addEventListener('click', () => {
  const o = odometer();
  if (o && o.from === 'car') return;
  $('odoInput').value = o ? Math.floor(o.km) : '';
  $('odoDialog').classList.add('open');
  setTimeout(() => $('odoInput').focus(), 50);
});
$('odoCancel').addEventListener('click', () => $('odoDialog').classList.remove('open'));
$('odoSave').addEventListener('click', () => {
  const km = parseInt($('odoInput').value, 10);
  if (!(km >= 0 && km < 1000000)) { $('odoInput').focus(); return; }
  store.set('c3.odo', { km, dist: distKm });
  $('odoDialog').classList.remove('open');
  refreshTrip();
});

function tickClock() {
  const d = new Date();
  $('clock').textContent = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
}
tickClock();
setInterval(tickClock, 10000);
refreshUi();

// ---------------------------------------------------------------------------
// Dock and app drawer
// ---------------------------------------------------------------------------
const SAMPLE_APPS = [
  ['ZLink', '#3b82f6'], ['Radio', '#f59e0b'], ['Musique', '#ec4899'], ['Bluetooth', '#6366f1'],
  ['Waze', '#22d3ee'], ['Spotify', '#22c55e'], ['Réglages', '#94a3b8'], ['Égaliseur', '#a78bfa'],
];

function letterIcon(label, color) {
  const c = document.createElement('canvas');
  c.width = c.height = 128;
  const g = c.getContext('2d');
  g.fillStyle = color; g.beginPath(); g.arc(64, 64, 60, 0, Math.PI * 2); g.fill();
  g.fillStyle = '#fff'; g.font = '600 56px Arial'; g.textAlign = 'center'; g.textBaseline = 'middle';
  g.fillText(label[0], 64, 68);
  return c.toDataURL();
}

let drawerApps = [];
let organizingApps = false;
let hiddenApps = new Set();
try {
  const saved = JSON.parse(localStorage.getItem('c3.hiddenApps') || '[]');
  if (Array.isArray(saved)) hiddenApps = new Set(saved.filter(pkg => typeof pkg === 'string'));
} catch (_) { /* Invalid preferences use the complete application list. */ }

function renderApps() {
  const box = $('apps');
  box.textContent = '';
  const visible = drawerApps.filter(app => !hiddenApps.has(app.pkg));
  $('appCount').textContent = organizingApps
    ? 'Choisissez les applications à afficher.'
    : visible.length + ' application' + (visible.length === 1 ? '' : 's') + ' visible' + (visible.length === 1 ? '' : 's');
  $('btnOrganizeApps').textContent = organizingApps ? 'Terminer' : 'Organiser';
  $('btnOrganizeApps').setAttribute('aria-pressed', String(organizingApps));
  const list = organizingApps ? drawerApps : visible;
  if (!list.length) {
    const empty = document.createElement('p');
    empty.className = 'appsEmpty';
    empty.textContent = drawerApps.length ? 'Vos applications sont masquées. Touchez Organiser pour les retrouver.' : 'Aucune application disponible.';
    box.appendChild(empty);
  }
  for (const app of list) {
    const row = document.createElement('div');
    row.className = 'app' + (hiddenApps.has(app.pkg) ? ' hiddenApp' : '');
    const launch = document.createElement('button');
    launch.className = 'appLaunch';
    launch.disabled = organizingApps;
    const img = document.createElement('img');
    img.src = app.icon; img.alt = '';
    const name = document.createElement('span');
    name.className = 'appName'; name.textContent = app.label;
    launch.append(img, name);
    launch.addEventListener('click', () => {
      if (native) native.launch(app.pkg);
      $('drawer').classList.remove('open');
    });
    row.appendChild(launch);
    if (organizingApps) {
      const toggle = document.createElement('button');
      toggle.className = 'appVisibility';
      const update = () => {
        const hidden = hiddenApps.has(app.pkg);
        row.classList.toggle('hiddenApp', hidden);
        toggle.textContent = hidden ? 'Afficher' : 'Masquer';
        toggle.setAttribute('aria-label', toggle.textContent + ' ' + app.label);
      };
      update();
      toggle.addEventListener('click', () => {
        const next = new Set(hiddenApps);
        if (next.has(app.pkg)) next.delete(app.pkg); else next.add(app.pkg);
        try { localStorage.setItem('c3.hiddenApps', JSON.stringify([...next])); }
        catch (_) { flash('Impossible de mémoriser ce choix'); return; }
        hiddenApps = next;
        update();
      });
      row.appendChild(toggle);
    }
    box.appendChild(row);
  }
}

function openDrawer() {
  try {
    const list = native ? JSON.parse(native.apps()) : SAMPLE_APPS.map(([label, color]) => ({ pkg: 'demo.' + label, label, icon: letterIcon(label, color) }));
    drawerApps = [...new Map(list.map(app => [app.pkg, app])).values()];
  } catch (_) { flash('Impossible de charger les applications'); return; }
  organizingApps = false;
  renderApps();
  $('apps').scrollTop = 0;
  $('drawer').classList.add('open');
}
$('btnOrganizeApps').addEventListener('click', () => {
  organizingApps = !organizingApps;
  renderApps();
});

const openCarplay = () => { if (native) native.openCarplay(); else flash('Sur l’autoradio, ce bouton ouvre ZLink'); };
$('btnCarplay').addEventListener('click', openCarplay);
$('cardPlay').addEventListener('click', openCarplay);
$('btnHome').addEventListener('click', () => {
  $('drawer').classList.remove('open');
  manualView = null; focusView = null; touchedUntil = 0;
});
$('btnApps').addEventListener('click', () => {
  if ($('drawer').classList.contains('open')) $('drawer').classList.remove('open');
  else openDrawer();
});
document.addEventListener('click', e => {
  if (!$('drawer').contains(e.target) && !$('btnApps').contains(e.target)) $('drawer').classList.remove('open');
});
$('btnCloseApps').addEventListener('click', () => $('drawer').classList.remove('open'));
$('btnVehicleSettings').addEventListener('click', () => {
  if (native) native.launch('com.qf.carsettings');
  else flash('Sur l’autoradio, ce bouton ouvre les réglages de la voiture');
});
$('btnView').addEventListener('click', nextView);

function flash(text) {
  const el = document.createElement('div');
  el.className = 'alert';
  el.style.cssText = 'position:fixed;left:50%;top:10vh;transform:translateX(-50%);z-index:40';
  el.textContent = text;
  document.body.appendChild(el);
  setTimeout(() => el.remove(), 2200);
}

// ---------------------------------------------------------------------------
// Demo: a scripted drive, for the emulator and for checking the screen.
// Long press on the speed (2 s) toggles it on the head unit.
// ---------------------------------------------------------------------------
const W_BULB = { proto: 'psa', code: 0x009A, detail: 0 };      // dipped beam bulb
const W_TYRE = { proto: 'psa', code: 0x008D, detail: 0 };      // tyre pressure low
const W_FUEL = { proto: 'psa', code: 0x00E0, detail: 0 };      // fuel low
const DEMO = [
  { doors: { FL: true }, lights: { beam: 'off' }, speed: 0, handbrake: true, warnings: [], sources: { car: true, gps: true },
    trip: { odometer: 128452, range: 486, instant: 0, outsideTemp: 11, trip1: { km: 1284.6, avgKmh: 41, avgL100: 5.9 } } },
  { doors: { FL: false }, lights: { beam: 'low' }, warnings: [W_BULB] },
  { handbrake: false, speed: 18, beltDriver: true },
  { speed: 34, trip: { instant: 7.4, range: 484 } },
  { beltDriver: false, speed: 52, lights: { turnLeft: true }, steer: 18 },
  { lights: { turnLeft: false }, steer: 0, speed: 70, warnings: [W_BULB, W_TYRE] },
  { speed: 82, trip: { instant: 5.2, range: 479, trip1: { km: 1291.2, avgL100: 5.8 } } },
  { lights: { beam: 'high' }, speed: 88 },
  { lights: { beam: 'low', fogFront: true }, speed: 60, warnings: [W_BULB, W_TYRE, W_FUEL] },
  { lights: { fogFront: false, brake: true }, speed: 20 },
  { lights: { brake: false, reverse: true }, speed: 4, steer: -20 },
  { lights: { reverse: false, beam: 'off' }, speed: 0, steer: 0, handbrake: true, hatch: true },
  { hatch: false, warnings: [W_BULB] },
];
let demoTimer = null;
let demoStep = 0;
function setDemo(on) {
  clearInterval(demoTimer);
  demoTimer = null;
  $('demoTag').style.display = on ? 'block' : 'none';
  if (!on) { if (native && native.ready) native.ready(); return; }   // back to the car's own state
  demoStep = 0;
  const step = () => { apply(DEMO[demoStep]); demoStep = (demoStep + 1) % DEMO.length; };
  step();
  demoTimer = setInterval(step, 4000);
}
let pressTimer = null;
$('speed').addEventListener('touchstart', () => { pressTimer = setTimeout(() => setDemo(!demoTimer), 2000); });
$('speed').addEventListener('touchend', () => clearTimeout(pressTimer));
if (new URLSearchParams(location.search).has('demo')) setDemo(true);

// ---------------------------------------------------------------------------
// Frame loop
// ---------------------------------------------------------------------------
const ease = (t) => t * t * (3 - 2 * t);
const clock = new THREE.Clock();
let blinkT = 0;
let shownSpeed = 0;
const setLamp = (name, intensity) => {
  const material = lampMats[name];
  if (!material) return;
  material.emissiveIntensity = intensity;
  // Keep the lens visible when off, and make its emitted light readable when on.
  material.opacity = intensity > 0
    ? Math.max(material.userData.offOpacity, intensity < 1 ? 0.45 : 0.95)
    : material.userData.offOpacity;
};

function animate() {
  requestAnimationFrame(animate);
  const dt = Math.min(clock.getDelta(), 0.05);
  const k = Math.min(1, dt * 7);

  for (const [name, o] of Object.entries(parts.hinges)) {
    const target = name === 'Hatch' ? state.hatch : state.doors[name.slice(5)];
    anim.hinge[name] += ((target ? 1 : 0) - anim.hinge[name]) * k;
    o.rotation[HINGES[name].axis] = HINGES[name].open * ease(Math.max(0, Math.min(1, anim.hinge[name])));
  }
  for (const [side, w] of Object.entries(parts.windows)) {
    anim.window[side] += (state.windows[side] - anim.window[side]) * Math.min(1, dt * 2.5);
    w.obj.position.y = w.y0 - WINDOW_TRAVEL[side] * anim.window[side];
  }
  shownSpeed += (state.speed - shownSpeed) * Math.min(1, dt * 3);
  const omega = (shownSpeed / 3.6) / WHEEL_RADIUS * (state.lights.reverse ? -1 : 1);
  const steer = THREE.MathUtils.degToRad(state.steer);
  for (const [side, w] of Object.entries(parts.wheels)) {
    w.rotation.x += omega * dt;
    if (side[0] === 'F') w.rotation.y += (steer - w.rotation.y) * k;
  }

  const L = state.lights;
  blinkT = (blinkT + dt) % 0.75;
  const blinkOn = L.turnPhase || blinkT < 0.4;
  const left = (L.turnLeft || L.hazard) && blinkOn;
  const right = (L.turnRight || L.hazard) && blinkOn;
  setLamp('Headlight', L.lowBeam == null
    ? ({ off: 0, position: 0.6, low: 5, high: 5 }[L.beam] || 0)
    : L.lowBeam ? 5 : L.position ? 0.6 : 0);
  setLamp('HighBeam', L.beam === 'high' ? 7 : 0);
  setLamp('FogLight', L.fogFront ? 5 : 0);
  setLamp('RearFog', L.fogRear ? 2.4 : 0);
  setLamp('Reverse', L.reverse ? 4 : 0);
  setLamp('BrakeLight', L.brake ? 2.6 : 0);
  setLamp('Taillight', L.brake ? 2.6 : ((L.position ?? (L.beam !== 'off')) ? 0.8 : 0));
  setLamp('Turn_L', left ? 1.2 : 0);
  setLamp('Turn_R', right ? 1.2 : 0);

  // a lamp the car reports as faulty pulses red; the warning markers breathe
  const t = performance.now();
  const pulse = 0.5 + 0.5 * Math.sin(t / 260);
  for (const [name, m] of Object.entries(lampMats)) {
    const base = lampColors.get(m);
    if (!base) continue;
    if (alertLamps.has(name)) {
      m.emissive.copy(LAMP_ALERT);
      m.emissiveIntensity = Math.max(m.emissiveIntensity, 0.4 + 2.2 * pulse);
    } else {
      m.emissive.copy(base);
    }
  }
  for (const s of markers.children) s.scale.setScalar(0.3 * (1 + 0.25 * Math.sin(t / 300 + s.userData.phase)));
  $('turnL').classList.toggle('on', left);
  $('turnR').classList.toggle('on', right);

  countDistance(dt);
  floorRings.rotation.z += dt * 0.04;
  if (model && performance.now() > touchedUntil) placeCamera(autoView(), Math.min(1, dt * 2.2));
  controls.update();
  renderer.render(scene, camera);
  placeCallouts();
}
animate();

// in the side panel a tap anywhere goes back to the dashboard
if (SIDE) document.addEventListener('click', () => { if (native && native.home) native.home(); });

$('btnSignals').addEventListener('click', () => { if (window.Android?.diagnostics) Android.diagnostics(); });
