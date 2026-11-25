#!/usr/bin/env python3
"""Manage the phones Claude Code can control through Phone MCP.

Each phone is a user-scope Claude Code MCP server (named "phone" or "phone-<name>").
Friendly aliases ("work phone", "Moto") live in ~/.config/phone-mcp/phones.json.
Tokens are only stored in Claude Code's own config, never in the registry or the output.

  phones.py add HOST[:PORT] [--name NAME] [--alias ALIAS ...] [--token-file FILE]
  phones.py alias NAME ALIAS [ALIAS ...]
  phones.py unalias ALIAS [ALIAS ...]
  phones.py resolve NAME_OR_ALIAS
  phones.py list
  phones.py remove NAME
"""
import argparse
import json
import os
import re
import subprocess
import sys
import unicodedata
import urllib.error
import urllib.request
from pathlib import Path

HOME = Path.home()
CLAUDE_JSON = HOME / ".claude.json"
REGISTRY = Path(os.environ.get("XDG_CONFIG_HOME", HOME / ".config")) / "phone-mcp" / "phones.json"
DEFAULT_PORT = 8765
NAME_RE = re.compile(r"^[A-Za-z0-9_-]+$")


def fail(message):
    sys.exit(f"error: {message}")


def load_registry():
    if REGISTRY.exists():
        return json.loads(REGISTRY.read_text())
    return {"phones": {}}


def save_registry(registry):
    REGISTRY.parent.mkdir(parents=True, exist_ok=True)
    tmp = REGISTRY.with_suffix(".tmp")
    tmp.write_text(json.dumps(registry, indent=2, ensure_ascii=False) + "\n")
    tmp.chmod(0o600)
    tmp.replace(REGISTRY)


def claude_servers():
    if not CLAUDE_JSON.exists():
        return {}
    return json.loads(CLAUDE_JSON.read_text()).get("mcpServers", {})


def server_token(name):
    server = claude_servers().get(name)
    if not server:
        return None
    return server.get("headers", {}).get("Authorization", "").removeprefix("Bearer ").strip() or None


def slug(text):
    ascii_text = unicodedata.normalize("NFKD", text.replace("ł", "l").replace("Ł", "L"))
    ascii_text = ascii_text.encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_text.lower()).strip("-")


def norm_alias(alias):
    return " ".join(alias.lower().split())


def alias_owner(registry, alias):
    key = norm_alias(alias)
    for name, phone in registry["phones"].items():
        if key == name.lower() or key in (norm_alias(a) for a in phone.get("aliases", [])):
            return name
    return None


