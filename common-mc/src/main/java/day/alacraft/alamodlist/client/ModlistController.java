package day.alacraft.alamodlist.client;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.client.screen.PreviewScreen;
import day.alacraft.alamodlist.config.ClientConfig;
import day.alacraft.alamodlist.flow.ChatLines;
import day.alacraft.alamodlist.flow.ChatMessage;
import day.alacraft.alamodlist.flow.ErrorText;
import day.alacraft.alamodlist.api.ApiException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the player's action does: the key, the command and its alias open the preview; the subcommands list the
 * player's lists and delete one.
 *
 * <p>The mod acts on an explicit action of the player only: no tick, event or start-up code opens the preview or
 * touches the network. The tick below only looks at the key and at a request the player made a moment ago.
 */
public final class ModlistController {

    /**
     * Set by the key and by the command, taken by the next client tick. Opening at once from a command would lose:
     * the chat screen that ran the command closes itself right after, and closes the screen the command opened.
     * Cleared when taken, so it needs no hook of its own.
     */
    private static final AtomicBoolean OPEN_REQUESTED = new AtomicBoolean();

    private ModlistController() {
    }

    /** Called every client tick: opens the preview on a press of the mod's key or a request from the command. */
    public static void handleKeys(Minecraft minecraft) {
        while (ModKeyMappings.OPEN.consumeClick()) {
            OPEN_REQUESTED.set(true);
        }
        if (OPEN_REQUESTED.getAndSet(false)) {
            showPreview(minecraft);
        }
    }

    /**
     * The player asked for the preview: by {@code /alamodlist} or {@code /aml}. The key does the same through the tick.
     *
     * @return 1, Brigadier's convention for "done"
     */
    public static int open(Minecraft minecraft) {
        OPEN_REQUESTED.set(true);

        return 1;
    }

    private static void showPreview(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.gui.screen() instanceof PreviewScreen) {
            return;
        }

        try {
            minecraft.gui.setScreen(new PreviewScreen(PreviewSession.start(minecraft)));
        } catch (RuntimeException e) {
            AlaModlist.LOGGER.warn("Could not open the preview", e);
            tell(minecraft.player, Component.translatable("message.alamodlist.failed"));
        }
    }

    /** {@code /alamodlist list}. */
    public static int list(Minecraft minecraft) {
        say(minecraft, ModlistServices.local().list());

        return 1;
    }

    /** {@code /alamodlist delete <id>}: only asks. */
    public static int askDelete(Minecraft minecraft, String id) {
        say(minecraft, List.of(ModlistServices.local().askDelete(id)));

        return 1;
    }

    /** {@code /alamodlist delete <id> cancel}. */
    public static int cancelDelete(Minecraft minecraft) {
        say(minecraft, List.of(ModlistServices.local().cancelDelete()));

        return 1;
    }

    /** {@code /alamodlist delete <id> confirm}: the one that deletes. */
    public static int confirmDelete(Minecraft minecraft, String id) {
        ModlistServices.Network network;

        try {
            network = ModlistServices.network();
        } catch (IllegalArgumentException e) {
            say(minecraft, List.of(ChatMessage.error(ChatMessage.Part.text(ErrorText.BAD_ADDRESS))));

            return 1;
        }

        network.actions().confirmDelete(id).whenComplete((messages, failure) -> {
            network.close();
            List<ChatMessage> answer = failure == null ? messages
                    : List.of(ChatLines.error(ApiException.of(failure).error(), ErrorText.Action.DELETE));

            minecraft.execute(() -> say(minecraft, answer));
        });

        return 1;
    }

    /** A short answer: above the hotbar, or in chat when the player switched that off. */
    private static void tell(LocalPlayer player, Component message) {
        if (ClientConfig.answerInActionBar) {
            player.sendOverlayMessage(message);
        } else {
            player.sendSystemMessage(message);
        }
    }

    private static void say(Minecraft minecraft, List<ChatMessage> messages) {
        LocalPlayer player = minecraft.player;

        if (player != null) {
            ChatRenderer.say(player, messages);
        }
    }
}
