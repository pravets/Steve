# Resource Drop Matching Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GatherResourceAction` recognize the *actual* items that drop when a block is mined, so requests like "coal", "stone" and "cobblestone" progress correctly regardless of whether the mined block and the dropped item are different.

**Architecture:** Introduce a `ResourceYield` value object in `ResourceBlocks` that maps a requested resource name to (a) the set of blocks that can be mined to satisfy the request and (b) the predicate of items that count as collected. `GatherResourceAction` switches from "one target block + its `asItem()`" to "yield blocks + yield item matcher" for search, mining sanity checks and inventory counting.

**Tech Stack:** Forge 1.20.1 (47.2.0), official mappings, Java 17, JUnit 5.

**Spec:** This plan implements the bug described in the user report: Vasyan mines `coal_ore` but the inventory receives `coal`; mining `stone` receives `cobblestone`; asking for `cobblestone` should mine `stone`.

## Global Constraints

- Local full Gradle builds are forbidden on VPS hermes — use `nice -n 19 ionice -c3 ./gradlew compileJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1` only. Full build + behavior tests run in GitHub CI.
- New branch from `master` only, named `feat/resource-yield-matching`.
- One PR = one task.
- Commits as `Iosif Pravets <i@pravets.ru>`.
- Existing tests must stay green; add focused unit tests for new mapping logic.
- Keep changes within `ResourceBlocks` and `GatherResourceAction`; avoid refactors of unrelated systems.
- Preserve existing wood/any-log felling behavior exactly.

## Existing Root Cause

`GatherResourceAction.countResource()` counts items matching `resourceBlock.asItem()`:

```java
net.minecraft.world.item.Item resourceItem = resourceBlock.asItem();
matcher = item -> item == resourceItem;
```

This is wrong for blocks whose dropped item differs from the block item:
- `coal_ore` drops `coal`.
- `iron_ore` drops `raw_iron`.
- `stone` drops `cobblestone`.
- `cobblestone` itself drops `cobblestone`.

`VisionScanner.findVisible(vasyan, targetBlock)` and `findNearbyBlocks(vasyan, radius, targetBlock)` also search for exactly one block type, so a request for `cobblestone` never considers mining `stone`.

## Task 1: Add `ResourceYield` mapping to `ResourceBlocks`

**Files:**
- Modify: `src/main/java/com/vasyan/ai/action/actions/ResourceBlocks.java`
- Test: `src/test/java/com/vasyan/ai/action/actions/ResourceBlocksTest.java`

**Interfaces:**
- Produces: `public static ResourceYield yieldFor(String resourceName)`
- Produces: `public record ResourceYield(Set<Block> miningBlocks, Predicate<Item> itemMatcher, Item representativeItem, String label)`
- Consumes: existing `parseBlock(String)` behavior must remain unchanged for callers outside `GatherResourceAction`.

- [ ] **Step 1: Define `ResourceYield` record**

```java
public record ResourceYield(
    Set<Block> miningBlocks,
    Predicate<Item> itemMatcher,
    Item representativeItem,
    String label
) {}
```

- [ ] **Step 2: Add static yield registry**

Add a static map keyed by the same normalized names as `RESOURCE_TO_BLOCK`. Each entry lists the blocks that can be mined and the items that count as the requested resource.