def ping(url, token):
    request = urllib.request.Request(
        url,
        data=b'{"jsonrpc":"2.0","id":1,"method":"ping"}',
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as e:
        return e.code
    except Exception:
        return None


def status_text(code):
    return {200: "connected", 401: "token rejected"}.get(code, "unreachable" if code is None else f"HTTP {code}")


def claude_mcp(*args):
    result = subprocess.run(["claude", "mcp", *args], cwd=HOME, capture_output=True, text=True)
    return result.returncode == 0, (result.stdout + result.stderr).strip()


def cmd_add(args):
    registry = load_registry()
    name = args.name or (f"phone-{slug(args.alias[0])}" if args.alias else "phone")
    if not NAME_RE.match(name):
        fail("names may only contain letters, digits, - and _")
    for alias in args.alias:
        owner = alias_owner(registry, alias)
        if owner and owner != name:
            fail(f"alias '{alias}' already belongs to {owner}")

    if args.token_file:
        token = Path(args.token_file).expanduser().read_text().strip()
    else:
        token = server_token(name)
        if not token:
            fail(f"no server named {name} yet; pass --token-file")
    if not token:
        fail("the token is empty")

    host = args.host if ":" in args.host else f"{args.host}:{DEFAULT_PORT}"
    url = f"http://{host}/mcp"
    config = json.dumps({"type": "http", "url": url, "headers": {"Authorization": f"Bearer {token}"}})
    claude_mcp("remove", name, "--scope", "user")
    ok, output = claude_mcp("add-json", "--scope", "user", name, config)
    if not ok:
        fail(f"claude mcp add-json failed: {output.replace(token, '***')}")

    phone = registry["phones"].setdefault(name, {"aliases": []})
    phone["url"] = url
    for alias in args.alias:
        if norm_alias(alias) not in (norm_alias(a) for a in phone["aliases"]):
            phone["aliases"].append(alias)
    save_registry(registry)

    code = ping(url, token)
    aliases = ", ".join(phone["aliases"]) or "none"
    print(f"{name} -> {url} ({status_text(code)}); aliases: {aliases}")
    print("Restart Claude Code sessions to load it.")


def cmd_alias(args):
    registry = load_registry()
    if args.name not in registry["phones"] and args.name not in claude_servers():
        fail(f"no phone named {args.name}")
    phone = registry["phones"].setdefault(args.name, {"aliases": []})
    for alias in args.aliases:
        owner = alias_owner(registry, alias)
        if owner and owner != args.name:
            fail(f"alias '{alias}' already belongs to {owner}")
        if norm_alias(alias) not in (norm_alias(a) for a in phone["aliases"]):
            phone["aliases"].append(alias)
    save_registry(registry)
    print(f"{args.name}: aliases {', '.join(phone['aliases'])}")


def cmd_unalias(args):
    registry = load_registry()
    for alias in args.aliases:
        owner = alias_owner(registry, alias)
        if not owner or owner.lower() == norm_alias(alias):
            fail(f"'{alias}' is not an alias")
        phone = registry["phones"][owner]
        phone["aliases"] = [a for a in phone["aliases"] if norm_alias(a) != norm_alias(alias)]
        print(f"removed '{alias}' from {owner}")
    save_registry(registry)


def cmd_resolve(args):
    registry = load_registry()
    owner = alias_owner(registry, args.query)
    if not owner and args.query in claude_servers():
        owner = args.query
    if not owner:
        fail(f"no phone called '{args.query}'; run: phones.py list")
    print(owner)


def cmd_list(_args):
    registry = load_registry()
    servers = claude_servers()
    names = sorted(set(registry["phones"]) | {n for n in servers if n == "phone" or n.startswith("phone-")})
    if not names:
        print("No phones configured.")
        return
    for name in names:
        phone = registry["phones"].get(name, {})
        server = servers.get(name)
        url = server.get("url") if server else phone.get("url", "?")
        state = status_text(ping(url, server_token(name))) if server else "not registered in Claude Code"
        aliases = ", ".join(phone.get("aliases", [])) or "-"
        print(f"{name}\n  aliases: {aliases}\n  url:     {url}\n  status:  {state}\n  tools:   mcp__{name}__*")


def cmd_remove(args):
    registry = load_registry()
    ok, _ = claude_mcp("remove", args.name, "--scope", "user")
    removed = registry["phones"].pop(args.name, None) is not None
    if not ok and not removed:
        fail(f"no phone named {args.name}")
    save_registry(registry)
    print(f"removed {args.name}. Restart Claude Code sessions to drop its tools.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("add", help="register or re-point a phone")
    p.add_argument("host", help="phone address: LAN IP, Tailscale IP or host name, optionally :port")
    p.add_argument("--name", help="MCP server name (default: phone-<first alias>, or phone)")
    p.add_argument("--alias", action="append", default=[], help="friendly name; repeat for more")
    p.add_argument("--token-file", help="file holding the phone's token (default: keep the current one)")
    p.set_defaults(func=cmd_add)

    p = sub.add_parser("alias", help="add aliases to a phone")
    p.add_argument("name")
    p.add_argument("aliases", nargs="+")
    p.set_defaults(func=cmd_alias)

    p = sub.add_parser("unalias", help="remove aliases")
    p.add_argument("aliases", nargs="+")
    p.set_defaults(func=cmd_unalias)

    p = sub.add_parser("resolve", help="print the MCP server name for a name or alias")
    p.add_argument("query")
    p.set_defaults(func=cmd_resolve)

    sub.add_parser("list", help="list phones with aliases and status").set_defaults(func=cmd_list)

    p = sub.add_parser("remove", help="remove a phone")
    p.add_argument("name")
    p.set_defaults(func=cmd_remove)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
