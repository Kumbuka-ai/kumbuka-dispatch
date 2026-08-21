package ai.kumbuka.dispatch.fixture;

import org.jboss.logging.Logger;

/**
 * Two log calls the convention exists to stop, for the guard to find.
 *
 * <p>Both are the shapes somebody reaches for while debugging a race at two in
 * the morning: print the title to see which exchange this is, print the whole
 * object to see everything at once. Neither is malicious and neither would be
 * noticed in review — the first ships a commission's title out of the
 * container past a boundary built as a missing GRANT, and the second ships the
 * title, the body and the metadata together.
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

    /** Stands in for the entity, so the fixture needs no domain import. */
    public static class Exchangeish {
        public String title = "a commission's title";
    }
}
