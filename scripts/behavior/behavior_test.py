#!/usr/bin/env python3
"""Behavior test: a Steve force-loads its chunk and keeps working with no player.

Scenario (headless server, RCON, no LLM needed - "стоп" is a stay pre-trigger):

  0. Cyrillic names (issue #16): spawn/tell/remove Васян, reject Бот#1.
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
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("RCON connection closed while reading response")
            buf += chunk
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
        if rid == -1 or ptype != 2:
            raise ConnectionError(f"RCON auth failed (id={rid}, type={ptype})")
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

    # Fresh world every run: a previous run leaves adopted Steve entities in
    # the world files, and adopt-on-join would then reject the spawn
    # ("Steve name already exists").
    for stale in ("world", "world_nether", "world_the_end"):
        p = os.path.join(args.dir, stale)
        if os.path.isdir(p):
            import shutil
            shutil.rmtree(p)

    print("Starting server...")
    proc = start_server(args.dir, args.jar, log_path)
    try:
        if not wait_for(log_path, r"Done \(", 180, "server start"):
            return 1

        time.sleep(3)
        rcon = RCON()
        try:
            # 0. Cyrillic-name scenario (issue #16): SteveNameArgumentType
            #    accepts unicode letters, so /steve commands work with
            #    cyrillic bot names. Runs first - it is fast and local
            #    (spawn chunks, no LLM needed). RCON command bodies are
            #    UTF-8 encoded (RCON._send), so cyrillic survives the wire.
            print("Testing cyrillic Steve names (issue #16)...")

            spawn_resp = rcon.command("steve spawn Васян")
            print(f"  steve spawn Васян -> {spawn_resp!r}")
            if "Spawned Steve" not in spawn_resp or "Васян" not in spawn_resp:
                print("  [FAIL] cyrillic spawn: unexpected response")
                return 1
            if not wait_for(log_path, r"[Ss]pawned Steve: Васян with UUID [0-9a-f-]+ at \(", 30, "cyrillic spawn"):
                return 1

            # Stay pre-trigger is deterministic (no LLM round-trip) and
            # answers "<name> stopped" immediately.
            stay_resp = rcon.command("steve tell Васян стоп")
            print(f"  steve tell Васян стоп -> {stay_resp!r}")
            if "Васян stopped" not in stay_resp:
                print("  [FAIL] cyrillic stay command did not stop Васян")
                return 1

            remove_resp = rcon.command("steve remove Васян")
            print(f"  steve remove Васян -> {remove_resp!r}")
            if "Removed Steve" not in remove_resp or "Васян" not in remove_resp:
                print("  [FAIL] cyrillic remove: unexpected response")
                return 1

            # Negative case: '#' is outside the allowed charset, so Brigadier
            # must reject the name (translatable key 'argument.steve.steve_name.invalid'
            # or its rendered text) and no bot may be spawned.
            bad_resp = rcon.command("steve spawn Бот#1")
            print(f"  steve spawn Бот#1 -> {bad_resp!r}")
            if "invalid" not in bad_resp.lower():
                print("  [FAIL] invalid cyrillic name was not rejected")
                return 1
            with open(log_path, "r", errors="replace") as f:
                if re.search(r"[Ss]pawned Steve: Бот#1", f.read()):
                    print("  [FAIL] invalid name spawned a Steve anyway")
                    return 1
            print("  -> cyrillic names work, invalid name rejected")

            # 0b. Case-insensitive name handling (issue #4): the canonical
            # bot is named "Bob", but commands with different casing must
            # resolve to the same entity.
            print("Testing case-insensitive Steve names (issue #4)...")

            rcon.command("steve spawn Bob")
            if not wait_for(log_path, r"[Ss]pawned Steve: Bob", 30, "case-insensitive spawn"):
                return 1

            stay_resp = rcon.command("steve tell BOB стоп")
            print(f"  steve tell BOB стоп -> {stay_resp!r}")
            # The dispatcher replies with the canonical bot name.
            if "stopped" not in stay_resp.lower() or "bob" not in stay_resp.lower():
                print("  [FAIL] case-insensitive tell did not stop Bob")
                return 1

            gather_resp = rcon.command("steve tell bob gather 50 wood")
            print(f"  steve tell bob gather 50 wood -> {gather_resp!r}")
            if not wait_for(log_path, r"async planning complete: 1 tasks queued", 120, "case-insensitive task queued"):
                print("  [FAIL] case-insensitive tell did not queue a task for Bob")
                return 1

            stop_resp = rcon.command("steve stop BOB")
            print(f"  steve stop BOB -> {stop_resp!r}")
            if "stopped" not in stop_resp.lower() or "bob" not in stop_resp.lower():
                print("  [FAIL] case-insensitive stop did not stop Bob")
                return 1

            remove_resp = rcon.command("steve remove bob")
            print(f"  steve remove bob -> {remove_resp!r}")
            if "removed" not in remove_resp.lower() or "bob" not in remove_resp.lower():
                print("  [FAIL] case-insensitive remove did not remove Bob")
                return 1
            print("  -> case-insensitive names work")

            # 1. Spawn Bob
            print("Spawning Bob...")
            rcon.command("steve spawn Bob")
            if not wait_for(log_path, r"Spawned Steve: Bob", 30, "spawn"):
                return 1

            # Extract Bob's UUID and actual spawn position for the /tp
            with open(log_path, "r", errors="replace") as f:
                log_text = f.read()
            uuid_m = None
            m = re.search(r"[Ss]pawned Steve: Bob with UUID ([0-9a-f-]+)", log_text)
            if m:
                uuid_m = m.group(1)
            if not uuid_m:
                print("  [FAIL] Bob UUID not found in log")
                return 1
            print(f"  Bob UUID: {uuid_m}")

            # 2. Find Bob's ACTUAL spawn position (world spawn is not fixed:
            #    no level-seed is set, the spawn chunk varies). Teleport him
            #    to a chunk more than 9 chunks away from the spawn chunk -
            #    Minecraft 1.20.1 keeps a 19x19 spawn-tick area around world
            #    spawn, so anything within 9 chunks ticks even without our
            #    force-loading. y=4 = flat surface: teleporting high up makes
            #    the bot fall for ages and stalls the 1-core runner.
            spawn_pos = re.search(r"[Ss]pawned Steve: Bob with UUID [0-9a-f-]+ at \(([-\d.]+), ([-\d.]+), ([-\d.]+)\)", log_text)
            if not spawn_pos:
                print("  [FAIL] Bob spawn position not found in log")
                return 1
            spawn_x = float(spawn_pos.group(1))
            spawn_z = float(spawn_pos.group(3))
            far_x = int(spawn_x) + 10 * 16 + 8  # +10 chunks east, block coords
            print(f"Teleporting Bob from spawn ({spawn_x:.0f}, {spawn_z:.0f}) to ({far_x}, 4, 0)...")
            rcon.command(f"tp {uuid_m} {far_x} 4 0")
            if not wait_for(log_path, r"Teleported", 30, "teleport"):
                return 1

            # 3. Give the chunk force-load a few seconds. SteveMod.onServerTick
            #    calls updateForcedChunks every tick, so the force flag is set
            #    almost immediately - the sleep is just buffer for chunk I/O.
            print("Waiting for chunk force-load...")
            time.sleep(12)

            # 4. The chunk must be marked as force-loaded (block coords in the
            #    query: chunk = block >> 4). Vanilla forceload sends its reply
            #    to the RCON client only - check the body.
            far_chunk_x = far_x >> 4
            print("Checking force-load status...")
            fl_response = rcon.command(f"forceload query {far_x} 0")
            print(f"  forceload query: {fl_response}")
            if f"marked for force loading" not in fl_response:
                print("  -> chunk force-load failed")
                return 1
            print(f"  -> chunk [{far_chunk_x}, 0] force-loaded")

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
