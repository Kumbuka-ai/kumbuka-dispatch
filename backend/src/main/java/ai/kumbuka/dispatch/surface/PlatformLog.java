/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.Task;
import ai.kumbuka.dispatch.domain.TaskState;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.UUID;

/**
 * Every log line of the packages that serve a caller, and the only logger
 * they hold (DEC-0051, C4; C13.1 to C13.3).
 *
 * <p>A line carries the platform's vocabulary and nothing else: the call, the
 * technical address, the scope id, the state, the reason code, a count, the
 * reference and the class names of an unforeseen failure. Each line is one
 * method here, with a constant message, and each parameter is a type the
 * service builds from what it holds itself, never from a {@code String}, a
 * number or a uuid a caller could have chosen. So a value a caller wrote -- a
 * scope's name, a selector, a title, a text -- cannot reach a line: there is
 * no parameter that would take it.
 *
 * <p>The request id travels through the MDC into the format, and the service
 * through the logger's category; neither is a parameter.
 *
 * <p>{@code LogFacadeAccessTest} holds that no other class of the service
 * holds or calls a logger, {@code LogFacadeTypeTest} that every parameter is
 * one of the platform's types and that none of them can be built from a text.
 */
public final class PlatformLog {

    /*
     * Each message is a literal inside its logger call: constant, written
     * once, and where MessageBundleTest reads a sentence as a note for the
     * log rather than a text for a caller.
     */
    private static final Logger LOG = Logger.getLogger(PlatformLog.class);

    private PlatformLog() {
    }

    // ======================================================================
    // Writes: INFO per completed write, by the technical address (C13.3).
    // The complete address carries the scope's name and the selector, which
    // are the caller's words, so it never stands in a line. Written by the
    // verb surface, which called the write; the domain logs nothing.
    // ======================================================================

    public static void created(TechnicalAddress entry, Status state) {
        LOG.infof("create %s -> %s", entry, state);
    }

    public static void createdInScope(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("create %s in scope %s", entry, scope);
    }

    public static void updated(TechnicalAddress entry) {
        LOG.infof("update %s", entry);
    }

    public static void deleted(TechnicalAddress entry) {
        LOG.infof("delete %s", entry);
    }

    public static void tagAttached(TechnicalAddress entry) {
        LOG.infof("tag_attach %s", entry);
    }

    public static void tagDetached(TechnicalAddress entry) {
        LOG.infof("tag_detach %s", entry);
    }

    public static void annotated(TechnicalAddress entry) {
        LOG.infof("annotate %s", entry);
    }

    public static void related(TechnicalAddress entry) {
        LOG.infof("relate %s", entry);
    }

    public static void unrelated(TechnicalAddress entry) {
        LOG.infof("unrelate %s", entry);
    }

    /** A transition or a take-up, with the state it entered. */
    public static void transitioned(VerbName call, TechnicalAddress entry, Status state) {
        LOG.infof("%s %s -> %s", call, entry, state);
    }

    /** A transition, with the scope it was made in. */
    public static void transitionedInScope(VerbName call, TechnicalAddress entry,
                                           ScopeId scope) {
        LOG.infof("%s %s in scope %s", call, entry, scope);
    }

    /** A child closed along with its bracket root, before the root closes. */
    public static void childWithdrawn(TechnicalAddress entry, Status state) {
        LOG.infof("withdraw %s -> %s", entry, state);
    }

    /** A repeated take-up under its key: the same task, a new receipt, nothing else. */
    public static void receiptReissued(VerbName call, TechnicalAddress entry) {
        LOG.infof("%s %s repeated: receipt reissued", call, entry);
    }

    public static void drawnInScope(TechnicalAddress entry, ScopeId scope) {
        LOG.infof("claim_next %s in scope %s", entry, scope);
    }

    /** A listing, by the scope and how many heads it answered; never by its selector. */
    public static void queried(ScopeId scope, Count heads) {
        LOG.debugf("query in scope %s: %s head(s)", scope, heads);
    }

    // ======================================================================
    // Refusals: DEBUG by their code, because a refusal is the surface
    // working and a log a caller can fill by sending broken calls is a log a
    // caller writes (C13.3). The scope's refusals are WARN as well: they say
    // something about the scope or the deployment, not about the call.
    // ======================================================================

    public static void refused(Reason code) {
        LOG.debugf("refusal: %s", code);
    }

    /**
     * A refusal of the domain or of the platform's directory, where it
     * arrives at the surface: DEBUG by its code, and the line the directory
     * wrote before the directory stopped logging, for the scope's refusals.
     */
    public static void domainRefused(DispatchException.Reason reason) {
        Reason code = Reason.of(reason);
        refused(code);
        switch (reason) {
            case SESSION_NOT_BOUND -> sessionNotBound(code);
            case SCOPE_UNRESOLVED -> scopeUnresolved(code);
            case SCOPE_KIND_UNSUPPORTED -> scopeKindRefused(code);
            case SCOPE_LOCKED -> scopeLocked(code);
            case SCOPE_READ_ONLY -> scopeReadOnly(code);
            default -> {
                // Every other refusal is the call's, and DEBUG says all of it.
            }
        }
    }

