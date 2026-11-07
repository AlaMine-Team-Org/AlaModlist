package day.alacraft.alamodlist.client;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.BuildInfo;
import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.api.ApiErrorCode;
import day.alacraft.alamodlist.assemble.EnvironmentAssembler;
import day.alacraft.alamodlist.client.collect.KnownValuesSampler;
import day.alacraft.alamodlist.config.ClientConfig;
import day.alacraft.alamodlist.flow.ChatLines;
import day.alacraft.alamodlist.flow.CollectJob;
import day.alacraft.alamodlist.flow.ErrorText;
import day.alacraft.alamodlist.flow.ModlistActions;
import day.alacraft.alamodlist.flow.PreviewModel;
import day.alacraft.alamodlist.flow.SiteLocale;
import day.alacraft.alamodlist.flow.SnapshotPipeline;
import day.alacraft.alamodlist.model.EnvironmentInfo;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.source.ModSource;
import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.nio.file.Path;
import java.util.List;

/**
 * One visit to the preview: the model the screen draws, the background job that fills it, and the send.
 *
 * <p>It is created on the game thread when the player asks, with everything the game had to be asked already read, and
 * every method here is called on the game thread - the screen's tick and its buttons. The two kinds of background work
 * (collecting, sending) report by flags the tick looks at and by {@link Minecraft#execute}, never by touching the
 * screen from their own thread.
 *
 * <p>Closing the screen {@link #close() closes the session}: the collection stops. A send that is already out is
 * not stopped (it cannot be, and the delete token must reach the disk): its answer goes to chat instead.
 */
public final class PreviewSession {

    private static final String CACHE_FILE = "hash-cache.json";

    private final Minecraft minecraft;
    private final ClientPlatform platform;
    private final PreviewModel model;
    private final EnvironmentInfo environment;
    private final ModSource source;
    private final Path cacheFile;
    private final boolean murmur2;
    private CollectJob job;
    private boolean open = true;

    private PreviewSession(Minecraft minecraft, ClientPlatform platform, EnvironmentInfo environment, ModSource source,
                           Path cacheFile, boolean murmur2) {
        this.minecraft = minecraft;
        this.platform = platform;
        this.environment = environment;
        this.source = source;
        this.cacheFile = cacheFile;
        this.murmur2 = murmur2;
        this.job = startJob();
        this.model = new PreviewModel(job.progress());
    }

    /** A session around a model the caller built: it starts nothing. */
    private PreviewSession(Minecraft minecraft, PreviewModel model) {
        this.minecraft = minecraft;
        this.platform = null;
        this.environment = null;
        this.source = null;
        this.cacheFile = null;
        this.murmur2 = false;
        this.job = null;
        this.model = model;
    }

    /** Reads what the game must be asked and starts collecting. Game thread. */
    public static PreviewSession start(Minecraft minecraft) {
        ClientPlatform platform = ClientPlatform.get();
        WorldVersion version = SharedConstants.getCurrentVersion();
        EnvironmentInfo environment = EnvironmentAssembler.create(version.name(), version.protocolVersion(),
                platform.loaderName(), platform.loaderVersion());

        return new PreviewSession(minecraft, platform, environment, platform.modSource(),
                platform.dataDirectory().resolve(CACHE_FILE), ClientConfig.computeMurmur2);
    }

    /**
     * A session over a model built by the caller, for the visual stands: they need the screen in states a real
     * collection passes through too quickly to be photographed (still reading, stopped by the privacy check, a long
     * list). It starts no job and reads nothing from the game; Send, Refresh and Cancel do nothing in it except change
     * the model.
     */
    public static PreviewSession forModel(Minecraft minecraft, PreviewModel model) {
        return new PreviewSession(minecraft, model);
    }

    public PreviewModel model() {
        return model;
    }

    private CollectJob startJob() {
        return CollectJob.start("alamodlist/" + BuildInfo.version(), environment, source, cacheFile, murmur2);
    }

