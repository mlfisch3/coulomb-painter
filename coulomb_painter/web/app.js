// Coulomb Painter front-end. Kept intentionally small: one file, no build.
//
// The canvas <img> is refreshed by URL-with-timestamp polling; painting is
// driven by pointer events converted into lattice coordinates and posted to
// /api/paint. Controls are two-way bound to server params so a slider in the
// tab panel and the segmented sign toggle at the bottom refer to the same
// state.

const $ = (q, root=document) => root.querySelector(q);
const $$ = (q, root=document) => Array.from(root.querySelectorAll(q));

const state = {
  params: null,
  stats: null,
  lattice: [256, 256],
  painting: false,
  packet: [],
  paintTimer: null,
};

// ------- server IO -------
async function api(path, opts) {
  const r = await fetch(path, opts);
  if (!r.ok) throw new Error(`${path}: ${r.status}`);
  return r.json();
}

async function refreshState() {
  const s = await api('/api/state');
  state.params = s.params;
  state.stats = s.stats;
  state.lattice = s.stats.lattice;
  renderStatus();
  syncControls();
}

async function pushParams(patch) {
  const s = await api('/api/params', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(patch),
  });
  Object.assign(state.params, s.applied);
  return s;
}

// ------- status -------
function renderStatus() {
  const s = state.stats; if (!s) return;
  const parts = [
    `${s.lattice[0]}×${s.lattice[1]}`,
    `n=${s.particles}`,
    `T=${Math.round(s.temperature)} K`,
    `${(s.moves_per_s/1e3).toFixed(1)} kmoves/s`,
    s.frozen ? 'FROZEN' : (s.paused ? 'PAUSED' : ''),
  ];
  $('#status').textContent = parts.filter(Boolean).join('  ·  ');
}

// ------- controls: sync UI to server -------
function syncControls() {
  const p = state.params; if (!p) return;
  // sign
  toggleSeg($('#sign-plus'), $('#sign-minus'), p.brush_sign >= 0);
  // target
  toggleSeg($('#target-fixed'), $('#target-mobile'), p.brush_target !== 'mobile');
  // thickness
  $('#thickness').value = p.brush_thickness;
  // freeze/pause/live
  $('#freeze-btn').classList.toggle('on', !!p.frozen);
  $('#freeze-btn').textContent = p.frozen ? 'Run' : 'Freeze';
  $('#pause-btn').classList.toggle('on', !!p.paused);
  $('#pause-btn').textContent = p.paused ? 'Resume' : 'Pause';
  $('#live-btn').classList.toggle('on', !!p.live_view);
  // panel sliders
  for (const el of $$('.pctrl')) {
    const k = el.dataset.key;
    if (k in p) {
      el.value = p[k];
      const out = el.parentElement.querySelector('output');
      if (out) out.textContent = fmt(p[k]);
    }
  }
  // segmented pairs (periodic, cooling)
  for (const seg of $$('.field .seg')) {
    const btns = $$('.seg-btn', seg);
    const key = btns[0]?.dataset.key;
    if (!key) continue;
    const cur = p[key];
    for (const b of btns) {
      const on = Number(b.dataset.val) === (cur ? 1 : 0);
      b.classList.toggle('on', on);
    }
  }
}
function toggleSeg(a, b, aOn) {
  a.classList.toggle('on', aOn);
  b.classList.toggle('on', !aOn);
}
function fmt(v) {
  if (typeof v === 'boolean') return v ? 'on' : 'off';
  if (Number.isInteger(v)) return String(v);
  if (typeof v === 'number') return Math.abs(v) < 10 ? v.toFixed(2) : v.toFixed(0);
  return String(v);
}

// ------- frame loop -------
const FRAME_MS = 100;
let framePending = false;
function tickFrame() {
  if (framePending) return;
  const p = state.params; if (!p || !p.live_view) return;
  framePending = true;
  const im = new Image();
  im.onload = () => { $('#frame').src = im.src; framePending = false; };
  im.onerror = () => { framePending = false; };
  im.src = `/api/frame.png?t=${Date.now()}`;
}
setInterval(tickFrame, FRAME_MS);
setInterval(() => api('/api/state').then(s => {
  state.stats = s.stats; renderStatus();
}).catch(()=>{}), 500);

// ------- pointer painting -------
function frameToLattice(ev) {
  const img = $('#frame');
  const r = img.getBoundingClientRect();
  const x = (ev.clientX - r.left) / r.width * state.lattice[1];
  const y = (ev.clientY - r.top)  / r.height * state.lattice[0];
  return [x, y];
}
function flushPacket(last=false) {
  if (state.packet.length === 0 && !last) return;
  const body = JSON.stringify({
    points: state.packet,
    first: !state.sentFirst,
    last,
  });
  state.packet = [];
  state.sentFirst = true;
  fetch('/api/paint', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body,
  }).then(r => r.json()).then(j => {
    if (j.stats) { state.stats = j.stats; renderStatus(); }
  }).catch(()=>{});
}
function onPointerDown(ev) {
  if (ev.button !== undefined && ev.button !== 0) return;
  ev.preventDefault();
  state.painting = true;
  state.packet = [frameToLattice(ev)];
  state.sentFirst = false;
  flushPacket(false);
  $('#frame').setPointerCapture?.(ev.pointerId);
  // send packets on a timer so a fast drag does not spam the network
  clearInterval(state.paintTimer);
  state.paintTimer = setInterval(() => flushPacket(false), 40);
}
function onPointerMove(ev) {
  if (!state.painting) return;
  ev.preventDefault();
  state.packet.push(frameToLattice(ev));
}
function onPointerUp(ev) {
  if (!state.painting) return;
  ev.preventDefault();
  clearInterval(state.paintTimer);
  state.paintTimer = null;
  state.painting = false;
  flushPacket(true);
}
const imgEl = $('#frame');
imgEl.addEventListener('pointerdown', onPointerDown);
imgEl.addEventListener('pointermove', onPointerMove);
imgEl.addEventListener('pointerup', onPointerUp);
imgEl.addEventListener('pointercancel', onPointerUp);
imgEl.addEventListener('contextmenu', e => e.preventDefault());

