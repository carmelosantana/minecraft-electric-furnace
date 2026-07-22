# ElectricFurnace

A redstone-powered industrial smelter for Paper servers. It smelts faster than a
vanilla furnace and recycles metal gear back into ingots — five of the same metal
returns ingots of that metal, while a mix fuses into a stronger alloy.

Running on **`play.xpfarm.org`** (Java and Bedrock, via Geyser + Floodgate).

## How it works

The furnace needs both a **redstone signal** and **redstone dust**. The signal is
the on/off switch; the dust is the fuel. Neither alone will run it.

| You put in | You get |
|---|---|
| 5× the same metal item | 3 ingots of that metal |
| 5× mixed metal items | 2 alloy ingots |
| Any number of one alloy's items | 1 ingot each |

Remelting is the one recipe that doesn't need a full five slots — drop in a single
worn Steel Sword, or a whole Steel armour set, and get an ingot back per piece.

### Alloys

Combining *different* metals produces something stronger than either parent — the
same reason bronze beat copper and steel beat iron. Remelting the *same* metal
repeatedly is where material is lost.

Some mixes make named alloys worth discovering:

| Alloy | Inputs |
|---|---|
| Steel | iron + coal |
| Rose Gold | copper + gold |
| Ferrocopper | copper + iron |
| Electrum Steel | gold + iron |
| Fused Alloy | any other mix |

Coal is a modifier, not a metal — it can't be recycled on its own, and it only
matters as an ingredient in a named recipe. (Carbon into iron is literally how
steel is made.)

Alloys sit between iron and diamond in strength. Never above netherite.

### Alloy gear

Alloy ingots craft into gear. Every alloy has all six pieces, in the vanilla
shapes with the alloy ingot in place of the metal — thirty items in all. They
show up in the in-game recipe book, and crafting them needs
`electricfurnace.craft`, the same node that gates the furnace itself.

| Piece | Costs |
|---|---|
| Sword | 2 ingots + 1 stick |
| Axe | 3 ingots + 2 sticks |
| Helmet | 5 ingots |
| Chestplate | 8 ingots |
| Leggings | 7 ingots |
| Boots | 4 ingots |

Each piece's stats are derived from its alloy's `stats` block in `config.yml`, so
retuning an alloy retunes its whole set.

The `alloys.<id>.base` key names the vanilla family an alloy's gear is built on — one of
`copper`, `iron`, `gold`, `diamond`, `netherite`. That's the only thing that makes
two alloys' gear look different: there is no resource pack, so the texture comes
from the base item. Steel is iron-shaped, Rose Gold is gold-shaped. A
netherite-based alloy gets neither fire immunity nor knockback resistance — both
are stripped.

Worn gear goes straight back in the furnace to be remelted, so nothing is wasted.

## Commands

| Command | Permission |
|---|---|
| `/electricfurnace give [player] [amount]` | `electricfurnace.give` (op) |
| `/electricfurnace alloy <id> [piece] [amount]` | `electricfurnace.give` (op) |
| `/electricfurnace reload` | `electricfurnace.reload` (op) |
| `/electricfurnace info` | `electricfurnace.use` (default) |

## Bedrock support

Fully supported. The furnace deliberately uses only vanilla blocks, particles, and
sounds that Geyser translates — no resource pack is required on either edition.
See [the design document](docs/superpowers/specs/2026-07-19-electric-furnace-design.md)
for the researched constraints behind that decision.

## Building

```bash
mvn --batch-mode --no-transfer-progress clean verify
```

Requires Java 25. Produces the shaded `electric-furnace` JAR in `target/`.

## License

AGPL-3.0-or-later. See [LICENSE](LICENSE).

Copyright © Carmelo Santana — [xpfarm.org](https://xpfarm.org)