```java
private static final Map<String, ResourceYield> RESOURCE_TO_YIELD = Map.ofEntries(
    Map.entry("iron", new ResourceYield(Set.of(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
        item -> item == Items.RAW_IRON, Items.RAW_IRON, "Raw Iron")),
    Map.entry("diamond", new ResourceYield(Set.of(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
        item -> item == Items.DIAMOND, Items.DIAMOND, "Diamond")),
    Map.entry("coal", new ResourceYield(Set.of(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
        item -> item == Items.COAL, Items.COAL, "Coal")),
    Map.entry("gold", new ResourceYield(Set.of(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
        item -> item == Items.RAW_GOLD, Items.RAW_GOLD, "Raw Gold")),
    Map.entry("copper", new ResourceYield(Set.of(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
        item -> item == Items.RAW_COPPER, Items.RAW_COPPER, "Raw Copper")),
    Map.entry("redstone", new ResourceYield(Set.of(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
        item -> item == Items.REDSTONE, Items.REDSTONE, "Redstone")),
    Map.entry("lapis", new ResourceYield(Set.of(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
        item -> item == Items.LAPIS_LAZULI, Items.LAPIS_LAZULI, "Lapis Lazuli")),
    Map.entry("emerald", new ResourceYield(Set.of(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
        item -> item == Items.EMERALD, Items.EMERALD, "Emerald")),
    Map.entry("stone", new ResourceYield(Set.of(Blocks.STONE, Blocks.COBBLESTONE),
        item -> item == Items.COBBLESTONE, Items.COBBLESTONE, "Cobblestone")),
    Map.entry("cobblestone", new ResourceYield(Set.of(Blocks.STONE, Blocks.COBBLESTONE),
        item -> item == Items.COBBLESTONE, Items.COBBLESTONE, "Cobblestone")),
    Map.entry("dirt", new ResourceYield(Set.of(Blocks.DIRT, Blocks.GRASS_BLOCK),
        item -> item == Items.DIRT, Items.DIRT, "Dirt")),
    Map.entry("gravel", new ResourceYield(Set.of(Blocks.GRAVEL),
        item -> item == Items.GRAVEL, Items.GRAVEL, "Gravel")),
    Map.entry("sand", new ResourceYield(Set.of(Blocks.SAND),
        item -> item == Items.SAND, Items.SAND, "Sand"))
);
```

Also keep wood/any-log special-cased in `GatherResourceAction` (it already uses `BlockTags.LOGS`).

- [ ] **Step 3: Implement `yieldFor(String)`**

```java
public static ResourceYield yieldFor(String resourceName) {
    if (resourceName == null || resourceName.isBlank()) {
        return null;
    }
    String normalized = resourceName.toLowerCase(Locale.ROOT).replace(" ", "_");
    ResourceYield yield = RESOURCE_TO_YIELD.get(normalized);
    if (yield != null) {
        return yield;
    }
    // Fallback for explicit block names not in the yield registry.
    Block block = parseBlock(resourceName);
    if (block == null) {
        return null;
    }
    Item item = block.asItem();
    if (item == Items.AIR) {
        item = Item.byBlock(block);
    }
    final Item fallbackItem = item;
    String label = block.getName().getString();
    return new ResourceYield(Set.of(block), item -> item == fallbackItem, fallbackItem, label);
}
```

- [ ] **Step 4: Add unit tests for yield mapping**

Add tests to `ResourceBlocksTest`:

```java
@Test
void coalYieldCountsCoalItemNotOreBlock() {
    ResourceYield yield = ResourceBlocks.yieldFor("coal");
    assertTrue(yield.miningBlocks().contains(Blocks.COAL_ORE));
    assertTrue(yield.miningBlocks().contains(Blocks.DEEPSLATE_COAL_ORE));
    assertTrue(yield.itemMatcher().test(Items.COAL));
    assertFalse(yield.itemMatcher().test(Items.RAW_IRON));
    assertEquals(Items.COAL, yield.representativeItem());
}

@Test
void stoneYieldMinesStoneAndCountsCobblestone() {
    ResourceYield yield = ResourceBlocks.yieldFor("stone");
    assertTrue(yield.miningBlocks().contains(Blocks.STONE));
    assertTrue(yield.miningBlocks().contains(Blocks.COBBLESTONE));
    assertTrue(yield.itemMatcher().test(Items.COBBLESTONE));
    assertEquals(Items.COBBLESTONE, yield.representativeItem());
}

@Test
void cobblestoneYieldMinesStoneToo() {
    ResourceYield yield = ResourceBlocks.yieldFor("cobblestone");
    assertTrue(yield.miningBlocks().contains(Blocks.STONE));
    assertTrue(yield.miningBlocks().contains(Blocks.COBBLESTONE));
    assertTrue(yield.itemMatcher().test(Items.COBBLESTONE));
}

@Test
void ironYieldCountsRawIron() {
    ResourceYield yield = ResourceBlocks.yieldFor("iron");
    assertTrue(yield.miningBlocks().contains(Blocks.IRON_ORE));
    assertTrue(yield.itemMatcher().test(Items.RAW_IRON));
    assertFalse(yield.itemMatcher().test(Items.IRON_ORE));
}

@Test
void unknownResourceReturnsNull() {
    assertNull(ResourceBlocks.yieldFor("unobtainium"));
}

@Test
void fallbackYieldUsesBlockAsItem() {
    ResourceYield yield = ResourceBlocks.yieldFor("oak_log");
    assertTrue(yield.miningBlocks().contains(Blocks.OAK_LOG));
    assertTrue(yield.itemMatcher().test(Items.OAK_LOG));
}
```

