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

- [ ] Repository is `carmelosantana/minecraft-<slug>` with an SSH `origin` and `main` branch.
- [ ] Existing user-owned worktree changes were identified and preserved.
- [ ] No `herobrinesystems` references remain in source, metadata, workflows, remotes, or documentation.

## 3. Metadata

- [ ] AGPL-3.0-or-later `LICENSE` and Maven license metadata are present and consistent.
- [ ] `https://xpfarm.org` metadata and Carmelo Santana author metadata are present.
- [ ] `play.xpfarm.org` is recorded as the public Minecraft server hostname where server identity is documented.
- [ ] New work uses the `org.xpfarm` Maven group, or an existing-coordinate compatibility decision is documented.
- [ ] Repository slug, artifact, releasable JAR, updater destination, and `plugin.yml` names are consistent.
- [ ] No secrets committed in source, defaults, tests, logs, history, or documentation.

## 4. Compatibility

- [ ] Java 25/Paper 26.1.2 build 74 compile succeeds and `plugin.yml` uses `api-version: '1.21'`.
- [ ] Hard dependencies, soft dependencies, optional APIs, and load ordering were reviewed and declared.
- [ ] Geyser/Floodgate/ViaVersion review covers Bedrock-safe input, UI, inventory, identity, and protocol behavior.

## 5. External services

- [ ] External integrations are disabled by default or require explicit configuration and have bounded timeouts.
- [ ] Ollama/Umami-style external endpoints are optional and failure-tolerant when applicable.
- [ ] Endpoint failure cannot fail server/plugin startup, and diagnostics redact secrets.

## 6. Tests and build

- [ ] Unit tests cover separable logic, configuration, serialization, permissions, and failure paths where applicable.
- [ ] `mvn --batch-mode --no-transfer-progress clean verify` succeeds.
- [ ] The shaded releasable JAR and embedded `plugin.yml` were inspected; `original-*` JARs are excluded.

## 7. Matrix

- [ ] Fresh-volume [Legendary Java Minecraft Geyser Floodgate stack](https://github.com/TheRemote/Legendary-Java-Minecraft-Geyser-Floodgate) test covers all ten updater-managed plugins.
- [ ] Each updater-managed plugin's manifest `enabled` value, default state, and expected fresh-volume behavior are recorded separately.
- [ ] Paper, Geyser, Floodgate, and ViaVersion start successfully together.
- [ ] Java and Bedrock smoke tests cover joins plus affected commands, events, permissions, persistence, and reloads where feasible.
- [ ] Public deployment smoke tests verify `play.xpfarm.org` reaches the intended Java and Bedrock entry points.
- [ ] Ollama and Umami unavailable-endpoint tests keep the server and plugins available when applicable.

## 8. CI/CD

- [ ] Identical standard plugin Actions workflow is installed with the required triggers, Temurin 25 build, artifact, checksum, and release behavior.
- [ ] Successful main Actions run is recorded before tagging.
- [ ] Workflow permissions contain no broader access than the documented contract.

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
