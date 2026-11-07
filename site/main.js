(() => {
const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const lerp = (a, b, t) => a + (b - a) * t, clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const reduce = matchMedia("(prefers-reduced-motion: reduce)").matches;
addEventListener("load", () => document.body.classList.add("loaded"));
setTimeout(() => document.body.classList.add("loaded"), 600);

/* ───────── reveal on scroll ───────── */
const io = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) { e.target.classList.add("in"); io.unobserve(e.target); } }), { threshold: 0.18 });
$$(".reveal").forEach((el, i) => { el.style.transitionDelay = (i % 3) * 80 + "ms"; io.observe(el); });

/* ───────── HERO: a sound field. Ridgelines that rise with the room's sound; a scan line sweeps through in orange ───────── */
const fc = $("#field"), fx = fc.getContext("2d"), hero = $(".hero");
const ROWS = 58, PTS = 220, PAPER = "#ecebe5", INK = "18,18,17", ORANGE = "#ff5a1f";
let FW = 0, FH = 0, dpr = 1, ptr = { x: -9, y: -9, on: 0 }, energy = 0.2, tgt = 0.2, pulses = [], heroOn = true;
function sizeField() { const r = fc.getBoundingClientRect(); dpr = Math.min(devicePixelRatio, 2); FW = r.width; FH = r.height; fc.width = FW * dpr; fc.height = FH * dpr; fx.setTransform(dpr, 0, 0, dpr, 0, 0); }
sizeField(); addEventListener("resize", sizeField);
hero.addEventListener("pointermove", (e) => { const r = fc.getBoundingClientRect(); ptr.x = (e.clientX - r.left) / r.width; ptr.y = (e.clientY - r.top) / r.height; ptr.on = 1; });
hero.addEventListener("pointerleave", () => (ptr.on = 0));
new IntersectionObserver((e) => (heroOn = e[0].isIntersecting)).observe(hero);
const hash = (n) => { const s = Math.sin(n * 127.1) * 43758.5453; return s - Math.floor(s); };
function ridge(u, d, t) {                                     // u: 0..1 across, d: 0 far .. 1 near; returns height in 0..1 units
  const c = 1 - Math.abs(u - 0.5) * 2, env = Math.pow(Math.max(0, c), 1.35);                 // centre-weighted envelope
  let h = 0.16 * Math.sin(u * 5.2 + t * 0.35 + d * 2.3) + 0.1 * Math.sin(u * 11 - t * 0.5 + d * 4.1) + 0.06 * Math.sin(u * 23 + t * 0.9 - d * 6);
  h = h * (0.35 + env) + 0.2 * env * energy * (hash(Math.floor(u * 120) + Math.floor(d * ROWS) * 13.7 + Math.floor(t * 9)) - 0.35);
  for (const p of pulses) { const dist = Math.hypot((u - p.x) * 1.6, (d - p.d) * 1.1), w = 0.12; h += p.a * Math.exp(-Math.pow((dist - p.v * (t - p.t0)) / w, 2)) * Math.max(0, 1 - (t - p.t0) / 4); }
  if (ptr.on) { const dx = (u - ptr.x) * 1.5, dd = (d - (0.2 + ptr.y * 0.8)) * 1.2; h += 0.42 * Math.exp(-(dx * dx + dd * dd) / 0.012); }
  return h;
}
function drawField(ts) {
  requestAnimationFrame(drawField);
  if (!heroOn) return;
  const t = ts / 1000, sy = Math.min(1, Math.max(0, scrollY / innerHeight));
  if (t > (drawField.next || 0)) { tgt = Math.random() < 0.4 ? 0.12 : 0.55 + Math.random() * 0.45; drawField.next = t + 0.35 + Math.random() * 0.9; }
  energy = lerp(energy, reduce ? 0.2 : tgt, 0.08);
  if (t > (drawField.pulse || 0)) { pulses.push({ x: 0.3 + Math.random() * 0.4, d: 0.3 + Math.random() * 0.6, v: 0.18 + Math.random() * 0.1, a: 0.5 + Math.random() * 0.4, t0: t }); drawField.pulse = t + 2.2 + Math.random() * 2; }
  pulses = pulses.filter((p) => t - p.t0 < 4);
  ptr.on *= 0.995;
  fx.clearRect(0, 0, FW, FH);
  const top = FH * 0.42, bot = FH * 0.98, amp = FH * 0.12 * (1 - sy * 0.7), scan = Math.floor((t * 6) % (ROWS + 30));
  for (let r = 0; r < ROWS; r++) {                           // far to near (painter's algorithm hides lines behind ridges)
    const d = r / (ROWS - 1), y0 = top + Math.pow(d, 1.9) * (bot - top), a = lerp(0.2, 1, Math.pow(d, 0.8)), sc = lerp(0.35, 1, d);
    fx.beginPath();
    for (let i = 0; i <= PTS; i++) { const u = i / PTS, x = (u - 0.5) * FW * lerp(0.82, 1.18, d) + FW / 2, y = y0 - ridge(u, d, t) * amp * sc * 2.4; i ? fx.lineTo(x, y) : fx.moveTo(x, y); }
    fx.lineTo(FW * 1.2, FH + 4); fx.lineTo(-FW * 0.2, FH + 4); fx.closePath(); fx.fillStyle = PAPER; fx.fill();
    const hot = Math.abs(r - scan) <= 0 && scan < ROWS;
    fx.strokeStyle = hot ? ORANGE : `rgba(${INK},${a.toFixed(2)})`; fx.lineWidth = hot ? 2 : lerp(0.7, 1.5, d); fx.stroke();
  }
}
requestAnimationFrame(drawField);

