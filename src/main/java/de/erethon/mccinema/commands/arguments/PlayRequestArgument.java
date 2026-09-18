package de.erethon.mccinema.commands.arguments;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.audio.AudioManager;
import de.erethon.mccinema.commands.PlayRequest;
import de.erethon.mccinema.video.FrameProcessor;
import de.erethon.mccinema.video.VideoFileTypes;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class PlayRequestArgument implements CustomArgumentType.Converted<PlayRequest, String> {

    private static final DynamicCommandExceptionType INVALID_REQUEST = new DynamicCommandExceptionType(message ->
        MessageComponentSerializer.message().serialize(Component.text(message.toString()))
    );

    @Override
    public @NonNull PlayRequest convert(@NonNull String input) throws CommandSyntaxException {
        if (input.isBlank()) {
            throw INVALID_REQUEST.create("A video file or URL is required.");
        }

        List<String> tokens = new ArrayList<>(Arrays.asList(input.trim().split("\\s+")));
        int sourceTokens = sourceTokenCount(tokens);
        String source = String.join(" ", tokens.subList(0, sourceTokens));

        boolean withAudio = false;
        boolean experimental = false;
        boolean explicitDither = false;
        boolean acceptTools = false;
        int chunkDurationMs = AudioManager.DEFAULT_CHUNK_DURATION_MS;
        FrameProcessor.DitheringMode ditheringMode = FrameProcessor.DitheringMode.FLOYD_STEINBERG_REDUCED;
        List<String> targetPlayerNames = new ArrayList<>();

        for (int i = sourceTokens; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equalsIgnoreCase("--audio")) {
                withAudio = true;
                if (i + 1 < tokens.size() && isAudioChunkArgument(tokens.get(i + 1))) {
                    String chunk = tokens.get(++i).toLowerCase(Locale.ROOT);
                    if (chunk.equals("single") || chunk.equals("0")) {
                        chunkDurationMs = 0;
                    } else {
                        int seconds = Integer.parseInt(chunk);
                        if (seconds < 0) {
                            throw INVALID_REQUEST.create("Chunk duration must be positive or 'single'.");
                        }
                        chunkDurationMs = seconds * 1000;
                    }
                }
            } else if (token.equalsIgnoreCase("--accept-tools")) {
                acceptTools = true;
            } else if (token.equalsIgnoreCase("--experimental")) {
                experimental = true;
            } else if (token.equalsIgnoreCase("--dither")) {
                explicitDither = true;
                if (i + 1 >= tokens.size()) {
                    throw INVALID_REQUEST.create("Missing dithering mode after --dither.");
                }
                String mode = tokens.get(++i);
                try {
                    ditheringMode = FrameProcessor.DitheringMode.valueOf(mode.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    throw INVALID_REQUEST.create("Invalid dithering mode: " + mode);
                }
            } else if (token.startsWith("--")) {
                throw INVALID_REQUEST.create("Unknown option: " + token);
            } else {
                targetPlayerNames.add(token);
            }
        }

        if (experimental && explicitDither) {
            throw INVALID_REQUEST.create("--experimental cannot be combined with --dither.");
        }

        return new PlayRequest(input.trim(), source, withAudio, chunkDurationMs, ditheringMode,
            experimental, acceptTools, List.copyOf(targetPlayerNames));
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.greedyString();
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        File videos = new File(MCCinema.getInstance().getDataFolder(), "videos");
        String[] files = videos.list((_, name) -> VideoFileTypes.isSupported(name));
        if (files != null && !remaining.contains("--")) {
            boolean suggestedFile = false;
            for (String name : files) {
                if (name.toLowerCase(Locale.ROOT).startsWith(remaining.toLowerCase(Locale.ROOT))) {
                    builder.suggest(name);
                    suggestedFile = true;
                }
            }
            if (suggestedFile) {
                return builder.buildFuture();
            }
        }

        int lastSpace = remaining.lastIndexOf(' ');
        if (lastSpace < 0) {
            return builder.buildFuture();
        }

        SuggestionsBuilder current = builder.createOffset(builder.getStart() + lastSpace + 1);
        String prefix = current.getRemainingLowerCase();
        String beforeCurrent = remaining.substring(0, lastSpace).trim();
        int previousSpace = beforeCurrent.lastIndexOf(' ');
        String previous = beforeCurrent.substring(previousSpace + 1);

        List<String> candidates;
        if (previous.equalsIgnoreCase("--audio")) {
            candidates = new ArrayList<>(List.of("single", "5", "10", "15", "30", "60"));
        } else if (previous.equalsIgnoreCase("--dither")) {
            candidates = new ArrayList<>(Arrays.stream(FrameProcessor.DitheringMode.values()).map(Enum::name).toList());
        } else {
            candidates = new ArrayList<>(List.of("--audio", "--dither", "--experimental"));
        }
        if (!previous.equalsIgnoreCase("--dither")) {
            Bukkit.getOnlinePlayers().stream().map(Player::getName).forEach(candidates::add);
        }
        candidates.stream()
            .filter(candidate -> candidate.toLowerCase(Locale.ROOT).startsWith(prefix))
            .forEach(current::suggest);
        return current.buildFuture();
    }

    private int sourceTokenCount(List<String> tokens) {
        if (isUrl(tokens.getFirst())) {
            return 1;
        }

        int optionIndex = tokens.size();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).startsWith("--")) {
                optionIndex = i;
                break;
            }
        }

        int matchingTokens = 0;
        for (int length = 1; length <= optionIndex; length++) {
            if (localVideoExists(String.join(" ", tokens.subList(0, length)))) {
                matchingTokens = length;
            }
        }
        return matchingTokens > 0 ? matchingTokens : Math.max(1, optionIndex);
    }

    private boolean localVideoExists(String fileName) {
        MCCinema plugin = MCCinema.getInstance();
        return new File(plugin.getDataFolder(), "videos/" + fileName).exists()
            || new File(plugin.getDataFolder(), fileName).exists();
    }

    private boolean isUrl(String source) {
        String lower = source.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private boolean isAudioChunkArgument(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("single") || lower.equals("0")) {
            return true;
        }
        try {
            Integer.parseInt(lower);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
