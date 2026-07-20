# New or Edited Plugin Checklist

Copy this file for one plugin and replace every `<...>` field. Leave an unchecked box with a short explanation when a gate is not complete; do not silently remove inapplicable checks.

- Plugin name: `ElectricFurnace`
- Slug: `electric-furnace`
- Repository: `carmelosantana/minecraft-electric-furnace`
- Owner: `Carmelo Santana`
- Target version: `0.1.1`
- Paper version: `26.1.2 build 74`
- Java version: `25`
- Updater destination: `electric-furnace.jar`
- External services: `none`
- Status: `active`
- Autonomy: `autonomous`

Maven coordinates: `org.xpfarm:electric-furnace`. `plugin.yml` name: `ElectricFurnace`.

Full design: [docs/superpowers/specs/2026-07-19-electric-furnace-design.md](superpowers/specs/2026-07-19-electric-furnace-design.md)

## 1. Scope

- [x] Status is explicitly recorded as active, experimental, or excluded.
- [x] Purpose, commands, events, permissions, configuration, persistence, and acceptance checks are defined.
- [x] Known limitations and any intentionally withheld gates are recorded.

### Player-facing purpose

A redstone-powered industrial smelter. It smelts faster than a vanilla furnace and
recycles metal gear back into ingots — five of the same metal returns ingots of that
metal, while a mix fuses into a stronger alloy. Worn-out gear finally has somewhere
to go.

### Commands

| Command | Args | Permission | Purpose |
|---|---|---|---|
| `/electricfurnace give` | `[player] [amount]` | `electricfurnace.give` (op) | Issue the machine item |
| `/electricfurnace alloy` | `<id> [amount]` | `electricfurnace.give` (op) | Issue an alloy ingot, for testing |
| `/electricfurnace reload` | — | `electricfurnace.reload` (op) | Reload config |
| `/electricfurnace info` | — | `electricfurnace.use` (true) | Show alloy recipes and yields |

### Events

