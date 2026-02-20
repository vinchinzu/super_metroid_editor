#!/usr/bin/env python3
"""Generate an interactive HTML visualization of the Super Metroid nav graph.

Usage:
    python visualize_graph.py /tmp/sm_export/nav_graph.json -o graph.html
    python visualize_graph.py /tmp/sm_export/nav_graph.json  # writes sm_nav_graph.html
"""
import argparse
import json
import html

# Area colors matching the game's aesthetic
AREA_COLORS = {
    "Crateria": "#4488cc",
    "Brinstar": "#44aa44",
    "Norfair": "#cc4422",
    "Wrecked Ship": "#7766aa",
    "Maridia": "#2299bb",
    "Tourian": "#cc9922",
    "Ceres": "#888888",
}

DOOR_CAP_COLORS = {
    "blue": "#3880D0",
    "red": "#D05050",
    "green": "#40C048",
    "yellow": "#D8C830",
    "grey": "#808088",
}


def build_html(graph_path: str) -> str:
    with open(graph_path) as f:
        data = json.load(f)

    nodes = data["nodes"]
    edges = data["edges"]

    # Build node lookup
    node_by_id = {n["roomId"]: n for n in nodes}

    # Build adjacency info per node for hover detail
    adj = {}  # roomId -> list of (destName, direction, cap)
    for e in edges:
        fid = e["fromRoomId"]
        tid = e["toRoomId"]
        to_node = node_by_id.get(tid)
        to_name = to_node["name"] if to_node else e["toRoomIdHex"]
        adj.setdefault(fid, []).append({
            "to": to_name,
            "dir": e["direction"],
            "cap": e["doorCapColor"],
            "ability": e["requiredAbility"],
            "elevator": e["isElevator"],
        })

    # JSON data for the HTML
    js_nodes = []
    for n in nodes:
        connections = adj.get(n["roomId"], [])
        conn_html_parts = []
        for c in connections:
            cap_str = ""
            if c["cap"]:
                cap_color = DOOR_CAP_COLORS.get(c["cap"], "#fff")
                cap_str = f' <span style="color:{cap_color};font-weight:bold">[{c["cap"]}]</span>'
            elev = " (elevator)" if c["elevator"] else ""
            conn_html_parts.append(f'{c["dir"]} → {html.escape(c["to"])}{cap_str}{elev}')

        tooltip = (
            f'<b>{html.escape(n["name"])}</b><br>'
            f'{html.escape(n["areaName"])} | {n["roomIdHex"]}<br>'
            f'Map: ({n["mapX"]}, {n["mapY"]}) | Size: {n["widthScreens"]}x{n["heightScreens"]}<br>'
            f'<hr style="margin:4px 0">'
            + "<br>".join(conn_html_parts) if conn_html_parts else "No doors"
        )

        js_nodes.append({
            "id": n["roomId"],
            "label": n["name"],
            "area": n["areaName"],
            "color": AREA_COLORS.get(n["areaName"], "#888"),
            "x": n["mapX"] * 40,
            "y": n["mapY"] * 40,
            "w": n["widthScreens"],
            "h": n["heightScreens"],
            "hex": n["roomIdHex"],
            "tooltip": tooltip,
        })

    js_edges = []
    for e in edges:
        color = "#555"
        width = 1
        if e["doorCapColor"]:
            color = DOOR_CAP_COLORS.get(e["doorCapColor"], "#888")
            width = 2
        if e["isElevator"]:
            width = 3
        js_edges.append({
            "from": e["fromRoomId"],
            "to": e["toRoomId"],
            "color": color,
            "width": width,
            "cap": e["doorCapColor"],
            "dir": e["direction"],
        })

    nodes_json = json.dumps(js_nodes)
    edges_json = json.dumps(js_edges)
    areas_json = json.dumps(AREA_COLORS)

    return f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>Super Metroid Navigation Graph</title>
