// Coulomb Painter front-end. One file, no build.
//
// The client mirrors the reference `anneal_gui.py` frontend: every knob has a
// two-way binding to server params, changes commit with an ack flash, and
// build-only fields go through /api/init.  Mobile-first layout: no page
// scroll, canvas anchors the view, controls that don't fit live behind a
// tabbed Controls popup or a menu popover.

const $  = (q, root=document) => root.querySelector(q);
const $$ = (q, root=document) => Array.from(root.querySelectorAll(q));

// ---------- state ----------
const state = {
  params: null,
  stats: null,
  lattice: [512, 512],
  // brush
  painting: false,
  paintMode: false,
  packet: [],
  paintTimer: null,
  sentFirst: false,
  // lens
  lensOn: false, lensSpan: 160,
  lensPinned: false, lensAt: null, lensBusy: false, lensPend: null,
};

// ---------- HTTP helpers ----------
async function api(path, opts) {
  const r = await fetch(path, opts);
  if (!r.ok) throw new Error(`${path}: ${r.status}`);
  return r.json();
}
async function post(url, body) {
  return api(url, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body || {}),
  });
}
async function refreshState() {
  try {
    const s = await api('/api/state');
    state.params = s.params;
    state.stats = s.stats;
    state.lattice = s.stats.lattice;
    renderStatus();
    syncControls();
  } catch (e) { /* the periodic loop will keep trying */ }
}
async function pushParams(patch) {
  const s = await post('/api/update', patch);
  Object.assign(state.params, s.applied || {});
  return s;
}

// ---------- status ----------
function renderStatus() {
  const s = state.stats; if (!s) return;
  const parts = [
    `${s.lattice[0]}×${s.lattice[1]}`,
    `n=${s.particles}`,
    `T=${Math.round(s.temperature)}K`,
    `${(s.moves_per_s/1e3).toFixed(1)}k moves/s`,
    `iter=${s.iteration}`,
    s.frozen ? 'FROZEN' : (s.paused ? 'HELD' : 'RUN'),
  ];
  $('#status').textContent = parts.join('  ·  ');
}

// ---------- ack flash ----------
function ack(el, ok) {
  if (!el) return;
  el.classList.remove('ack-ok', 'ack-bad');
  void el.offsetWidth;
  el.classList.add(ok ? 'ack-ok' : 'ack-bad');
  setTimeout(() => el.classList.remove('ack-ok', 'ack-bad'), 800);
}

// ---------- controls: build panel widgets ----------
function buildFieldWidgets() {
  // .field-num - a numeric input bound to a param key
  $$('.field-num').forEach(node => {
    if (node.dataset.built) return;
    node.dataset.built = '1';
    const inp = document.createElement('input');
    inp.type = 'number';
    if (node.dataset.min !== undefined) inp.min = node.dataset.min;
    if (node.dataset.max !== undefined) inp.max = node.dataset.max;
    if (node.dataset.step !== undefined) inp.step = node.dataset.step;
    node.appendChild(inp);
    const commit = () => {
      const v = parseFloat(inp.value);
      if (isNaN(v)) return ack(inp, false);
      if (node.dataset.local) {
        applyLocal(node.dataset.key, v);
        ack(inp, true);
        return;
      }
      const patch = {}; patch[node.dataset.key] = v;
      pushParams(patch).then(() => ack(inp, true)).catch(() => ack(inp, false));
    };
    inp.addEventListener('change', commit);
    inp.addEventListener('keydown', ev => {
      if (ev.key === 'Enter') { ev.preventDefault(); commit(); }
    });
  });
  // .field-bool - a checkbox
  $$('.field-bool').forEach(node => {
    if (node.dataset.built) return;
    node.dataset.built = '1';
    const inp = document.createElement('input');
    inp.type = 'checkbox';
    node.appendChild(inp);
    inp.addEventListener('change', () => {
      if (node.dataset.local) { applyLocal(node.dataset.key, inp.checked); return; }
      const patch = {}; patch[node.dataset.key] = inp.checked;
      pushParams(patch).then(() => ack(inp, true)).catch(() => ack(inp, false));
    });
  });
  // .field-sel - a select
  $$('.field-sel').forEach(node => {
    if (node.dataset.built) return;
    node.dataset.built = '1';
    const sel = document.createElement('select');
    node.appendChild(sel);
    setSelectOptions(sel, (node.dataset.opts || '').split(',').filter(Boolean));
    sel.addEventListener('change', () => {
      const key = node.dataset.key;
      if (key === 'image') {
        // image is a build-only field
        rebuildWithImage(sel.value).then(() => ack(sel, true)).catch(() => ack(sel, false));
        return;
      }
      const patch = {}; patch[key] = sel.value;
      pushParams(patch).then(() => ack(sel, true)).catch(() => ack(sel, false));
    });
  });
}
function setSelectOptions(sel, opts) {
  const cur = sel.value;
  sel.innerHTML = opts.map(o => `<option value="${o}">${o}</option>`).join('');
  if (opts.includes(cur)) sel.value = cur;
}

