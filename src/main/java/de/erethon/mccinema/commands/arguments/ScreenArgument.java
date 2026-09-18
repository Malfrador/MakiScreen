package de.erethon.mccinema.commands.arguments;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import de.erethon.mccinema.MCCinema;
import de.erethon.mccinema.screen.Screen;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.NonNull;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class ScreenArgument implements CustomArgumentType.Converted<Screen, String> {

    private static final DynamicCommandExceptionType UNKNOWN_SCREEN = new DynamicCommandExceptionType(name ->
        MessageComponentSerializer.message().serialize(Component.text("Screen '" + name + "' not found."))
    );

    @Override
    public @NonNull Screen convert(@NonNull String input) throws CommandSyntaxException {
        return MCCinema.getInstance().getScreenManager().getScreen(input)
            .orElseThrow(() -> UNKNOWN_SCREEN.create(input));
    }

    @Override
    public @NonNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public <S> @NonNull CompletableFuture<Suggestions> listSuggestions(@NonNull CommandContext<S> context, SuggestionsBuilder builder) {
        String prefix = builder.getRemainingLowerCase();
        MCCinema.getInstance().getScreenManager().getAllScreens().stream()
            .map(Screen::getName)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
