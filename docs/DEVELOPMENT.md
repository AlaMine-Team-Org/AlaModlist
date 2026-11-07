# Developing Ala Modlist

How the project is laid out, how to build it, run the game from the sources and test it.

## Prerequisites

- **JDK 25.** Minecraft 26.2 and 26.3 require Java 25. Gradle finds an installed JDK 25 and can download one
  when none is found.
- **Git.** The build stamps the jar with the commit it was built from.
- Nothing else: the Gradle wrapper (`gradlew`, `gradlew.bat`) downloads Gradle, Minecraft, the loaders
  and every library.

## Project layout

A client-only mod, two loaders, one set of sources:

| Module | What lives there | Sees Minecraft |
|---|---|---|
| `common` | plain Java: logic that needs no game, the config engine, the secret store, build info | no - the compiler keeps it that way |
| `common-mc` | the shared client code: key bindings, client commands, screens, mixins, **all resources** | vanilla 26.3 with official Mojang names |
| `fabric` | the Fabric client entrypoint and Mod Menu hook - a thin adapter | Fabric Loader + Fabric API |
| `neoforge` | the same for NeoForge | NeoForge |

`common` and `common-mc` are compiled into each loader's jar as sources, so each jar contains one copy
of the shared classes. The build logic shared by the modules is in `build-logic/`; versions are in
`gradle.properties` (everything the mod metadata uses) and `gradle/libs.versions.toml` (libraries and
plugins).

The loaders are adapters on purpose. Each has exactly one entrypoint, a client one, and both do the same
steps in the same order: they hand the shared code a `ClientPlatform` (the only seam to the loader),
register the key bindings listed in `ModKeyMappings`, and subscribe to the game events through one facade,
`client/ClientHooks`. A feature is therefore written once, in shared code, and behaves the same on both
loaders. The mod has no server half: no registered content, no packets, no player data.

## Building

```bash
./gradlew build
```

This compiles every module, runs the unit tests and checks that the client test stands compile, and
writes the jars of the default line (26.3):

- `fabric/build/libs/alamodlist-fabric-26.3-<version>.jar`
- `neoforge/build/libs/alamodlist-neoforge-26.3-<version>.jar`

The mod is built for two Minecraft lines, 26.2 and 26.3 (one file per line in `gradle/mc/`). `./gradlew build -Pmc=26.2`
builds the other one, and its jars are named `alamodlist-<loader>-26.2-<version>.jar`.

The jars are reproducible: the version stamp inside comes from the commit, not from the clock, so the same
commit builds the same bytes. `./gradlew verifyAll` adds the repository checks and is the bar for "done".

## Running the game from the sources

| Command | What starts |
|---|---|
| `./gradlew :fabric:runClient` | a Fabric client, player `Dev1`, in `fabric/runs/<line>/client` |
| `./gradlew :neoforge:runClient` | a NeoForge client, player `Dev1`, in `neoforge/runs/<line>/client` |

`<line>` is the Minecraft line the build is for (`mc` in `gradle.properties`, or `-Pmc=<line>`; its versions are in
`gradle/mc/<line>.properties`): a world opened on one line does not open on another, so each line has its own run
directories. Each run has its own directory, so worlds, options and player data stay between launches. Run one
loader at a time: the project allows a single Gradle run per directory.

Both loaders generate IntelliJ IDEA run configurations when the project is imported, and those can be
started in debug mode. The Gradle configuration cache is off on purpose: it breaks the run
configurations generated for Fabric.

## Tests

| Level | Where | Run with |
|---|---|---|
| Unit tests of the plain-Java code | `common/src/test` | `./gradlew :common:test` (part of `build`) |
| Tests of the shared game code without a game: architecture rules, translations | `common-mc/src/test` | `./gradlew :common-mc:test` (part of `build`) |
| Client game test - frames of the settings screen, the key and the command, Fabric | `fabric/src/gametest` | `./gradlew :fabric:runClientGameTest` (opens a game window), then `python tools/shot_manifest_check.py` |

There is no server world to test, so there are no server game tests. The client game test compares frames
of the screen and of the HUD before and after an action, and checks that the mixin was applied. It does not
ship in the jars.

## Conventions

- Shared behaviour goes into `common` or `common-mc`; the loader modules only adapt it. Code that compiles
  without Minecraft belongs in `common`.
- Every text a player sees comes from a lang key in `common-mc/src/main/resources/assets/alamodlist/lang/`,
  and every locale has exactly the keys of `en_us.json`.
- `gradle.properties` is ASCII only: Gradle reads it as ISO-8859-1. The mod description, the one text that
  wants more, is in `mod_description.txt`.
- The minimum loader versions the mod declares (`*_min_version` in `gradle.properties`) are separate from
  the versions the project builds against, and they are raised on purpose only.
- Nothing that identifies the player or their machine leaves the client; a stored secret goes through
  `SecretStore`, which writes it to a file only its owner can read, outside `config/`.

## Troubleshooting

- **"Another Gradle build is already running in this directory"** - the build allows one Gradle run per
  directory at a time, because two parallel runs corrupt each other's outputs. Wait for the other one, or
  work in a separate `git worktree`.
- **The mod does not appear on a dedicated server** - that is intended: it is client-only, and a server
  does not load it.