// ------- top bar buttons -------
$('#pause-btn').onclick = () =>
  pushParams({ paused: !state.params.paused }).then(syncControls);
$('#freeze-btn').onclick = () =>
  pushParams({ frozen: !state.params.frozen }).then(syncControls);
$('#live-btn').onclick = () =>
  pushParams({ live_view: !state.params.live_view }).then(syncControls);

// ------- bottom bar buttons -------
$('#sign-plus').onclick = () =>
  pushParams({ brush_sign: 1 }).then(syncControls);
$('#sign-minus').onclick = () =>
  pushParams({ brush_sign: -1 }).then(syncControls);
$('#target-fixed').onclick = () =>
  pushParams({ brush_target: 'fixed' }).then(syncControls);
$('#target-mobile').onclick = () =>
  pushParams({ brush_target: 'mobile' }).then(syncControls);
$('#thickness').oninput = e =>
  pushParams({ brush_thickness: Number(e.target.value) });

// ------- menu / popover -------
function togglePanel(el) {
  const opening = el.classList.contains('hidden');
  // close everything, then open this one
  $$('.popover, .panel').forEach(p => p.classList.add('hidden'));
  if (opening) el.classList.remove('hidden');
}
$('#menu-btn').onclick = () => togglePanel($('#menu-panel'));
$('#panel-btn').onclick = () => togglePanel($('#panel'));
$('#panel-close').onclick = () => $('#panel').classList.add('hidden');
$('#mn-reset').onclick = async () => {
  $('#menu-panel').classList.add('hidden');
  await api('/api/reset', { method: 'POST' });
  await refreshState();
};
$('#mn-new').onclick = () => {
  $('#menu-panel').classList.add('hidden');
  openNewDialog();
};

// ------- controls panel -------
$$('.tab').forEach(t => t.onclick = () => {
  $$('.tab').forEach(x => x.classList.toggle('on', x === t));
  const key = t.dataset.tab;
  $$('.tabpane').forEach(p => p.classList.toggle('on', p.dataset.panel === key));
});
$$('.pctrl').forEach(el => {
  const upd = () => {
    const out = el.parentElement.querySelector('output');
    if (out) out.textContent = fmt(Number(el.value));
  };
  el.addEventListener('input', upd);
  el.addEventListener('change', () => {
    const patch = {};
    const v = Number(el.value);
    patch[el.dataset.key] = v;
    pushParams(patch);
  });
});
// segmented pairs inside the panel (periodic, cooling)
$$('.field .seg .seg-btn').forEach(b => {
  b.addEventListener('click', () => {
    const key = b.dataset.key; if (!key) return;
    const val = Number(b.dataset.val) === 1;
    const patch = {}; patch[key] = val;
    pushParams(patch).then(syncControls);
  });
});

// ------- New Canvas dialog -------
function openNewDialog() {
  const dlg = $('#dlg-new');
  dlg.classList.remove('hidden');
  // default to current
  const p = state.params;
  if (p) {
    for (const b of $$('#dlg-new [data-res]')) {
      b.classList.toggle('on', Number(b.dataset.res) === p.resolution);
    }
    $('#new-fill').value = p.fill;
    $('#new-fill-out').textContent = Number(p.fill).toFixed(2);
  }
}
$$('#dlg-new [data-res]').forEach(b => b.onclick = () => {
  $$('#dlg-new [data-res]').forEach(x => x.classList.toggle('on', x === b));
});
$('#new-fill').oninput = e => {
  $('#new-fill-out').textContent = Number(e.target.value).toFixed(2);
};
$('#new-cancel').onclick = () => $('#dlg-new').classList.add('hidden');
$('#new-ok').onclick = async () => {
  const res = Number($$('#dlg-new [data-res].on')[0]?.dataset.res || 256);
  const fill = Number($('#new-fill').value);
  await api('/api/new_canvas', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({
      resolution: res, fill,
      // carry the current physics forward so a New Canvas doesn't reset
      // the artist's tuning
      strength: state.params?.strength, screening: state.params?.screening,
      cutoff: state.params?.cutoff, periodic: state.params?.periodic,
      attract_depth: state.params?.attract_depth,
      attract_range: state.params?.attract_range,
      temperature: state.params?.temperature, cooling: state.params?.cooling,
    }),
  });
  $('#dlg-new').classList.add('hidden');
  await refreshState();
};

// close popovers on outside click
document.addEventListener('pointerdown', ev => {
  const inMenu = ev.target.closest('#menu-panel, #menu-btn');
  const inPanel = ev.target.closest('#panel, #panel-btn');
  if (!inMenu) $('#menu-panel').classList.add('hidden');
  if (!inPanel) $('#panel').classList.add('hidden');
}, true);

// initial load
refreshState().then(() => {
  // if canvas is at defaults and looks empty, show new-canvas dialog on first
  // load so the user immediately picks resolution + fill
  if (state.stats && state.stats.iteration === 0) openNewDialog();
});