function applyLocal(key, value) {
  if (key === 'lens_on') { state.lensOn = !!value; $('#lens-wrap').classList.toggle('hidden', !state.lensOn); }
  else if (key === 'lens_span') { state.lensSpan = Math.max(16, Math.min(2048, Math.round(value))); if (state.lensAt) lensFetch(state.lensAt[0], state.lensAt[1]); }
}

async function loadImagesList() {
  try {
    const list = await api('/api/images');
    $$('.field-sel[data-key="image"] select').forEach(s => {
      setSelectOptions(s, list);
      if (state.params) s.value = state.params.image;
    });
  } catch (e) {}
}

// Push value from server params into UI widget for a given key/node
function writeFieldValue(node) {
  const key = node.dataset.key;
  if (node.dataset.local) {
    const inp = node.querySelector('input');
    if (!inp) return;
    if (key === 'lens_on') inp.checked = state.lensOn;
    if (key === 'lens_span') inp.value = state.lensSpan;
    return;
  }
  const p = state.params; if (!p || !(key in p)) return;
  const inp = node.querySelector('input, select');
  if (!inp) return;
  if (inp.type === 'checkbox') { inp.checked = !!p[key]; return; }
  if (inp.tagName === 'SELECT') { if ([...inp.options].some(o => o.value === String(p[key]))) inp.value = String(p[key]); return; }
  inp.value = p[key];
}

function syncControls() {
  const p = state.params; if (!p) return;
  // segmented toggles at the bottom
  $('#sign-plus').classList.toggle('on', p.brush_sign >= 0);
  $('#sign-minus').classList.toggle('on', p.brush_sign < 0);
  $('#target-fixed').classList.toggle('on', p.brush_target !== 'mobile');
  $('#target-mobile').classList.toggle('on', p.brush_target === 'mobile');
  $('#thickness').value = p.brush_thickness;
  $('#thickness-out').textContent = Math.round(p.brush_thickness);
  // top bar toggles
  $('#run-btn').textContent = p.paused ? 'Anneal' : 'Pause';
  $('#run-btn').classList.toggle('on', !p.paused);
  $('#freeze-btn').classList.toggle('on', !!p.frozen);
  $('#freeze-btn').textContent = p.frozen ? 'Run' : 'Freeze';
  $('#lens-btn').classList.toggle('on', state.lensOn);
  $('#paint-mode-btn').classList.toggle('on', state.paintMode);
  $('#canvas-wrap').classList.toggle('painting', state.paintMode);
  // panel field widgets
  $$('.field-num, .field-bool, .field-sel').forEach(writeFieldValue);
}

// ---------- rebuilds ----------
async function rebuildWithImage(image) {
  const p = Object.assign({}, state.params || {}, { image });
  await post('/api/init', p);
  await refreshState();
}

// ---------- frame loop ----------
let framePending = false;
function tickFrame() {
  if (framePending) return;
  const p = state.params;
  if (p && !p.live_view) return;
  framePending = true;
  const im = new Image();
  im.onload  = () => { $('#frame').src = im.src; framePending = false; };
  im.onerror = () => { framePending = false; };
  const mp = p && p.display_px ? p.display_px : 1000;
  im.src = `/api/frame.png?max_px=${mp}&t=${Date.now()}`;
}
setInterval(tickFrame, 120);
setInterval(() => refreshState(), 700);

