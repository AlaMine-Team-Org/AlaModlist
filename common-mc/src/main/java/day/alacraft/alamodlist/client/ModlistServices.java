package day.alacraft.alamodlist.client;

import day.alacraft.alamodlist.BuildInfo;
import day.alacraft.alamodlist.api.ModlistApi;
import day.alacraft.alamodlist.config.ClientConfig;
import day.alacraft.alamodlist.flow.LocalLists;
import day.alacraft.alamodlist.flow.ModlistActions;
import day.alacraft.alamodlist.net.HttpTransport;
import day.alacraft.alamodlist.service.SendService;
import day.alacraft.alamodlist.store.SecretStore;
import day.alacraft.alamodlist.store.Tokens;
import day.alacraft.alamodlist.store.UploadHistory;
import net.minecraft.SharedConstants;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Puts together what the actions need: the stores in the mod's data directory, and - only when something is to be
 * sent or deleted - an API client for the address in the settings.
 *
 * <p>The two stores are made once and kept: both are synchronized inside, and two instances over the same files
 * would overwrite each other's writes. They are not world state - the data directory is fixed for the life of the
 * process - so, like the hash cache, they need no hook that clears them when a world is left.
 *
 * <p>The API client is the opposite: made for one action and closed after it. It is cheap, it follows a change of the
 * address in the settings without a restart, and nothing is left running between the player's actions.
 */
public final class ModlistServices {

    private static final Object LOCK = new Object();

    /** Set once, under {@link #LOCK}. */
    private static Stores stores;

    private ModlistServices() {
    }

    private record Stores(Tokens tokens, UploadHistory history) {
    }

    /** A client for one action; closing it lets go of its threads (off the calling thread). */
    public record Network(ModlistActions actions, ModlistApi api) implements AutoCloseable {

        @Override
        public void close() {
            // ModlistApi.close() waits for its threads: never on the game thread, nor on one of its own.
            Thread closer = new Thread(api::close, "AlaModlist close");
            closer.setDaemon(true);
            closer.start();
        }
    }

    /** The commands that need no network. */
    public static LocalLists local() {
        Stores s = stores();

        return new LocalLists(s.tokens(), s.history(), Clock.systemUTC());
    }

    /**
     * Send and delete, against the address in the settings.
     *
     * @throws IllegalArgumentException when {@code apiBaseUrl} is not an http(s) address; nothing has been made
     */
    public static Network network() {
        Stores s = stores();
        ClientPlatform platform = ClientPlatform.get();
        String minecraft = SharedConstants.getCurrentVersion().name();
        ModlistApi api = ModlistApi.builder(ClientConfig.apiBaseUrl)
                .userAgent(HttpTransport.userAgent(BuildInfo.version(), minecraft, platform.loaderName()))
                .accountToken(s.tokens()::accountToken)
                .build();
        Clock clock = Clock.systemUTC();
        ModlistActions actions = new ModlistActions(new SendService(api, s.tokens(), s.history(), clock), s.history(),
                clock);

        return new Network(actions, api);
    }

    private static Stores stores() {
        synchronized (LOCK) {
            if (stores == null) {
                Path directory = ClientPlatform.get().dataDirectory();

                stores = new Stores(new Tokens(new SecretStore(directory.resolve(SecretStore.FILE_NAME))),
                        new UploadHistory(directory.resolve(UploadHistory.FILE_NAME)));
            }

            return stores;
        }
    }
}
