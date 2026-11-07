package day.alacraft.alamodlist.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * One chat message of the mod, as data: translation keys, plain values and click actions, with no game type in it.
 *
 * <p>The game layer turns it into a component. Keeping it as data has one reason that matters: everything the mod can
 * say to the player in chat is visible here as strings, so a test can read every one of them - and prove that none
 * holds a delete token, which no amount of reading the rendering code can do.
 *
 * @param tone  the colour family of the body: information or a problem
 * @param parts the pieces, left to right
 */
public record ChatMessage(Tone tone, List<Part> parts) {

    /** The colour family of the body. */
    public enum Tone {
        INFO, ERROR
    }

    /** What a piece is. */
    public enum Kind {
        /** A translated sentence: {@code key} with {@code args}. */
        TEXT,
        /** A plain value in a muted colour (a title, an id, a date): {@code value}. */
        NOTE,
        /** An address, shown without its scheme and clickable: {@code value}. */
        LINK,
        /** A bracketed button: label {@code key}, tooltip {@code hoverKey}, then {@code action} on {@code value}. */
        BUTTON,
        /** One space. */
        SPACE
    }

    /** What a button does when clicked. */
    public enum Action {
        OPEN_URL, COPY, RUN_COMMAND
    }

    /**
     * @param kind     what it is
     * @param key      a translation key ({@link Kind#TEXT}, {@link Kind#BUTTON}), else {@code null}
     * @param hoverKey a translation key for a button tooltip, else {@code null}
     * @param action   what a button does, else {@code null}
     * @param value    the text of a note or link, or what a button acts on, else {@code null}
     * @param args     the values a translated sentence takes
     */
    public record Part(Kind kind, String key, String hoverKey, Action action, String value, List<String> args) {

        public Part {
            args = List.copyOf(args);
        }

        public static Part text(String key, String... args) {
            return new Part(Kind.TEXT, key, null, null, null, List.of(args));
        }

        public static Part note(String value) {
            return new Part(Kind.NOTE, null, null, null, value, List.of());
        }

        public static Part link(String url) {
            return new Part(Kind.LINK, null, null, null, url, List.of());
        }

        public static Part button(String labelKey, String hoverKey, Action action, String value) {
            return new Part(Kind.BUTTON, labelKey, hoverKey, action, value, List.of());
        }

        public static Part space() {
            return new Part(Kind.SPACE, null, null, null, null, List.of());
        }
    }

    public ChatMessage {
        parts = List.copyOf(parts);
    }

    public static ChatMessage of(Tone tone, Part... parts) {
        return new ChatMessage(tone, List.of(parts));
    }

    public static ChatMessage info(Part... parts) {
        return of(Tone.INFO, parts);
    }

    public static ChatMessage error(Part... parts) {
        return of(Tone.ERROR, parts);
    }

    /** Every string in the message, keys, values and arguments alike: what a test searches for a secret. */
    public List<String> strings() {
        List<String> all = new ArrayList<>();

        for (Part part : parts) {
            addIfPresent(all, part.key());
            addIfPresent(all, part.hoverKey());
            addIfPresent(all, part.value());
            all.addAll(part.args());
        }

        return all;
    }

    private static void addIfPresent(List<String> into, String text) {
        if (text != null) {
            into.add(text);
        }
    }
}
