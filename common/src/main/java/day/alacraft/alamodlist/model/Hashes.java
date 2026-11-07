package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

/**
 * The hashes of a file. SHA-1 is what the service matches on; {@code sha512} is not sent (the schema refuses it).
 *
 * @param sha1    40 hexadecimal digits
 * @param murmur2 the 32-bit CurseForge fingerprint, only when it was computed
 */
public record Hashes(String sha1, Long murmur2) {

    public Hashes {
        sha1 = WireSchema.clean(Hashes.class, "sha1", sha1);
        murmur2 = WireSchema.clean(Hashes.class, "murmur2", murmur2);
    }
}