// ---------- painting ----------
function frameToLattice(ev) {
  const img = $('#frame');
  const r = img.getBoundingClientRect();
  const x = (ev.clientX - r.left) / r.width * state.lattice[1];
  const y = (ev.clientY - r.top)  / r.height * state.lattice[0];
  return [x, y];
}
function flushPacket(last=false) {
  if (state.packet.length === 0 && !last) return;
  const body = { points: state.packet, first: !state.sentFirst, last };
  state.packet = [];
  state.sentFirst = true;
  fetch('/api/paint', {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body),
  }).then(r => r.json()).then(j => {
    if (j.stats) { state.stats = j.stats; renderStatus(); }
  }).catch(()=>{});
}
function onPointerDown(ev) {
  if (!state.paintMode) return;             // free hover otherwise
  if (ev.button !== undefined && ev.button !== 0) return;
  ev.preventDefault();
  state.painting = true;
  state.packet = [frameToLattice(ev)];
  state.sentFirst = false;
  flushPacket(false);
  ev.target.setPointerCapture?.(ev.pointerId);
  clearInterval(state.paintTimer);
  state.paintTimer = setInterval(() => flushPacket(false), 40);
}
function onPointerMove(ev) {
  if (state.painting) { ev.preventDefault(); state.packet.push(frameToLattice(ev)); return; }
  // hover: lens follow
  if (!state.lensOn || state.lensPinned) return;
  const p = frameToLattice(ev);
  state.lensAt = p;
  lensFetch(p[0], p[1]);
  updateLensLabel();
}
function onPointerUp(ev) {
  if (!state.painting) return;
  ev.preventDefault();
  clearInterval(state.paintTimer);
  state.paintTimer = null;
  state.painting = false;
  flushPacket(true);
}
function onCanvasTap(ev) {
  // A pointerup that did not paint pins/unpins the lens (paint mode owns
  // taps when armed).
  if (state.paintMode) return;
  if (!state.lensOn) return;
  const p = frameToLattice(ev);
  if (!state.lensPinned) { state.lensAt = p; }
  state.lensPinned = !state.lensPinned;
  updateLensLabel();
  if (state.lensAt) lensFetch(state.lensAt[0], state.lensAt[1]);
}

function updateLensLabel() {
  const el = $('#lens-label');
  if (!el || !state.lensAt) return;
  el.textContent = `${Math.round(state.lensAt[0])}, ${Math.round(state.lensAt[1])}`
    + (state.lensPinned ? ' [pinned]' : '');
}
function lensFetch(x, y) {
  if (!state.lensOn) return;
  if (state.lensBusy) { state.lensPend = [x, y]; return; }
  state.lensBusy = true;
  const span = state.lensSpan;
  const im = new Image();
  im.onload = () => { $('#lens').src = im.src; state.lensBusy = false;
    if (state.lensPend) { const p = state.lensPend; state.lensPend = null; lensFetch(p[0], p[1]); } };
  im.onerror = () => { state.lensBusy = false; };
  im.src = `/api/zoom.png?cx=${Math.round(x)}&cy=${Math.round(y)}` +
           `&span=${span}&out=560&t=${Date.now()}`;
}
setInterval(() => {
  if (!state.lensOn || !state.lensPinned || !state.lensAt) return;
  lensFetch(state.lensAt[0], state.lensAt[1]);
}, 400);

const imgEl = $('#frame');
imgEl.addEventListener('pointerdown', onPointerDown);
imgEl.addEventListener('pointermove', onPointerMove);
imgEl.addEventListener('pointerup', ev => { const painted = state.painting; onPointerUp(ev); if (!painted) onCanvasTap(ev); });
imgEl.addEventListener('pointercancel', onPointerUp);
imgEl.addEventListener('contextmenu', e => e.preventDefault());

