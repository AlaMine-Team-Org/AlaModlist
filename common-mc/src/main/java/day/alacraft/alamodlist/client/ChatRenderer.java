package day.alacraft.alamodlist.client;

import day.alacraft.alamodlist.flow.ChatMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.net.URI;
import java.util.List;

/**
 * Turns the mod's {@link ChatMessage}s into chat components, and says them.
 *
 * <p>Everything is {@code translatable}: this is a client mod, whoever reads the line has the mod, so the game resolves
 * the key against the mod's language files (and the player's resource packs get a say too).
 *
 * <p>Built on an empty, unstyled parent on purpose: a component's children inherit its style, so appending to a
 * coloured prefix would paint the whole line the prefix's colour.
 */
public final class ChatRenderer {

    private static final ChatFormatting BRAND = ChatFormatting.GREEN;
    private static final ChatFormatting BODY = ChatFormatting.GRAY;
    private static final ChatFormatting PROBLEM = ChatFormatting.RED;
    private static final ChatFormatting NOTE = ChatFormatting.WHITE;
    private static final ChatFormatting LINK = ChatFormatting.AQUA;
    private static final ChatFormatting BUTTON = ChatFormatting.DARK_AQUA;

    private ChatRenderer() {
    }

    /** The messages, one chat line each, in chat (not above the hotbar: a link must be clickable and stay). */
    public static void say(LocalPlayer player, List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            player.sendSystemMessage(render(message));
        }
    }

    public static MutableComponent render(ChatMessage message) {
        ChatFormatting body = message.tone() == ChatMessage.Tone.ERROR ? PROBLEM : BODY;
        MutableComponent line = Component.empty()
                .append(Component.translatable("message.alamodlist.prefix").withStyle(BRAND))
                .append(Component.literal(" "));

        for (ChatMessage.Part part : message.parts()) {
            line.append(render(part, body));
        }

        return line;
    }

    private static MutableComponent render(ChatMessage.Part part, ChatFormatting body) {
        return switch (part.kind()) {
            case TEXT -> Component.translatable(part.key(), part.args().toArray()).withStyle(body);
            case NOTE -> Component.literal(part.value()).withStyle(NOTE);
            case LINK -> link(part.value());
            case BUTTON -> button(part);
            case SPACE -> Component.literal(" ");
        };
    }

    /** An address as a clickable link, shown without its scheme: it is read aloud and typed on phones. */
    private static MutableComponent link(String url) {
        String shown = url.replaceFirst("^https?://", "");

        try {
            URI uri = URI.create(url);

            return Component.literal(shown).withStyle(style -> style
                    .withColor(LINK)
                    .withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(uri))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
        } catch (IllegalArgumentException e) {
            return Component.literal(shown).withStyle(NOTE);
        }
    }

    private static MutableComponent button(ChatMessage.Part part) {
        MutableComponent label = Component.literal("[").append(Component.translatable(part.key())).append("]");

        return label.withStyle((Style style) -> style
                .withColor(BUTTON)
                .withClickEvent(click(part))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable(part.hoverKey()))));
    }

    private static ClickEvent click(ChatMessage.Part part) {
        return switch (part.action()) {
            case OPEN_URL -> new ClickEvent.OpenUrl(URI.create(part.value()));
            case COPY -> new ClickEvent.CopyToClipboard(part.value());
            case RUN_COMMAND -> new ClickEvent.RunCommand(part.value());
        };
    }
}