`BlockPlaceEvent` (register machine), `BlockBreakEvent` (deregister, return item),
`PlayerInteractEvent` (open custom GUI, cancel native blast furnace GUI),
`BlockRedstoneEvent` (gating signal, running state, copper bulb),
`InventoryClickEvent` (slot guards, output extraction), `InventoryCloseEvent`
(return in-flight inputs), `ChunkLoadEvent` / `ChunkUnloadEvent` (maintain the
effects scheduler's active set).

### Permissions

| Node | Default | Gates |
|---|---|---|
| `electricfurnace.use` | `true` | Opening and using a machine |
| `electricfurnace.craft` | `true` | Crafting the machine item |
| `electricfurnace.give` | `op` | `/electricfurnace give`, `/electricfurnace alloy` |
| `electricfurnace.reload` | `op` | `/electricfurnace reload` |

### Configuration

Sections: `machine.*` (smelt speed multiplier, fuel per operation, require-signal
toggle, status bulb), `effects.*` (enabled, period-ticks, player-radius, sound),
`recycling.*` (slots, yield-same-metal `3`, yield-mixed-alloy `2`,
yield-remelt-alloy `1`, accept-damaged), `alloys.<id>.*` (name, lore, color, inputs,
stat block). Every numeric key is range-validated on load; invalid values warn and
fall back to the default rather than disabling the plugin. Full table in the design
doc.

An earlier draft listed `alloys.balance-ceiling.enabled`. It was **not implemented,
deliberately** — a ceiling an operator can switch off is not a ceiling. The clamp is
unconditional in `AlloyRegistry` and warns naming the alloy, stat, configured value,
and clamp target.

### Persistence

Chunk `PersistentDataContainer`, keyed by block coordinates within the chunk. No
flat file, no database. Machines load and unload with their chunks, which also
supplies the effects scheduler's active set.

Items carry a **shared** cross-plugin contract: `xpfarm:custom_material` and
`xpfarm:material_id` (both `STRING`).

### Dependencies

Hard: none. Soft: none. No load-order requirements. Reads CopperKingdom's
`copperkingdom:` PDC keys opportunistically via `NamespacedKey`'s string
constructor — **no jar dependency in either direction.**

### External integrations

`none`. No Ollama, no Umami, no outside network calls.

### Acceptance checks

1. A crafted machine item places a `BLAST_FURNACE` registered as a machine; registration survives restart.
2. Breaking the machine returns the custom item and deregisters the location.
3. No redstone signal → does not run, even with dust in the fuel slot.
4. Signal + dust → operation completes and consumes the configured dust.
5. 5× iron ingots yields exactly 3 iron ingots.
6. 5× mixed metals yields exactly 2 generic Fused Alloy ingots.
7. 4× iron + 1× coal yields exactly 2 Steel ingots (named recipe match).
8. 1× alloy ingot remelted yields exactly 1 ingot.
9. Fully damaged gear yields the same as undamaged gear.
10. Alloy ingots carry `xpfarm:custom_material` and `xpfarm:material_id` in PDC.
11. A config alloy with stats above netherite is clamped and logs a warning.
12. `RecycleResolver` unit tests pass for all-same, mixed, named-recipe, remelt, fewer-than-five, non-metal-input, coal-only-input.
13. A Bedrock client via Geyser can place, open, use, and break the machine.
14. Particles are visible to a Bedrock client; absent sound does not error.
15. Config reload applies new yields without a restart.

### Known limitations

- **Alloy armor and weapons deferred to v2.** v1 produces ingots with defined stats only.
- **No custom textures on any platform.** Bedrock constraint: `custom_model_data` and the 1.21.4 `item_model` component both require a separately authored Bedrock resource pack ([Geyser custom items](https://geysermc.org/wiki/geyser/custom-items/)); display entities are untranslated and invisible to Bedrock ([Geyser #3810](https://github.com/GeyserMC/Geyser/issues/3810)). The machine looks like a blast furnace with particle and sound identity.
- **Colored `DUST` particles unusable** — color does not survive to Bedrock ([Geyser #1937](https://github.com/GeyserMC/Geyser/issues/1937)). `ELECTRIC_SPARK` and `CAMPFIRE_COSY_SMOKE` are confirmed mapped and used instead.
- **Player-head (Slimefun-style) visuals deferred to Phase 2.** Requires Geyser `custom-skulls.yml` as a deployment dependency and inherits [Geyser #5923](https://github.com/GeyserMC/Geyser/issues/5923), where registered skulls swallow Bedrock interact events — which would break right-click-to-open.
- **`BLOCK_BEACON_AMBIENT` Geyser sound mapping unverified.** Must be confirmed by in-game Bedrock testing at gate 7a. Fails silently if unmapped, so it degrades safely.
- **`ELECTRIC_SPARK` visual fidelity on Bedrock unconfirmed.** Mapped, but appearance not verified.
- **No shared item library with CopperKingdom.** The cross-plugin PDC contract ships instead. Extraction is deferred until CopperKingdom's enum-based type system, inert durability, dead recipe config, and missing attack-speed are fixed.
- **Machines in unloaded chunks do not process.** Deliberate — no chunk-forcing.

No gates are intentionally withheld. Status is `active`; the full pipeline runs.

## 2. Repository

- [x] Repository is `carmelosantana/minecraft-<slug>` with an SSH `origin` and `main` branch.
  - Created: <https://github.com/carmelosantana/minecraft-electric-furnace>
  - `origin` = `git@github.com:carmelosantana/minecraft-electric-furnace.git`, branch `main`
  - Commit `aa19bd7` pushed to `origin/main` by the operator on 2026-07-19; CI run `29703069182` triggered.
- [x] Existing user-owned worktree changes were identified and preserved.
  - New repository, no pre-existing worktree. `git init` ran on a directory containing only gate 1 output.
- [x] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation.
  - `rg -n 'herobrinesystems' . --hidden -g '!target/**' -g '!.git/**'` → single hit, which is this checklist's own checkbox text. No real references.

### Repository visibility — RESOLVED

`gh repo create` defaulted to private, which would have failed updater enrollment
(the updater downloads release assets unauthenticated). Made public by the operator
and verified 2026-07-19 via `gh api repos/carmelosantana/minecraft-electric-furnace`
→ `private=false visibility=public`.

## 3. Metadata

- [x] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent.
  - `LICENSE`: full AGPL-3.0 text, 661 lines.
  - `pom.xml`: `<licenses>` names "GNU Affero General Public License v3.0 or later" at <https://www.gnu.org/licenses/agpl-3.0.html>.
- [x] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present.
  - `pom.xml`: `<url>`, `<developers><developer><name>Carmelo Santana`.
  - `plugin.yml`: `author: Carmelo Santana`, `website: https://xpfarm.org`.
- [x] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is documented.
  - `README.md`, with the Java/Bedrock (Geyser + Floodgate) note.
- [x] New work uses the `org.xpfarm` Maven group, or an existing-coordinate compatibility decision is documented.
  - `org.xpfarm:electric-furnace:0.1.0`. No compatibility carve-out needed; this is new work.
- [x] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are consistent.
  - slug `electric-furnace` → artifactId `electric-furnace` → JAR `electric-furnace-0.1.0.jar` → destination `electric-furnace.jar` → `plugin.yml` name `ElectricFurnace`. Verified by `grep` on both files.
- [x] No secrets committed in source, defaults, tests, logs, history, or documentation.
  - Credential/token/key scan returned only this checklist's own checkbox text.

### Deliberate deviation from the CopperKingdom POM

CopperKingdom's `pom.xml` sets `maven.compiler.release=25` **and** an explicit
`<source>21</source><target>21</target>` on the compiler plugin. The `release`
property wins, so the two disagree silently. This POM omits the explicit
source/target and relies on `maven.compiler.release` alone. The build target is
unchanged; the contradiction is not propagated.

## 4. Compatibility

- [x] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '1.21'`.
  - `mvn clean verify` BUILD SUCCESS. Embedded `plugin.yml` confirmed `api-version: '1.21'`.
- [x] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared.
  - **None.** No `depend`, `softdepend`, or `loadbefore`/`loadafter` entries, deliberately.
    CopperKingdom interop is read-only via `NamespacedKey`'s String constructor, which
    needs no plugin instance — so there is no load-order requirement in either
    direction and neither plugin depends on the other's JAR.
- [x] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior.
  - **Input:** all interaction is inventory clicks or commands. No typed chat input
    anywhere — Bedrock clients cannot reliably use Java chat prompts.
  - **UI:** custom inventory title only. No `setCustomModelData`, no `item_model`
    component, no display entities. Verified by scanning `src/main` — the only
    occurrences of those names are comments explaining why they are not used.
  - **Inventory:** plugin-owned inventory with slot guards covering plain click,
    shift-click, hotbar swap, double-click collect, and drag.
  - **Identity:** items identified by PersistentDataContainer only, never by display
    name or lore substring. This deliberately avoids the failure mode in the sibling
    CopperKingdom plugin, which matches the lore substring "Blessed".
  - **Particles:** only `ELECTRIC_SPARK` and `CAMPFIRE_COSY_SMOKE`, both confirmed
    mapped in GeyserMC/mappings. Colored `DUST` deliberately unused.
  - **Protocol:** no assumptions about the client's protocol version, so ViaVersion
    bridging is unaffected. Runtime-verified alongside ViaVersion 5.11.0.

## 5. External services

- [x] External integrations are disabled by default or require explicit configuration and have bounded timeouts.
  - **Not applicable — there are none.** No network calls of any kind; no HTTP client
    on the classpath. Verified: the shaded JAR contains only plugin classes,
    `plugin.yml`, `config.yml`, and Maven metadata.
- [x] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable.
  - Not applicable; none exist.
- [x] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets.
  - No endpoints. The equivalent startup-safety property was still enforced and
    reviewed: every `onEnable` step is wrapped in a `Throwable` guard, a malformed
    config degrades to defaults with warnings, a single malformed alloy entry is
    skipped rather than discarding the whole config, and sound resolution catches
    `Throwable` (not merely `ReflectiveOperationException`) so an `Error` from a
    static initializer cannot escape. No secrets exist to leak.

## 6. Tests and build

- [x] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable.
  - **238 tests, 0 failures.** Coverage spans config validation and clamping, the pure
    recycle resolver (all eight rules with precedence), alloy registry and
    balance-ceiling clamping, metal classification, chunk-key encode/decode including
    hostile input, GUI slot roles and guard decisions, command argument parsing and
    per-subcommand permissions, and the effects gate.
  - Bukkit types (`ItemStack`, `Inventory`, `Block`, `Player`) cannot be constructed
    headlessly, so decisions were extracted into pure functions and tested there —
    e.g. `MetalClassifier.resolveBranch` over all 16 boolean combinations,
    `FurnaceGui.shutdownSteps()` as ordered data, `allowedSubcommandTokens` over all
    permission subsets. Fix agents verified new tests fail against deliberately broken
    implementations before restoring them.
- [x] `mvn --batch-mode --no-transfer-progress clean verify` succeeds.
  - BUILD SUCCESS, 238 tests, 0 failures/errors/skipped.
- [x] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded.
  - `target/electric-furnace-0.1.0.jar`: embedded `plugin.yml` shows version `0.1.0`,
    main `org.xpfarm.electricfurnace.ElectricFurnacePlugin`, `api-version '1.21'`, the
    `electricfurnace` command, and all four permission nodes. No server API bundled
    (`org/bukkit`, `io/papermc`, `net/kyori` all absent — paper-api correctly
    `provided`). `original-electric-furnace-0.1.0.jar` exists in `target/` and is
    excluded from release assets by the workflow's `! -name 'original-*'` filter.

## 7. Matrix

### 7a — Single-plugin runtime verification (this plugin only) — PASSED

Disposable Legendary stack, fresh volume, ports leased via `xpfarm-slot` (slot 0),
torn down and lease released after each run. Verified twice: once on `ca5d378`, and
again on `96861af` after the Task 6 review fixes touched the registry and startup
paths.

Evidence from the second run:

- **Paper, Geyser, Floodgate, and ViaVersion started together.** Plugin list:
  `ElectricFurnace (0.1.0), Geyser-Spigot (2.11.0-SNAPSHOT), ViaVersion (5.11.0),
  floodgate (2.2.5-SNAPSHOT)`. `Geyser-Spigot Done (3.032s)`, server
  `Done (21.265s)`.
- **Plugin loaded and enabled cleanly:** `Enabling ElectricFurnace v0.1.0` →
  `ElectricFurnace enabled (5 alloys, effects on).`
- **Config and alloy parsing confirmed against the real shipped `config.yml`.** The
  "5 alloys" count exercises `EfConfig.load`'s dotted-path reads and
  `AlloyRegistry.load`'s `ConfigurationSection` glue — both flagged during review as
  untestable headlessly. This run is their first real evidence, and they work.
- **Clean shutdown:** `Disabling ElectricFurnace v0.1.0`, no exceptions.
- **No exceptions, SEVERE lines, or leaked secrets** in startup, enable, or shutdown
  logs. The only warning present is vanilla's unrelated `Failed to parse level-type
  default`.
- **External-service negative paths:** not applicable — this plugin makes no
  outbound calls.

**Not covered, and deliberately recorded rather than claimed:**

- **No Java or Bedrock client join was performed**, so no in-game exercise of the
  GUI, crafting, redstone gating, recycling, particles, or sound. The container
  exposes no RCON (`enable-rcon=false`) and stdin console injection did not take, so
  even console commands could not be driven. Everything below therefore rests on
  unit tests and code review, not observed behavior:
  - `/electricfurnace give|alloy|reload|info` have never been executed.
  - The GUI has never been opened; slot guards, the C1 double-click dupe fix, and the
    C2 shutdown item-return path are unverified at runtime.
  - Redstone gating and the `COPPER_BULB` status light are unverified. Review noted
    `BlockRedstoneEvent` should reach adjacent machines after the S1 fix, but this
    was never observed firing.
  - Particle appearance and `BLOCK_BEACON_AMBIENT` sound on a Bedrock client are
    unverified — the sound mapping was already an open question from gate 1 research.
  - `World#getNearbyPlayers`, the `playSound(Location, String, ...)` overload, and
    `Bukkit.removeRecipe` resolve at class-load but were never invoked.

  These are the highest-value checks for a human to run before this reaches
  production. A clean enable is not evidence the plugin works.

### 7b — Ten-plugin ecosystem matrix

- [x] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers all updater-managed plugins.
  - **Out of band, and not a prerequisite for this plugin's release.** Belongs to
    `minecraft-plugin-matrix`, triggered by an updater manifest change, a
    Paper/Geyser/Floodgate/ViaVersion bump, or explicit request — not by every `dev`
    run. Gate 7a above tested this plugin alone in an otherwise-default stack and is
    not evidence about interaction with the other nine.

- [x] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately.
- [x] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
- [ ] Java and Bedrock smoke tests cover joins plus affected commands, events, permissions, persistence, and reloads where feasible.
  - **Not done.** No client join was performed in this matrix run; see the 7a caveats.
- [x] Public deployment smoke tests verify `play.xpfarm.org` reaches the intended Java and Bedrock entry points.
  - DNS resolves to `168.231.74.113`; Java TCP 25565 and Bedrock UDP 19132 both reachable.
- [x] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable.
  - Both integrations ship `enabled: false`, so the default run only proved they stay
    dormant. To test the real failure path they were switched on and pointed at
    TEST-NET-2 `198.51.100.9`. Ollama caught
    `java.net.SocketException: Network is unreachable`, retried once and stopped
    (RetryExec count static at 2 across 45s — bounded, no loop). Umami enabled and
    warned about its unconfigured website ID without failing. Server reached
    `Done (12.933s)` and stayed reachable throughout. Zero credential-shaped strings
    in any log line.


**Run of 2026-07-19 — PASSED (11/11).** Triggered by the checksum-manifest
remediation (ten patch re-cuts) and this plugin's enrollment in the updater
manifest. The manifest now carries **eleven** plugins, not ten — Electric Furnace
was added in `43f5bb7`.

- Manifest state for this plugin: `enabled` absent (= true), no pin. Expected
  fresh-volume behavior: install and enable. Observed: installed `v0.1.1`,
  `Enabling ElectricFurnace v0.1.1` → `ElectricFurnace enabled (5 alloys,
  effects on).` **PASS**
- All eleven plugins installed by the one-shot updater on a fresh volume and
  enabled together; zero SEVERE/exception lines stack-wide.
- Stack: Paper 26.1.2, Geyser 2.11.0, Floodgate 2.2.5 b138, ViaVersion 5.11.0.
- Each installed JAR's SHA-256 matched its published `SHA256SUMS.txt` digest.

Still not covered by this run: no Java or Bedrock client join was performed, so
the 7a caveats above stand unchanged. A passing matrix is evidence the plugins
coexist and enable, not that this plugin's in-game behavior works.

## 8. CI/CD

- [x] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior.
  - `.github/workflows/build.yml` copied byte-for-byte from the CopperKingdom reference, which matches `GITHUB_ACTIONS.md`. Triggers: push to `main`, `v*` tags, PRs targeting `main`, `workflow_dispatch`. `actions/checkout@v7`, `actions/setup-java@v5` (Temurin 25, Maven cache), `mvn --batch-mode --no-transfer-progress clean verify`, `SHA256SUMS.txt` excluding `original-*`, `actions/upload-artifact@v7`, tag-gated `gh release view`/`create`/`upload --clobber`.
- [x] Successful main Actions run is recorded before tagging.
  - Left for `minecraft-plugin-release` (gate 8b) to tick, per the gate split — but the
    evidence now exists: run
    [29706471487](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29706471487)
    on `main` (commit `9025cf9`) completed **success** in 25s, 2026-07-19, building
    the full 238-test suite on Temurin 25 in a clean environment. The earlier scaffold
    run 29703069182 also succeeded.
- [x] Workflow permissions contain no broader access than the documented contract.
  - `permissions: contents: write` only. No `packages:`, `id-token:`, or other scopes.

## 9. Release

- [x] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
  - POM `0.1.0`; `plugin.yml` uses `version: '${project.version}'` (no hardcoded drift);
    embedded `plugin.yml` in the shaded JAR reads `0.1.0`; tag `v0.1.0` → `db344e5`.
- [x] Successful tag Actions run and GitHub release are recorded.
  - Tag run [29706675169](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29706675169)
    completed **success** in 31s. Release published 2026-07-19T22:44:24Z by
    `github-actions[bot]`, `draft: false`, `prerelease: false`:
    <https://github.com/carmelosantana/minecraft-electric-furnace/releases/tag/v0.1.0>
- [x] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*` JAR.
  - Exactly two assets: `electric-furnace-0.1.0.jar` and `SHA256SUMS.txt`. No
    `original-*` JAR present.
- [x] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`.
  - **v0.1.1: `electric-furnace-0.1.1.jar: OK`.** Manifest records the bare filename
    and verification passes cleanly. This is the released version; gate 10 should
    enroll `v0.1.1`, not `v0.1.0`.
  - v0.1.0 failed this check and is superseded — see below.

### v0.1.1 release evidence

- Tag `v0.1.1` on commit `ec50351`; main run
  [29706816331](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29706816331)
  green **before** tagging; tag run
  [29706840456](https://github.com/carmelosantana/minecraft-electric-furnace/actions/runs/29706840456)
  completed success in 32s.
- Release <https://github.com/carmelosantana/minecraft-electric-furnace/releases/tag/v0.1.1>,
  `draft: false`, `prerelease: false`.
- Exactly two assets: `electric-furnace-0.1.1.jar` + `SHA256SUMS.txt`. No `original-*`.
- `sha256sum --check` → `OK`.

### Checksum verification failure in v0.1.0 — ecosystem-wide workflow defect (FIXED HERE)

`sha256sum --check SHA256SUMS.txt` on the downloaded assets reports:

```
sha256sum: target/electric-furnace-0.1.0.jar: No such file or directory
target/electric-furnace-0.1.0.jar: FAILED open or read
```

**The JAR itself is intact.** Its actual SHA-256 is
`b65e12588410b86b172b6d6dba6af797355fcd5e953c14dc9cd897196e4a395e`, which matches the
recorded digest exactly. Stripping the path prefix makes the check report `OK`.

The defect is in the workflow's checksum step. `.github/workflows/build.yml` runs:

```bash
find target -maxdepth 1 -name '*.jar' ! -name 'original-*' -print0 | sort -z | xargs -0 sha256sum > target/SHA256SUMS.txt
```

`sha256sum` records whatever path it was given, so the manifest contains the
build-time path `target/electric-furnace-0.1.0.jar`. Release assets download flat,
so the recorded path never resolves and verification always fails.

**This is not specific to this plugin.** The workflow was copied byte-for-byte from
the CopperKingdom reference, which matches `GITHUB_ACTIONS.md`. Verified against the
latest published releases of three siblings — all carry the same `target/` prefix:

| Plugin | Published `SHA256SUMS.txt` entry |
|---|---|
| copper-kingdom | `…  target/copper-kingdom-0.2.0.jar` |
| death-depot | `…  target/death-depot-1.1.0.jar` |
| curse | `…  target/curse-0.2.0.jar` |

Every plugin release in the ecosystem therefore fails gate 9's checksum requirement
as published. The fix belongs in the shared standard (`GITHUB_ACTIONS.md` and every
repository's `build.yml`), not in this plugin alone — e.g. `cd target && sha256sum
*.jar` or piping through `basename`.

Escalated to the operator rather than resolved unilaterally: the remedy requires
either moving an already-published tag or burning a version number, and it changes a
convention shared by ten repositories.

## 10. Updater

- [x] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs, enabled state, and optional pin.
  - Enrolled in `carmelosantana/minecraft-plugin-updater` commit `43f5bb7`.
  - `repo` `carmelosantana/minecraft-electric-furnace`; `destination` `electric-furnace.jar`
    (unique across all 11 entries, verified); `asset_regex` `^electric-furnace-[0-9].*\.jar$`;
    `legacy_globs` `["electric-furnace-[0-9]*.jar"]`; `enabled` absent (= true); **no pin**,
    deliberately — the entry follows the latest stable release.
  - Regex verified to select exactly one asset: matches `electric-furnace-0.1.1.jar`;
    rejects `SHA256SUMS.txt`, `original-electric-furnace-0.1.1.jar`, and other plugins' JARs.
  - `python3 -m json.tool plugins.json` valid; `python3 -m unittest discover -s tests` 11/11 OK.
- [x] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure behaviors pass.
  - **Fresh install:** resolves `v0.1.1`; installed JAR SHA-256
    `3e1532e8a929dd014ddc6b027fc6bc69615a225bde8d96acf304b9fc926eacae` matches the
    published `SHA256SUMS.txt` bit for bit — release-to-install verified end to end.
  - **No-op:** second real run reports `already current (v0.1.1)`.
  - **Upgrade:** stale bytes at the destination trigger a reinstall.
  - **Legacy glob:** selects a versioned leftover (`electric-furnace-0.0.9.jar`) while
    correctly excluding the destination filename it just installed.
  - **Endpoint failure:** a 404 repo logs `WARNING: ... keeping installed JAR`, exits 0
    (fail-open), leaves the destination JAR untouched, and the other ten plugins still process.
  - **Checksum failure:** covered by the updater's own unit suite (11/11 OK), not re-derived here.
- [x] Updater dry-run uses a disposable directory and never a production plugin directory.
  - All runs used `/tmp/minecraft-plugin-updater-dry-run`. Every non-dry-run invocation
    overrode all three of `--plugins-dir`, `--state-file`, and `--backup-dir` into that
    sandbox, so nothing touched the `/minecraft` production volume. Tree deleted afterward.
- [x] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.
  - Confirmed by the endpoint-failure case above: exit 0, JAR retained, batch continued.

## 11. Deployment

- [ ] Dokploy redeployment notes identify the full recreation used to rerun the one-shot updater.
- [ ] Updater completion, Minecraft startup, destination JAR, and stack/plugin logs were inspected.
- [ ] No production plugin hot reload was used.

## 12. Handoff

- [ ] Current-state documentation refreshed with release, CI, updater, deployment, and local pending state.
- [ ] Known limitations, skipped checks, configuration or migration notes, rollback guidance, and follow-up owner are recorded.
- [ ] Evidence distinguishes source commit, published tag/release, updater state, and deployed state without exposing secrets.
