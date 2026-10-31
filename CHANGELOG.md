## [Unreleased]

### Added

- `/jukebox record start [<name>]` / `stop` / `cancel` / `status`: records the server into `<world>/jukebox/discs/<name>.disc`. Tick rate changes (`/tick rate|freeze|step|sprint`) are recorded; frozen ticks are skipped.
- Sounds played by the world (blocks, entities, noteblocks, ...) are recorded with their raw pos, volume, pitch and random seed, even when no player is in range.
- Sounds sent to players directly (`/playsound`, raid horn, other mods) are recorded once per sound with the receiving players as `targets`; `/playsound` keeps its raw position, volume and `minVolume`.
- `/stopsound` is recorded as a `stop` event with its sound, category and receiving players, in order with the sounds around it.