<style>
* {{ margin: 0; padding: 0; box-sizing: border-box; }}
body {{ background: #0a0a14; color: #ccc; font-family: 'Consolas', 'Monaco', monospace; overflow: hidden; }}
#canvas {{ display: block; cursor: grab; }}
#canvas.dragging {{ cursor: grabbing; }}
#tooltip {{
    display: none; position: fixed; background: #1a1a2e; border: 1px solid #444;
    padding: 8px 12px; border-radius: 6px; font-size: 12px; line-height: 1.5;
    pointer-events: none; z-index: 100; max-width: 350px;
    box-shadow: 0 4px 12px rgba(0,0,0,0.6);
}}
#tooltip hr {{ border-color: #444; }}
#controls {{
    position: fixed; top: 10px; left: 10px; z-index: 50;
    background: #1a1a2e; border: 1px solid #333; border-radius: 6px;
    padding: 10px; font-size: 12px;
}}
#controls label {{ display: block; margin: 3px 0; cursor: pointer; }}
#controls label:hover {{ color: #fff; }}
#legend {{
    position: fixed; bottom: 10px; left: 10px; z-index: 50;
    background: #1a1a2e; border: 1px solid #333; border-radius: 6px;
    padding: 10px; font-size: 11px;
}}
.legend-item {{ display: flex; align-items: center; gap: 6px; margin: 2px 0; }}
.legend-swatch {{ width: 14px; height: 14px; border-radius: 3px; flex-shrink: 0; }}
.legend-line {{ width: 20px; height: 0; flex-shrink: 0; }}
#stats {{
    position: fixed; top: 10px; right: 10px; z-index: 50;
    background: #1a1a2e; border: 1px solid #333; border-radius: 6px;
    padding: 10px; font-size: 12px; text-align: right;
}}
#search {{
    background: #111; color: #ccc; border: 1px solid #444; border-radius: 4px;
    padding: 4px 8px; width: 160px; font-size: 12px; margin-bottom: 6px;
    font-family: inherit;
}}
#search:focus {{ outline: none; border-color: #4488cc; }}
</style>
</head>
<body>
<canvas id="canvas"></canvas>
<div id="tooltip"></div>
<div id="controls">
    <input id="search" type="text" placeholder="Search rooms...">
    <div style="margin-bottom:6px;font-weight:bold;">Areas</div>
</div>
<div id="legend">
    <div style="font-weight:bold;margin-bottom:4px;">Door Caps</div>
    <div class="legend-item"><div class="legend-line" style="border-top:2px solid #3880D0"></div> Blue (beam)</div>
    <div class="legend-item"><div class="legend-line" style="border-top:2px solid #D05050"></div> Red (missile)</div>
    <div class="legend-item"><div class="legend-line" style="border-top:2px solid #40C048"></div> Green (super)</div>
    <div class="legend-item"><div class="legend-line" style="border-top:2px solid #D8C830"></div> Yellow (PB)</div>
    <div class="legend-item"><div class="legend-line" style="border-top:2px solid #808088"></div> Grey (boss)</div>
    <div class="legend-item"><div class="legend-line" style="border-top:1px solid #555"></div> No cap</div>
    <div class="legend-item"><div class="legend-line" style="border-top:3px dashed #aaa"></div> Elevator</div>
</div>
<div id="stats"></div>
<script>
const NODES = {nodes_json};
const EDGES = {edges_json};
const AREA_COLORS = {areas_json};

const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');
const tooltip = document.getElementById('tooltip');
const statsEl = document.getElementById('stats');
const searchEl = document.getElementById('search');
const controlsEl = document.getElementById('controls');

// Area visibility toggles
const areaVisible = {{}};
Object.keys(AREA_COLORS).forEach(a => {{ areaVisible[a] = true; }});

Object.entries(AREA_COLORS).forEach(([area, color]) => {{
    const label = document.createElement('label');
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = true;
    cb.addEventListener('change', () => {{ areaVisible[area] = cb.checked; draw(); }});
    const swatch = document.createElement('span');
    swatch.style.cssText = `display:inline-block;width:10px;height:10px;background:${{color}};border-radius:2px;margin:0 4px;vertical-align:middle;`;
    label.appendChild(cb);
    label.appendChild(swatch);
    label.appendChild(document.createTextNode(area));
    controlsEl.appendChild(label);
}});

