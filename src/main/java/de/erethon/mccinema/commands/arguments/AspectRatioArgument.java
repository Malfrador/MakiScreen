package de.erethon.mccinema.commands.arguments;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import de.erethon.mccinema.screen.AspectRatio;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class AspectRatioArgument implements CustomArgumentType.Converted<AspectRatio, String> {

    private static final DynamicCommandExceptionType UNKNOWN_RATIO = new DynamicCommandExceptionType(value ->
        MessageComponentSerializer.message().serialize(Component.text("Unknown aspect ratio: " + value))
    );

    @Override
    public @NonNull AspectRatio convert(@NonNull String input) throws CommandSyntaxException {
        return Arrays.stream(AspectRatio.values())
            .filter(ratio -> ratio.name().equalsIgnoreCase(input))
            .findFirst()
            .orElseThrow(() -> UNKNOWN_RATIO.create(input));
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        Arrays.stream(AspectRatio.values())
            .map(Enum::name)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