// ---------- top bar buttons ----------
$('#run-btn').onclick = async () => {
  const running = !state.params?.paused && !state.params?.frozen;
  const r = await post('/api/control', { action: running ? 'pause' : 'run' });
  if (r.stats) { state.stats = r.stats; state.params = r.params; renderStatus(); syncControls(); }
};
$('#step-btn').onclick = async () => {
  const r = await post('/api/control', { action: 'step' });
  if (r.stats) { state.stats = r.stats; state.params = r.params; renderStatus(); syncControls(); }
  tickFrame();
};
$('#freeze-btn').onclick = async () => {
  const wantFreeze = !state.params?.frozen;
  const r = await post('/api/control', { action: wantFreeze ? 'freeze' : 'unfreeze' });
  if (r.stats) { state.stats = r.stats; state.params = r.params; renderStatus(); syncControls(); }
};
$('#autoT-btn').onclick = async () => {
  const r = await post('/api/control', { action: 'auto_temp' });
  if (r.stats) { state.stats = r.stats; state.params = r.params; renderStatus(); syncControls(); }
  toast(r.temperature ? `T -> ${Math.round(r.temperature)} K` : 'no uphill moves');
};
$('#lens-btn').onclick = () => {
  state.lensOn = !state.lensOn;
  $('#lens-wrap').classList.toggle('hidden', !state.lensOn);
  syncControls();
  if (state.lensOn) togglePopover($('#lens-panel'));
  else $('#lens-panel').classList.add('hidden');
};

// ---------- bottom bar ----------
$('#sign-plus').onclick   = () => pushParams({ brush_sign:  1 }).then(syncControls);
$('#sign-minus').onclick  = () => pushParams({ brush_sign: -1 }).then(syncControls);
$('#target-fixed').onclick  = () => pushParams({ brush_target: 'fixed'  }).then(syncControls);
$('#target-mobile').onclick = () => pushParams({ brush_target: 'mobile' }).then(syncControls);
$('#paint-mode-btn').onclick = () => {
  state.paintMode = !state.paintMode;
  syncControls();
};
$('#thickness').oninput = () => {
  $('#thickness-out').textContent = $('#thickness').value;
};
$('#thickness').onchange = () => {
  pushParams({ brush_thickness: Number($('#thickness').value) });
};

// ---------- menu / popover ----------
function togglePopover(el) {
  const opening = el.classList.contains('hidden');
  $$('.popover, .panel').forEach(p => p.classList.add('hidden'));
  if (opening) el.classList.remove('hidden');
}
$('#menu-btn').onclick   = () => togglePopover($('#menu-panel'));
$('#panel-btn').onclick  = () => togglePopover($('#panel'));
$('#panel-close').onclick = () => $('#panel').classList.add('hidden');

$('#mn-new').onclick    = () => { $('#menu-panel').classList.add('hidden'); openNewDialog(); };
$('#mn-reset').onclick  = async () => { $('#menu-panel').classList.add('hidden');
  const r = await post('/api/control', { action: 'reset' });
  if (r.stats) { state.stats = r.stats; state.params = r.params; renderStatus(); syncControls(); }
  tickFrame();
};
$('#mn-add').onclick    = async () => { $('#menu-panel').classList.add('hidden');
  const r = await post('/api/control', { action: 'add_uniform' });
  if (r.stats) { state.stats = r.stats; state.params = r.params; renderStatus(); syncControls(); }
  tickFrame();
};
$('#mn-undo').onclick   = async () => { $('#menu-panel').classList.add('hidden');
  await post('/api/undo'); tickFrame(); refreshState(); };
$('#mn-clear').onclick  = async () => { $('#menu-panel').classList.add('hidden');
  await post('/api/clear_paint'); tickFrame(); refreshState(); };
$('#mn-snap').onclick   = async () => { $('#menu-panel').classList.add('hidden');
  toast('rendering snapshot...');
  const r = await post('/api/snapshot');
  if (r.data_url) {
    const a = document.createElement('a');
    a.href = r.data_url;
    a.download = `painter-${Date.now()}.png`;
    document.body.appendChild(a); a.click(); a.remove();
    toast(`snapshot: ${(r.bytes/1024).toFixed(0)} KB`);
  } else toast(r.error || 'snapshot failed');
};
$('#mn-save').onclick   = async () => { $('#menu-panel').classList.add('hidden');
  const r = await post('/api/save'); toast(r.error || 'saved');
};

