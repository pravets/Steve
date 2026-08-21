# Issue #4 — Case-insensitive Vasyan names Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all `/vasyan` commands that accept a Vasyan name match bots regardless of case (`/vasyan tell VASYAN ...`, `/vasyan tell vasyan ...`, `/vasyan tell Vasyan ...` behave identically) and add behavior tests proving it.

**Architecture:** Keep canonical names unchanged in `VasyanManager.activeVasyans` so `/vasyan list` and chat feedback preserve the player-supplied casing. Introduce a single private case-insensitive lookup helper used by every code path that resolves a name to an entity: `getVasyan(String)`, `adopt`, `spawnVasyan`, `removeVasyan`, and `onVasyanUnload`. The world sweep in `removeVasyan` and `findVasyanInLevel` also compare with `equalsIgnoreCase`. Because the active Vasyan count is tiny (≤10), a linear scan is sufficient and avoids the complexity of a parallel normalized map.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Mockito, Gradle. Python 3 behavior tests using RCON.

**Spec:** GitHub issue #4 in `pravets/Vasyan` — "Имена ботов в командах должны учитываться регистронезависимо".

## Global Constraints

- Branch from `master`; one PR = one feature; do not commit to `master`.
- Commit identity for `pravets/*` repos: `Iosif Pravets <i@pravets.ru>`.
- TDD: failing test first, then minimal implementation, then pass.
- No bundled unrelated changes; do not refactor beyond what the fix requires.
- Preserve existing public method signatures and field names.
- Behavior tests must run against a headless Forge server via RCON; reuse the existing `scripts/behavior/behavior_test.py` harness.

---

### Task 1: Add case-insensitive name lookup to VasyanManager

**Files:**
- Modify: `src/main/java/com/vasyan/ai/entity/VasyanManager.java`

**Interfaces:**
- Consumes: existing `Map<String, VasyanEntity> activeVasyans` keyed by canonical (player-supplied) name.
- Produces: private helper `VasyanEntity findByNameIgnoreCase(String name)` returning the tracked entity whose key matches `name.equalsIgnoreCase`, or `null`. All existing public methods keep their signatures; only internal lookups change.

- [ ] **Step 1: Open `src/main/java/com/vasyan/ai/entity/VasyanManager.java`**

- [ ] **Step 2: Add a private helper after the constructors**

```java
    private VasyanEntity findByNameIgnoreCase(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, VasyanEntity> entry : activeVasyans.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
```

- [ ] **Step 3: Replace `activeVasyans.get(name)` lookups with `findByNameIgnoreCase(name)`**

Update these locations in order:

1. In `adopt(VasyanEntity vasyan)` (around line 53):
   - Change `VasyanEntity existing = activeVasyans.get(name);` to `VasyanEntity existing = findByNameIgnoreCase(name);`

2. In `spawnVasyan(ServerLevel, Vec3, String)`:
   - Change `if (activeVasyans.containsKey(name)) {` to `if (findByNameIgnoreCase(name) != null) {`
   - Change `if (activeVasyans.get(name) == vasyan && vasyan.isAlive()) {` to `if (findByNameIgnoreCase(name) == vasyan && vasyan.isAlive()) {`

3. In `findVasyanInLevel(ServerLevel, String)` (around line 167):
   - Change `&& name.equals(vasyan.getVasyanName())` to `&& name.equalsIgnoreCase(vasyan.getVasyanName())`

4. In `getVasyan(String name)` (around line 175):
   - Change `return name == null ? null : activeVasyans.get(name);` to `return name == null ? null : findByNameIgnoreCase(name);`

5. In `removeVasyan(String, MinecraftServer)` (around line 216):
   - Change `VasyanEntity trackedInWorldSweep = activeVasyans.get(name);` to `VasyanEntity trackedInWorldSweep = findByNameIgnoreCase(name);`

6. In `onVasyanUnload(VasyanEntity)` (around line 251):
   - Change `VasyanEntity tracked = activeVasyans.get(name);` to `VasyanEntity tracked = findByNameIgnoreCase(name);`

- [ ] **Step 4: Verify the file compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vasyan/ai/entity/VasyanManager.java
git -c user.name="Iosif Pravets" -c user.email="i@pravets.ru" commit -m "fix(VasyanManager): case-insensitive lookup of Vasyan names

