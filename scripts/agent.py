"""Run roadmap steps on the Antigravity managed agent (gemini-4-argon).

Each step runs in a fresh remote sandbox that clones the repo from GitHub, so
progress carries over only through pushed branches (see docs/LONG_TERM_PLAN.md §5).

Usage:
    .venv/bin/python scripts/agent.py run 0          # one step
    .venv/bin/python scripts/agent.py run 0 1 2 3 4  # steps in order, stop on failure
    .venv/bin/python scripts/agent.py status
    .venv/bin/python scripts/agent.py cancel

Requires GEMINI_API_KEY and GITHUB_TOKEN in .env. The GitHub token is injected by
the sandbox egress proxy and never enters the sandbox or the model context.
"""

import base64
import json
import os
import sys
import time
from pathlib import Path

from dotenv import load_dotenv
from google import genai

ROOT = Path(__file__).resolve().parent.parent
RUNS = ROOT / "agent-runs"
STATE = RUNS / "state.json"
REPO = "https://github.com/Znasif/monarch-chromium"

# Reference files mounted into the sandbox but never committed (public repo).
HOME = Path.home()
REFS = {
    "/workspace/ref/builder-guide.txt": Path(
        "/mnt/c/Users/znasi/Downloads/[External] Gemini 4 Argon_ Hackathon Builder Guide.txt"
    ),
    "/workspace/ref/ideation-transcript.jsonl": HOME
    / ".gemini/antigravity-cli/brain/4617acab-dc57-41d6-9c7e-06cdd95f8124/.system_generated/logs/transcript.jsonl",
    "/workspace/ref/skills/gemini-api-dev.md": HOME
    / ".gemini/config/plugins/gemini-skills/skills/gemini-api-dev/SKILL.md",
    "/workspace/ref/skills/gemini-omni-flash-api.md": HOME
    / ".gemini/config/plugins/gemini-skills/skills/gemini-omni-flash-api/SKILL.md",
}

POLL_SECONDS = 30
STEP_TIMEOUT_MINUTES = 120


def load_state():
    return json.loads(STATE.read_text()) if STATE.exists() else {"steps": {}}


def save_state(state):
    RUNS.mkdir(exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2))


GEMINI_CREDENTIAL = "monarch-gemini-key"


def ensure_gemini_credential(client):
    """Store the Gemini key once so the sandbox can run Argon grading.

    The sandbox only sees a placeholder; the egress proxy substitutes the real key
    on requests to generativelanguage.googleapis.com.
    """
    try:
        client.credentials.get(id=GEMINI_CREDENTIAL)
    except Exception:
        client.credentials.create(
            id=GEMINI_CREDENTIAL,
            type="environment_variable",
            value=os.environ["GEMINI_API_KEY"],
            trusted_domains=["generativelanguage.googleapis.com"],
            injection_location="header",
        )


def environment(github_token):
    basic = base64.b64encode(f"x-oauth-basic:{github_token}".encode()).decode()
    sources = [{"type": "repository", "source": REPO, "target": "/workspace/monarch-chromium"}]
    for target, path in REFS.items():
        sources.append({"type": "inline", "content": path.read_text(encoding="utf-8-sig"), "target": target})
    return {
        "type": "remote",
        "sources": sources,
        "env": {"GEMINI_API_KEY": {"credential": GEMINI_CREDENTIAL}},
        "network": {
            "allowlist": [
                {"domain": "github.com", "transform": {"Authorization": f"Basic {basic}"}},
                {"domain": "api.github.com", "transform": {"Authorization": f"Bearer {github_token}"}},
                # Build tools need Maven, Gradle, npm and Playwright hosts; no secrets go there.
                {"domain": "*"},
            ]
        },
    }


def prompt(step):
    common = (ROOT / "docs/agent/common.md").read_text()
    body = (ROOT / f"docs/agent/step-{step}.md").read_text()
    return f"{common}\n---\n\n{body}"


def run_step(client, state, step, github_token):
    running = state["steps"].get(str(step), {})
    if running.get("status") in ("in_progress", "queued"):
        # Resume watching a step a previous launcher started; do not start it twice.
        interaction = client.interactions.get(id=running["interaction_id"])
        print(f"step {step}: resuming {interaction.id}", flush=True)
        return wait_for(client, state, step, interaction)

    request = {
        "agent": "antigravity-preview-09-2026",
        "agent_config": {"type": "antigravity", "model": "gemini-4-argon"},
        "input": prompt(step),
        "environment": environment(github_token),
        "background": True,
    }
    previous = state.get("last_interaction_id")
    if previous:
        request["previous_interaction_id"] = previous

    interaction = client.interactions.create(**request)
    state["steps"][str(step)] = {"interaction_id": interaction.id, "status": interaction.status}
    state["active_interaction_id"] = interaction.id
    save_state(state)
    print(f"step {step}: started {interaction.id}", flush=True)
    return wait_for(client, state, step, interaction)


def wait_for(client, state, step, interaction):
    state["active_interaction_id"] = interaction.id
    save_state(state)
    deadline = time.time() + STEP_TIMEOUT_MINUTES * 60
    while interaction.status in ("in_progress", "queued"):
        if time.time() > deadline:
            client.interactions.cancel(id=interaction.id)
            print(f"step {step}: timed out after {STEP_TIMEOUT_MINUTES} min, cancelled", flush=True)
            interaction = client.interactions.get(id=interaction.id)
            break
        time.sleep(POLL_SECONDS)
        interaction = client.interactions.get(id=interaction.id)

    output = interaction.output_text or ""
    (RUNS / f"step-{step}.md").write_text(output)
    state["steps"][str(step)]["status"] = interaction.status
    state.pop("active_interaction_id", None)
    if interaction.status == "completed":
        state["last_interaction_id"] = interaction.id
    save_state(state)
    print(f"step {step}: {interaction.status}\n{output[-1500:]}", flush=True)
    return interaction.status == "completed"


def main():
    load_dotenv(ROOT / ".env")
    client = genai.Client()
    state = load_state()
    command = sys.argv[1] if len(sys.argv) > 1 else "status"

    if command == "run":
        github_token = os.environ["GITHUB_TOKEN"]
        ensure_gemini_credential(client)
        for step in sys.argv[2:]:
            if not run_step(client, state, step, github_token):
                print(f"stopping: step {step} did not complete", flush=True)
                sys.exit(1)
    elif command == "status":
        active = state.get("active_interaction_id")
        if active:
            interaction = client.interactions.get(id=active)
            print(f"active {active}: {interaction.status}")
        print(json.dumps(state["steps"], indent=2))
    elif command == "cancel":
        active = state.get("active_interaction_id")
        if active:
            client.interactions.cancel(id=active)
            print(f"cancelled {active}")
        else:
            print("nothing running")
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