/* ───────── ACTS: scroll drives a flat schematic of the room ───────── */
const svg = $("#stage"), door = $("#s-door"), led = $("#s-led"), glow = $("#s-glow"), needle = $("#s-needle"), hp = $("#s-hp"), rip = $("#s-rip");
const cmds = $$("#cmds p"), cap = $("#cap");
(function ticks() { const g = $("#s-ticks"); for (let i = 0; i <= 40; i++) { const a = (-120 + (i / 40) * 240) * Math.PI / 180, major = i % 5 === 0, r1 = 56, r2 = major ? 46 : 51; const l = document.createElementNS("http://www.w3.org/2000/svg", "line"); l.setAttribute("x1", Math.sin(a) * r1); l.setAttribute("y1", -Math.cos(a) * r1); l.setAttribute("x2", Math.sin(a) * r2); l.setAttribute("y2", -Math.cos(a) * r2); l.setAttribute("class", i >= 36 ? "tk hot" : "tk"); g.append(l); } })();
const CAPS = ["The servo swings the door shut.", "One beep from the buzzer.", "The servo opens it again.", "SOS in Morse, on the light and the buzzer.", "Out of range: the needle swings, the door opens, your phone buzzes."];
let cur = -2, sosRun = 0;
function ripple() { rip.classList.remove("go"); void rip.getBoundingClientRect(); rip.classList.add("go"); }
async function morse() { const id = ++sosRun, u = [1, 1, 1, 3, 3, 3, 1, 1, 1]; for (const n of u) { if (id !== sosRun) return; led.classList.add("on"); glow.classList.add("on"); ripple(); await sleep(n * 170); led.classList.remove("on"); glow.classList.remove("on"); await sleep(130); } }
function setNeedle(deg) { needle.style.transform = `rotate(${deg}deg)`; }
function step(i) {
  if (i === cur) return; cur = i; sosRun++; led.classList.remove("on"); glow.classList.remove("on");
  cmds.forEach((c, k) => c.classList.toggle("on", k === i)); cap.textContent = i < 0 ? "A servo, an LED and a buzzer on an ESP8266." : CAPS[i];
  svg.classList.toggle("open", i >= 2); hp.classList.toggle("away", i === 4);
  setNeedle(i === 4 ? 110 : -70);
  if (i === 1) ripple();
  if (i === 3) morse();
  if (i === 4) { led.classList.add("on"); glow.classList.add("on"); setTimeout(() => cur === 4 && (led.classList.remove("on"), glow.classList.remove("on")), 700); }
}
const acts = $("#acts");
function actsScroll() { const r = acts.getBoundingClientRect(), total = r.height - innerHeight, p = clamp(-r.top / total, 0, 1); step(r.top > 0 ? -1 : Math.min(4, Math.floor(p * 5.2))); }
addEventListener("scroll", actsScroll, { passive: true }); actsScroll();
setNeedle(-70);
(function idle() { if (cur < 0) { const t = performance.now() / 1000; needle.style.transform = `rotate(${-70 + Math.sin(t * 0.9) * 5}deg)`; } requestAnimationFrame(idle); })();