    /**
     * WARN: the read contract could not be bound for this session, which is a
     * statement about the deployment, not about the call.
     */
    private static void sessionNotBound(Reason code) {
        LOG.warnf("directory call with unbound session: %s", code);
    }

    /** WARN: the directory answers no scope of that name for this subject. */
    private static void scopeUnresolved(Reason code) {
        LOG.warnf("scope unresolved: %s", code);
    }

    /** WARN: the scope is of a kind this service does not serve. */
    private static void scopeKindRefused(Reason code) {
        LOG.warnf("scope of a kind not served refused: %s", code);
    }

    private static void scopeLocked(Reason code) {
        LOG.warnf("write into a locked scope refused: %s", code);
    }

    private static void scopeReadOnly(Reason code) {
        LOG.warnf("write without the write right refused: %s", code);
    }

    /**
     * WARN: a token that authenticated and carries no capacity is a realm
     * misconfiguration no caller can fix. The subject is not logged; which
     * token it was belongs to the audit log.
     */
    public static void tokenWithoutCapacity(Reason code) {
        LOG.warnf("a token authenticated without a capacity: %s", code);
    }

    /** That the endpoint was reached, and nothing about who reached it. */
    public static void whoamiReached() {
        LOG.debug("whoami");
    }

    // ======================================================================
    // Start
    // ======================================================================

    public static void declarationServed(Count calls, Count reasons) {
        LOG.infof("surface declaration served: %s calls, %s reasons", calls, reasons);
    }

    public static void catalogueComplete(Count codes) {
        LOG.infof("refusal catalogue complete: %s codes", codes);
    }

    /**
     * WARN, on a line of its own before the start fails (C13.3): the format
     * prints no exception (C13.1), so the problem is named here by its
     * constant. The exception's own text, which names configuration values,
     * stays out of the line.
     */
    public static void startRefused(StartProblem problem) {
        LOG.warnf("start refused: %s", problem);
    }

    // ======================================================================
    // The unforeseen failure (C8.3): ERROR, with the reference the caller is
    // told to report and the class names of the failure's chain.
    // ======================================================================

    static void unexpectedFailure(VerbName call, UnexpectedFailures.FailureReference reference,
                                  UnexpectedFailures.FailureCause cause) {
        LOG.errorf("unexpected failure on %s, reference %s, cause %s", call, reference, cause);
    }

    static void unexpectedFailureBeforeACall(UnexpectedFailures.FailureReference reference,
                                             UnexpectedFailures.FailureCause cause) {
        LOG.errorf("unexpected failure on this request, reference %s, cause %s", reference, cause);
    }

    // ======================================================================
    // The types a line takes
    // ======================================================================

    /**
     * A task by its technical address, {@code dispatch://<uuid>}: the durable
     * identity the database drew at the insert, and no value its caller
     * wrote. Built from the entity only. Never the complete address
     * {@code dispatch://<scope>/<selector>/<number>.<sub>}, which carries the
     * scope's name and the selector.
     */
    public static final class TechnicalAddress {

        private final String address;

        private TechnicalAddress(String address) {
            this.address = address;
        }

        static TechnicalAddress of(Task entry) {
            return new TechnicalAddress("dispatch://" + entry.uuid);
        }

        @Override
        public String toString() {
            return address;
        }
    }

    /** The scope a call was made in, by the id the platform's directory resolved it to. */
    public static final class ScopeId {

        private final UUID scope;

        private ScopeId(UUID scope) {
            this.scope = scope;
        }

        static ScopeId of(UUID resolved) {
            return new ScopeId(resolved);
        }

        @Override
        public String toString() {
            return scope.toString();
        }
    }

    /** A state of the lifecycle, as the wire names it. */
    public static final class Status {

        private final TaskState state;

        private Status(TaskState state) {
            this.state = state;
        }

        public static Status of(TaskState state) {
            return new Status(state);
        }

        @Override
        public String toString() {
            return state.wireName();
        }
    }

    /**
     * The reason code of a refusal, out of one of the service's closed sets:
     * the domain's, or the surface's.
     */
    public static final class Reason {

        private final Enum<?> reason;

        private Reason(Enum<?> reason) {
            this.reason = reason;
        }

        public static Reason of(DispatchException.Reason reason) {
            return new Reason(reason);
        }

        public static Reason of(RefusalCode code) {
            return new Reason(code);
        }

        @Override
        public String toString() {
            return reason.name();
        }
    }

    /** How many members a collection the service holds has. */
    public static final class Count {

        private final int size;

        private Count(int size) {
            this.size = size;
        }

        public static Count of(Collection<?> members) {
            return new Count(members.size());
        }

        @Override
        public String toString() {
            return Integer.toString(size);
        }
    }

    /**
     * What a start was refused for, without a value: the configuration value
     * that was wrong stays in the exception, out of the line.
     */
    public enum StartProblem {

        /** The principal claim is not pinned to the token's subject. */
        PRINCIPAL_CLAIM_NOT_SUBJECT,

        /** No audience is configured, so none would be validated. */
        AUDIENCE_NOT_SET
    }
}
