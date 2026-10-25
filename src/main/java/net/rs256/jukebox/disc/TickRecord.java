package net.rs256.jukebox.disc;

import java.util.List;

/** One line of {@code ticks.json}: the events of a jukebox tick in the order they happened. */
public record TickRecord(int tick, List<JukeboxEvent> events) {
}
