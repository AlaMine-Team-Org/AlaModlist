package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * One network channel of the connection.
 *
 * @param phase  {@code configuration} or {@code play}
 * @param source how the channel was found: {@code negotiated}, {@code adhoc}, {@code common} or {@code sendable}
 */
public record ChannelEntry(String id, String version, String phase, String source) {

    public ChannelEntry {
        id = WireSchema.clean(ChannelEntry.class, "id", id);
        version = WireSchema.clean(ChannelEntry.class, "version", version);
        phase = WireSchema.clean(ChannelEntry.class, "phase", phase);
        source = WireSchema.clean(ChannelEntry.class, "source", source);
    }
}
