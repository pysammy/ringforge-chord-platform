const state = {
  snapshot: null,
  lookup: null,
};

const nodeColors = ["#2f6fbd", "#1f8f6a", "#b67818", "#7257a8", "#167f89", "#c44747", "#3b7d4b", "#8b5a2b"];

async function api(path, options = {}) {
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || "Request failed");
  }
  return data;
}

async function refresh() {
  state.snapshot = await api("/api/snapshot");
  renderSnapshot();
}

function renderSnapshot() {
  const snapshot = state.snapshot;
  document.getElementById("problem").textContent = snapshot.problem;
  document.getElementById("nodeCount").textContent = `${snapshot.metrics.nodeCount} nodes`;
  document.getElementById("keyCount").textContent = `${snapshot.metrics.keyCount} keys`;
  document.getElementById("replicaCount").textContent = `${snapshot.metrics.replicaCount} replicas`;
  document.getElementById("failedCount").textContent = `${snapshot.metrics.failedNodeCount} failed`;

  const badge = document.getElementById("healthBadge");
  badge.textContent = snapshot.metrics.healthy ? "Healthy" : "Needs attention";
  badge.className = `badge ${snapshot.metrics.healthy ? "ok" : "error"}`;

  const startSelect = document.getElementById("startNode");
  const selected = startSelect.value;
  startSelect.innerHTML = "";
  snapshot.nodes.forEach((node) => {
    const option = document.createElement("option");
    option.value = node.id;
    option.textContent = node.id;
    startSelect.appendChild(option);
  });
  if ([...startSelect.options].some((option) => option.value === selected)) {
    startSelect.value = selected;
  }

  renderHealth(snapshot);
  renderDiagnostics(snapshot);
  renderAdvice(snapshot);
  renderDistribution(snapshot);
  renderReplicas(snapshot);
  renderEvents(snapshot);
  drawRing(snapshot, state.lookup);
}

function renderHealth(snapshot) {
  const healthList = document.getElementById("healthList");
  healthList.innerHTML = "";
  if (snapshot.health.healthy) {
    const row = document.createElement("div");
    row.className = "finding";
    row.textContent = "All active links, finger tables, and key ownership checks pass.";
    healthList.appendChild(row);
    return;
  }
  snapshot.health.findings.forEach((finding) => {
    const row = document.createElement("div");
    row.className = `finding ${finding.severity.toLowerCase()}`;
    row.textContent = `${finding.severity}: ${finding.message}`;
    healthList.appendChild(row);
  });
}

function renderDistribution(snapshot) {
  const distribution = document.getElementById("distribution");
  distribution.innerHTML = "";
  snapshot.nodes.forEach((node) => {
    const row = document.createElement("div");
    row.className = "node-row";
    const title = document.createElement("strong");
    title.textContent = `Node ${node.id}`;
    const keys = document.createElement("span");
    keys.className = "keys";
    const entries = Object.entries(node.keys);
    keys.textContent = entries.length
      ? entries.map(([key, value]) => `${key}:${value}`).join(", ")
      : "empty";
    row.append(title, keys);
    distribution.appendChild(row);
  });
}

function renderReplicas(snapshot) {
  const replicas = document.getElementById("replicas");
  replicas.innerHTML = "";
  snapshot.nodes.forEach((node) => {
    const row = document.createElement("div");
    row.className = "node-row";
    const title = document.createElement("strong");
    title.textContent = `Node ${node.id}`;
    const keys = document.createElement("span");
    keys.className = "keys";
    const entries = Object.entries(node.replicas || {});
    keys.textContent = entries.length
      ? entries.map(([key, value]) => `${key}:${value}`).join(", ")
      : "no replicas";
    row.append(title, keys);
    replicas.appendChild(row);
  });
}

function renderDiagnostics(snapshot) {
  const diagnostics = document.getElementById("diagnosticsList");
  diagnostics.innerHTML = "";
  const findings = snapshot.diagnostics?.findings || [];
  if (!findings.length) {
    const row = document.createElement("div");
    row.className = "diagnostic";
    row.textContent = "No diagnostic findings. Ownership, routing, and replicas are consistent.";
    diagnostics.appendChild(row);
    return;
  }
  findings.forEach((finding) => {
    const row = document.createElement("div");
    row.className = `diagnostic ${finding.severity.toLowerCase()}`;
    row.textContent = `${finding.severity} · ${finding.category}: ${finding.message}`;
    diagnostics.appendChild(row);
  });
}

function renderAdvice(snapshot) {
  const advice = document.getElementById("opsAdvice");
  advice.innerHTML = "";
  const summary = snapshot.opsAdvice?.summary || [];
  const actions = snapshot.opsAdvice?.recommendedActions || [];
  [...summary, ...actions].forEach((text) => {
    const row = document.createElement("div");
    row.className = "advice-row";
    row.textContent = text;
    advice.appendChild(row);
  });
}

function renderEvents(snapshot) {
  const timeline = document.getElementById("eventTimeline");
  timeline.innerHTML = "";
  const events = [...(snapshot.events || [])].reverse().slice(0, 18);
  if (!events.length) {
    const row = document.createElement("div");
    row.className = "event-row";
    row.textContent = "No events recorded yet.";
    timeline.appendChild(row);
    return;
  }

  events.forEach((event) => {
    const row = document.createElement("div");
    row.className = "event-row";

    const seq = document.createElement("div");
    seq.className = "event-seq";
    seq.textContent = `#${event.sequence}`;

    const body = document.createElement("div");
    body.className = "event-body";

    const type = document.createElement("span");
    type.className = `event-type ${event.type.toLowerCase()}`;
    type.textContent = event.type.replaceAll("_", " ");

    const message = document.createElement("div");
    message.className = "event-message";
    message.textContent = event.message;

    const details = document.createElement("div");
    details.className = "event-details";
    details.textContent = formatEventDetails(event);

    body.append(type, message, details);
    row.append(seq, body);
    timeline.appendChild(row);
  });
}

