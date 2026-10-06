package ai.kumbuka.dispatch.domain;

/**
 * A claimed task and the receipt that proves the claim.
 *
 * <p>The receipt is the only copy; the row stores its hash. No text of the
 * task travels with the claim: the holder reads the commission next, with
 * {@code read_text}.
 */
public record TaskClaim(TaskView task, String receipt) {
}
