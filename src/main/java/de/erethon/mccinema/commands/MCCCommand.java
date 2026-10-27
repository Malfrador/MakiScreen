package de.erethon.mccinema.commands;

import de.erethon.mccinema.commands.arguments.AspectRatioArgument;
import de.erethon.mccinema.commands.arguments.DownloadRequestArgument;
import de.erethon.mccinema.commands.arguments.PlayRequestArgument;
import de.erethon.mccinema.commands.arguments.ScreenArgument;
import de.erethon.mccinema.commands.arguments.SeekTimeArgument;
import de.erethon.mccinema.screen.AspectRatio;
import de.erethon.mccinema.screen.Screen;
import net.strokkur.commands.Command;
import net.strokkur.commands.Executes;
import net.strokkur.commands.Literal;
import net.strokkur.commands.arguments.IntArg;
import net.strokkur.commands.arguments.StringArg;
import net.strokkur.commands.paper.Description;
import net.strokkur.commands.paper.Executor;
import net.strokkur.commands.paper.arguments.CustomArg;
import net.strokkur.commands.permission.Permission;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.strokkur.commands.arguments.StringArgType.GREEDY;

@Command("mcc")
@Description("Manage MCCinema screens and playback")
public final class MCCCommand {

    private final CropCommand crop = new CropCommand();
    private final DebugCommand debug = new DebugCommand();
    private final DeleteCommand delete = new DeleteCommand();
    private final DownloadCommand download = new DownloadCommand();
    private final HelpCommand help = new HelpCommand();
    private final InfoCommand info = new InfoCommand();
    private final ListCommand list = new ListCommand();
    private final MediaCommand media = new MediaCommand();
    private final PauseCommand pause = new PauseCommand();
    private final PlayCommand play = new PlayCommand();
    private final QualityCommand quality = new QualityCommand();
    private final ReloadCommand reload = new ReloadCommand();
    private final ResumeCommand resume = new ResumeCommand();
    private final ScreenCreateCommand create = new ScreenCreateCommand();
    private final SeekCommand seek = new SeekCommand();
    private final StopCommand stop = new StopCommand();

    @Executes
    @Permission("mccinema.help")
    void root(CommandSender sender) {
        help.onExecute(sender);
    }

    @Executes
    @Permission("mccinema.help")
    void help(CommandSender sender, @Literal({"help", "?"}) String subcommand) {
        help.onExecute(sender);
    }

    @Executes
    @Permission("mccinema.create")
    void create(@Executor Player player, @Literal({"create", "new", "add"}) String subcommand, String name) {
        create.onExecute(player, name);
    }

    @Executes
    @Permission("mccinema.create")
    void createWithAspect(@Executor Player player, @Literal({"create", "new", "add"}) String subcommand,
                          String name, @CustomArg(AspectRatioArgument.class) AspectRatio aspect) {
        create.onExecute(player, name, aspect);
    }

    @Executes
    @Permission("mccinema.create")
    void createWithDisplayAspect(@Executor Player player,
                                 @Literal({"create", "new", "add"}) String subcommand, String name,
                                 @Literal({"16:9", "4:3", "1:1", "2:1"}) String aspect) {
        create.onExecute(player, name, AspectRatio.fromString(aspect));
    }

    @Executes
    @Permission("mccinema.create")
    void createWithSize(@Executor Player player, @Literal({"create", "new", "add"}) String subcommand,
                        String name, @CustomArg(AspectRatioArgument.class) AspectRatio aspect,
                        @IntArg(min = 1, max = 64) int width, @IntArg(min = 1, max = 32) int height) {
        create.onExecute(player, name, aspect, width, height);
    }

    @Executes
    @Permission("mccinema.create")
    void createWithDisplayAspectAndSize(@Executor Player player,
                                        @Literal({"create", "new", "add"}) String subcommand, String name,
                                        @Literal({"16:9", "4:3", "1:1", "2:1"}) String aspect,
                                        @IntArg(min = 1, max = 64) int width,
                                        @IntArg(min = 1, max = 32) int height) {
        create.onExecute(player, name, AspectRatio.fromString(aspect), width, height);
    }