// State
let camX = 0, camY = 0, zoom = 1.0;
let dragging = false, dragStartX = 0, dragStartY = 0, dragCamX = 0, dragCamY = 0;
let hoveredNode = null;
let searchTerm = '';
let highlightedNodes = new Set();

const nodeById = {{}};
NODES.forEach(n => {{ nodeById[n.id] = n; }});

// Stats
statsEl.innerHTML = `${{NODES.length}} rooms | ${{EDGES.length}} doors`;

function resize() {{
    canvas.width = window.innerWidth;
    canvas.height = window.innerHeight;
    draw();
}}

function worldToScreen(wx, wy) {{
    return [
        (wx - camX) * zoom + canvas.width / 2,
        (wy - camY) * zoom + canvas.height / 2,
    ];
}}

function screenToWorld(sx, sy) {{
    return [
        (sx - canvas.width / 2) / zoom + camX,
        (sy - canvas.height / 2) / zoom + camY,
    ];
}}

function nodeRadius(n) {{
    return Math.max(4, Math.min(n.w, n.h) * 2 + 3);
}}

function draw() {{
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    // Edges
    for (const e of EDGES) {{
        const fromN = nodeById[e.from];
        const toN = nodeById[e.to];
        if (!fromN || !toN) continue;
        if (!areaVisible[fromN.area] && !areaVisible[toN.area]) continue;

        const [x1, y1] = worldToScreen(fromN.x, fromN.y);
        const [x2, y2] = worldToScreen(toN.x, toN.y);

        // Skip offscreen
        const margin = 50;
        if (Math.max(x1, x2) < -margin || Math.min(x1, x2) > canvas.width + margin) continue;
        if (Math.max(y1, y2) < -margin || Math.min(y1, y2) > canvas.height + margin) continue;

        const dimmed = (!areaVisible[fromN.area] || !areaVisible[toN.area]);
        const highlighted = highlightedNodes.size > 0 && (highlightedNodes.has(e.from) || highlightedNodes.has(e.to));
        const isHoverEdge = hoveredNode && (e.from === hoveredNode.id || e.to === hoveredNode.id);

        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.strokeStyle = isHoverEdge ? '#ffffff' : (dimmed ? '#222' : e.color);
        ctx.lineWidth = isHoverEdge ? e.width + 1 : e.width;
        ctx.globalAlpha = dimmed ? 0.15 : (highlighted || highlightedNodes.size === 0 ? 0.5 : 0.08);
        if (e.cap === null && !isHoverEdge) ctx.globalAlpha *= 0.5;
        ctx.stroke();
    }}

    ctx.globalAlpha = 1;

    // Nodes
    for (const n of NODES) {{
        if (!areaVisible[n.area]) continue;
        const [sx, sy] = worldToScreen(n.x, n.y);
        if (sx < -50 || sx > canvas.width + 50 || sy < -50 || sy > canvas.height + 50) continue;

        const r = nodeRadius(n) * zoom;
        const isHovered = hoveredNode && hoveredNode.id === n.id;
        const isHighlighted = highlightedNodes.has(n.id);
        const dimmed = highlightedNodes.size > 0 && !isHighlighted && !isHovered;

        ctx.beginPath();
        ctx.arc(sx, sy, Math.max(r, 2), 0, Math.PI * 2);
        ctx.fillStyle = n.color;
        ctx.globalAlpha = dimmed ? 0.15 : (isHovered ? 1 : 0.85);
        ctx.fill();

        if (isHovered) {{
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 2;
            ctx.stroke();
        }}

        // Labels at sufficient zoom
        if (zoom > 1.5 || isHovered || isHighlighted) {{
            ctx.globalAlpha = dimmed ? 0.1 : (isHovered ? 1 : 0.8);
            ctx.fillStyle = '#fff';
            ctx.font = `${{Math.max(9, 11 * zoom)}}px Consolas, Monaco, monospace`;
            ctx.textAlign = 'center';
            ctx.fillText(n.label, sx, sy - r - 4);
        }}
    }}

    ctx.globalAlpha = 1;
}}

