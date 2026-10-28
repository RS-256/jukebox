package net.rs256.jukebox.server;

import net.minecraft.server.ServerTickRateManager;
import net.rs256.jukebox.disc.JukeboxEvent;
import org.jspecify.annotations.Nullable;

/**
 * The jukebox tick: counts only server ticks that ran game elements, so freeze is skipped and
 * lag or sprint do not matter. Tick rate state is polled instead of hooked so that mods writing
 * {@code nanosecondsPerTick} directly are followed too.
 */
public final class TickClock {
    private final ServerTickRateManager manager;
    private int tick;
    private long nspt;
    private boolean frozen;
    private int steps;
    private boolean stepping;
    private int sprint;

    public TickClock(ServerTickRateManager manager) {
        this.manager = manager;
        this.nspt = manager.nanosecondsPerTick();
        this.frozen = manager.isFrozen();
        this.steps = manager.frozenTicksToRun();
        this.sprint = (int) manager.scheduledCurrentSprintTicks;
    }

    public int tick() {
        return this.tick;
    }

    /** The whole state at the start of a recording; {@code steps} and {@code sprint} only while active. */
    public JukeboxEvent.TimingEvent initialEvent() {
        Integer steps = null;
        if (this.steps > 0) {
            steps = this.steps;
        }
        Integer sprint = null;
        if (this.sprint > 0) {
            sprint = this.sprint;
        }
        return new JukeboxEvent.TimingEvent(this.nspt, this.frozen, steps, sprint);
    }

    /**
     * What changed since the last poll, or null. {@code sprint} is reported when a sprint starts (its
     * length) and when it ends (0). {@code steps} is reported once when a {@code /tick step} starts,
     * not while it counts down: a tick that stayed frozen but still ran game elements is a step, and by
     * the time it is polled that step is already taken from {@code frozenTicksToRun}, hence the {@code + 1}.
     */
    public JukeboxEvent.@Nullable TimingEvent poll() {
        long nspt = this.manager.nanosecondsPerTick();
        boolean frozen = this.manager.isFrozen();
        int steps = this.manager.frozenTicksToRun();
        boolean stepping = frozen && this.frozen && this.manager.runsNormally();
        int sprint = (int) this.manager.scheduledCurrentSprintTicks;

        Long nsptChange = null;
        if (nspt != this.nspt) {
            nsptChange = nspt;
        }
        Boolean frozenChange = null;
        if (frozen != this.frozen) {
            frozenChange = frozen;
        }
        Integer stepsChange = null;
        if (stepping && (!this.stepping || steps + 1 > this.steps)) {
            stepsChange = steps + 1;
        }
        Integer sprintChange = null;
        if (sprint != this.sprint) {
            sprintChange = sprint;
        }

        this.nspt = nspt;
        this.frozen = frozen;
        this.steps = steps;
        this.stepping = stepping;
        this.sprint = sprint;

        if (nsptChange == null && frozenChange == null && stepsChange == null && sprintChange == null) {
            return null;
        } else {
            return new JukeboxEvent.TimingEvent(nsptChange, frozenChange, stepsChange, sprintChange);
        }
    }

    /** Moves to the next jukebox tick if this server tick ran game elements. */
    public boolean advance() {
        if (this.manager.runsNormally()) {
            this.tick++;
            return true;
        } else {
            return false;
        }
    }
}