    @Executes
    @Permission("mccinema.delete")
    void delete(CommandSender sender, @Literal({"delete", "remove", "rm"}) String subcommand,
                @CustomArg(ScreenArgument.class) Screen screen) {
        delete.onExecute(sender, screen);
    }

    @Executes
    @Permission("mccinema.list")
    void list(CommandSender sender, @Literal({"list", "ls", "screens"}) String subcommand) {
        list.listScreens(sender);
    }

    @Executes
    @Permission("mccinema.list")
    void listScreensExplicit(CommandSender sender, @Literal({"list", "ls"}) String subcommand,
                             @Literal("screens") String type) {
        list.listScreens(sender);
    }

    @Executes
    @Permission("mccinema.list")
    void listVideos(CommandSender sender, @Literal({"list", "ls", "screens"}) String subcommand,
                    @Literal({"videos", "files"}) String type) {
        list.listVideos(sender);
    }

    @Executes
    @Permission("mccinema.info")
    void info(CommandSender sender, @Literal({"info", "status", "stats"}) String subcommand,
              @CustomArg(ScreenArgument.class) Screen screen) {
        info.onExecute(sender, screen);
    }

    @Executes
    @Permission("mccinema.play")
    void play(CommandSender sender, @Literal({"play", "start"}) String subcommand,
              @CustomArg(ScreenArgument.class) Screen screen,
              @CustomArg(PlayRequestArgument.class) PlayRequest request) {
        play.onExecute(screen, request, sender);
    }

    @Executes
    @Permission("mccinema.control")
    void pause(CommandSender sender, @Literal("pause") String subcommand,
               @CustomArg(ScreenArgument.class) Screen screen) {
        pause.onExecute(sender, screen);
    }

    @Executes
    @Permission("mccinema.control")
    void resume(CommandSender sender, @Literal({"resume", "unpause"}) String subcommand,
                @CustomArg(ScreenArgument.class) Screen screen) {
        resume.onExecute(sender, screen);
    }

    @Executes
    @Permission("mccinema.control")
    void stop(CommandSender sender, @Literal("stop") String subcommand,
              @CustomArg(ScreenArgument.class) Screen screen) {
        stop.onExecute(sender, screen);
    }

    @Executes
    @Permission("mccinema.control")
    void seek(CommandSender sender, @Literal({"seek", "goto", "jump"}) String subcommand,
              @CustomArg(ScreenArgument.class) Screen screen,
              @CustomArg(SeekTimeArgument.class) Long time) {
        seek.onExecute(sender, screen, time);
    }

    @Executes
    @Permission("mccinema.crop")
    void crop(@Executor Player player, @Literal({"crop", "zoom", "viewport"}) String subcommand,
              @CustomArg(ScreenArgument.class) Screen screen) {
        crop.start(player, screen);
    }

    @Executes
    @Permission("mccinema.crop")
    void cropSave(@Executor Player player, @Literal({"crop", "zoom", "viewport"}) String subcommand,
                  @Literal("save") String action) {
        crop.save(player);
    }

    @Executes
    @Permission("mccinema.crop")
    void cropReset(@Executor Player player, @Literal({"crop", "zoom", "viewport"}) String subcommand,
                   @Literal("reset") String action) {
        crop.reset(player);
    }

    @Executes
    @Permission("mccinema.crop")
    void cropCancel(@Executor Player player, @Literal({"crop", "zoom", "viewport"}) String subcommand,
                    @Literal("cancel") String action) {
        crop.cancel(player);
    }

    @Executes
    @Permission("mccinema.download")
    void download(CommandSender sender, @Literal({"download", "dl"}) String subcommand,
                  @CustomArg(DownloadRequestArgument.class) DownloadRequest request) {
        download.onExecute(request, sender);
    }

    @Executes
    @Permission("mccinema.media")
    void media(CommandSender sender, @Literal({"media", "toolchain", "resolver"}) String subcommand) {
        media.show(sender);
    }