// Interaction
canvas.addEventListener('mousedown', (e) => {{
    dragging = true;
    dragStartX = e.clientX;
    dragStartY = e.clientY;
    dragCamX = camX;
    dragCamY = camY;
    canvas.classList.add('dragging');
}});

canvas.addEventListener('mousemove', (e) => {{
    if (dragging) {{
        camX = dragCamX - (e.clientX - dragStartX) / zoom;
        camY = dragCamY - (e.clientY - dragStartY) / zoom;
        draw();
        return;
    }}

    const [wx, wy] = screenToWorld(e.clientX, e.clientY);
    let closest = null;
    let closestDist = Infinity;
    for (const n of NODES) {{
        if (!areaVisible[n.area]) continue;
        const dx = n.x - wx;
        const dy = n.y - wy;
        const dist = Math.sqrt(dx * dx + dy * dy);
        const hitRadius = nodeRadius(n) + 8 / zoom;
        if (dist < hitRadius && dist < closestDist) {{
            closestDist = dist;
            closest = n;
        }}
    }}

    if (closest !== hoveredNode) {{
        hoveredNode = closest;
        draw();
    }}

    if (closest) {{
        tooltip.style.display = 'block';
        tooltip.innerHTML = closest.tooltip;
        let tx = e.clientX + 15;
        let ty = e.clientY + 15;
        if (tx + 300 > window.innerWidth) tx = e.clientX - 310;
        if (ty + 200 > window.innerHeight) ty = e.clientY - 210;
        tooltip.style.left = tx + 'px';
        tooltip.style.top = ty + 'px';
    }} else {{
        tooltip.style.display = 'none';
    }}
}});

canvas.addEventListener('mouseup', () => {{
    dragging = false;
    canvas.classList.remove('dragging');
}});

canvas.addEventListener('wheel', (e) => {{
    e.preventDefault();
    const factor = e.deltaY > 0 ? 0.9 : 1.1;
    const [wx, wy] = screenToWorld(e.clientX, e.clientY);
    zoom *= factor;
    zoom = Math.max(0.1, Math.min(zoom, 20));
    // Keep the point under mouse fixed
    camX = wx - (e.clientX - canvas.width / 2) / zoom;
    camY = wy - (e.clientY - canvas.height / 2) / zoom;
    draw();
}});

// Search
searchEl.addEventListener('input', () => {{
    searchTerm = searchEl.value.toLowerCase().trim();
    highlightedNodes.clear();
    if (searchTerm.length > 0) {{
        for (const n of NODES) {{
            if (n.label.toLowerCase().includes(searchTerm) ||
                n.hex.toLowerCase().includes(searchTerm) ||
                n.area.toLowerCase().includes(searchTerm)) {{
                highlightedNodes.add(n.id);
            }}
        }}
    }}
    draw();
}});

// Center on map
function centerView() {{
    if (NODES.length === 0) return;
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const n of NODES) {{
        minX = Math.min(minX, n.x);
        minY = Math.min(minY, n.y);
        maxX = Math.max(maxX, n.x);
        maxY = Math.max(maxY, n.y);
    }}
    camX = (minX + maxX) / 2;
    camY = (minY + maxY) / 2;
    const spanX = maxX - minX + 100;
    const spanY = maxY - minY + 100;
    zoom = Math.min(canvas.width / spanX, canvas.height / spanY) * 0.85;
    zoom = Math.max(0.3, Math.min(zoom, 5));
}}

window.addEventListener('resize', resize);
resize();
centerView();
draw();
</script>
</body>
</html>"""


def main():
    parser = argparse.ArgumentParser(description="Visualize SM nav graph as interactive HTML")
    parser.add_argument("graph_json", help="Path to nav_graph.json")
    parser.add_argument("-o", "--output", default="sm_nav_graph.html", help="Output HTML file")
    args = parser.parse_args()

    html_content = build_html(args.graph_json)
    with open(args.output, "w") as f:
        f.write(html_content)
    print(f"Wrote {args.output} ({len(html_content)} bytes)")


if __name__ == "__main__":
    main()
