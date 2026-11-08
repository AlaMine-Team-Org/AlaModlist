# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.1.0] - 2026-11-08

### Added

- The first version of Ala Modlist: share what you have installed as one short link.
- Press F8 (rebindable under Controls) or type `/alamodlist`, or `/aml` for short, to open a preview of your list.
- The preview shows the Minecraft, loader and Java versions, the operating system family and your installed mods,
  with a count and a switch for each category and a field for a title of your own.
- Nothing is sent until you press Send. The mod makes no request at start-up or in the background.
- Each mod is listed with its name, version, file name, file size and a SHA-1 fingerprint of the jar. A CurseForge
  fingerprint can be added in the settings.
- A privacy check stops a list that contains your player name, your computer user name, a server address or another
  detail that identifies you, and tells you what it found.
- The link in chat comes with open, copy and delete buttons. `/alamodlist list` shows your recent lists and
  `/alamodlist delete <id>` removes one after a confirmation. The site keeps a list for 90 days after it was last opened.
- Settings for the messages above the hotbar, the CurseForge fingerprint and the site address, with a config screen
  through Mod Menu on Fabric and the mod list screen on NeoForge.
- Available in English, Russian and Ukrainian, for Minecraft 26.2 and 26.3 on Fabric and NeoForge.
- Resource packs, shaders and server plugins are planned for a later version.
