package de.erethon.mccinema.commands.arguments;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.commands.DownloadRequest;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class DownloadRequestArgument implements CustomArgumentType.Converted<DownloadRequest, String> {

    private static final SimpleCommandExceptionType MISSING_SOURCE = new SimpleCommandExceptionType(
        MessageComponentSerializer.message().serialize(Component.text("A YouTube URL or video ID is required."))
    );

    @Override
    public @NonNull DownloadRequest convert(@NonNull String input) throws CommandSyntaxException {
        if (input.isBlank()) {
            throw MISSING_SOURCE.create();
        }

        List<String> tokens = new ArrayList<>(Arrays.asList(input.trim().split("\\s+")));
        String source = null;
        List<String> customName = new ArrayList<>();
        boolean convertFps = MCCinema.getInstance().getConfig().getBoolean("youtube.auto-convert-fps", true);
        boolean acceptTools = false;

        for (String token : tokens) {
            if (token.equalsIgnoreCase("--no-convert")) {
                convertFps = false;
            } else if (token.equalsIgnoreCase("--accept") || token.equalsIgnoreCase("--accept-tools")) {
                acceptTools = true;
            } else if (source == null) {
                source = token;
            } else {
                customName.add(token);
            }
        }

        if (source == null) {
            throw MISSING_SOURCE.create();
        }
        return new DownloadRequest(source, customName.isEmpty() ? null : String.join(" ", customName),
            convertFps, acceptTools);
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.greedyString();
    }
}
