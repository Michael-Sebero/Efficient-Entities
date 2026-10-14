# Efficient Entities

A client-side Minecraft 1.12.2 mod that draws entity models in batches. Vanilla draws each model part as its own display list call and sets up the part's transform with separate GL matrix calls. This mod applies the bone transforms on the CPU, writes the vertices into a persistent-mapped VBO, and draws each model's parts with one draw call.

## Requirements

- **Forge 1.12.2** with [MixinBooter](https://github.com/CleanroomMC/MixinBooter) 10 or newer, or
- **Cleanroom** (MixinBooter is built in).

The jar is plain Java 8 bytecode, so the same file runs on Forge (Java 8) and Cleanroom (Java 21+).

## Behaviour

- **OptiFine:** when OptiFine is installed, the mod steps aside so OptiFine's shaders and custom entity models keep working.
- **Chests:** chest renderers, including modded `*ChestRenderer`s, use vanilla rendering.
- **Custom render code:** model parts whose class overrides `render`, and boxes whose class overrides `render`, also use vanilla rendering. They are positioned correctly within the batched model.
- **No persistent buffers:** GPUs without persistent buffer support, such as macOS's legacy OpenGL, fall back to client-side vertex arrays.

## Configuration

`config/efficiententities.cfg`, also editable from the in-game mod list:

| Option | Default | |
|---|---|---|
| `enabled` | `true` | Turn off to compare against vanilla rendering without restarting. |
| `statisticsLogPeriod` | `0` | Log draw/box counts every N frames; `0` disables. |

## Building

Requires a JDK 17+ to run Gradle; the Java 8 toolchain used for compiling is provisioned automatically.

```bash
./gradlew build
```

The mod jar is written to `build/libs/efficient-entities-<version>.jar`. `./gradlew runClient` starts a development client with the coremod loaded, and `./gradlew test` checks the baked geometry against vanilla's own quad output and the ASM transformer against every vanilla model class.
