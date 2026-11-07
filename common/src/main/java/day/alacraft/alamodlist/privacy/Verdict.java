package day.alacraft.alamodlist.privacy;

import java.util.List;

/**
 * The privacy gate's answer. A request may be sent only when {@link #clean()}.
 *
 * @param findings what was found; empty when nothing was
 * @param error    non-null when the gate itself failed (the kind of failure, never any content): the request is
 *                 not sent then either - the gate fails closed
 */
public record Verdict(List<Finding> findings, String error) {

    public Verdict {
        findings = List.copyOf(findings);
    }

    /** True only when the gate ran to the end and found nothing. */
    public boolean clean() {
        return error == null && findings.isEmpty();
    }

    /** True when the gate could not do its job. */
    public boolean errored() {
        return error != null;
    }
}
