# New or Edited Plugin Checklist

Copy this file for one plugin and replace every `<...>` field. Leave an unchecked box with a short explanation when a gate is not complete; do not silently remove inapplicable checks.

- Plugin name: `ElectricFurnace`
- Slug: `electric-furnace`
- Repository: `carmelosantana/minecraft-electric-furnace`
- Owner: `Carmelo Santana`
- Target version: `0.1.0`
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
stat block) and `alloys.balance-ceiling.enabled`. Every numeric key is
range-validated on load; invalid values warn and fall back to the default rather
than disabling the plugin. Full table in the design doc.

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

### Open blocker — must be resolved before gate 10

**Repository visibility is still `PRIVATE`; it must be `PUBLIC` before updater
enrollment.** `gh repo create` defaulted to private. All four sibling plugin repos
(copper-kingdom, death-depot, curse, starter-pack) are `PUBLIC`, and the updater
downloads release assets unauthenticated — a private repository fails enrollment.

Verified still private on 2026-07-19 after the push, via
`gh api repos/carmelosantana/minecraft-electric-furnace` → `private=true`. The
operator reported having changed it; the change did not take. Re-run:

```bash
gh repo edit carmelosantana/minecraft-electric-furnace \
  --visibility public --accept-visibility-change-consequences
```

This does not block gates 4–7a. It blocks gate 10.

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

- [ ] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers all ten updater-managed plugins.
  - **Out of band, and not a prerequisite for this plugin's release.** Belongs to
    `minecraft-plugin-matrix`, triggered by an updater manifest change, a
    Paper/Geyser/Floodgate/ViaVersion bump, or explicit request — not by every `dev`
    run. Gate 7a above tested this plugin alone in an otherwise-default stack and is
    not evidence about interaction with the other nine.
- [ ] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately.
- [ ] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
- [ ] Java and Bedrock smoke tests cover joins plus affected commands, events, permissions, persistence, and reloads where feasible.
- [ ] Public deployment smoke tests verify `play.xpfarm.org` reaches the intended Java and Bedrock entry points.
- [ ] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable.

## 8. CI/CD

- [x] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior.
  - `.github/workflows/build.yml` copied byte-for-byte from the CopperKingdom reference, which matches `GITHUB_ACTIONS.md`. Triggers: push to `main`, `v*` tags, PRs targeting `main`, `workflow_dispatch`. `actions/checkout@v7`, `actions/setup-java@v5` (Temurin 25, Maven cache), `mvn --batch-mode --no-transfer-progress clean verify`, `SHA256SUMS.txt` excluding `original-*`, `actions/upload-artifact@v7`, tag-gated `gh release view`/`create`/`upload --clobber`.
- [ ] Successful main Actions run is recorded before tagging.
  - Not tickable here — this is `minecraft-plugin-release`'s (gate 8b). Additionally **no run exists yet**, because the push was blocked (see §2 blockers).
- [x] Workflow permissions contain no broader access than the documented contract.
  - `permissions: contents: write` only. No `packages:`, `id-token:`, or other scopes.

## 9. Release

- [ ] Semantic version matches the POM, plugin metadata, and `v<version>` tag.
- [ ] Successful tag Actions run and GitHub release are recorded.
- [ ] Release contains exactly one updater-matching JAR plus `SHA256SUMS.txt` and no `original-*` JAR.
- [ ] Downloaded release assets pass `sha256sum --check SHA256SUMS.txt`.

## 10. Updater

- [ ] Updater manifest/tests cover repository, destination, anchored asset regex, legacy globs, enabled state, and optional pin.
- [ ] Fresh install, upgrade, no-op, legacy archival, endpoint failure, and checksum failure behaviors pass.
- [ ] Updater dry-run uses a disposable directory and never a production plugin directory.
- [ ] Failure retains the installed JAR and default fail-open behavior permits Minecraft startup.

## 11. Deployment

- [ ] Dokploy redeployment notes identify the full recreation used to rerun the one-shot updater.
- [ ] Updater completion, Minecraft startup, destination JAR, and stack/plugin logs were inspected.
- [ ] No production plugin hot reload was used.

## 12. Handoff

- [ ] Current-state documentation refreshed with release, CI, updater, deployment, and local pending state.
- [ ] Known limitations, skipped checks, configuration or migration notes, rollback guidance, and follow-up owner are recorded.
- [ ] Evidence distinguishes source commit, published tag/release, updater state, and deployed state without exposing secrets.
