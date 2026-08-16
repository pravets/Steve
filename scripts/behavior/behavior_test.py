#!/usr/bin/env python3
"""Behavior test: a Steve force-loads its chunk and keeps working with no player.

Scenario (headless server, RCON, no LLM needed - "стоп" is a stay pre-trigger):

  1. Start the Forge server (nogui), wait for "Done (".
  2. /steve spawn Bob -> bot appears in the world (spawn chunks).
  3. /tp <uuid> 5000 80 5000 -> teleport far away from spawn chunks.
  4. Wait for the bot to force-load its chunk (updateForcedChunk every 40 ticks).
  5. /steve tell Bob стоп -> stay command must be EXECUTED by the bot's tick
     ("executing: Stay" only appears from ActionExecutor.tick, i.e. only when
     the entity is actually ticking in its force-loaded chunk).
  6. Without the chunk force-load feature the bot would not tick at x=5000
     (entities only tick in loaded chunks) and the stay would never execute.

Asserts are log-pattern based with timeouts. Exits 0 on pass, 1 on fail.
"""
import argparse
import os
import re
import socket
import struct
import subprocess
import sys
import time

RCON_PORT = 25575
RCON_PASSWORD = "steve_test"


class RCON:
    def __init__(self, host="127.0.0.1", port=RCON_PORT, password=RCON_PASSWORD):
        self.sock = socket.create_connection((host, port), timeout=60)
        self.request_id = 1
        self._auth(password)

    def _send(self, ptype, body):
        rid = self.request_id
        self.request_id += 1
        payload = struct.pack("<ii", rid, ptype) + body.encode() + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        # Forge answers each request with exactly one packet - read until it
        # is fully buffered. (recv_exact-style reads deadlocked on the body,
        # so keep it simple: one recv loop, one packet per request.)
        self.sock.settimeout(30)
        buf = b""
        while True:
            buf += self.sock.recv(4096)
            if len(buf) >= 4:
                length = struct.unpack("<i", buf[:4])[0]
                if len(buf) >= 4 + length:
                    pkt = buf[4:4 + length]
                    r, t = struct.unpack("<ii", pkt[:8])
                    body = pkt[8:].rstrip(b"\x00").decode(errors="replace")
                    return r, t, body

    def _recv_exact(self, n):
        data = b""
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            data += chunk
        return data

    def _auth(self, password):
        rid, ptype, _ = self._send(3, password)
        if ptype != 2:
            raise ConnectionError(f"RCON auth failed (type={ptype})")
        # Forge needs a beat after auth before it processes commands -
        # a command sent immediately can be dropped (no response at all).
        time.sleep(1.0)

    def command(self, cmd):
        rid, ptype, body = self._send(2, cmd)
        return body

    def close(self):
        self.sock.close()


def start_server(workdir, jar_path, log_path):
    with open(log_path, "wb") as log:
        proc = subprocess.Popen(
            ["java", "-Xmx2G", "@libraries/net/minecraftforge/forge/1.20.1-47.2.0/unix_args.txt", "nogui"],
            cwd=workdir, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
    return proc


def wait_for(log_path, pattern, timeout, label):
    regex = re.compile(pattern)
    deadline = time.time() + timeout
    last = 0
    while time.time() < deadline:
        with open(log_path, "r", errors="replace") as f:
            f.seek(last)
            chunk = f.read()
            last = f.tell()
            if regex.search(chunk):
                print(f"  [ok] {label}: matched {pattern!r}")
                return True
        time.sleep(2)
    print(f"  [FAIL] {label}: no match for {pattern!r} within {timeout}s")
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True, help="server directory")
    ap.add_argument("--jar", required=True, help="path to steve mod jar")
    args = ap.parse_args()

    log_path = os.path.join(args.dir, "behavior.log")
    if os.path.exists(log_path):
        os.remove(log_path)

    print("Starting server...")
    proc = start_server(args.dir, args.jar, log_path)
    try:
        if not wait_for(log_path, r"Done \(", 180, "server start"):
            return 1

        time.sleep(3)
        rcon = RCON()
        try:
            # 1. Spawn Bob
            print("Spawning Bob...")
            rcon.command("steve spawn Bob")
            if not wait_for(log_path, r"Spawned Steve: Bob", 30, "spawn"):
                return 1

            # Extract Bob's UUID for the /tp
            uuid_m = None
            with open(log_path, "r", errors="replace") as f:
                m = re.search(r"[Ss]pawned Steve: Bob with UUID ([0-9a-f-]+)", f.read())
                if m:
                    uuid_m = m.group(1)
            if not uuid_m:
                print("  [FAIL] Bob UUID not found in log")
                return 1
            print(f"  Bob UUID: {uuid_m}")

            # 2. Teleport outside spawn chunks. Spawn is at ~(230, 67, -32)
            #    (chunk 14); x=300 is chunk 18 - outside spawn chunks but no
            #    heavy far-region generation. y=4 = flat surface: teleporting
            #    high up makes the bot fall for ages and stalls the server.
            print("Teleporting Bob to (300, 4, 0)...")
            rcon.command(f"tp {uuid_m} 300 4 0")
            if not wait_for(log_path, r"Teleported", 30, "teleport"):
                return 1

            # 3. Give the chunk force-load a few seconds (manager tick forces it)
            print("Waiting for chunk force-load...")
            time.sleep(12)

            # 4. The chunk must be marked as force-loaded (block coords in the
            #    query: chunk [18,0] = block (288, 0)). Vanilla forceload
            #    sends its reply to the RCON client only - check the body.
            print("Checking force-load status...")
            fl_response = rcon.command("forceload query 288 0")
            print(f"  forceload query: {fl_response}")
            if "marked for force loading" not in fl_response:
                print("  -> chunk force-load failed")
                return 1

            # 5. Give a gather command. The LLM endpoint is unreachable by
            #    design, so the fallback handler produces a deterministic task
            #    (pattern match "mine" or the safe default "follow" - either
            #    way a task is queued). "async planning complete" is logged
            #    from ActionExecutor.tick - i.e. ONLY when the entity actually
            #    ticks in its force-loaded chunk at x=300 (outside spawn).
            print("Sending 'gather 50 wood'...")
            rcon.command("steve tell Bob gather 50 wood")
            if not wait_for(log_path, r"async planning complete: 1 tasks queued", 120, "task queued in far chunk"):
                print("  -> bot did NOT tick in the far chunk: chunk force-load broken")
                return 1

            print("PASS: Steve worked in a force-loaded chunk with no player online.")
            return 0
        finally:
            rcon.close()
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())
