package ai.kumbuka.dispatch.fixture.allowed;

import java.util.UUID;

import org.jboss.logging.Logger;

/**
 * Every shape the logging convention allows, for the guard to pass over.
 *
 * <p>The convention names eight things a log line may carry: an address, a
 * selector, a number, a transition, a status, a typed reason, a duration and a
 * scope id. This class writes one log call per item, using the bare identifier
 * a developer would actually reach for — {@code selector}, not
 * {@code theSelectorName} — because the bare form is the one a substring check
 * gets wrong.
 *
 * <p>It exists because a guard that only ever reports is half a guard. Until
 * something asserts that the permitted shapes come back clean, "no offence
 * found over the main sources" is a statement about a tree that happens not to
 * contain the offending shapes, not about a check that can tell them apart.
 * The concrete failure this pins: {@code selector} was reported as carrying
 * the actor, because the actor was looked for as the substring {@code ctor}
 * and {@code sele-ctor} contains it. Nothing caught that, because nothing here
 * logged a selector.
 *
 * <p>It lives in the test sources, in its own package, and is wired into
 * nothing. The package matters: the guard points one test at this directory
 * and requires zero offences, so a forbidden call must never be added here.
 * Forbidden calls belong one directory up, in {@code ForbiddenLogFixture}.
 */
public class AllowedLogFixture {

    private static final Logger LOG = Logger.getLogger(AllowedLogFixture.class);

    /** An address. The public name of an exchange, and not its content. */
    public void logAnAddress(String address) {
        LOG.infof("dispatch %s", address);
    }

    /**
     * A selector. On the permitted list, immediately after the address — and
     * the identifier the old substring check reported as the actor.
     */
    public void logASelector(String selector) {
        LOG.debugf("checking selector '%s'", selector);
    }

    /** A number. A count of things, carrying nothing about any of them. */
    public void logANumber(int number) {
        LOG.debugf("addendum number %d", number);
    }

    /** A transition. Two status names and the arrow between them. */
    public void logATransition(String from, String to) {
        LOG.infof("%s -> %s", from, to);
    }

    /** A status. The state machine's word for where the exchange stands. */
    public void logAStatus(String status) {
        LOG.infof("status now %s", status);
    }

    /**
     * A typed reason. A constant from a closed set, so the log carries the
     * category of the refusal and never the caller's prose about it.
     */
    public void logATypedReason(Reason reason) {
        LOG.warnf("refused: %s", reason);
    }

    /** A duration. How long it took, which is about the machine. */
    public void logADuration(long durationMillis) {
        LOG.debugf("took %d ms", durationMillis);
    }

    /** A scope id. An opaque identifier, resolvable only with the directory. */
    public void logAScopeId(UUID scopeId) {
        LOG.debugf("scope %s", scopeId);
    }

    /**
     * Stands in for the typed reason, so the fixture needs no domain import.
     *
     * <p>Upper case on purpose: a constant is how a typed reason reaches a log
     * call in the main sources, and it must survive the check as written.
     */
    public enum Reason {
        SCOPE_UNRESOLVED, SELECTOR_WITHDRAWN
    }
}