function toast(msg) {
  const t = $('#saved-toast'); if (!t) return;
  t.textContent = msg;
  clearTimeout(toast._t); toast._t = setTimeout(() => t.textContent = '', 6000);
}

// ---------- controls panel: tabs + widget wiring ----------
$$('.tab').forEach(t => t.onclick = () => {
  $$('.tab').forEach(x => x.classList.toggle('on', x === t));
  const key = t.dataset.tab;
  $$('.tabpane').forEach(p => p.classList.toggle('on', p.dataset.panel === key));
});
buildFieldWidgets();

// ---------- source tab actions ----------
$('#upload-input').addEventListener('change', async e => {
  const f = e.target.files[0]; if (!f) return;
  const fd = new FormData(); fd.append('file', f);
  const r = await fetch('/api/upload', { method: 'POST', body: fd }).then(r => r.json());
  if (r.error) { toast(r.error); return; }
  await loadImagesList();
  await rebuildWithImage('upload:current');
  toast(`uploaded ${r.bytes} bytes`);
});
$('#rebuild-btn').onclick = async () => {
  await post('/api/init', state.params);
  await refreshState(); tickFrame();
};

// ---------- new canvas dialog ----------
function openNewDialog() {
  const dlg = $('#dlg-new');
  dlg.classList.remove('hidden');
  const p = state.params;
  if (p) {
    // parse aspect from "blank:WxH" if present
    const m = /^blank:(\d+)x(\d+)$/.exec(p.image || '');
    if (m) { $('#new-w').value = m[1]; $('#new-h').value = m[2]; }
    $('#new-res').value = p.resolution;
    $('#new-fill').value = p.fill;
    $('#new-fill-out').textContent = Number(p.fill).toFixed(2);
  }
}
$('#new-fill').oninput = e => {
  $('#new-fill-out').textContent = Number(e.target.value).toFixed(2);
};
$('#new-cancel').onclick = () => $('#dlg-new').classList.add('hidden');
$('#new-ok').onclick = async () => {
  const w = Math.max(16, parseInt($('#new-w').value, 10) || 1920);
  const h = Math.max(16, parseInt($('#new-h').value, 10) || 1080);
  const res = Math.max(64, Math.min(4096, parseInt($('#new-res').value, 10) || 512));
  const fill = Math.max(0, Math.min(1, parseFloat($('#new-fill').value)));
  const upload = $('#new-upload').files[0];
  let image = `blank:${w}x${h}`;
  if (upload) {
    const fd = new FormData(); fd.append('file', upload);
    const r = await fetch('/api/upload', { method: 'POST', body: fd }).then(r => r.json());
    if (!r.error) { image = 'upload:current'; await loadImagesList(); }
  }
  const carry = Object.assign({}, state.params || {}, {
    image, resolution: res, fill,
    line_density: image.startsWith('blank:') ? 0 : (state.params?.line_density ?? 1),
  });
  await post('/api/init', carry);
  $('#dlg-new').classList.add('hidden');
  await refreshState();
  tickFrame();
};

// close popovers on outside pointerdown, but never inside a dialog
document.addEventListener('pointerdown', ev => {
  if (ev.target.closest('.overlay')) return;
  const inMenu = ev.target.closest('#menu-panel, #menu-btn');
  const inPanel = ev.target.closest('#panel, #panel-btn');
  const inLensP = ev.target.closest('#lens-panel, #lens-btn');
  if (!inMenu)   $('#menu-panel').classList.add('hidden');
  if (!inPanel)  $('#panel').classList.add('hidden');
  if (!inLensP)  $('#lens-panel').classList.add('hidden');
}, true);

// ---------- initial load ----------
(async () => {
  // Prime the frame src so no broken image icon appears while we bootstrap.
  $('#frame').src = $('#frame-placeholder').src;
  await refreshState();
  await loadImagesList();
  syncControls();
  // If the canvas is fresh (iteration 0 AND blank source), invite New Canvas.
  const s = state.stats;
  const p = state.params;
  if (s && s.iteration === 0 && p && p.image && p.image.startsWith('blank:')) {
    openNewDialog();
  }
  tickFrame();
})();
