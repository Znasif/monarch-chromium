"""Local monitoring panel for the managed-agent run.

Serves http://127.0.0.1:8765 and polls on its own every 60 s:
  - each step's interaction status (Gemini Interactions API, read-only `get`)
  - agent/* branches, their latest commit, and pull requests (GitHub REST)
  - the tail of agent-runs.log and each step's final reply (agent-runs/step-N.md)
  - whether the launcher process is still running

Usage: .venv/bin/python scripts/panel.py   (Ctrl+C to stop)
"""

import json
import os
import subprocess
import threading
import time
import urllib.request
import warnings
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from dotenv import load_dotenv
from google import genai

warnings.filterwarnings("ignore")

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / "agent-runs"
LOG = ROOT / "agent-runs.log"
REPO = "Znasif/monarch-chromium"
PORT = 8765
POLL_SECONDS = 60

load_dotenv(ROOT / ".env")
client = genai.Client()
snapshot = {"updated": None, "error": None}


def github(path):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/{path}",
        headers={
            "Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}",
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def poll():
    state = json.loads((RUNS / "state.json").read_text()) if (RUNS / "state.json").exists() else {"steps": {}}
    steps = []
    for step, info in sorted(state.get("steps", {}).items(), key=lambda kv: int(kv[0])):
        status = info.get("status")
        if status in ("in_progress", "queued"):
            try:
                status = client.interactions.get(id=info["interaction_id"]).status
            except Exception as error:  # keep the panel alive on API hiccups
                status = f"{status} (poll failed: {str(error)[:80]})"
        reply = RUNS / f"step-{step}.md"
        steps.append({"step": step, "status": status, "reply": reply.read_text()[-4000:] if reply.exists() else ""})

    branches = []
    for branch in github("branches?per_page=100"):
        if branch["name"].startswith("agent/"):
            commit = github(f"commits/{branch['commit']['sha']}")
            branches.append({
                "name": branch["name"],
                "sha": branch["commit"]["sha"][:7],
                "message": commit["commit"]["message"].splitlines()[0],
                "date": commit["commit"]["committer"]["date"],
            })
    pulls = [
        {"number": p["number"], "title": p["title"], "state": p["state"], "draft": p["draft"],
         "merged": bool(p.get("merged_at")), "url": p["html_url"], "head": p["head"]["ref"]}
        for p in github("pulls?state=all&per_page=30")
    ]
    launcher = subprocess.run(["pgrep", "-f", "scripts/agent.py run"], capture_output=True).returncode == 0
    log_tail = LOG.read_text().splitlines()[-40:] if LOG.exists() else []
    return {"steps": steps, "branches": sorted(branches, key=lambda b: b["name"]), "pulls": pulls,
            "launcher_running": launcher, "log": log_tail}


def poller():
    while True:
        try:
            snapshot.update(poll(), error=None)
        except Exception as error:
            snapshot["error"] = str(error)[:300]
        snapshot["updated"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
        time.sleep(POLL_SECONDS)


PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Agent Monitor</title>
<style>
:root { --bg:#fff; --fg:#1b1b1b; --muted:#666; --line:#ddd; --ok:#1a7f37; --run:#9a6700; --bad:#cf222e; }
@media (prefers-color-scheme: dark) { :root { --bg:#111; --fg:#eee; --muted:#999; --line:#333; --ok:#3fb950; --run:#d29922; --bad:#f85149; } }
body { background:var(--bg); color:var(--fg); font:15px/1.5 system-ui, sans-serif; margin:0 auto; max-width:1000px; padding:16px; }
h1 { font-size:20px; } h2 { font-size:16px; margin-top:24px; border-bottom:1px solid var(--line); }
table { border-collapse:collapse; width:100%; } td, th { text-align:left; padding:4px 8px; border-bottom:1px solid var(--line); vertical-align:top; }
.completed, .open { color:var(--ok); } .in_progress, .queued { color:var(--run); } .failed, .cancelled { color:var(--bad); }
pre { white-space:pre-wrap; background:color-mix(in srgb, var(--fg) 6%, transparent); padding:8px; max-height:320px; overflow:auto; }
.muted { color:var(--muted); } a { color:inherit; }
</style></head><body>
<h1>Cloud agent monitor</h1>
<p class="muted" id="meta">loading…</p>
<h2>Steps</h2><table id="steps"></table>
<h2>Branches</h2><table id="branches"></table>
<h2>Pull requests</h2><table id="pulls"></table>
<h2>Launcher log</h2><pre id="log"></pre>
<script>
const esc = s => String(s ?? '').replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c]));
async function refresh() {
  const d = await (await fetch('/api')).json();
  document.getElementById('meta').textContent =
    `updated ${d.updated} · launcher ${d.launcher_running ? 'running' : 'not running'}` + (d.error ? ` · error: ${d.error}` : '');
  document.getElementById('steps').innerHTML = '<tr><th>Step</th><th>Status</th><th>Final reply</th></tr>' +
    (d.steps || []).map(s => `<tr><td>${esc(s.step)}</td><td class="${esc(s.status)}">${esc(s.status)}</td><td><pre>${esc(s.reply) || '<span class=muted>—</span>'}</pre></td></tr>`).join('');
  document.getElementById('branches').innerHTML = '<tr><th>Branch</th><th>Commit</th><th>Message</th><th>When (UTC)</th></tr>' +
    (d.branches || []).map(b => `<tr><td><a href="https://github.com/${'""" + REPO + """'}/tree/${esc(b.name)}">${esc(b.name)}</a></td><td>${esc(b.sha)}</td><td>${esc(b.message)}</td><td>${esc(b.date)}</td></tr>`).join('');
  document.getElementById('pulls').innerHTML = '<tr><th>#</th><th>Title</th><th>Branch</th><th>State</th></tr>' +
    (d.pulls || []).map(p => `<tr><td><a href="${esc(p.url)}">${p.number}</a></td><td>${esc(p.title)}</td><td>${esc(p.head)}</td><td class="${p.merged ? 'completed' : esc(p.state)}">${p.merged ? 'merged' : esc(p.state)}${p.draft ? ' (draft)' : ''}</td></tr>`).join('');
  document.getElementById('log').textContent = (d.log || []).join('\\n');
}
refresh(); setInterval(refresh, 15000);
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/api":
            body, kind = json.dumps(snapshot).encode(), "application/json"
        else:
            body, kind = PAGE.encode(), "text/html; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    threading.Thread(target=poller, daemon=True).start()
    print(f"Agent monitor on http://127.0.0.1:{PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
