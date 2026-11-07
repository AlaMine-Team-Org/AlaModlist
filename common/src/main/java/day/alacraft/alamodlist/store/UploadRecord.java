package day.alacraft.alamodlist.store;

import java.time.Instant;
import java.util.Objects;

/**
 * One list the player made, remembered so it can be found and deleted later.
 *
 * <p>There is deliberately no token here: a record is shown on screens and may be logged, and the delete token is a
 * secret that lives in {@link Tokens} under the same id. The address and the name of the server the player
 * was on are not kept either - the service never gets them, and neither does the disk.
 *
 * @param id            the public id of the list, also the last part of {@code url}
 * @param url           the page of the list as it was handed to the player
 * @param createdAt     when the list was made on this machine (the clock of the machine, not the service's)
 * @param title         the title of the list; may be empty
 * @param mods          how many mods the list holds
 * @param resourcePacks how many resource packs
 * @param shaderPacks   how many shader packs
 * @param dataPacks     how many data packs
 * @param plugins       how many plugins
 */
public record UploadRecord(String id, String url, Instant createdAt, String title, int mods, int resourcePacks,
                           int shaderPacks, int dataPacks, int plugins) {

    public UploadRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");

        if (id.isBlank()) {
            throw new IllegalArgumentException("A record needs an id");
        }

        url = url == null ? "" : url;
        title = title == null ? "" : title;
    }
}
