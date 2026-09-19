package ai.kumbuka.dispatch.surface;

/**
 * The two surfaces of this service, and the vocabulary each one speaks.
 *
 * <p>The distinction is not cosmetic and it is not a rendering detail. A
 * refusal names the call <em>the caller made</em>, and {@code next} lists the
 * calls the caller can make <em>on the surface it is calling through</em>. A
 * caller on REST that was told to call {@code dispatch_accept_return} has been
 * told to call something that does not exist for it; a caller on MCP that was
 * told to call {@code accept} has been told the kernel's name for an act it
 * cannot reach by that name.
 *
 * <p>So the surface travels with the call, all the way down to where the
 * message and the next-list are built. It is deliberately not inferred from
 * the adapter that happens to be on the stack: an inference would be a second
 * place the question is answered, and the one that would be wrong is the one
 * used by whatever adapter is written next.
 */
public enum Surface {

    /**
     * The assistant surface. Carries the process verbs of section 5 of the
     * contract, each prefixed {@code dispatch_}.
     */
    MCP,

    /**
     * The generic surface. Keeps the generic verbs it always had; only the
     * answer and refusal shapes change. The verb set is deliberately untouched
     * — REST is the complete surface, and narrowing it to the process verbs
     * would remove acts that have no process-verb equivalent.
     */
    REST
}
