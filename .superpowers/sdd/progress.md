# ElectricFurnace SDD Progress Ledger

Plan: `docs/superpowers/plans/2026-07-19-electric-furnace-plan.md`
Branch: `main` (xpfarm lifecycle convention; autonomy = autonomous)
Scaffold base: `22b8a5e`

## Tasks

- [x] Task 1 — Configuration layer
- [ ] Task 2 — Alloy model and pure recycle resolver
- [ ] Task 3 — Item layer and cross-plugin PDC contract
- [ ] Task 4 — Machine persistence
- [ ] Task 5 — GUI and machine listeners
- [ ] Task 6 — Effects, command, and plugin wiring

## Completed

- Task 1: complete (commits 22b8a5e..d86db74, spec ✅, quality approved). ConfigValidatorTest 20/20.
- Task 2: complete (commits d86db74..a4b1ba0, spec ✅, quality approved after one Important fix).
  47 tests green. Two plan deviations accepted, both verified sound by review:
  (a) rule 3 counts ALL items, not only non-modifiers — the literal wording would
  reject the plan's own mandated "4 iron + 1 coal → Steel" example;
  (b) `alloys.balance-ceiling.enabled` toggle omitted, ceiling enforced
  unconditionally. **The design doc's config table still lists that key — it must be
  corrected at exit, or the doc lies about the shipped config.**
  Important finding fixed in a4b1ba0: deleted a vacuous damaged/undamaged test that
  could not fail. **accept-damaged coverage is therefore owed by Task 3.**

- Task 3: complete (commits a4b1ba0..47e4d17, spec ✅, quality approved after one
  correctness fix). 67 tests green. Accept-damaged coverage owed by Task 2 was
  delivered here and is non-vacuous.
  **Correctness bug found by the controller, not the reviewer, and fixed in 47e4d17:**
  the CopperKingdom foreign-PDC branch was guarded by `metalOf(material).isEmpty()`.
  CopperKingdom armor uses LEATHER_* bases (fallback fired, correct) but its weapons
  use IRON_SWORD/IRON_AXE/IRON_PICKAXE bases, which ARE in METAL_TABLE — so copper
  swords classified as IRON and would have returned iron ingots. PDC now takes
  precedence over the material table. The precedence decision was extracted into a
  pure `resolveBranch(...)` helper, tested across all 16 boolean combinations.
  Latent, not yet a bug: `ingotValue` is a flat 1 for every material including gear
  that really costs 1-8 ingots. `RecycleResolver` does not consult it today. If
  Task 5/6 surfaces it to players (GUI tooltip, `/electricfurnace info`), it must be
  made accurate first.

- Task 4: complete (commits 47e4d17..586c7b2, spec ✅, quality approved). 99 tests green.
  Decode logic reviewed as total — no unguarded parse, index, or substring on hostile
  input. Follow-up 586c7b2 added 12 hostile-input tests (empty segments, int overflow,
  leading separators, whitespace, null) and hardened `MachineRegistry`'s PDC read:
  `has()` check plus a RuntimeException guard, because a non-STRING NBT value written
  under the same key by another plugin can make `get()` throw, and that exception would
  escape into Bukkit's chunk-load path. Degrade the plugin, never break the world.

## Environment note for all later tasks

`java` and `mvn` are not on PATH by default. Source
`~/.sdkman/bin/sdkman-init.sh` first (Java 25.0.3-tem, Maven 3.9.16).

## Minor findings roll-up (for final whole-branch review)

- **Task 1, `EfConfig.java:445-464`** — `resolveSound` catches
  `ReflectiveOperationException` but not `Error`. If `org.bukkit.Sound`'s static
  initializer throws an `Error`, it escapes and violates the central
  "config problems never fail startup" contract. **Assigned to Task 6**, which owns
  `onEnable`. Widen the catch to `Throwable` there.
- **Task 1, `ConfigValidator.java:336-344`** — warning messages render the *parsed*
  value, so raw `"99.90"` prints as `99.9`. Cosmetic.
