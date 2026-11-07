# Ala Modlist

Press F8, check exactly what is about to leave your game, and share your installed mods as one short link on
[alacraft.day](https://alacraft.day/en/modlists).

Asking for help with a crash, or showing a friend your setup, usually starts with "what do you have installed?". Ala
Modlist reads what is installed, shows you the result before anything is sent, and after you confirm gives you a
link in chat:

    [Ala Modlist] Your mod list is ready (mods: 112, libraries: 74). [open] [copy] [delete]

Anyone with the link sees a page with your list. Mods the site can match to Modrinth by file fingerprint are marked
as checked and link to their pages.

## Requirements

| | Fabric | NeoForge |
|---|---|---|
| Minecraft | 26.2 or 26.3 | 26.2 or 26.3 |
| Loader | Fabric Loader 0.19.3 or newer on 26.2, 0.19.5 or newer on 26.3 | NeoForge 26.2.0.67 or newer on 26.2, 26.3.0.99 or newer on 26.3 |
| Also needed | [Fabric API](https://modrinth.com/mod/fabric-api) 0.161.0 or newer, built for your Minecraft version | - |
| Optional | [Mod Menu](https://modrinth.com/mod/modmenu), for the config screen | - |

Minecraft 26.2 and 26.3 run on Java 25; the official launcher brings it along.

## Installation

Put the jar for your game version and loader into the `mods` folder:
`alamodlist-fabric-<minecraft>-<version>.jar` or `alamodlist-neoforge-<minecraft>-<version>.jar`, for example
`alamodlist-fabric-26.3-0.1.0.jar`. The mod is client-only: it belongs on the player's side, and a dedicated
server neither needs it nor loads it.

## Using it

1. Press **F8** (rebindable under *Controls > Ala Modlist*) or type `/alamodlist`, or `/aml` for short. Both are
   run by the client; a server never sees them.
2. The preview opens and collects your list. It shows every category with a count and a switch, and a field for a
   title of your own. Nothing has left your computer yet.
3. Press **Send**. The link appears in chat with three buttons: open it, copy it, or delete the list from the site.

More commands:

- `/alamodlist list` shows your recent lists, newest first.
- `/alamodlist delete <id>` removes one from the site after asking you to confirm.

A list is kept for 90 days after it was last opened, and sooner if you delete it.

## What is in a list

- Minecraft, loader and Java versions, and the operating system family.
- Your mods: name, version, file name, file size and a SHA-1 fingerprint of each jar. A CurseForge fingerprint is
  added only if you switch it on in the settings.

The preview also lists categories this version cannot collect yet. They are shown switched off and labelled, and
nothing about them is sent.

## What is sent, and what never is

The mod sends data only when you press **Send** in the preview. It makes no request at start-up, in the
background or on a timer. The list goes to the site address in the settings (alacraft.day by default).

Never sent: file paths, your computer user name, your player name or UUID, server addresses, the world seed, your
IP address, tokens. A check before every send stops a list that contains something that identifies you, such as
your player name in the title, and tells you what it found; nothing is sent in that case. Like any web request, a
send shows the site the address it came from.

A list is a statement made by the player, not proof: anyone can send anything. The page says so, and only entries
whose fingerprint the site could match to Modrinth are marked as checked.

Details, retention and how to report a list: [the privacy notice](https://alacraft.day/en/modlists#privacy).

## Settings

`config/alamodlist-client.json`. The settings can also be changed in game: *Mods > Ala Modlist > Config* on
NeoForge, or through Mod Menu on Fabric.

- **Messages above the hotbar** - short messages of the mod appear above the hotbar instead of in chat.
- **CurseForge file fingerprint** - also compute the CurseForge fingerprint of every jar. It reads each jar a second
  time, so collecting takes longer.
- **Site address** - where lists are sent. Keep the default unless you run your own copy of the site.

Languages: English, Russian and Ukrainian.

## Building from source

```bash
./gradlew build
```

The jars land in `fabric/build/libs` and `neoforge/build/libs`. `./gradlew build -Pmc=26.2` builds the 26.2 line; the
default is 26.3. Java 25 is required; Gradle downloads everything else.
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) explains the project layout, running the game from the sources and the
tests.

## License

MIT - see [LICENSE](LICENSE).

Source code and issue tracker: <https://github.com/AlaMine-Team-Org/AlaModlist>.
