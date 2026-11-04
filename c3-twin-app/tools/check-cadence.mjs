// Live regression check. Node 22+, adb, C3 Twin open with its model loaded.
// Usage: node tools/check-cadence.mjs <adb-serial>
import { execFileSync } from 'node:child_process';
const serial = process.argv[2];
if (!serial) throw new Error('Provide the autoradio adb serial');
const adb = (...args) => execFileSync('adb', ['-s', serial, ...args], { encoding: 'utf8' }).trim();
const pid = adb('shell', 'pidof', 'fr.dabi.c3twin');
const port = adb('forward', 'tcp:0', 'localabstract:webview_devtools_remote_' + pid);
try {
  const pages = await (await fetch('http://127.0.0.1:' + port + '/json')).json();
  const page = pages.find(p => p.url.startsWith('https://c3twin.local/index.html'));
  if (!page) throw new Error('Open the main dashboard first');
  const result = await new Promise((resolve, reject) => {
    const socket = new WebSocket(page.webSocketDebuggerUrl);
    const timer = setTimeout(() => { socket.close(); reject(new Error('Measurement timed out')); }, 30000);
    socket.onerror = reject;
    socket.onopen = () => socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: {
      expression: `new Promise(resolve => {
        if (!document.getElementById('loader').classList.contains('hidden')) {
          resolve({error: 'Model still loading'}); return;
        }
        let samples = [], last = performance.now();
        function frame(t) {
          samples.push(t - last); last = t;
          if (samples.length < 41) { requestAnimationFrame(frame); return; }
          samples.shift(); samples.sort((a,b) => a-b);
          resolve({medianMs: samples[20], p95Ms: samples[38]});
        }
        requestAnimationFrame(frame);
      })`, awaitPromise: true, returnByValue: true
    }}));
    socket.onmessage = event => {
      const response = JSON.parse(event.data);
      if (response.id !== 1) return;
      clearTimeout(timer); socket.close();
      resolve(response.result?.result?.value);
    };
  });
  console.log(JSON.stringify(result));
  if (!result || result.error || result.medianMs > 50 || result.p95Ms > 100) process.exitCode = 1;
} finally { adb('forward', '--remove', 'tcp:' + port); }