/* ───────── listens: thin waveform + live words ───────── */
const wave = $("#wave"), wc = wave.getContext("2d"); let voice = 0.15, inView = false;
function sizeWave() { const r = wave.getBoundingClientRect(), d = Math.min(devicePixelRatio, 2); wave.width = r.width * d; wave.height = r.height * d; wc.setTransform(d, 0, 0, d, 0, 0); }
sizeWave(); addEventListener("resize", sizeWave);
(function drawWave() {
  const w = wave.clientWidth, h = wave.clientHeight, t = performance.now() / 1000; wc.clearRect(0, 0, w, h);
  wc.strokeStyle = "#eceae3"; wc.lineWidth = 1.2; wc.beginPath();
  for (let x = 0; x <= w; x += 2) { const u = x / w, env = Math.sin(u * Math.PI) ** 1.2; const y = h / 2 + (Math.sin(u * 22 + t * 3) * 0.5 + Math.sin(u * 61 - t * 5.1) * 0.35 + Math.sin(u * 9 + t * 1.3) * 0.4) * voice * h * 0.42 * env; x ? wc.lineTo(x, y) : wc.moveTo(x, y); }
  wc.stroke(); wc.fillStyle = ORANGE; wc.fillRect(((t * 0.12) % 1) * w - 1, 0, 2, h);
  requestAnimationFrame(drawWave);
})();
const phrases = ["Hey Atlas, I’m going into a meeting, summarize it after.", "so we ship the demo on Friday and Priya finishes the dashboard", "Hey Atlas, what did we decide about the storage quota?", "Atlas, SOS."];
new IntersectionObserver((e) => (inView = e[0].isIntersecting), { threshold: 0.2 }).observe($("#listens"));
(async function live() {
  const el = $("#live-text"); let i = 0;
  while (true) {
    if (!inView) { voice = 0.12; await sleep(300); continue; }
    const words = phrases[i++ % phrases.length].split(" ");
    for (let k = 0; k < words.length && inView; k++) { el.textContent = words.slice(0, k + 1).join(" "); const c = document.createElement("span"); c.className = "caret"; el.append(c); voice = 0.8 + Math.random() * 0.4; await sleep(170 + Math.random() * 110); voice = 0.25; }
    voice = 0.12; await sleep(1700);
  }
})();

/* ───────── remembers / knows loops ───────── */
const eg = $("#eg-sum"), egA = "“…Priya will finish the dashboard by Friday… Rohan needs extension cords…”", egB = "Priya: dashboard by Friday. Rohan: extension cords.";
let egi = 0; eg.textContent = egA; setInterval(() => { eg.style.opacity = 0; setTimeout(() => { eg.textContent = egi++ % 2 ? egA : egB; eg.style.opacity = 1; }, 450); }, 3600);
const dot = $("#dot"), trk = $("#trk"); let away = false;
setInterval(() => { away = !away; dot.style.left = away ? "88%" : "6%"; setTimeout(() => { dot.classList.toggle("out", away); trk.textContent = away ? "out of range · door opens, phone buzzes" : "in range · 1.2 m"; }, away ? 1000 : 300); }, 3800);

/* ───────── 0G kill switch ───────── */
const kill = $("#kill"), stop = $(".stop"), ks = $("#kstate");
kill.addEventListener("click", () => {
  const down = kill.getAttribute("aria-pressed") !== "true"; kill.setAttribute("aria-pressed", down); stop.classList.toggle("down", down);
  ks.textContent = down ? "link cut · safe-stop: servo released, light and buzzer off, one chirp, nothing guessed" : "link up · decisions running";
});
})();
