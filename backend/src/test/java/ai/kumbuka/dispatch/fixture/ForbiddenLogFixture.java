package ai.kumbuka.dispatch.fixture;

import org.jboss.logging.Logger;

/**
 * Log calls the convention exists to stop, for the guard to find.
 *
 * <p>The first two are the shapes somebody reaches for while debugging a race
 * at two in the morning: print the title to see which exchange this is, print
 * the whole object to see everything at once. Neither is malicious and neither
 * would be noticed in review — the first ships a commission's title out of the
 * container past a boundary built as a missing GRANT, and the second ships the
 * title, the body and the metadata together.
 *
 * <p>The rest carry the actor, in the four shapes it is written in: the bare
 * identifier, the type, the getter and the accessor. The actor is forbidden
 * for a different reason than the title, and it is the harder one to hold on
 * to. It belongs in the audit log, whose collection is governed; a second,
 * aggregatable stream of the same fact, kept somewhere with different rules,
 * is how not-collecting-behavioural-data gets circumvented without anybody
 * deciding to circumvent it. Four shapes rather than one because the guard was
 * once looking for the substring {@code ctor}, which caught all four and also
 * caught {@code selector}. Narrowing the check to identifiers is exactly the
 * move that could quietly drop the actor along with the false positive, so
 * each shape is written down here and asserted individually.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be reported: without it, the guard's clean result over the main
 * sources would be a statement about a detection nobody has seen work.
 */
public class ForbiddenLogFixture {

    private static final Logger LOG = Logger.getLogger(ForbiddenLogFixture.class);

    public void logATitle(Exchangeish e) {
        LOG.infof("working on %s", e.title);
    }

    public void logAWholeEntity(Exchangeish e) {
        LOG.debugf("state now: %s", e);
    }

    public void logABody(Exchangeish e) {
        LOG.debugf("carrying: %s", e.body);
    }

    public void logMetadataText(Exchangeish e) {
        LOG.debugf("carrying: %s", e.metadata);
    }

    /** The receipt is a bearer token, in a file the provider operates. */
    public void logAReceipt(String receipt) {
        LOG.debugf("carrying: %s", receipt);
    }

    /** The subject is the actor under the name the token gives it. */
    public void logASubject(String subject) {
        LOG.debugf("carrying: %s", subject);
    }

    /** The actor as a bare identifier — a local, a parameter, a field. */
    public void logTheActorBare(String actor) {
        LOG.infof("changed by %s", actor);
    }

    /** The actor as a type, reached through one of its constants. */
    public void logTheActorType() {
        LOG.infof("changed by %s", Actor.EXECUTOR);
    }

    /** The actor through a getter. */
    public void logTheActorGetter(Exchangeish e) {
        LOG.infof("changed by %s", e.getActor());
    }

    /** The actor through an accessor, the shape a record gives it. */
    public void logTheActorAccessor(Exchangeish e) {
        LOG.infof("changed by %s", e.actor());
    }

    /** Stands in for the entity, so the fixture needs no domain import. */
    public static class Exchangeish {
        public String title = "a commission's title";
        public String body = "a commission's body";
        public String metadata = "free text belonging to the caller";

        public String getActor() {
            return "who did it";
        }

        public String actor() {
            return "who did it";
        }
    }

    /** Stands in for the actor type, so the fixture needs no domain import. */
    public enum Actor {
        EXECUTOR, STEERING
    }
}
