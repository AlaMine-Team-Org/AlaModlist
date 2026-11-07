package day.alacraft.alamodlist.archfixture.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The clean counterpart: ordered collections inside the same zone must not be flagged. */
public final class OrderedUser {

    private OrderedUser() {
    }

    public static Set<String> names(List<String> source) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(new ArrayList<>(source)));
    }

    public static List<String> fixed() {
        return List.of("first", "second");
    }
}