    @Executes
    @Permission("mccinema.media")
    void mediaRetry(CommandSender sender, @Literal({"media", "toolchain", "resolver"}) String subcommand,
                    @Literal("retry") String action) {
        media.retry(sender);
    }

    @Executes
    @Permission("mccinema.debug")
    void debug(@Executor Player player, @Literal({"debug", "metrics", "perf"}) String subcommand,
               @CustomArg(ScreenArgument.class) Screen screen) {
        debug.onExecute(CommandInput.arguments(subcommand, screen.getName()), player);
    }

    @Executes
    @Permission("mccinema.debug")
    void debugSetting(@Executor Player player, @Literal({"debug", "metrics", "perf"}) String subcommand,
                      @CustomArg(ScreenArgument.class) Screen screen,
                      @Literal({"show", "status", "temporal", "temp", "error-threshold", "threshold", "et",
                          "temporal-threshold", "tt", "error-diffusion", "diffusion", "ed", "mode",
                          "dither-mode", "dm", "patch-mode", "patch", "pm", "max-patches", "patch-limit",
                          "mpl", "patch-block-size", "pbs", "min-patch-area", "mpa", "full-update-threshold",
                          "fut"}) String setting) {
        debug.onExecute(CommandInput.arguments(subcommand, screen.getName(), setting), player);
    }

    @Executes
    @Permission("mccinema.debug")
    void debugValue(@Executor Player player, @Literal({"debug", "metrics", "perf"}) String subcommand,
                    @CustomArg(ScreenArgument.class) Screen screen,
                    @Literal({"temporal", "temp", "error-threshold", "threshold", "et", "temporal-threshold",
                        "tt", "error-diffusion", "diffusion", "ed", "mode", "dither-mode", "dm",
                        "patch-mode", "patch", "pm", "max-patches", "patch-limit", "mpl", "patch-block-size",
                        "pbs", "min-patch-area", "mpa", "full-update-threshold", "fut"}) String setting,
                    @StringArg(GREEDY) String value) {
        debug.onExecute(CommandInput.arguments(subcommand, screen.getName(), setting, value), player);
    }

    @Executes
    @Permission("mccinema.debug")
    void quality(CommandSender sender, @Literal({"quality", "preset", "q"}) String subcommand,
                 @CustomArg(ScreenArgument.class) Screen screen) {
        quality.show(sender, screen);
    }

    @Executes
    @Permission("mccinema.debug")
    void qualityShow(CommandSender sender, @Literal({"quality", "preset", "q"}) String subcommand,
                     @CustomArg(ScreenArgument.class) Screen screen,
                     @Literal({"show", "status"}) String action) {
        quality.show(sender, screen);
    }

    @Executes
    @Permission("mccinema.debug")
    void qualityHigh(CommandSender sender, @Literal({"quality", "preset", "q"}) String subcommand,
                     @CustomArg(ScreenArgument.class) Screen screen,
                     @Literal({"high", "quality"}) String preset) {
        quality.apply(sender, screen, QualityCommand.QualityPreset.QUALITY);
    }

    @Executes
    @Permission("mccinema.debug")
    void qualityBalanced(CommandSender sender, @Literal({"quality", "preset", "q"}) String subcommand,
                         @CustomArg(ScreenArgument.class) Screen screen,
                         @Literal("balanced") String preset) {
        quality.apply(sender, screen, QualityCommand.QualityPreset.BALANCED);
    }

    @Executes
    @Permission("mccinema.debug")
    void qualityPerformance(CommandSender sender, @Literal({"quality", "preset", "q"}) String subcommand,
                            @CustomArg(ScreenArgument.class) Screen screen,
                            @Literal("performance") String preset) {
        quality.apply(sender, screen, QualityCommand.QualityPreset.PERFORMANCE);
    }

    @Executes
    @Permission("mccinema.reload")
    void reload(CommandSender sender, @Literal("reload") String subcommand) {
        reload.onExecute(sender);
    }
}
