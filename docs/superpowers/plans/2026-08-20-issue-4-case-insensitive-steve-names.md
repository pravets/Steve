# Issue #4 — Case-insensitive Steve names Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all `/steve` commands that accept a Steve name match bots regardless of case (`/steve tell STEVE ...`, `/steve tell steve ...`, `/steve tell Steve ...` behave identically) and add behavior tests proving it.

**Architecture:** Keep canonical names unchanged in `SteveManager.activeSteves` so `/steve list` and chat feedback preserve the player-supplied casing. Introduce a single private case-insensitive lookup helper used by every code path that resolves a name to an entity: `getSteve(String)`, `adopt`, `spawnSteve`, `removeSteve`, and `onSteveUnload`. The world sweep in `removeSteve` and `findSteveInLevel` also compare with `equalsIgnoreCase`. Because the active Steve count is tiny (≤10), a linear scan is sufficient and avoids the complexity of a parallel normalized map.

**Tech Stack:** Java 17, Forge 1.20.1, JUnit 5, Mockito, Gradle. Python 3 behavior tests using RCON.

**Spec:** GitHub issue #4 in `pravets/Steve` — "Имена ботов в командах должны учитываться регистронезависимо".

## Global Constraints

- Branch from `master`; one PR = one feature; do not commit to `master`.
- Commit identity for `pravets/*` repos: `Iosif Pravets <i@pravets.ru>`.
- TDD: failing test first, then minimal implementation, then pass.
- No bundled unrelated changes; do not refactor beyond what the fix requires.
- Preserve existing public method signatures and field names.
- Behavior tests must run against a headless Forge server via RCON; reuse the existing `scripts/behavior/behavior_test.py` harness.

---

### Task 1: Add case-insensitive name lookup to SteveManager

**Files:**
- Modify: `src/main/java/com/steve/ai/entity/SteveManager.java`

**Interfaces:**
- Consumes: existing `Map<String, SteveEntity> activeSteves` keyed by canonical (player-supplied) name.
- Produces: private helper `SteveEntity findByNameIgnoreCase(String name)` returning the tracked entity whose key matches `name.equalsIgnoreCase`, or `null`. All existing public methods keep their signatures; only internal lookups change.

- [ ] **Step 1: Open `src/main/java/com/steve/ai/entity/SteveManager.java`**

- [ ] **Step 2: Add a private helper after the constructors**

```java
    private SteveEntity findByNameIgnoreCase(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, SteveEntity> entry : activeSteves.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }
```

- [ ] **Step 3: Replace `activeSteves.get(name)` lookups with `findByNameIgnoreCase(name)`**

Update these locations in order:

1. In `adopt(SteveEntity steve)` (around line 53):
   - Change `SteveEntity existing = activeSteves.get(name);` to `SteveEntity existing = findByNameIgnoreCase(name);`

2. In `spawnSteve(ServerLevel, Vec3, String)`:
   - Change `if (activeSteves.containsKey(name)) {` to `if (findByNameIgnoreCase(name) != null) {`
   - Change `if (activeSteves.get(name) == steve && steve.isAlive()) {` to `if (findByNameIgnoreCase(name) == steve && steve.isAlive()) {`

3. In `findSteveInLevel(ServerLevel, String)` (around line 167):
   - Change `&& name.equals(steve.getSteveName())` to `&& name.equalsIgnoreCase(steve.getSteveName())`

4. In `getSteve(String name)` (around line 175):
   - Change `return name == null ? null : activeSteves.get(name);` to `return name == null ? null : findByNameIgnoreCase(name);`

5. In `removeSteve(String, MinecraftServer)` (around line 216):
   - Change `SteveEntity trackedInWorldSweep = activeSteves.get(name);` to `SteveEntity trackedInWorldSweep = findByNameIgnoreCase(name);`

6. In `onSteveUnload(SteveEntity)` (around line 251):
   - Change `SteveEntity tracked = activeSteves.get(name);` to `SteveEntity tracked = findByNameIgnoreCase(name);`

- [ ] **Step 4: Verify the file compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/steve/ai/entity/SteveManager.java
git -c user.name="Iosif Pravets" -c user.email="i@pravets.ru" commit -m "fix(SteveManager): case-insensitive lookup of Steve names

All name-based lookups (getSteve, adopt, spawnSteve, removeSteve,
onSteveUnload) now match regardless of letter case while preserving
the canonical casing stored at registration. World sweep uses
equalsIgnoreCase as well.

