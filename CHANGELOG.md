## [Unreleased]

### Added

- `/jukebox record start [<name>]` / `stop` / `cancel` / `status`: records the server into `<world>/jukebox/discs/<name>.disc`. Tick rate changes (`/tick rate|freeze|step|sprint`) are recorded; frozen ticks are skipped.
- Sounds played by the world (blocks, entities, noteblocks, ...) are recorded with their raw pos, volume, pitch and random seed, even when no player is in range.
