## [Unreleased]

### Added

- `/jukebox record start [<name>]` / `stop` / `cancel` / `status`: records the server into `<world>/jukebox/discs/<name>.disc`. Only tick rate changes (`/tick rate|freeze|step|sprint`) are recorded so far; frozen ticks are skipped.
