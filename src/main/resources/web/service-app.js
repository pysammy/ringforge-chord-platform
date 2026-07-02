const state = {
  snapshot: null,
  opsReport: null,
  lookup: null,
};

const colors = ["#2f6fbd", "#1f8f6a", "#b67818", "#7257a8", "#167f89", "#c44747"];

async function requestJson(path, options = {}) {
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error || "Request failed");
  }
  return data;
}

async function refresh() {
  const [snapshot, opsReport] = await Promise.all([
    requestJson("/api/cluster/snapshot"),
    requestJson("/api/cluster/ops-report"),
  ]);
  state.snapshot = snapshot;
  state.opsReport = opsReport;
  render();
}

async function writeKey() {
  const key = document.getElementById("keyInput").value || "45";
  const value = document.getElementById("valueInput").value || "value";
  await requestJson(`/api/dht/put?key=${encodeURIComponent(key)}&value=${encodeURIComponent(value)}`, {
    method: "POST",
  });
  await readKey();
}

async function readKey() {
  const key = document.getElementById("keyInput").value || "45";
  state.lookup = await requestJson(`/api/dht/get?key=${encodeURIComponent(key)}`);
  await refresh();
  renderLookup();
  drawRing();
}

function render() {
  renderStatus();
  renderMetrics();
  renderNodes();
  renderOps();
  drawRing();
}

function renderStatus() {
  const metrics = state.snapshot.metrics || {};
  const status = document.getElementById("statusStrip");
  status.innerHTML = `
    <span>${state.snapshot.memberCount || 0} members</span>
    <span>${metrics.reachableCount || 0} reachable</span>
    <span>${metrics.primaryKeyCount || 0} primary</span>
    <span>${metrics.replicaKeyCount || 0} replicas</span>
  `;
}

function renderMetrics() {
  const metrics = state.snapshot.metrics || {};
  const values = [
    ["Members", state.snapshot.memberCount || 0],
    ["Reachable", metrics.reachableCount || 0],
    ["Primary Keys", metrics.primaryKeyCount || 0],
    ["Replica Keys", metrics.replicaKeyCount || 0],
    ["Heartbeat Runs", metrics.heartbeatRunCount || 0],
    ["Unreachable", (metrics.unreachableNodeIds || []).join(", ") || "none"],
  ];
  document.getElementById("metricGrid").innerHTML = values.map(([name, value]) => `
    <div><strong>${escapeHtml(name)}</strong><span>${escapeHtml(String(value))}</span></div>
  `).join("");
}

function renderNodes() {
  const nodes = state.snapshot.nodes || [];
  document.getElementById("nodeList").innerHTML = nodes.map((node) => {
    const nodeState = node.state || {};
    const keys = formatEntries(nodeState.keys || {}, "empty");
    const replicas = formatEntries(nodeState.replicas || {}, "none");
    const status = node.reachable ? "reachable" : "unreachable";
    return `
      <div class="node-row">
        <div class="node-heading">
          <strong>Node ${node.nodeId}</strong>
          <span class="${status}">${status}</span>
        </div>
        <div class="kv">primary: ${keys}</div>
        <div class="kv">replica: ${replicas}</div>
      </div>
    `;
  }).join("") || "<div class=\"node-row\">No nodes returned.</div>";
}

function renderOps() {
  const lines = [
    ...(state.opsReport.summary || []),
    ...(state.opsReport.recommendedActions || []),
  ];
  document.getElementById("opsReport").innerHTML = lines.map((line) => `
    <div class="ops-row">${escapeHtml(line)}</div>
  `).join("") || "<div class=\"ops-row\">No operational findings.</div>";
}

function renderLookup() {
  const target = document.getElementById("lookupResult");
  const result = state.lookup;
  if (!result) {
    target.textContent = "No lookup yet.";
    return;
  }
  const value = result.found ? result.value : "missing";
  target.innerHTML = `
    <div><strong>Key ${result.key}</strong> resolved to node <strong>${result.responsibleNodeId}</strong></div>
    <div>Value: <strong>${escapeHtml(String(value))}</strong></div>
    <div class="path">${(result.path || []).map((id) => `<span>${id}</span>`).join("")}</div>
  `;
}

function drawRing() {
  if (!state.snapshot) {
    return;
  }
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
  const cy = height / 2 + 6;
  const radius = Math.min(width, height) * 0.34;
  const nodes = state.snapshot.nodes || [];
  const positions = new Map();

  context.clearRect(0, 0, width, height);
  context.strokeStyle = "#c9d2df";
  context.lineWidth = 2;
  context.beginPath();
  context.arc(cx, cy, radius, 0, Math.PI * 2);
  context.stroke();

  nodes.forEach((node, index) => {
    const id = node.nodeId;
    const angle = (id / 256) * Math.PI * 2 - Math.PI / 2;
    const x = cx + Math.cos(angle) * radius;
    const y = cy + Math.sin(angle) * radius;
    positions.set(id, { x, y, angle, color: colors[index % colors.length] });
  });

  if (state.lookup && state.lookup.path && state.lookup.path.length > 1) {
    context.strokeStyle = "#2f6fbd";
    context.lineWidth = 4;
    context.setLineDash([8, 7]);
    context.beginPath();
    state.lookup.path.forEach((id, index) => {
      const point = positions.get(id);
      if (!point) {
        return;
      }
      if (index === 0) {
        context.moveTo(point.x, point.y);
      } else {
        context.lineTo(point.x, point.y);
      }
    });
    context.stroke();
    context.setLineDash([]);
  }

  nodes.forEach((node) => {
    const point = positions.get(node.nodeId);
    const nodeState = node.state || {};
    const primaryCount = Object.keys(nodeState.keys || {}).length;
    const replicaCount = Object.keys(nodeState.replicas || {}).length;
    context.fillStyle = node.reachable ? point.color : "#9aa4b2";
    context.beginPath();
    context.arc(point.x, point.y, 22, 0, Math.PI * 2);
    context.fill();

    context.fillStyle = "#fff";
    context.font = "700 13px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.fillText(String(node.nodeId), point.x, point.y);

    const labelX = cx + Math.cos(point.angle) * (radius + 64);
    const labelY = cy + Math.sin(point.angle) * (radius + 64);
    context.fillStyle = "#17202a";
    context.font = "12px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
    context.fillText(`${primaryCount} primary`, labelX, labelY - 8);
    context.fillStyle = "#657181";
    context.fillText(`${replicaCount} replica`, labelX, labelY + 9);
  });

  context.fillStyle = "#657181";
  context.font = "13px -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif";
  context.textAlign = "left";
  context.fillText("Identifier space: 0-255", 18, 26);
  context.fillText("Dashed path: latest gateway lookup", 18, 46);
}

function formatEntries(values, fallback) {
  const entries = Object.entries(values);
  if (!entries.length) {
    return fallback;
  }
  return entries.map(([key, value]) => `${escapeHtml(key)}:${escapeHtml(String(value))}`).join(", ");
}

function escapeHtml(value) {
  return value
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

document.getElementById("putButton").addEventListener("click", () => {
  writeKey().catch(showError);
});
document.getElementById("getButton").addEventListener("click", () => {
  readKey().catch(showError);
});
document.getElementById("refreshButton").addEventListener("click", () => {
  refresh().catch(showError);
});
window.addEventListener("resize", drawRing);

function showError(error) {
  document.getElementById("lookupResult").textContent = error.message;
}

refresh().then(renderLookup).catch(showError);
