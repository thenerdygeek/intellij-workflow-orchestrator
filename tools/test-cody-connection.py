#!/usr/bin/env python3
"""
Standalone Cody CLI connection test script.

Tests the same JSON-RPC flow the IntelliJ plugin uses:
1. Find cody binary
2. Launch: cody api jsonrpc-stdio
3. Send initialize with token
4. Handle secrets/get if sent
5. Send chat/new + chat/submitMessage

Usage:
  python test-cody-connection.py

Env vars:
  SRC_ACCESS_TOKEN  — Sourcegraph access token (required)
  SRC_ENDPOINT      — Sourcegraph server URL (default: https://sourcegraph.com)
  CODY_BINARY       — Path to cody binary (default: auto-detect)
"""

import asyncio
import json
import os
import shutil
import sys
import platform

MSG_ID = 0

async def send_request(writer, method, params):
    global MSG_ID
    MSG_ID += 1
    msg = {"jsonrpc": "2.0", "id": MSG_ID, "method": method, "params": params}
    body = json.dumps(msg)
    frame = f"Content-Length: {len(body.encode())}\r\n\r\n{body}"
    writer.write(frame.encode())
    await writer.drain()
    print(f"  >>> [{MSG_ID}] {method}")
    return MSG_ID

async def send_notification(writer, method, params):
    msg = {"jsonrpc": "2.0", "method": method, "params": params}
    body = json.dumps(msg)
    frame = f"Content-Length: {len(body.encode())}\r\n\r\n{body}"
    writer.write(frame.encode())
    await writer.drain()
    print(f"  >>> (notification) {method}")

async def read_message(reader, timeout=30):
    try:
        headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), timeout=timeout)
        header_str = headers.decode()
        length = 0
        for line in header_str.strip().split("\r\n"):
            if line.startswith("Content-Length:"):
                length = int(line.split(":")[1].strip())
        body = await asyncio.wait_for(reader.readexactly(length), timeout=timeout)
        return json.loads(body.decode())
    except asyncio.TimeoutError:
        return None
    except Exception as e:
        print(f"  !!! Read error: {e}")
        return None

async def read_response(reader, writer, req_id, token, timeout=30):
    """Read messages until we get the response matching req_id.
    Handle server->client requests (like secrets/get) along the way."""
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0:
            print(f"  !!! Timeout waiting for response to request {req_id}")
            return None
        msg = await read_message(reader, timeout=remaining)
        if msg is None:
            return None

        # Check if it's the response we're waiting for
        if "id" in msg and msg["id"] == req_id:
            if "error" in msg:
                print(f"  <<< [{msg['id']}] ERROR: {msg['error']}")
            else:
                print(f"  <<< [{msg['id']}] result keys: {list(msg.get('result', {}).keys()) if isinstance(msg.get('result'), dict) else type(msg.get('result')).__name__}")
            return msg

        # Server->client request (e.g., secrets/get)
        if "method" in msg and "id" in msg:
            method = msg["method"]
            print(f"  <<< [server request {msg['id']}] {method}")
            if method == "secrets/get":
                key = msg.get("params", {}).get("key", "")
                print(f"      key='{key}' -> returning token")
                resp = {"jsonrpc": "2.0", "id": msg["id"], "result": token}
                body = json.dumps(resp)
                frame = f"Content-Length: {len(body.encode())}\r\n\r\n{body}"
                writer.write(frame.encode())
                await writer.drain()
            elif method == "secrets/store":
                print(f"      storing secret (acknowledged)")
                resp = {"jsonrpc": "2.0", "id": msg["id"], "result": None}
                body = json.dumps(resp)
                frame = f"Content-Length: {len(body.encode())}\r\n\r\n{body}"
                writer.write(frame.encode())
                await writer.drain()
            else:
                print(f"      (unhandled, returning null)")
                resp = {"jsonrpc": "2.0", "id": msg["id"], "result": None}
                body = json.dumps(resp)
                frame = f"Content-Length: {len(body.encode())}\r\n\r\n{body}"
                writer.write(frame.encode())
                await writer.drain()
            continue

        # Server->client notification
        if "method" in msg and "id" not in msg:
            print(f"  <<< (notification) {msg['method']}")
            continue

        print(f"  <<< (unknown) {json.dumps(msg)[:200]}")

def find_binary():
    custom = os.getenv("CODY_BINARY")
    if custom and os.path.isfile(custom):
        return custom

    is_win = platform.system() == "Windows"
    candidates = ["cody.cmd", "cody"] if is_win else ["cody"]
    for name in candidates:
        path = shutil.which(name)
        if path:
            return path

    return None

