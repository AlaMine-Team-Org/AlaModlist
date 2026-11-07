package day.alacraft.alamodlist.archfixture.registry;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Breaks the ordered-collections rule with a constructor REFERENCE only - no call, no field. A check
 * that looks at calls alone misses it (036 MOD-313).
 */
public final class HashReferenceViolator {

    private HashReferenceViolator() {
    }

    public static Supplier<Set<String>> factory() {
        return HashSet::new;
    }
}
