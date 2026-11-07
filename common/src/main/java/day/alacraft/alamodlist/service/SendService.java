package day.alacraft.alamodlist.service;

import day.alacraft.alamodlist.AlaModlist;
import day.alacraft.alamodlist.api.ApiError;
import day.alacraft.alamodlist.api.ApiErrorCode;
import day.alacraft.alamodlist.api.ApiException;
import day.alacraft.alamodlist.api.CreateResult;
import day.alacraft.alamodlist.api.Limits;
import day.alacraft.alamodlist.api.ModlistApi;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.store.Tokens;
import day.alacraft.alamodlist.store.UploadHistory;
import day.alacraft.alamodlist.store.UploadRecord;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The order of things around a create and a delete, in one place: check the limits, send, <em>write down the
 * delete token and the history entry</em>, and only then report to the player; on a delete, forget the record only
 * after the service has answered.
 *
 * <p>The order is the point. A token not written down before the player is told "done" is a list that can never be
 * taken back; a record erased before the service confirmed is a list the player can no longer find while it is
 * still public.
 *
 * <p>This class decides what is stored and what is returned, never what is shown: it returns {@link Sent} and
 * {@link Deletion}, which hold no delete token, so nothing built from them can put one in chat or a log.
 */
public final class SendService {

    /** What the player needs after a create. The delete token is not here: it is in {@link Tokens}. */
    public record Sent(String id, String url, CreateResult.Counts counts, boolean truncated, Optional<Instant> expires,
                       boolean deletable, boolean tokenOnDisk) {
    }

    /** How a delete ended, when it did not fail. */
    public enum Deletion {
        /** The service deleted the list. */
        DELETED,
        /** The service said it is not there (expired or taken down): the wanted state, and the record is gone too. */
        ALREADY_GONE
    }

    private final ModlistApi api;
    private final Tokens tokens;
    private final UploadHistory history;
    private final Clock clock;

    public SendService(ModlistApi api, Tokens tokens, UploadHistory history, Clock clock) {
        this.api = api;
        this.tokens = tokens;
        this.history = history;
        this.clock = clock;
    }

    /**
     * Sends a list.
     *
     * <p>Fails with {@link ApiErrorCode#TOO_LARGE} or {@link ApiErrorCode#TOO_MANY_ENTRIES} <em>without sending</em>
     * when the body exceeds what the service says it accepts (if the limits cannot be read, the published ones are
     * used). Everything else is the answer of {@link ModlistApi#create}, including a refusal by the privacy gate.
     *
     * @param body        the exact bytes of the request body
     * @param known       the values the privacy gate looks for
     * @param largestList the length of the longest list in the request, for the entry limit
     * @param title       the title of the list, for the history
     */
    public CompletableFuture<Sent> send(byte[] body, KnownValues known, int largestList, String title) {
        return api.limits()
                .exceptionally(failure -> {
                    AlaModlist.LOGGER.debug("Could not read the limits ({}); using the published ones.",
                            ApiException.of(failure).code().wireName());

                    return Limits.DEFAULTS;
                })
                .thenCompose(limits -> {
                    Optional<ApiErrorCode> refusal = limits.refusal(body.length, largestList);

                    if (refusal.isPresent()) {
                        return CompletableFuture.<CreateResult>failedFuture(new ApiException(ApiError.client(
                                refusal.get(), "The request is over the limit the service published.")));
                    }

                    return api.create(body, known);
                })
                .thenApply(result -> remember(result, title));
    }

    /**
     * Deletes a list with the token stored for it.
     *
     * <p>Fails with {@link ApiErrorCode#INVALID_TOKEN} - without a request - when no token is stored (the game was
     * closed after a session whose disk write failed), and with the service's own answer when it refuses; in both
     * cases the record stays. A list that is already gone counts as deleted.
     */
    public CompletableFuture<Deletion> delete(String id) {
        Optional<String> token = tokens.deleteToken(id);

        if (token.isEmpty()) {
            return CompletableFuture.failedFuture(new ApiException(ApiError.client(ApiErrorCode.INVALID_TOKEN,
                    "No delete token is stored for this list.")));
        }

        return api.delete(id, token.get()).handle((ignored, failure) -> {
            if (failure == null) {
                forget(id);

                return Deletion.DELETED;
            }

            ApiException error = ApiException.of(failure);

            if (error.code() == ApiErrorCode.NOT_FOUND || error.code() == ApiErrorCode.REMOVED) {
                forget(id);

                return Deletion.ALREADY_GONE;
            }

            throw error;
        });
    }

    /**
     * Forgets the lists the service no longer keeps, with their tokens. Cheap; call it when the player looks at
     * their lists or before a send.
     */
    public void expire() {
        List<UploadRecord> dropped = history.expire(clock.instant());

        for (UploadRecord record : dropped) {
            tokens.forgetDeleteToken(record.id());
        }
    }

    // ---- internals ----

    /** Token first, then history, then the answer: see the class comment. Neither failing fails the send. */
    private Sent remember(CreateResult result, String title) {
        boolean tokenOnDisk = false;

        try {
            expire();

            if (result.isDeletable()) {
                tokenOnDisk = tokens.saveDeleteToken(result.id(), result.deleteToken());
            }

            CreateResult.Counts counts = result.counts();

            history.add(new UploadRecord(result.id(), result.url(), clock.instant(), title, counts.mods(),
                    counts.resourcePacks(), counts.shaderPacks(), counts.dataPacks(), counts.plugins()));
        } catch (RuntimeException e) {
            // The list exists and the link is the thing the player asked for: say so, and log only the kind.
            AlaModlist.LOGGER.warn("Could not record a new list ({}).", e.getClass().getSimpleName());
        }

        return new Sent(result.id(), result.url(), result.counts(), result.truncated(), result.expires(),
                result.isDeletable(), tokenOnDisk);
    }

    private void forget(String id) {
        try {
            history.remove(id);
            tokens.forgetDeleteToken(id);
        } catch (RuntimeException e) {
            AlaModlist.LOGGER.warn("Could not forget a deleted list ({}).", e.getClass().getSimpleName());
        }
    }
}
