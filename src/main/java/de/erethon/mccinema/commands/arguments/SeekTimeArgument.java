package de.erethon.mccinema.commands.arguments;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public final class SeekTimeArgument implements CustomArgumentType.Converted<Long, String> {

    private static final Pattern TIME_PATTERN = Pattern.compile("(?:(?:(\\d+):)?(\\d+):)?(\\d+)");
    private static final SimpleCommandExceptionType INVALID_TIME = new SimpleCommandExceptionType(
        MessageComponentSerializer.message().serialize(Component.text("Invalid time. Use [HH:]MM:SS or seconds."))
    );

    @Override
    public @NonNull Long convert(@NonNull String input) throws CommandSyntaxException {
        try {
            long milliseconds = Math.multiplyExact(Long.parseLong(input), 1000L);
            if (milliseconds < 0) {
                throw INVALID_TIME.create();
            }
            return milliseconds;
        } catch (NumberFormatException | ArithmeticException ignored) {
            // Try the colon-separated format below.
        }

        Matcher matcher = TIME_PATTERN.matcher(input);
        if (!matcher.matches()) {
            throw INVALID_TIME.create();
        }

        try {
            long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
            long minutes = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
            long seconds = Long.parseLong(matcher.group(3));
            long milliseconds = Math.multiplyExact(Math.addExact(Math.addExact(Math.multiplyExact(hours, 3600),
                Math.multiplyExact(minutes, 60)), seconds), 1000L);
            if (milliseconds < 0) {
                throw INVALID_TIME.create();
            }
            return milliseconds;
        } catch (NumberFormatException | ArithmeticException ignored) {
            throw INVALID_TIME.create();
        }
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.greedyString();
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context,
                                                                       SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        Stream.of("0:00", "1:00", "5:00", "10:00")
            .filter(time -> time.startsWith(prefix))
            .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
