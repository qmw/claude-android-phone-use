#!/usr/bin/env python3
"""Smoke-test a running Phone MCP server: initialize, list tools, read screen info.

Usage: scripts/smoke_test.py http://PHONE_IP:8765/mcp TOKEN [--screenshot out.jpg]
"""
import base64
import json
import sys
import urllib.request


def rpc(url, token, method, params=None, msg_id=1):
    body = {"jsonrpc": "2.0", "method": method, "params": params or {}}
    if msg_id is not None:
        body["id"] = msg_id
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers={
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
    })
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read()
        return json.loads(raw) if raw else None


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    url, token = sys.argv[1], sys.argv[2]
    init = rpc(url, token, "initialize", {
        "protocolVersion": "2025-06-18", "capabilities": {},
        "clientInfo": {"name": "smoke-test", "version": "0"}})
    print("server:", init["result"]["serverInfo"], "protocol:", init["result"]["protocolVersion"])
    rpc(url, token, "notifications/initialized", msg_id=None)
    tools = rpc(url, token, "tools/list", msg_id=2)["result"]["tools"]
    print("tools:", ", ".join(t["name"] for t in tools))
    info = rpc(url, token, "tools/call", {"name": "get_screen_info", "arguments": {}}, msg_id=3)
    print(info["result"]["content"][0]["text"])
    if "--screenshot" in sys.argv:
        out = sys.argv[sys.argv.index("--screenshot") + 1]
        shot = rpc(url, token, "tools/call", {"name": "screenshot", "arguments": {}}, msg_id=4)["result"]
        if shot["isError"]:
            sys.exit("screenshot failed: " + shot["content"][0]["text"])
        with open(out, "wb") as f:
            f.write(base64.b64decode(shot["content"][0]["data"]))
        print("saved", out, "-", shot["content"][1]["text"])


if __name__ == "__main__":
    main()