async def main():
    token = os.getenv("SRC_ACCESS_TOKEN", "")
    endpoint = os.getenv("SRC_ENDPOINT", "https://sourcegraph.com")

    if not token:
        print("ERROR: SRC_ACCESS_TOKEN not set")
        print("Set it: export SRC_ACCESS_TOKEN=sgp_...")
        sys.exit(1)

    binary = find_binary()
    if not binary:
        print("ERROR: cody binary not found")
        print("Install: npm install -g @sourcegraph/cody")
        sys.exit(1)

    print(f"Platform: {platform.system()} {platform.machine()}")
    print(f"Binary:   {binary}")
    print(f"Endpoint: {endpoint}")
    print(f"Token:    {token[:8]}...{token[-4:]}")
    print()

    # Launch agent
    is_win = platform.system() == "Windows"
    if is_win and binary.endswith(".cmd"):
        cmd = ["cmd.exe", "/c", binary, "api", "jsonrpc-stdio"]
    else:
        cmd = [binary, "api", "jsonrpc-stdio"]

    print(f"[1] Launching: {' '.join(cmd)}")
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        env={**os.environ, "CODY_AGENT_DEBUG_REMOTE": "false"}
    )
    print(f"    PID: {proc.pid}")

    # Start stderr reader
    async def log_stderr():
        while True:
            line = await proc.stderr.readline()
            if not line:
                break
            print(f"  [stderr] {line.decode().rstrip()}")
    asyncio.create_task(log_stderr())

    reader = proc.stdout
    writer = proc.stdin

    # Step 2: Initialize
    print(f"\n[2] Sending initialize...")
    client_info = {
        "name": "cody-connection-test",
        "version": "1.0.0",
        "workspaceRootUri": f"file:///{os.getcwd().replace(os.sep, '/')}",
        "extensionConfiguration": {
            "serverEndpoint": endpoint,
            "accessToken": token,
            "customConfiguration": {}
        },
        "capabilities": {
            "chat": "streaming",
            "edit": "enabled",
            "editWorkspace": "enabled",
            "showDocument": "none",
            "codeActions": "none",
            "codeLenses": "none",
            "completions": "none",
            "git": "none",
            "progressBars": "none",
            "showWindowMessage": "notification",
            "secrets": "client-managed",
            "globalState": "server-managed"
        }
    }
    req_id = await send_request(writer, "initialize", client_info)
    result = await read_response(reader, writer, req_id, token, timeout=30)

    if result is None:
        print("\n  FAIL: initialize timed out")
        proc.terminate()
        sys.exit(1)

    if "error" in result:
        print(f"\n  FAIL: initialize error: {result['error']}")
        proc.terminate()
        sys.exit(1)

    server_info = result.get("result", {})
    print(f"    Server: {server_info.get('name', '?')}")
    print(f"    Authenticated: {server_info.get('authenticated', '?')}")
    auth = server_info.get("authStatus", {})
    if auth:
        print(f"    Username: {auth.get('username', '?')}")
        print(f"    Site version: {auth.get('siteVersion', '?')}")

    if not server_info.get("authenticated"):
        print("\n  FAIL: Not authenticated")
        proc.terminate()
        sys.exit(1)

    # Step 3: Send initialized notification
    print(f"\n[3] Sending initialized notification...")
    await send_notification(writer, "initialized", None)
    await asyncio.sleep(0.5)

    # Step 4: Create chat session
    print(f"\n[4] Creating chat session (chat/new)...")
    req_id = await send_request(writer, "chat/new", None)
    result = await read_response(reader, writer, req_id, token, timeout=15)

    if result is None or "error" in result:
        print(f"\n  FAIL: chat/new failed: {result}")
        proc.terminate()
        sys.exit(1)

    chat_id = result.get("result")
    print(f"    Chat ID: {chat_id}")

    # Step 5: Send a test message
    print(f"\n[5] Sending test message (chat/submitMessage)...")
    submit_params = {
        "id": chat_id,
        "message": {
            "command": "submit",
            "text": "Reply with exactly: CODY_TEST_OK",
            "submitType": "user",
            "addEnhancedContext": False,
            "contextItems": []
        }
    }
    req_id = await send_request(writer, "chat/submitMessage", submit_params)
    result = await read_response(reader, writer, req_id, token, timeout=60)

    if result is None or "error" in result:
        print(f"\n  FAIL: chat/submitMessage failed: {result}")
        proc.terminate()
        sys.exit(1)

    transcript = result.get("result", {})
    messages = transcript.get("messages", [])
    print(f"    Transcript messages: {len(messages)}")
    for i, msg in enumerate(messages):
        speaker = msg.get("speaker", "?")
        text = (msg.get("text") or "")[:200]
        print(f"    [{i}] {speaker}: {text}")

    # Cleanup
    print(f"\n[6] Shutting down...")
    await send_request(writer, "shutdown", None)
    await asyncio.sleep(0.5)
    proc.terminate()

    print(f"\n{'='*50}")
    print(f"  ALL TESTS PASSED")
    print(f"{'='*50}")

if __name__ == "__main__":
    asyncio.run(main())