All name-based lookups (getVasyan, adopt, spawnVasyan, removeVasyan,
onVasyanUnload) now match regardless of letter case while preserving
the canonical casing stored at registration. World sweep uses
equalsIgnoreCase as well.

Closes #4"
```

---

### Task 2: Add unit tests for case-insensitive VasyanManager behavior

**Files:**
- Modify: `src/test/java/com/vasyan/ai/entity/VasyanManagerTest.java`

**Interfaces:**
- Consumes: `mockVasyan` helper from `VasyanManagerTest`; public `VasyanManager` methods updated in Task 1.
- Produces: new test methods proving case-insensitive behavior for lookup, dedup, spawn guard, remove, and unload.

- [ ] **Step 1: Open `src/test/java/com/vasyan/ai/entity/VasyanManagerTest.java`**

- [ ] **Step 2: Append the following tests at the end of the class, before the closing brace**

```java
    // ==================== case-insensitive name handling (issue #4) ====================

    @Test
    void getVasyanMatchesIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity vasyan = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(vasyan);

        assertSame(vasyan, manager.getVasyan("Vasyan"));
        assertSame(vasyan, manager.getVasyan("vasyan"));
        assertSame(vasyan, manager.getVasyan("VASYAN"));
        assertSame(vasyan, manager.getVasyan("StEvE"));
    }

    @Test
    void adoptRejectsDuplicateIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity original = mockVasyan("Vasyan", UUID.randomUUID());
        VasyanEntity duplicate = mockVasyan("vasyan", UUID.randomUUID());

        assertSame(original, manager.adopt(original));
        assertNull(manager.adopt(duplicate));

        assertSame(original, manager.getVasyan("Vasyan"));
        verify(duplicate, never()).discard();
    }

    @Test
    void spawnVasyanRejectsDuplicateIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity existing = mockVasyan("Vasyan", UUID.randomUUID());
        manager.adopt(existing);

        ServerLevel level = mock(ServerLevel.class);

        assertNull(manager.spawnVasyan(level, new net.minecraft.world.phys.Vec3(0, 0, 0), "vasyan"));
        assertNull(manager.spawnVasyan(level, new net.minecraft.world.phys.Vec3(0, 0, 0), "VASYAN"));
        assertSame(existing, manager.getVasyan("Vasyan"));
    }

    @Test
    void removeVasyanMatchesIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        VasyanEntity vasyan = mockVasyan("Bob", UUID.randomUUID());
        manager.adopt(vasyan);

        assertTrue(manager.removeVasyan("BOB", null));
        assertNull(manager.getVasyan("Bob"));
        assertEquals(0, manager.getActiveCount());
        verify(vasyan).discard();
    }

    @Test
    void onVasyanUnloadMatchesIgnoringCase() {
        VasyanManager manager = new VasyanManager();
        UUID uuid = UUID.randomUUID();
        VasyanEntity vasyan = mockVasyan("Alex", uuid);
        manager.adopt(vasyan);

        manager.onVasyanUnload(vasyan);

        assertNull(manager.getVasyan("alex"));
        assertNull(manager.getVasyan(uuid));
        assertEquals(0, manager.getActiveCount());
    }
```

- [ ] **Step 3: Run only the new tests**

Run: `./gradlew test --tests "com.vasyan.ai.entity.VasyanManagerTest"`
Expected: all tests pass; specifically the five new case-insensitive tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/vasyan/ai/entity/VasyanManagerTest.java
git -c user.name="Iosif Pravets" -c user.email="i@pravets.ru" commit -m "test(VasyanManager): case-insensitive name lookups

Unit tests covering getVasyan, adopt dedup, spawnVasyan guard, removeVasyan,
and onVasyanUnload with mismatched letter casing.

Relates #4"
```

---

### Task 3: Extend behavior tests to verify case-insensitive command names

**Files:**
- Modify: `scripts/behavior/behavior_test.py`

**Interfaces:**
- Consumes: existing RCON harness, server start/stop, `wait_for`, and the existing Cyrillic + Bob scenarios.
- Produces: a new scenario that spawns `Bob`, sends `/vasyan tell BOB стоп`, `/vasyan tell bob gather 50 wood`, and `/vasyan stop BOB`, asserting each command resolves to the same bot.

