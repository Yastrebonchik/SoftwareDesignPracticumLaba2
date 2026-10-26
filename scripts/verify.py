#!/usr/bin/env python3
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
BASE = "http://127.0.0.1"


def compose(*args):
    subprocess.run(["docker", "compose", *args], cwd=ROOT, check=True)


def request(path, method="GET", data=None, expected=200):
    body = None if data is None else json.dumps(data).encode()
    req = urllib.request.Request(BASE + path, data=body, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        content = response.read()
        if response.status != expected:
            raise AssertionError(f"{method} {path}: expected {expected}, got {response.status}: {content!r}")
        return json.loads(content) if content else None


def main():
    if not (ROOT / ".env").exists():
        sys.exit("Create .env from .env.example and set your database password first.")
    compose("up", "--build", "--detach", "--wait", "--wait-timeout", "180")
    assert request("/health") == {"status": "UP"}
    marker = f"Persistence check {time.time_ns()}"
    note = request("/notes", "POST", {"text": marker}, 201)
    path = f"/notes/{note['id']}"
    assert request(path)["text"] == marker
    assert any(item["id"] == note["id"] for item in request("/notes"))
    for body in ({}, {"text": "   "}, {"text": "x" * 1001}):
        request("/notes", "POST", body, 400)
    print("PASS: health, create, read, list, input validation", flush=True)

    compose("restart")
    compose("up", "--detach", "--wait", "--wait-timeout", "180")
    assert request(path)["text"] == marker
    print("PASS: PostgreSQL data survives restart", flush=True)

    compose("down")
    compose("up", "--detach", "--wait", "--wait-timeout", "180")
    assert request(path)["text"] == marker
    print("PASS: PostgreSQL data survives container recreation", flush=True)

    ids = subprocess.check_output(["docker", "compose", "ps", "--quiet"], cwd=ROOT, text=True).split()
    containers = json.loads(subprocess.check_output(["docker", "inspect", *ids], text=True))
    assert len(containers) == 3
    for container in containers:
        service = container["Config"]["Labels"]["com.docker.compose.service"]
        assert container["State"]["Health"]["Status"] == "healthy", service
        bindings = container["HostConfig"]["PortBindings"] or {}
        if service == "nginx":
            assert set(bindings) == {"80/tcp"}, bindings
            assert all(port["HostPort"] == "80" for port in bindings["80/tcp"])
        else:
            assert not bindings, (service, bindings)
        networks = set(container["NetworkSettings"]["Networks"])
        expected = {"app": {"frontend", "backend"}, "nginx": {"frontend"}, "db": {"backend"}}[service]
        assert {name.rsplit("_", 1)[-1] for name in networks} == expected, (service, networks)
        if service == "app":
            assert container["Config"]["User"] == "app:app"
            assert container["HostConfig"]["ReadonlyRootfs"]
    print("PASS: all containers healthy, networks, non-root app, only nginx publishes port 80", flush=True)

    compose("stop", "db")
    try:
        assert request("/health", expected=503) == {"status": "DOWN"}
        print("PASS: health detects database outage", flush=True)
    finally:
        compose("up", "--detach", "--wait", "--wait-timeout", "180")
    assert request("/health") == {"status": "UP"}
    request(path, "DELETE", expected=204)
    request(path, expected=404)
    request(path, "DELETE", expected=404)
    print("PASS: recovery, delete, missing note returns 404", flush=True)
    compose("ps")


if __name__ == "__main__":
    main()