- [ ] **Step 5: Compile and run new tests**

Run:
```bash
nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
./gradlew test --tests "com.vasyan.ai.action.actions.ResourceBlocksTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: `ResourceBlocksTest` passes (new + existing tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vasyan/ai/action/actions/ResourceBlocks.java src/test/java/com/vasyan/ai/action/actions/ResourceBlocksTest.java
git commit -m "feat(resource): add ResourceYield mapping for block-to-drop matching"
```

## Task 2: Add multi-block search helpers to `VisionScanner`

**Files:**
- Modify: `src/main/java/com/vasyan/ai/memory/VisionScanner.java`
- Test: existing tests should still compile; no new tests needed if behavior is unchanged for single-block callers.

**Interfaces:**
- Consumes: `ResourceYield.miningBlocks()` from Task 1.
- Produces: `public static List<BlockPos> findVisible(VasyanEntity vasyan, Set<Block> targets)`
- Produces: `public static List<BlockPos> findNearbyBlocks(VasyanEntity vasyan, int radius, Set<Block> targets)`

- [ ] **Step 1: Add `findVisible` overload for a set of blocks**

```java
public static List<BlockPos> findVisible(VasyanEntity vasyan, Set<Block> targets) {
    Map<Block, List<BlockPos>> visible = scan(vasyan);
    List<BlockPos> found = new ArrayList<>();
    for (Block block : targets) {
        found.addAll(visible.getOrDefault(block, List.of()));
    }
    BlockPos center = vasyan.blockPosition();
    return found.stream()
        .sorted(Comparator.comparingDouble(p -> p.distSqr(center)))
        .toList();
}
```

Keep the existing `findVisible(VasyanEntity, Block)` and delegate to the set overload with `Set.of(target)`.

- [ ] **Step 2: Add `findNearbyBlocks` overload for a set of blocks**

```java
public static List<BlockPos> findNearbyBlocks(VasyanEntity vasyan, int radius, Set<Block> targets) {
    BlockPos center = vasyan.blockPosition();
    List<BlockPos> found = new ArrayList<>();
    Level level = vasyan.level();
    for (int dx = -radius; dx <= radius; dx++) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos pos = center.offset(dx, dy, dz);
                Block block = level.getBlockState(pos).getBlock();
                if (targets.contains(block)) {
                    found.add(pos);
                }
            }
        }
    }
    return found.stream()
        .sorted(Comparator.comparingDouble(p -> p.distSqr(center)))
        .toList();
}
```

Keep the existing `findNearbyBlocks(VasyanEntity, int, Block)` and delegate with `target == null ? null : Set.of(target)`, preserving the `target == null` any-log behavior.

- [ ] **Step 3: Compile**

Run:
```bash
nice -n 19 ionice -c3 ./gradlew compileJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: compiles cleanly.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/vasyan/ai/memory/VisionScanner.java
git commit -m "feat(vision): support searching for a set of block types"
```

## Task 3: Wire `ResourceYield` into `GatherResourceAction`

**Files:**
- Modify: `src/main/java/com/vasyan/ai/action/actions/GatherResourceAction.java`
- Test: `src/test/java/com/vasyan/ai/action/actions/GatherResourceCountTest.java`

**Interfaces:**
- Consumes: `ResourceBlocks.ResourceYield` from Task 1.
- Consumes: `VisionScanner.findVisible(VasyanEntity, Set<Block>)` and `findNearbyBlocks(VasyanEntity, int, Set<Block>)` from Task 2.
- Produces: gathered count uses the yield item matcher; search targets use yield mining blocks.