Closes #4"
```

---

### Task 2: Add unit tests for case-insensitive SteveManager behavior

**Files:**
- Modify: `src/test/java/com/steve/ai/entity/SteveManagerTest.java`

**Interfaces:**
- Consumes: `mockSteve` helper from `SteveManagerTest`; public `SteveManager` methods updated in Task 1.
- Produces: new test methods proving case-insensitive behavior for lookup, dedup, spawn guard, remove, and unload.

- [ ] **Step 1: Open `src/test/java/com/steve/ai/entity/SteveManagerTest.java`**

- [ ] **Step 2: Append the following tests at the end of the class, before the closing brace**

```java
    // ==================== case-insensitive name handling (issue #4) ====================

    @Test
    void getSteveMatchesIgnoringCase() {
        SteveManager manager = new SteveManager();
        SteveEntity steve = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(steve);

        assertSame(steve, manager.getSteve("Steve"));
        assertSame(steve, manager.getSteve("steve"));
        assertSame(steve, manager.getSteve("STEVE"));
        assertSame(steve, manager.getSteve("StEvE"));
    }

    @Test
    void adoptRejectsDuplicateIgnoringCase() {
        SteveManager manager = new SteveManager();
        SteveEntity original = mockSteve("Steve", UUID.randomUUID());
        SteveEntity duplicate = mockSteve("steve", UUID.randomUUID());

        assertSame(original, manager.adopt(original));
        assertNull(manager.adopt(duplicate));

        assertSame(original, manager.getSteve("Steve"));
        verify(duplicate, never()).discard();
    }

    @Test
    void spawnSteveRejectsDuplicateIgnoringCase() {
        SteveManager manager = new SteveManager();
        SteveEntity existing = mockSteve("Steve", UUID.randomUUID());
        manager.adopt(existing);

        ServerLevel level = mock(ServerLevel.class);

        assertNull(manager.spawnSteve(level, new net.minecraft.world.phys.Vec3(0, 0, 0), "steve"));
        assertNull(manager.spawnSteve(level, new net.minecraft.world.phys.Vec3(0, 0, 0), "STEVE"));
        assertSame(existing, manager.getSteve("Steve"));
    }

    @Test
    void removeSteveMatchesIgnoringCase() {
        SteveManager manager = new SteveManager();
        SteveEntity steve = mockSteve("Bob", UUID.randomUUID());
        manager.adopt(steve);

        assertTrue(manager.removeSteve("BOB", null));
        assertNull(manager.getSteve("Bob"));
        assertEquals(0, manager.getActiveCount());
        verify(steve).discard();
    }

    @Test
    void onSteveUnloadMatchesIgnoringCase() {
        SteveManager manager = new SteveManager();
        UUID uuid = UUID.randomUUID();
        SteveEntity steve = mockSteve("Alex", uuid);
        manager.adopt(steve);

        manager.onSteveUnload(steve);

        assertNull(manager.getSteve("alex"));
        assertNull(manager.getSteve(uuid));
        assertEquals(0, manager.getActiveCount());
    }
```

- [ ] **Step 3: Run only the new tests**

Run: `./gradlew test --tests "com.steve.ai.entity.SteveManagerTest"`
Expected: all tests pass; specifically the five new case-insensitive tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/steve/ai/entity/SteveManagerTest.java
git -c user.name="Iosif Pravets" -c user.email="i@pravets.ru" commit -m "test(SteveManager): case-insensitive name lookups

Unit tests covering getSteve, adopt dedup, spawnSteve guard, removeSteve,
and onSteveUnload with mismatched letter casing.

Relates #4"
```

---

### Task 3: Extend behavior tests to verify case-insensitive command names

**Files:**
- Modify: `scripts/behavior/behavior_test.py`

**Interfaces:**
- Consumes: existing RCON harness, server start/stop, `wait_for`, and the existing Cyrillic + Bob scenarios.
- Produces: a new scenario that spawns `Bob`, sends `/steve tell BOB стоп`, `/steve tell bob gather 50 wood`, and `/steve stop BOB`, asserting each command resolves to the same bot.

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
            print("Testing case-insensitive Steve names (issue #4)...")

            rcon.command("steve spawn Bob")
            if not wait_for(log_path, r"[Ss]pawned Steve: Bob", 30, "case-insensitive spawn"):
                return 1

            stay_resp = rcon.command("steve tell BOB стоп")
            print(f"  steve tell BOB стоп -> {stay_resp!r}")
            if "BOB stopped" not in stay_resp and "Bob stopped" not in stay_resp:
                print("  [FAIL] case-insensitive tell did not stop Bob")
                return 1

            gather_resp = rcon.command("steve tell bob gather 50 wood")
            print(f"  steve tell bob gather 50 wood -> {gather_resp!r}")
            if not wait_for(log_path, r"async planning complete: 1 tasks queued", 120, "case-insensitive task queued"):
                print("  [FAIL] case-insensitive tell did not queue a task for Bob")
                return 1

            stop_resp = rcon.command("steve stop BOB")
            print(f"  steve stop BOB -> {stop_resp!r}")
            if "BOB stopped" not in stop_resp and "Bob stopped" not in stop_resp:
                print("  [FAIL] case-insensitive stop did not stop Bob")
                return 1

            remove_resp = rcon.command("steve remove bob")
            print(f"  steve remove bob -> {remove_resp!r}")
            if "Removed Steve" not in remove_resp or "bob" not in remove_resp.lower():
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

Add an RCON scenario that drives /steve tell / stop / remove with
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
   - [x] `getSteve` is case-insensitive.
   - [x] Spawn duplicate check is case-insensitive.
   - [x] `/steve tell`, `/steve tp`, `/steve stop`, `/steve remove`, `/steve inventory` work because they all route through `SteveManager.getSteve` or `spawnSteve`/`removeSteve`.
   - [x] Behavior tests added.
2. **Placeholder scan:** No TODO/TBD; every step contains exact code or exact commands.
3. **Type consistency:** `findByNameIgnoreCase` returns `SteveEntity`; signatures of public methods unchanged.
4. **No unrelated changes:** Only `SteveManager.java`, `SteveManagerTest.java`, and `behavior_test.py` are touched.
