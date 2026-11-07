# Contributing to Ala Modlist

Thank you for taking the time. Bug reports, translations and code are all welcome.

## Reporting a bug

Open an issue at <https://github.com/AlaMine-Team-Org/AlaModlist/issues> with:

- the loader (Fabric or NeoForge) and its version, the Minecraft version and the mod version
  (the mod list of the game shows it);
- what you did, what you expected and what happened instead;
- the log: `logs/latest.log` of the game or the server, and `crash-reports/` if the game crashed.
  A link to a paste service is better than a screenshot of the log.

Please try without other mods first: if the problem disappears, name the mod that causes it.

## Translations

Translations live in `common-mc/src/main/resources/assets/alamodlist/lang/`. `en_us.json` is the
source: copy it to `<locale>.json` (for example `de_de.json`) and translate the values, never the keys.
Keep every `%s` placeholder, and keep the file valid JSON in UTF-8. A translation must have exactly the
keys of `en_us.json` - the build checks it.

## Code

This repository is published from the main development repository, so a pull request is not merged
here directly: an accepted change is applied there and comes back with the next sync. Open an issue
before a large change, so we can agree on the approach first.

Before sending a change:

- build both loaders and run the tests: `./gradlew build` (see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md));
- test in the game on **both** Fabric and NeoForge if players can see the change;
- keep shared behaviour in `common` or `common-mc`; the `fabric` and `neoforge` modules only adapt it to
  their loader, and both of them must take the same steps;
- put every text a player sees into the lang files, in English at least;
- follow the style of the code around your change.

By contributing you agree that your contribution is licensed under the license of this project
([LICENSE](LICENSE)).
