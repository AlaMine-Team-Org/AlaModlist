package day.alacraft.alamodlist.archfixture.registry;

import java.util.Set;

/** Breaks the ordered-collections rule with a CALL: Set.of iterates in an order salted per JVM run. */
public final class SaltedCallViolator {

    private SaltedCallViolator() {
    }

    public static Set<String> names() {
        return Set.of("first", "second");
    }
}
