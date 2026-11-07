package day.alacraft.alamodlist.util;

import java.util.Objects;

/**
 * A set-once slot for something a loader provides to the shared code.
 *
 * <p>One policy for every seam: installed exactly once, by the loader entrypoint, before anything
 * reads it - and a read before that fails loudly, naming the seam. The alternative policies a mod
 * accumulates otherwise (return an empty default, return null, throw only sometimes) each hide a
 * different wiring mistake until a player finds it.
 */
public final class Seam<T> {

    private final String name;
    private volatile T value;

    public Seam(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** Installs the implementation. A second install is a wiring mistake and throws. */
    public void install(T implementation) {
        Objects.requireNonNull(implementation, name);
        synchronized (this) {
            if (value != null) {
                throw new IllegalStateException(name + " is already installed (" + value.getClass().getName()
                        + "); a loader entrypoint installs it exactly once");
            }
            value = implementation;
        }
    }

    /** The implementation, or an exception that names the seam and who was supposed to install it. */
    public T get() {
        T current = value;
        if (current == null) {
            throw new IllegalStateException(name + " is not installed yet: the loader entrypoint must install it"
                    + " as its first step, before any shared code runs");
        }
        return current;
    }

    public boolean isInstalled() {
        return value != null;
    }

    @Override
    public String toString() {
        return "Seam[" + name + (value == null ? ", not installed]" : ", " + value.getClass().getSimpleName() + "]");
    }
}
