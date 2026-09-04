<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Banco de validación GPU</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500;600&family=IBM+Plex+Sans+Condensed:wght@600;700&family=IBM+Plex+Sans:wght@400;500;600&display=swap">
<style>
  :root{
    --bg:#f2f5f7; --surface:#ffffff; --surface-2:#eaeef1;
    --ink:#131b24; --ink-soft:#4c5b6b; --ink-faint:#7c8a98; --border:#dbe2e8;
    --accent:#0e7c86; --accent-ink:#075a62;
    --thermal:#c96a12; --critical:#b5402a; --critical-soft:#f6dcd5;
    --ok:#2f7d43; --ok-soft:#dcedd9; --warn:#a9790c; --warn-soft:#f3e6c4;
    --console-bg:#111820; --console-fg:#dfe7ee; --console-accent:#5fd0da; --console-border:#26323d;
    --shadow:0 1px 2px rgba(19,27,36,.04), 0 8px 24px -12px rgba(19,27,36,.12);
    color-scheme: light;
  }
  @media (prefers-color-scheme: dark){
    :root{
      --bg:#0b0f13; --surface:#12181e; --surface-2:#1a222a;
      --ink:#e8edf1; --ink-soft:#a7b3bd; --ink-faint:#71808d; --border:#263039;
      --accent:#4bc0ca; --accent-ink:#8fdde3;
      --thermal:#e2933f; --critical:#e2705a; --critical-soft:#3c211c;
      --ok:#68c17e; --ok-soft:#1c3324; --warn:#d9b24f; --warn-soft:#3a2f14;
      --console-bg:#0a0e12; --console-fg:#dbe4ea; --console-accent:#6fd6e0; --console-border:#1e2830;
      color-scheme: dark;
    }
  }
  *{box-sizing:border-box;}
  body{margin:0;background:var(--bg);color:var(--ink);font-family:"IBM Plex Sans",system-ui,sans-serif;font-size:15px;line-height:1.55;}
  h1,h2,h3{font-family:"IBM Plex Sans Condensed","IBM Plex Sans",sans-serif;font-weight:700;letter-spacing:.01em;margin:0;}
  code,.mono{font-family:"IBM Plex Mono",ui-monospace,monospace;}
  .titlebar{border-bottom:1px solid var(--border);background:var(--surface);}
  .titlebar-inner{max-width:1180px;margin:0 auto;padding:26px 24px;display:flex;justify-content:space-between;align-items:center;gap:16px;flex-wrap:wrap;}
  .eyebrow{font-family:"IBM Plex Mono",monospace;font-size:11px;letter-spacing:.1em;text-transform:uppercase;color:var(--accent-ink);display:flex;align-items:center;gap:8px;}
  .eyebrow::before{content:"";width:7px;height:7px;border-radius:50%;background:var(--accent);}
  h1{font-size:22px;margin-top:4px;}
  .tools-status{display:flex;gap:8px;flex-wrap:wrap;}
  .tool-chip{font-family:"IBM Plex Mono",monospace;font-size:11px;padding:4px 9px;border-radius:100px;border:1px solid var(--border);display:flex;align-items:center;gap:6px;color:var(--ink-soft);}
  .tool-chip::before{content:"";width:6px;height:6px;border-radius:50%;background:var(--ink-faint);}
  .tool-chip.ok{color:var(--ok);border-color:var(--ok-soft);}
  .tool-chip.ok::before{background:var(--ok);}
  .tool-chip.missing{color:var(--critical);border-color:var(--critical-soft);}
  .tool-chip.missing::before{background:var(--critical);}

  .layout{max-width:1180px;margin:0 auto;padding:32px 24px 100px;display:grid;grid-template-columns:340px minmax(0,1fr);gap:28px;align-items:start;}
  @media (max-width:940px){.layout{grid-template-columns:1fr;}}

  .panel{background:var(--surface);border:1px solid var(--border);border-radius:14px;box-shadow:var(--shadow);padding:20px;margin-bottom:20px;}
  .panel h2{font-size:15px;margin-bottom:4px;}
  .panel .hint{color:var(--ink-faint);font-size:12.5px;margin:0 0 14px;}

  .gpu-list{display:flex;flex-direction:column;gap:8px;margin-bottom:6px;}
  .gpu-item{display:flex;align-items:center;gap:10px;padding:10px 12px;border:1px solid var(--border);border-radius:10px;font-size:13.5px;cursor:pointer;}
  .gpu-item:hover{background:var(--surface-2);}
  .gpu-item .serial{color:var(--ink-faint);font-family:"IBM Plex Mono",monospace;font-size:11.5px;margin-left:auto;}
  .empty-note{color:var(--ink-faint);font-size:13px;padding:10px 0;}

  .field{margin-bottom:14px;}
  .field label{display:block;font-size:12.5px;font-weight:600;margin-bottom:6px;}
  .field .desc{color:var(--ink-faint);font-size:11.8px;margin-top:4px;}
  select, input[type=number]{
    width:100%;padding:8px 10px;border:1px solid var(--border);border-radius:8px;
    background:var(--surface);color:var(--ink);font-family:"IBM Plex Mono",monospace;font-size:13px;
  }
  .checkbox-row{display:flex;align-items:center;gap:8px;}
  .checkbox-row input{width:16px;height:16px;}

  .btn{
    font-family:"IBM Plex Sans",sans-serif;font-weight:600;font-size:13.5px;
    padding:10px 16px;border-radius:9px;border:1px solid transparent;cursor:pointer;
  }
  .btn-primary{background:var(--accent);color:#fff;width:100%;}
  .btn-primary:disabled{opacity:.5;cursor:not-allowed;}
  .btn-secondary{background:transparent;border-color:var(--border);color:var(--ink-soft);}
  .btn-danger{background:transparent;border-color:var(--critical-soft);color:var(--critical);}
  .btn-preset{background:var(--surface-2);color:var(--ink);border-color:var(--border);width:100%;margin-bottom:10px;}

  .status-line{display:flex;align-items:center;gap:10px;margin-bottom:12px;font-size:13.5px;}
  .dot{width:9px;height:9px;border-radius:50%;background:var(--ink-faint);}
  .dot.running{background:var(--thermal);box-shadow:0 0 0 4px color-mix(in srgb, var(--thermal) 20%, transparent);animation:pulse 1.4s infinite;}
  @keyframes pulse{0%,100%{opacity:1;}50%{opacity:.4;}}

  .console{background:var(--console-bg);color:var(--console-fg);border:1px solid var(--console-border);border-radius:12px;padding:14px;font-family:"IBM Plex Mono",monospace;font-size:12px;line-height:1.6;height:220px;overflow-y:auto;white-space:pre-wrap;}
  .console .l{color:var(--console-fg);}

  .table-wrap{overflow-x:auto;border:1px solid var(--border);border-radius:12px;box-shadow:var(--shadow);background:var(--surface);}
  table{border-collapse:collapse;width:100%;min-width:820px;}
  th,td{text-align:left;padding:10px 12px;border-bottom:1px solid var(--border);font-size:13px;vertical-align:top;}
  th{font-family:"IBM Plex Mono",monospace;font-size:10.5px;letter-spacing:.05em;text-transform:uppercase;color:var(--ink-faint);background:var(--surface-2);}
  tr:last-child td{border-bottom:none;}
  td.num{font-family:"IBM Plex Mono",monospace;text-align:right;}
  td input[type=text], td input[type=number]{width:90px;padding:5px 7px;font-size:12px;}
  td input.notes{width:160px;}

  .pill{display:inline-flex;align-items:center;gap:6px;font-family:"IBM Plex Mono",monospace;font-size:11px;font-weight:500;padding:3px 9px;border-radius:100px;white-space:nowrap;}
  .pill::before{content:"";width:6px;height:6px;border-radius:50%;}
  .pill-ok{background:var(--ok-soft);color:var(--ok);} .pill-ok::before{background:var(--ok);}
  .pill-warn{background:var(--warn-soft);color:var(--warn);} .pill-warn::before{background:var(--warn);}
  .pill-critical{background:var(--critical-soft);color:var(--critical);} .pill-critical::before{background:var(--critical);}
  .pill-pending{background:var(--surface-2);color:var(--ink-faint);} .pill-pending::before{background:var(--ink-faint);}
  select.verdict-select{font-size:11px;padding:3px 6px;width:auto;}

  .actions-row{display:flex;gap:10px;margin-top:14px;flex-wrap:wrap;}
  .actions-row .btn{flex:0 0 auto;}
  .empty-results{color:var(--ink-faint);font-size:13.5px;padding:24px;text-align:center;}
  .reasons{font-size:11.5px;color:var(--ink-faint);max-width:220px;}

  .cards{display:flex;flex-direction:column;gap:12px;margin-bottom:18px;}
  .result-card{border:1px solid var(--border);border-left:5px solid var(--ink-faint);border-radius:12px;padding:16px 18px;background:var(--surface);}
  .result-card.ok{border-left-color:var(--ok);}
  .result-card.warn{border-left-color:var(--warn);}
  .result-card.critical{border-left-color:var(--critical);}
  .card-top{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:4px;}
  .card-name{font-weight:600;font-size:14.5px;}
  .card-headline{font-family:"IBM Plex Sans Condensed",sans-serif;font-weight:700;font-size:18px;margin-bottom:6px;}
  .result-card.ok .card-headline{color:var(--ok);}
  .result-card.warn .card-headline{color:var(--warn);}
  .result-card.critical .card-headline{color:var(--critical);}
  .card-text{color:var(--ink-soft);font-size:13.5px;line-height:1.6;}
  .detail-toggle{background:none;border:none;color:var(--accent-ink);font-size:12.5px;font-weight:600;cursor:pointer;padding:4px 0;margin-bottom:10px;}
</style>
</head>
<body>

<div class="titlebar">
  <div class="titlebar-inner">
    <div>
      <div class="eyebrow">Herramienta local · corre en este servidor</div>
      <h1>Banco de validación de tarjetas GPU</h1>
    </div>
    <div class="tools-status" id="tools-status"></div>
  </div>
</div>

<div class="layout">
  <div>
    <div class="panel">
      <h2>1 · Tarjetas a probar</h2>
      <p class="hint">Se procesan una detrás de otra, en el orden mostrado.</p>
      <div class="gpu-list" id="gpu-list"><div class="empty-note">Buscando tarjetas…</div></div>
    </div>

    <div class="panel">
      <h2>2 · Batería de pruebas</h2>
      <p class="hint">Marca lo que quieras ejecutar. Lo que se deje en blanco se omite.</p>

      <button class="btn btn-preset" id="preset-btn">Usar batería recomendada</button>

      <div class="field">
        <label>DCGM diag (diagnóstico oficial NVIDIA)</label>
        <select id="dcgm-level">
          <option value="0">No ejecutar</option>
          <option value="1">Nivel 1 · rápido (segundos)</option>
          <option value="2">Nivel 2 · medio (~2 min)</option>
          <option value="3" selected>Nivel 3 · completo (~30 min)</option>
          <option value="4">Nivel 4 · exhaustivo (1-2 h)</option>
        </select>
      </div>

      <div class="field">
        <label>gpu-burn · estrés máximo</label>
        <input type="number" id="burn-seconds" value="300" min="0" step="30">
        <div class="desc">Segundos de estrés al 100%. 0 = no ejecutar.</div>
      </div>

      <div class="field">
        <label>memtest_vulkan · estrés de VRAM</label>
        <input type="number" id="memtest-seconds" value="120" min="0" step="30">
        <div class="desc">Segundos de prueba de memoria. 0 = no ejecutar.</div>
      </div>

      <div class="field">
        <div class="checkbox-row">
          <input type="checkbox" id="run-benchmark" checked>
          <label style="margin:0;">Benchmark comparable (Unigine Superposition vía Phoronix)</label>
        </div>
      </div>

      <button class="btn btn-primary" id="start-btn">Iniciar batería</button>
      <button class="btn btn-danger" id="stop-btn" style="width:100%;margin-top:8px;display:none;">Detener</button>
    </div>
  </div>

  <div>
    <div class="panel">
      <h2>Estado</h2>
      <div class="status-line">
        <span class="dot" id="status-dot"></span>
        <span id="status-text">En espera.</span>
      </div>
      <div class="console" id="console"></div>
    </div>

    <div class="panel">
      <div style="display:flex;justify-content:space-between;align-items:center;">
        <h2>Informe comparativo</h2>
        <div class="actions-row" style="margin:0;">
          <a class="btn btn-secondary" href="/api/report.html" target="_blank">Ver informe</a>
          <a class="btn btn-secondary" href="/api/report.csv">Exportar CSV</a>
        </div>
      </div>
      <p class="hint">Resumen en lenguaje sencillo de cada tarjeta probada. Si quieres los números exactos (temperaturas, potencia, puntuación) puedes abrir el detalle técnico debajo.</p>
      <div class="cards" id="results-cards"></div>
      <button class="detail-toggle" id="detail-toggle" style="display:none;">Ver detalle técnico ▾</button>
      <div class="table-wrap" id="detail-wrap" style="display:none;">
      <p class="hint">La puntuación del benchmark y las notas se pueden corregir a mano si la extracción automática falla. Si las tarjetas del lote son de <strong>modelos distintos</strong>, rellena "Ref. puntuación" y "Ref. temp máx" con los valores típicos de cada modelo (por ejemplo, buscándolos en TechPowerUp u OpenBenchmarking.org) — así cada tarjeta se compara contra su propio modelo en vez de contra las demás. El veredicto se recalcula al terminar cada tarjeta y también se puede fijar a mano.</p>
        <table id="results-table">
          <thead>
            <tr>
              <th>Tarjeta / serie</th><th class="num">Temp idle</th><th class="num">Temp máx</th>
              <th class="num">Potencia máx</th><th class="num">Puntuación</th>
              <th class="num">Ref. puntuación</th><th class="num">Ref. temp máx</th>
              <th>DCGM</th><th>gpu-burn</th><th>memtest</th><th>Notas</th><th>Veredicto</th>
            </tr>
          </thead>
          <tbody id="results-body"></tbody>
        </table>
      </div>
      <div id="empty-results" class="empty-results">Todavía no se ha probado ninguna tarjeta.</div>
    </div>
  </div>
</div>

<script>
const RECOMMENDED = { dcgm: 3, burn: 300, memtest: 120, benchmark: true };
let selectedIndices = new Set();

document.getElementById('preset-btn').addEventListener('click', () => {
  document.getElementById('dcgm-level').value = RECOMMENDED.dcgm;
  document.getElementById('burn-seconds').value = RECOMMENDED.burn;
  document.getElementById('memtest-seconds').value = RECOMMENDED.memtest;
  document.getElementById('run-benchmark').checked = RECOMMENDED.benchmark;
});

async function loadTools(){
  const r = await fetch('/api/tools'); const t = await r.json();
  const labels = {nvidia_smi:'nvidia-smi', dcgmi:'DCGM', gpu_burn:'gpu-burn', memtest_vulkan:'memtest_vulkan', phoronix:'Phoronix'};
  const el = document.getElementById('tools-status');
  el.innerHTML = Object.entries(labels).map(([k,label]) => {
    const ok = t[k];
    return `<span class="tool-chip ${ok?'ok':'missing'}">${label}</span>`;
  }).join('');
}

async function loadGpus(){
  const r = await fetch('/api/gpus'); const data = await r.json();
  const el = document.getElementById('gpu-list');
  if(!data.available){
    el.innerHTML = '<div class="empty-note">nvidia-smi no responde. ¿Está instalado el driver?</div>';
    return;
  }
  if(data.gpus.length === 0){
    el.innerHTML = '<div class="empty-note">No se detecta ninguna GPU NVIDIA ahora mismo.</div>';
    return;
  }
  el.innerHTML = '';
  data.gpus.forEach(g => {
    const id = 'gpu-' + g.index;
    const row = document.createElement('label');
    row.className = 'gpu-item';
    row.innerHTML = `<input type="checkbox" id="${id}" checked>
      <span>${g.index} · ${g.name}</span><span class="serial">${g.serial}</span>`;
    row.querySelector('input').addEventListener('change', e => {
      if(e.target.checked) selectedIndices.add(g.index); else selectedIndices.delete(g.index);
    });
    selectedIndices.add(g.index);
    el.appendChild(row);
  });
}

document.getElementById('start-btn').addEventListener('click', async () => {
  if(selectedIndices.size === 0){ alert('Selecciona al menos una tarjeta.'); return; }
  const body = {
    gpu_indices: Array.from(selectedIndices),
    dcgm_level: parseInt(document.getElementById('dcgm-level').value, 10),
    burn_seconds: parseInt(document.getElementById('burn-seconds').value, 10) || 0,
    memtest_seconds: parseInt(document.getElementById('memtest-seconds').value, 10) || 0,
    run_benchmark: document.getElementById('run-benchmark').checked,
  };
  const r = await fetch('/api/run', {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify(body)});
  if(r.status === 409){ alert('Ya hay una batería en curso.'); return; }
  if(!r.ok){ const j = await r.json(); alert(j.error || 'No se pudo iniciar.'); return; }
});

document.getElementById('stop-btn').addEventListener('click', async () => {
  await fetch('/api/stop', {method:'POST'});
});

function verdictPill(v){
  const map = {OK:'pill-ok', REVISAR:'pill-warn', DEVOLVER:'pill-critical'};
  const cls = map[v] || 'pill-pending';
  return `<span class="pill ${cls}">${v || 'pendiente'}</span>`;
}

function verdictCardClass(v){
  return {OK:'ok', REVISAR:'warn', DEVOLVER:'critical'}[v] || '';
}

function fmt(n, unit){ return (n===null || n===undefined) ? '—' : (n + (unit||'')); }

document.getElementById('detail-toggle').addEventListener('click', () => {
  const wrap = document.getElementById('detail-wrap');
  const btn = document.getElementById('detail-toggle');
  const showing = wrap.style.display !== 'none';
  wrap.style.display = showing ? 'none' : 'block';
  btn.textContent = showing ? 'Ver detalle técnico ▾' : 'Ocultar detalle técnico ▴';
});

function renderCards(results){
  const el = document.getElementById('results-cards');
  const toggle = document.getElementById('detail-toggle');
  if(results.length === 0){ el.innerHTML=''; toggle.style.display='none'; return; }
  toggle.style.display='inline-block';
  el.innerHTML = results.map(res => {
    const cls = verdictCardClass(res.verdict);
    const headline = res.summary_headline || (res.verdict || 'Pendiente');
    const text = res.summary_text || 'Todavía no hay resumen para esta tarjeta.';
    return `<div class="result-card ${cls}">
      <div class="card-top">
        <span class="card-name">${res.name} <span class="mono" style="color:var(--ink-faint);font-size:11px;">${res.serial}</span></span>
        ${verdictPill(res.verdict)}
      </div>
      <div class="card-headline">${headline}</div>
      <div class="card-text">${text}</div>
    </div>`;
  }).join('');
}

async function loadResults(){
  const r = await fetch('/api/results'); const results = await r.json();
  renderCards(results);
  const body = document.getElementById('results-body');
  const empty = document.getElementById('empty-results');
  if(results.length === 0){ body.innerHTML=''; empty.style.display='block'; return; }
  empty.style.display='none';
  body.innerHTML = results.map(res => {
    const dcgm = res.dcgm && res.dcgm.ran ? (res.dcgm.fail ? 'Fail' : 'Pass') : '—';
    const burn = res.gpu_burn && res.gpu_burn.ran ? (res.gpu_burn.errors ? 'errores' : 'ok') : '—';
    const mem = res.memtest && res.memtest.ran ? (res.memtest.errors ? 'errores' : 'ok') : '—';
    const reasons = (res.verdict_reasons || []).join(' · ');
    return `<tr>
      <td><strong>${res.name}</strong><br><span class="mono" style="color:var(--ink-faint);font-size:11px;">${res.serial}</span></td>
      <td class="num mono">${fmt(res.temp_idle,'°C')}</td>
      <td class="num mono">${fmt(res.temp_max,'°C')}</td>
      <td class="num mono">${fmt(res.power_max,'W')}</td>
      <td class="num"><input type="number" class="score-input" data-serial="${res.serial}" value="${res.benchmark_score ?? ''}"></td>
      <td class="num"><input type="number" class="ref-score-input" data-serial="${res.serial}" value="${res.reference_score ?? ''}" placeholder="típica del modelo"></td>
      <td class="num"><input type="number" class="ref-temp-input" data-serial="${res.serial}" value="${res.reference_temp_max ?? ''}" placeholder="límite del modelo"></td>
      <td>${dcgm}</td><td>${burn}</td><td>${mem}</td>
      <td><input type="text" class="notes-input" data-serial="${res.serial}" value="${(res.notes||'').replace(/"/g,'&quot;')}"></td>
      <td>
        ${verdictPill(res.verdict)}
        <div class="reasons">${reasons}</div>
        <select class="verdict-select" data-serial="${res.serial}">
          <option value="">auto</option>
          <option value="OK" ${res.manual_verdict==='OK'?'selected':''}>OK</option>
          <option value="REVISAR" ${res.manual_verdict==='REVISAR'?'selected':''}>REVISAR</option>
          <option value="DEVOLVER" ${res.manual_verdict==='DEVOLVER'?'selected':''}>DEVOLVER</option>
        </select>
      </td>
    </tr>`;
  }).join('');

  document.querySelectorAll('.score-input').forEach(inp => {
    inp.addEventListener('change', e => patchResult(e.target.dataset.serial, {benchmark_score: e.target.value}));
  });
  document.querySelectorAll('.ref-score-input').forEach(inp => {
    inp.addEventListener('change', e => patchResult(e.target.dataset.serial, {reference_score: e.target.value}));
  });
  document.querySelectorAll('.ref-temp-input').forEach(inp => {
    inp.addEventListener('change', e => patchResult(e.target.dataset.serial, {reference_temp_max: e.target.value}));
  });
  document.querySelectorAll('.notes-input').forEach(inp => {
    inp.addEventListener('change', e => patchResult(e.target.dataset.serial, {notes: e.target.value}));
  });
  document.querySelectorAll('.verdict-select').forEach(sel => {
    sel.addEventListener('change', e => patchResult(e.target.dataset.serial, {manual_verdict: e.target.value}));
  });
}

async function patchResult(serial, patch){
  await fetch('/api/results/' + encodeURIComponent(serial), {
    method:'PATCH', headers:{'Content-Type':'application/json'}, body: JSON.stringify(patch)
  });
  loadResults();
}

async function pollStatus(){
  const r = await fetch('/api/status'); const s = await r.json();
  const dot = document.getElementById('status-dot');
  const text = document.getElementById('status-text');
  dot.classList.toggle('running', s.running);
  document.getElementById('start-btn').disabled = s.running;
  document.getElementById('stop-btn').style.display = s.running ? 'block' : 'none';
  text.textContent = s.running ? (s.current_step || ('Probando ' + s.current_gpu)) : 'En espera.';
  const c = document.getElementById('console');
  c.innerHTML = s.log.map(l => `<div class="l">${l.replace(/</g,'&lt;')}</div>`).join('');
  c.scrollTop = c.scrollHeight;
}

loadTools(); loadGpus(); loadResults(); pollStatus();
setInterval(pollStatus, 1500);
setInterval(loadResults, 4000);
</script>
</body>
</html>
