package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.HoldReason;
import ai.kumbuka.dispatch.domain.TaskTextView;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.surface.CallRouter;
import ai.kumbuka.dispatch.surface.NextList;
import ai.kumbuka.dispatch.surface.Refused;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every answer of the verb surface, in the shape both surfaces put on the wire.
 *
 * <h2>Two sizes (concept section 3.3)</h2>
 *
 * A writing call answers lean: the address, the state with its attributes, the
 * conflict token, the end of the lease for the holder, and {@code next}; a
 * claim adds the receipt. {@code read} and {@code query} answer the full head:
 * in addition the title, the apparatus, both metadata, the technical address as
 * {@code identity}, whether the caller, another or nobody holds the task, and
 * the list of the texts that exist, by type and suffix.
 *
 * <p>No answer here carries a text of a task but {@link #text}: the head the
 * answers are built from has no component that could hold one. No answer names
 * the identity of an actor.
 */
public final class Answers {

    private static final String ADDRESS = "address";
    private static final String FIELDS = "fields";

    private Answers() {
    }

    /** The wire shape of one outcome of a call. */
    public static Object of(CallRouter.Outcome outcome) {
        return switch (outcome) {
            case CallRouter.Answered answered -> answer(answered);
            case CallRouter.Listed listed -> listing(listed);
            case CallRouter.TextRead read -> text(read);
            case CallRouter.Deleted deleted -> Map.of(ADDRESS, deleted.address());
            case CallRouter.Refusal refusal -> envelope(refusal.refused());
        };
    }

    /** A refusal, in the envelope both surfaces answer it with. */
    public static Payloads.RefusalEnvelope envelope(Refused refused) {
        return new Payloads.RefusalEnvelope(refused.code().name(), refused.getMessage(),
            refused.data());
    }

    /** One task: lean for a writing call, the full head for a read or a listing. */
    public static Map<String, Object> answer(CallRouter.Answered answered) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put(ADDRESS, answered.address());
        answer.put(FIELDS, fields(answered.view(), answered.head()));
        answer.put("conflict_token", answered.view().conflictToken());
        answer.put("next", steps(answered.next()));
        if (answered.waitingFor() != null) {
            answer.put("waiting_for", answered.waitingFor());
        }
        if (answered.receipt() != null) {
            answer.put("receipt", answered.receipt());
        }
        return answer;
    }

    private static Map<String, Object> fields(TaskView v, boolean head) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("state", v.state().wireName());
        putIfPresent(fields, "hold_reason", v.holdReason() == null ? null
            : v.holdReason().wireName());
        putIfPresent(fields, "outcome", v.outcome() == null ? null : v.outcome().wireName());
        putIfPresent(fields, "not_before", v.notBefore() == null ? null
            : v.notBefore().toString());
        putIfPresent(fields, "lease_expires_at", v.leaseExpiresAt() == null ? null
            : v.leaseExpiresAt().toString());
        if (!head) {
            return fields;
        }
        fields.put("title", v.title());
        fields.put("apparatus", v.apparatus());
        fields.put("identity", "dispatch://" + v.identity());
        fields.put("holder", v.holder().wireName());
        fields.put("lapse_count", v.lapseCount());
        if (v.holdReason() == HoldReason.QUESTION && v.questionOptions() != null) {
            fields.put("question", v.questionOptions());
        }
        putIfPresent(fields, "dispatch_metadata", v.dispatchMetadata());
        putIfPresent(fields, "return_metadata", v.returnMetadata());
        putIfPresent(fields, "curated_in", v.curatedIn());
        fields.put("texts", v.texts().stream().map(Answers::textHead).toList());
        return fields;
    }

    private static Map<String, Object> textHead(TaskView.TextHead head) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("type", head.type().wireName());
        putIfPresent(rendered, "suffix", head.addendumSuffix());
        return rendered;
    }

    /** A listing: the heads, and whether the page bound cut it. */
    public static Map<String, Object> listing(CallRouter.Listed listed) {
        Map<String, Object> listing = new LinkedHashMap<>();
        listing.put("tasks", listed.heads().stream().map(Answers::answer).toList());
        listing.put("cut", listed.cut());
        return listing;
    }

    /** One part of a task's text: the only answer that carries one. */
    public static Map<String, Object> text(CallRouter.TextRead read) {
        TaskTextView text = read.text();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put(ADDRESS, read.address());
        answer.put("part", text.part().wireName());
        answer.put("texts", text.entries().stream().map(entry -> {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rendered.put("type", entry.type().wireName());
            putIfPresent(rendered, "suffix", entry.addendumSuffix());
            rendered.put("text", entry.text());
            putIfPresent(rendered, "created_at", entry.createdAt() == null ? null
                : entry.createdAt().toString());
            return rendered;
        }).toList());
        answer.put("addenda", text.addenda());
        return answer;
    }

    private static List<Map<String, String>> steps(List<NextList.Step> next) {
        return next.stream().map(step -> {
            Map<String, String> rendered = new LinkedHashMap<>();
            rendered.put("call", step.call());
            rendered.put("does", step.does());
            return rendered;
        }).toList();
    }

    private static void putIfPresent(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }
}