function formatEventDetails(event) {
  const detailText = Object.entries(event.details || {})
    .map(([key, value]) => `${key}=${value}`)
    .join(" · ");
  const time = new Date(event.timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  return detailText ? `${time} · ${detailText}` : time;
}

function renderLookup(result) {
  const trace = document.getElementById("lookupTrace");
  const value = result.found ? result.value : "missing";
  trace.innerHTML = `
    <div><strong>Key ${result.key}</strong> resolves to node <strong>${result.responsibleNodeId}</strong></div>
    <div>Value: <strong>${value}</strong> · Hops: <strong>${result.hopCount}</strong> · ${result.latencyMillis.toFixed(4)} ms</div>
    <div class="path">${result.path.map((node) => `<span>${node}</span>`).join("")}</div>
  `;
}

function drawRing(snapshot, lookup) {
  const canvas = document.getElementById("ringCanvas");
  const context = canvas.getContext("2d");
  const rect = canvas.getBoundingClientRect();
  const scale = window.devicePixelRatio || 1;
  canvas.width = Math.floor(rect.width * scale);
  canvas.height = Math.floor(rect.height * scale);
  context.setTransform(scale, 0, 0, scale, 0, 0);

  const width = rect.width;
  const height = rect.height;
  const cx = width / 2;
  const cy = height / 2 + 8;
  const radius = Math.min(width, height) * 0.34;
  const nodes = snapshot.nodes;
  const positions = new Map();

  context.clearRect(0, 0, width, height);

  context.strokeStyle = "#c9d2df";
  context.lineWidth = 2;
  context.beginPath();
  context.arc(cx, cy, radius, 0, Math.PI * 2);
  context.stroke();

  nodes.forEach((node, index) => {
    const angle = (node.id / snapshot.ringSize) * Math.PI * 2 - Math.PI / 2;
    const x = cx + Math.cos(angle) * radius;
    const y = cy + Math.sin(angle) * radius;
    positions.set(node.id, { x, y, angle, color: nodeColors[index % nodeColors.length] });
  });

  if (lookup && lookup.path.length > 1) {
    context.strokeStyle = "#2f6fbd";
    context.lineWidth = 4;
    context.setLineDash([8, 7]);
    context.beginPath();
    lookup.path.forEach((id, index) => {
      const point = positions.get(id);
      if (!point) return;
      if (index === 0) context.moveTo(point.x, point.y);
      else context.lineTo(point.x, point.y);
    });
    context.stroke();
    context.setLineDash([]);
  }

  nodes.forEach((node) => {
    const point = positions.get(node.id);
    const keyCount = Object.keys(node.keys).length;
    context.fillStyle = point.color;
    context.beginPath();
    context.arc(point.x, point.y, 22, 0, Math.PI * 2);
    context.fill();

    context.fillStyle = "#fff";
    context.font = "700 13px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.fillText(String(node.id), point.x, point.y);

    context.fillStyle = "#17202a";
    context.font = "12px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
    const labelX = cx + Math.cos(point.angle) * (radius + 48);
    const labelY = cy + Math.sin(point.angle) * (radius + 48);
    context.fillText(`${keyCount} keys`, labelX, labelY);
  });

  context.fillStyle = "#657181";
  context.font = "13px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
  context.textAlign = "left";
  context.fillText(`Identifier space: 0-${snapshot.ringSize - 1}`, 20, 28);
  context.fillText("Dashed line shows the latest lookup path", 20, 48);
}

async function performLookup() {
  const start = document.getElementById("startNode").value || "0";
  const key = document.getElementById("lookupKey").value || "99";
  state.lookup = await api(`/api/lookup?start=${encodeURIComponent(start)}&key=${encodeURIComponent(key)}`);
  renderLookup(state.lookup);
  drawRing(state.snapshot, state.lookup);
}

async function mutate(path) {
  try {
    await api(path, { method: "POST" });
    state.lookup = null;
    document.getElementById("lookupTrace").textContent = "Run a lookup to inspect the path.";
    await refresh();
  } catch (error) {
    document.getElementById("lookupTrace").textContent = error.message;
  }
}

async function runBenchmark() {
  try {
    const result = await api("/api/benchmark", { method: "POST" });
    const target = document.getElementById("benchmarkResult");
    target.innerHTML = `
      <div><strong>${result.lookupCount}</strong> lookups across <strong>${result.nodeCount}</strong> nodes and <strong>${result.keyCount}</strong> keys</div>
      <div>Average hops: <strong>${result.averageHops.toFixed(2)}</strong> · Max hops: <strong>${result.maxHops}</strong> · Healthy: <strong>${result.healthy}</strong></div>
    `;
    await refresh();
  } catch (error) {
    document.getElementById("benchmarkResult").textContent = error.message;
  }
}

document.getElementById("lookupButton").addEventListener("click", performLookup);
document.getElementById("leaveButton").addEventListener("click", () => mutate("/api/leave?node=65"));
document.getElementById("crashButton").addEventListener("click", () => mutate("/api/crash?node=65"));
document.getElementById("joinButton").addEventListener("click", () => mutate("/api/join?node=65"));
document.getElementById("repairButton").addEventListener("click", () => mutate("/api/repair"));
document.getElementById("benchmarkButton").addEventListener("click", runBenchmark);
document.getElementById("resetButton").addEventListener("click", () => mutate("/api/reset"));
window.addEventListener("resize", () => state.snapshot && drawRing(state.snapshot, state.lookup));

refresh().catch((error) => {
  document.getElementById("lookupTrace").textContent = error.message;
});