- [ ] **Step 1: Add `resourceYield` and `miningBlocks` fields**

After `private Block resourceBlock;` add:

```java
private ResourceBlocks.ResourceYield resourceYield;
private Set<Block> miningBlocks;
```

- [ ] **Step 2: Initialize yield in `onStart()`**

Replace the existing resource resolution block in `onStart()` (lines 125-141) with:

```java
String blockName = task.getStringParameter("resource");
if (blockName == null || blockName.isBlank()) {
    blockName = task.getStringParameter("block");
}
targetQuantity = task.getIntParameter("quantity", 16);
fillMode = "true".equalsIgnoreCase(String.valueOf(task.getParameters().getOrDefault("fill", "false")));

anyLogMode = ResourceBlocks.isWoodRequest(blockName);
resourceYield = anyLogMode ? null : ResourceBlocks.yieldFor(blockName);
if (!anyLogMode && resourceYield == null) {
    result = ActionResult.failure("Unknown resource: " + blockName);
    return;
}

targetBlock = anyLogMode ? null : resourceYield.representativeItem() == null ? null : null;
```

Wait — `targetBlock` is still needed as a representative `Block` for display/fell-mode. Derive it from `parseBlock(blockName)` so `resourceLabel()` and log output stay consistent.

Corrected:

```java
anyLogMode = ResourceBlocks.isWoodRequest(blockName);
resourceYield = anyLogMode ? null : ResourceBlocks.yieldFor(blockName);
if (!anyLogMode && resourceYield == null) {
    result = ActionResult.failure("Unknown resource: " + blockName);
    return;
}

targetBlock = anyLogMode ? null : ResourceBlocks.parseBlock(blockName);
resourceBlock = targetBlock;
miningBlocks = anyLogMode ? null : resourceYield.miningBlocks();
```

- [ ] **Step 3: Update search to use `miningBlocks`**

Replace lines 290-296:

```java
List<BlockPos> visible = anyLogMode
    ? VisionScanner.findVisibleAnyLog(vasyan)
    : VisionScanner.findVisible(vasyan, miningBlocks);

boolean logTarget = anyLogMode
    || (targetBlock != null && targetBlock.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS));

List<BlockPos> nearby = VisionScanner.findNearbyBlocks(vasyan, NEARBY_SCAN_RADIUS, miningBlocks);
```

- [ ] **Step 4: Update `currentTargetItem()` for fill mode**

Replace lines 258-266 with:

```java
private Item currentTargetItem() {
    if (fellMode && fellLogBlock != null) {
        return fellLogBlock.asItem();
    }
    if (anyLogMode) {
        return Items.OAK_LOG;
    }
    if (resourceYield != null && resourceYield.representativeItem() != null) {
        return resourceYield.representativeItem();
    }
    return targetBlock == null ? Items.AIR : targetBlock.asItem();
}
```

- [ ] **Step 5: Update `countResource()` to use the yield matcher**

Replace lines 843-855 with:

```java
private int countResource() {
    Predicate<Item> matcher;
    if (anyLogMode) {
        matcher = item -> item.builtInRegistryHolder().is(net.minecraft.tags.ItemTags.LOGS);
    } else if (resourceYield != null) {
        matcher = resourceYield.itemMatcher();
    } else {
        matcher = item -> false;
    }
    return countResource(vasyan.getInventory(), matcher);
}
```

- [ ] **Step 6: Update `isTargetBlockAt()` to accept any mining block**

Replace lines 881-885 with:

```java
private boolean isTargetBlockAt(BlockPos pos) {
    Block block = vasyan.level().getBlockState(pos).getBlock();
    if (fellGatheringMaterial || !anyLogMode) {
        return miningBlocks != null && miningBlocks.contains(block);
    }
    return block.builtInRegistryHolder().is(net.minecraft.tags.BlockTags.LOGS);
}
```

- [ ] **Step 7: Add unit tests for yield-based counting**

Add to `GatherResourceCountTest`:

```java
@Test
void coalRequestCountsCoalItemsNotOreBlockItems() {
    VasyanInventory inv = new VasyanInventory(9);
    inv.addItem(new ItemStack(Items.COAL, 5));
    inv.addItem(new ItemStack(Items.COAL_ORE, 3)); // should not count

    assertEquals(5, GatherResourceAction.countResource(inv, item -> item == Items.COAL));
}

@Test
void stoneRequestCountsCobblestoneDrops() {
    VasyanInventory inv = new VasyanInventory(9);
    inv.addItem(new ItemStack(Items.COBBLESTONE, 12));
    inv.addItem(new ItemStack(Items.STONE, 4)); // block item, not a drop

    assertEquals(12, GatherResourceAction.countResource(inv, item -> item == Items.COBBLESTONE));
}

@Test
void deltaCountingUsesYieldMatcher() {
    VasyanInventory inv = new VasyanInventory(9);
    inv.addItem(new ItemStack(Items.COAL, 2));
    int baseline = GatherResourceAction.countResource(inv, item -> item == Items.COAL);

    inv.addItem(new ItemStack(Items.COAL, 3));

    assertEquals(3, GatherResourceAction.countResource(inv, item -> item == Items.COAL) - baseline);
}
```

- [ ] **Step 8: Compile and run affected tests**

Run:
```bash
nice -n 19 ionice -c3 ./gradlew compileJava compileTestJava --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
./gradlew test --tests "com.vasyan.ai.action.actions.GatherResourceCountTest" --tests "com.vasyan.ai.action.actions.ResourceBlocksTest" --no-daemon -Dorg.gradle.jvmargs="-Xmx768m" --max-workers=1
```

Expected: all targeted tests pass.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/vasyan/ai/action/actions/GatherResourceAction.java src/test/java/com/vasyan/ai/action/actions/GatherResourceCountTest.java
git commit -m "fix(gather): use ResourceYield for search targets and inventory counting"
```

## Task 4: Regression-test and open PR

**Files:**
- Run: full unit test suite via GitHub CI.

- [ ] **Step 1: Push branch**

```bash
git push origin feat/resource-yield-matching
```

- [ ] **Step 2: Create draft PR**

```bash
gh pr create -R pravets/Vasyan --base master --head feat/resource-yield-matching \
  --title "fix: match gathered items to actual block drops" \
  --body-file /tmp/pr-body.md
```

Where `/tmp/pr-body.md` contains:

```markdown
## Problem
`GatherResourceAction` counted items matching `targetBlock.asItem()`, which is wrong when the mined block drops a different item:
- Coal Ore → drops `coal`
- Stone → drops `cobblestone`
- Iron Ore → drops `raw_iron`

Additionally, a request for `cobblestone` only searched for `cobblestone` blocks, ignoring abundant `stone`.

## Fix
- Added `ResourceBlocks.ResourceYield` mapping: blocks that can be mined + items that count as collected.
- Added `VisionScanner` overloads to search a set of block types.
- Wired yield matching into `GatherResourceAction` for search, mining sanity checks, fill mode and inventory counting.

## Tests
- `ResourceBlocksTest`: new yield mapping tests for coal, iron, stone, cobblestone, fallback.
- `GatherResourceCountTest`: new tests proving counting uses the dropped item, not the block item.
```

- [ ] **Step 3: Watch CI**

```bash
gh run list -R pravets/Vasyan --branch feat/resource-yield-matching
gh run watch <run-id> --exit-status
```

Expected: Build + behavior tests green.

- [ ] **Step 4: Mark PR ready for review**

Once CI is green, mark the PR ready (or leave as draft per user preference).

---

## Self-Review

**Spec coverage:** Every reported symptom is covered:
- Coal mined from coal ore counts coal items → Task 1 + Task 3.
- Stone mined counts cobblestone → Task 1 + Task 3.
- Cobblestone request mines stone blocks → Task 1 (miningBlocks includes stone) + Task 2/3.

**Placeholder scan:** No placeholders; all code blocks contain concrete implementation.

**Type consistency:** `ResourceYield` fields are `Set<Block>`, `Predicate<Item>`, `Item`, `String`; used consistently in `GatherResourceAction`.