- [ ] **Step 1: Open `scripts/behavior/behavior_test.py`**

- [ ] **Step 2: Insert a new scenario after the cyrillic block and before the original Bob spawn block**

Locate the comment:

```python
            print("  -> cyrillic names work, invalid name rejected")

            # 1. Spawn Bob
```

Replace that transition with:

```python
            print("  -> cyrillic names work, invalid name rejected")

            # 0b. Case-insensitive name handling (issue #4): the canonical
            # bot is named "Bob", but commands with different casing must
            # resolve to the same entity.
            print("Testing case-insensitive Vasyan names (issue #4)...")

            rcon.command("vasyan spawn Bob")
            if not wait_for(log_path, r"[Ss]pawned Vasyan: Bob", 30, "case-insensitive spawn"):
                return 1

            stay_resp = rcon.command("vasyan tell BOB стоп")
            print(f"  vasyan tell BOB стоп -> {stay_resp!r}")
            # The dispatcher replies using the canonical bot name, e.g. "Bob stopped".
            if "stopped" not in stay_resp.lower() or "bob" not in stay_resp.lower():
                print("  [FAIL] case-insensitive tell did not stop Bob")
                return 1

            gather_resp = rcon.command("vasyan tell bob gather 50 wood")
            print(f"  vasyan tell bob gather 50 wood -> {gather_resp!r}")
            if not wait_for(log_path, r"async planning complete: 1 tasks queued", 120, "case-insensitive task queued"):
                print("  [FAIL] case-insensitive tell did not queue a task for Bob")
                return 1

            stop_resp = rcon.command("vasyan stop BOB")
            print(f"  vasyan stop BOB -> {stop_resp!r}")
            # stopVasyan returns "Stopped Vasyan: " + the supplied argument name.
            if "stopped" not in stop_resp.lower() or "bob" not in stop_resp.lower():
                print("  [FAIL] case-insensitive stop did not stop Bob")
                return 1

            remove_resp = rcon.command("vasyan remove bob")
            print(f"  vasyan remove bob -> {remove_resp!r}")
            if "Removed Vasyan" not in remove_resp or "bob" not in remove_resp.lower():
                print("  [FAIL] case-insensitive remove did not remove Bob")
                return 1
            print("  -> case-insensitive names work")

            # 1. Spawn Bob
```

- [ ] **Step 3: Verify Python syntax**

Run: `python3 -m py_compile scripts/behavior/behavior_test.py`
Expected: no output (success).

- [ ] **Step 4: Commit**

```bash
git add scripts/behavior/behavior_test.py
git -c user.name="Iosif Pravets" -c user.email="i@pravets.ru" commit -m "test(behavior): case-insensitive command names (issue #4)

Add an RCON scenario that drives /vasyan tell / stop / remove with
uppercase and lowercase variants of the canonical name 'Bob'.

Relates #4"
```

---

### Task 4: Run full unit-test suite and verify green baseline

**Files:**
- None (verification task).

**Interfaces:**
- Consumes: implementation and tests from Tasks 1–3.
- Produces: test report confirming no regressions.

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all existing + new tests pass.

- [ ] **Step 2: If any test fails, diagnose and fix**

Use `superpowers:systematic-debugging`. Do not proceed to final review until the suite is green.

- [ ] **Step 3: Commit any fixes**

If no fixes were needed, no commit. If fixes were made, commit with a clear message describing the regression fixed.

---

## Self-Review Checklist

1. **Spec coverage:** Does the plan address every requirement from issue #4?
   - [x] `getVasyan` is case-insensitive.
   - [x] Spawn duplicate check is case-insensitive.
   - [x] `/vasyan tell`, `/vasyan tp`, `/vasyan stop`, `/vasyan remove`, `/vasyan inventory` work because they all route through `VasyanManager.getVasyan` or `spawnVasyan`/`removeVasyan`.
   - [x] Behavior tests added.
2. **Placeholder scan:** No TODO/TBD; every step contains exact code or exact commands.
3. **Type consistency:** `findByNameIgnoreCase` returns `VasyanEntity`; signatures of public methods unchanged.
4. **No unrelated changes:** Only `VasyanManager.java`, `VasyanManagerTest.java`, and `behavior_test.py` are touched.
