package day.alacraft.alamodlist.fabric.gametest.visual;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.api.ApiErrorCode;
import day.alacraft.alamodlist.assemble.EnvironmentAssembler;
import day.alacraft.alamodlist.client.AlaModlistClient;
import day.alacraft.alamodlist.client.ClientMixinProbe;
import day.alacraft.alamodlist.client.ModKeyMappings;
import day.alacraft.alamodlist.client.PreviewSession;
import day.alacraft.alamodlist.client.command.ClientCommandTree;
import day.alacraft.alamodlist.client.screen.KnobConfigScreen;
import day.alacraft.alamodlist.client.screen.PreviewScreen;
import day.alacraft.alamodlist.flow.Category;
import day.alacraft.alamodlist.flow.GateReport;
import day.alacraft.alamodlist.flow.PreviewModel;
import day.alacraft.alamodlist.hash.Progress;
import day.alacraft.alamodlist.model.Hashes;
import day.alacraft.alamodlist.model.ModEntry;
import day.alacraft.alamodlist.model.Snapshot;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The stands of a client-only mod: what the mod does on the client, shown to the lane one at a time.
 * Each one is called from {@link Stands#run}; none builds a world of its own (the lane builds one, and a
 * client process does not survive many - see {@link AlaModlistClientGameTest}).
 *
 * <p>The preview stands come in two kinds. {@link #openKey} and {@link #commandAndAlias} go through the real road a
 * player takes and wait for the real collection. The others open the screen over a model built here, because the states
 * they photograph (still reading, stopped by the privacy check, a list of six hundred) are ones a real run passes
 * through too quickly, or never reaches in a test world.
 */
final class ClientStands {

    private static final String BASE_SNAPSHOT_SOURCE = "alamodlist/stand";

    private ClientStands() {
    }

    /**
     * The shared mixin config reached this loader: the client mixin ran on a client tick.
     *
     * @implements TC-CLIENT-001-BOOT02 - the shared client mixin is applied on the running loader
     * @covers R-BOOT-02
     */
    static void mixinApplied(ClientGameTestContext context) {
        context.waitTicks(1);
        if (!ClientMixinProbe.ticked()) {
            throw new AssertionError("[CLIENT] the client mixin never ran: the shared mixin config is not applied on "
                    + "this loader (it is listed in fabric.mod.json, and under `client` in the mixin config)");
        }
    }

    /**
     * The settings screen, opened the way Mod Menu and the NeoForge mod list open it, and closed again.
     *
     * @implements TC-CLIENT-001-GUI01 - the settings screen opens and is drawn
     * @covers R-GUI-02 R-VIS-01
     */
    static void configScreen(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.setScreenAndShow(AlaModlistClient.configScreen(null)));
        context.waitFor(mc -> mc.gui.screen() instanceof KnobConfigScreen);
        context.waitTicks(3);
        ShotRecorder.captureScreen(KnobConfigScreen.class, "open");
        close(context);
    }

    /**
     * The mod's key, at its default (F8, unbound by nothing: the lane starts from a clean options file), opens the
     * preview, the real collection runs to the end behind it, and the screen is drawn with its categories and lines.
     *
     * @implements TC-CLIENT-001-KEY01 - the mod's key, at its default, opens the preview and the collection finishes
     * @covers R-CLI-01 R-GUI-02 R-VIS-01
     */
    static void openKey(ClientGameTestContext context) {
        context.getInput().pressKey(ModKeyMappings.OPEN);
        awaitPreview(context, "the key is not polled, or the preview does not open");
        awaitReady(context);
        ShotRecorder.captureScreen(PreviewScreen.class, "ready");
        close(context);
    }

    /**
     * Both names of the command are registered in the live dispatcher - the alias as a redirect to the root, with its
     * own action - and both, run as a player runs them, open the preview.
     *
     * @implements TC-CLIENT-001-CLI02 - /alamodlist and /aml are registered and open the preview
     * @covers R-CLI-02
     */
    static void commandAndAlias(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            CommandDispatcher<FabricClientCommandSource> dispatcher = ClientCommands.getActiveDispatcher();
            CommandNode<FabricClientCommandSource> root = dispatcher.getRoot().getChild("alamodlist");
            CommandNode<FabricClientCommandSource> alias = dispatcher.getRoot().getChild(ClientCommandTree.ALIAS);
            if (root == null || alias == null) {
                throw new AssertionError("[CLIENT] the command is not registered under both names: /alamodlist="
                        + (root != null) + ", /" + ClientCommandTree.ALIAS + "=" + (alias != null));
            }
            if (root.getCommand() == null || alias.getCommand() == null) {
                throw new AssertionError("[CLIENT] a name of the command has no action of its own");
            }
            if (alias.getRedirect() != root) {
                throw new AssertionError("[CLIENT] the alias is not a redirect to the root: a subcommand added to "
                        + "the root would not be there under the short name");
            }
            for (String sub : new String[] {"list", "delete"}) {
                if (root.getChild(sub) == null) {
                    throw new AssertionError("[CLIENT] /alamodlist has no `" + sub + "` subcommand");
                }
            }
        });

        sendCommand(context, "alamodlist");
        awaitPreview(context, "/alamodlist did not open the preview");
        close(context);

        sendCommand(context, ClientCommandTree.ALIAS);
        awaitPreview(context, "/" + ClientCommandTree.ALIAS + " did not open the preview");
        close(context);
    }

    /**
     * Still reading the jars: the bar and the line above it, Send off, the second button saying Cancel.
     *
     * @implements TC-PREVIEW-001-GUI02 - the preview while the snapshot is being built
     * @covers R-GUI-02 R-VIS-01
     */
    static void previewHashing(ClientGameTestContext context) {
        Progress progress = new Progress();
        progress.stage(Progress.Stage.HASHING);
        progress.total(214, 3_300_000_000L);
        PreviewModel model = new PreviewModel(progress);

        show(context, model, Category.MODS);
        ShotRecorder.captureScreen(PreviewScreen.class, "hashing");
        close(context);
    }

    /**
     * Six hundred mods: the list is there, with its scrollbar, and the categories that cannot be collected yet stand
     * switched off beside it with their reasons.
     *
     * @implements TC-PREVIEW-001-GUI03 - the preview with a list of six hundred
     * @covers R-GUI-02 R-VIS-01
     */
    static void previewLongList(ClientGameTestContext context) {
        show(context, readyModel(600), Category.MODS);
        ShotRecorder.captureScreen(PreviewScreen.class, "long");
        close(context);
    }

    /**
     * A category the mod cannot collect yet is chosen: its name and the reason are shown instead of an empty list.
     *
     * @implements TC-PREVIEW-001-GUI04 - an unavailable category shows its reason
     * @covers R-GUI-02 R-VIS-01
     */
    static void previewUnavailable(ClientGameTestContext context) {
        show(context, readyModel(12), Category.DATA_PACKS);
        ShotRecorder.captureScreen(PreviewScreen.class, "unavailable");
        close(context);
    }

    /**
     * The privacy check stopped the list: what was found and where, in words, and Send still there for another try.
     *
     * @implements TC-PREVIEW-001-GUI05 - the preview after the privacy check stopped the list
     * @covers R-GUI-02 R-VIS-01
     */
    static void previewBlocked(ClientGameTestContext context) {
        PreviewModel model = readyModel(12);
        model.blocked(new GateReport(List.of(
                new GateReport.Line("screen.alamodlist.gate.found.player_name", "title")), 0));

        show(context, model, Category.MODS);
        ShotRecorder.captureScreen(PreviewScreen.class, "blocked");
        close(context);
    }

    /**
     * The request is out, and then the site said to wait: both states of the footer, with the buttons as they should be.
     *
     * @implements TC-PREVIEW-001-GUI06 - the preview while sending and after a refusal
     * @covers R-GUI-02 R-VIS-01
     */
    static void previewSendStates(ClientGameTestContext context) {
        PreviewModel model = readyModel(12);
        model.sending();
        show(context, model, Category.MODS);
        ShotRecorder.captureScreen(PreviewScreen.class, "sending");

        model.sendFailed(new ApiError(429, ApiErrorCode.RATE_LIMITED, "rate_limited", "",
                Optional.of(Duration.ofMinutes(3))));
        context.waitTicks(3);
        ShotRecorder.captureScreen(PreviewScreen.class, "failed");
        close(context);
    }

    // ---- helpers ----

    /** A model whose snapshot is built, with {@code mods} mods of which every tenth is a library. */
    private static PreviewModel readyModel(int mods) {
        List<ModEntry> entries = new ArrayList<>();
        for (int i = 0; i < mods; i++) {
            entries.add(new ModEntry("mod" + i, "Example mod number " + i, "1." + i, i % 10 == 9 ? "library" : "mod",
                    null, "universal", "example-" + i + ".jar", 1000L + i, new Hashes("a".repeat(40), null), null,
                    List.of()));
        }
        PreviewModel model = new PreviewModel(new Progress());
        model.collected(new Snapshot(BASE_SNAPSHOT_SOURCE, null, null, null,
                EnvironmentAssembler.create("26.3", 777, "fabric", "0.19.5"), entries, List.of(), List.of(),
                List.of(), null, null, List.of()));

        return model;
    }

    private static void show(ClientGameTestContext context, PreviewModel model, Category first) {
        context.runOnClient(mc -> mc.setScreenAndShow(new PreviewScreen(PreviewSession.forModel(mc, model), first)));
        awaitPreview(context, "the preview did not open over a model");
        context.waitTicks(3);
    }

    private static void awaitPreview(ClientGameTestContext context, String hint) {
        try {
            context.waitFor(mc -> mc.gui.screen() instanceof PreviewScreen);
        } catch (RuntimeException e) {
            throw new AssertionError("[CLIENT] " + hint, e);
        }
    }

    /** The real collection ends: the model leaves the collecting phase and is ready to send. */
    private static void awaitReady(ClientGameTestContext context) {
        context.waitFor(mc -> mc.gui.screen() instanceof PreviewScreen preview
                && preview.model().phase() == PreviewModel.Phase.READY);
        context.waitTicks(3);
    }

    private static void close(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.setScreenAndShow(null));
        context.waitFor(mc -> mc.gui.screen() == null);
    }

    /** Runs a client command the way a player's chat line does, and lets a few ticks pass. */
    private static void sendCommand(ClientGameTestContext context, String name) {
        context.runOnClient(mc -> mc.getConnection().sendCommand(name));
        context.waitTicks(2);
    }
}
