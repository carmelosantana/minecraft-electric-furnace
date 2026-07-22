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
| 1× alloy item | 1 ingot |

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
