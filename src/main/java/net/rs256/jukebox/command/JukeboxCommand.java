package net.rs256.jukebox.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.rs256.jukebox.JukeboxMod;
import net.rs256.jukebox.server.ServerRecorder;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;

/** {@code /jukebox}, for gamemasters (permission level 2). */
public final class JukeboxCommand {
    private JukeboxCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal(JukeboxMod.MOD_ID)
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("record")
                                .then(Commands.literal("start")
                                        .executes(context -> start(context.getSource(), null))
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .executes(context -> start(context.getSource(), StringArgumentType.getString(context, "name")))
                                        )
                                )
                                .then(Commands.literal("stop")
                                        .executes(context -> stop(context.getSource()))
                                )
                        )
        );
    }

    private static int start(CommandSourceStack source, @Nullable String name) {
        ServerRecorder recorder = ServerRecorder.get();
        ServerRecorder.Status status = recorder.status();
        if (status != null) {
            source.sendFailure(Messages.translatable("jukebox.command.record.already_recording", status.name()));
            return 0;
        }
        if (name == null) {
            name = recorder.defaultName();
        }

        try {
            recorder.start(name);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Messages.translatable("jukebox.command.record.invalid_name", name));
            return 0;
        } catch (FileAlreadyExistsException e) {
            source.sendFailure(Messages.translatable("jukebox.command.record.exists", Path.of(e.getFile()).getFileName().toString()));
            return 0;
        } catch (IOException e) {
            JukeboxMod.LOGGER.error("Could not start recording {}", name, e);
            source.sendFailure(Messages.translatable("jukebox.command.record.failed", e.getMessage()));
            return 0;
        }
        String started = name;
        source.sendSuccess(() -> Messages.translatable("jukebox.command.record.start", started), true);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        ServerRecorder recorder = ServerRecorder.get();
        ServerRecorder.Status status = recorder.status();
        if (status == null) {
            source.sendFailure(Messages.translatable("jukebox.command.record.not_recording"));
            return 0;
        }

        Path disc;
        try {
            disc = recorder.stop();
        } catch (IOException e) {
            JukeboxMod.LOGGER.error("Could not save recording {}", status.name(), e);
            source.sendFailure(Messages.translatable("jukebox.command.record.failed", e.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Messages.translatable("jukebox.command.record.stop", disc.getFileName().toString(), status.ticks()), true);
        return 1;
    }
}