    // ---- the screen's tick and buttons ----

    /** Looks at the background job; the screen calls it every tick. */
    public void tick() {
        if (job != null && model.phase() == PreviewModel.Phase.COLLECTING && job.isDone()) {
            SnapshotPipeline.Result result = job.resultOrNull();

            if (result != null) {
                model.collected(result.snapshot(), result.hashing().failed() + result.hashing().timedOut());
            } else {
                model.collectFailed(job.wasCancelled());
            }
        }
    }

    /** The second button while collecting. */
    public void cancelCollecting() {
        if (model.phase() == PreviewModel.Phase.COLLECTING) {
            if (job != null) {
                job.cancel();
            }
            model.collectFailed(true);
        }
    }

    /** The second button otherwise: collect again (the hash cache makes it quick). */
    public void refresh() {
        if (job != null && model.buttons().slotLive() && model.buttons().slot() == PreviewModel.Slot.REFRESH) {
            job = startJob();
            model.restart(job.progress());
        }
    }

    /** The screen is gone. */
    public void close() {
        open = false;
        if (job != null && !job.isDone()) {
            job.cancel();
        }
    }

    /**
     * Send. The final bytes are made here, the values the privacy gate must not find are read now, and everything
     * slow happens on a thread of its own.
     */
    public void send() {
        if (!model.buttons().send() || platform == null) {
            return;
        }

        PreviewModel.Prepared prepared;
        KnownValues known;
        ModlistServices.Network network;

        try {
            prepared = model.prepare(SiteLocale.of(minecraft.getLanguageManager().getSelected()));
            known = KnownValuesSampler.sample(minecraft, platform);
        } catch (RuntimeException e) {
            AlaModlist.LOGGER.warn("Could not prepare the request", e);
            model.sendFailed(ApiError.client(ApiErrorCode.INTERNAL, "The request could not be prepared."));
            return;
        }

        try {
            network = ModlistServices.network();
        } catch (IllegalArgumentException e) {
            model.rejected(ErrorText.BAD_ADDRESS);
            return;
        }

        model.sending();
        Thread worker = new Thread(() -> {
            ModlistActions.Outcome outcome;

            try {
                outcome = network.actions().send(prepared, known).join();
            } catch (RuntimeException e) {
                AlaModlist.LOGGER.warn("The send ended in a way it should not", e);
                ApiError error = ApiError.client(ApiErrorCode.INTERNAL, "The send failed inside the mod.");
                outcome = new ModlistActions.Outcome(ModlistActions.Kind.FAILED,
                        List.of(ChatLines.error(error, ErrorText.Action.SEND)), error, null);
            } finally {
                network.close();
            }

            ModlistActions.Outcome result = outcome;
            minecraft.execute(() -> finished(result));
        }, "AlaModlist send");
        worker.setDaemon(true);
        worker.start();
    }

    /** The answer of a send, back on the game thread. */
    private void finished(ModlistActions.Outcome outcome) {
        LocalPlayer player = minecraft.player;

        switch (outcome.kind()) {
            case SENT -> {
                model.sent();
                // The link goes to chat whether or not the screen is still open: it is what the player asked for.
                if (player != null) {
                    ChatRenderer.say(player, outcome.chat());
                }
                if (open) {
                    minecraft.gui.setScreen(null);
                }
            }
            case BLOCKED -> {
                model.blocked(outcome.gate());
                if (!open && player != null) {
                    ChatRenderer.say(player, List.of(ChatLines.error(
                            ApiError.client(ApiErrorCode.GATE_BLOCKED, "The privacy check stopped the list."),
                            ErrorText.Action.SEND)));
                }
            }
            case FAILED -> {
                model.sendFailed(outcome.error());
                if (!open && player != null) {
                    ChatRenderer.say(player, outcome.chat());
                }
            }
        }
    }
}
