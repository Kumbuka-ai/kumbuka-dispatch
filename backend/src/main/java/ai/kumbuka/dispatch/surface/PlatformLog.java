/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.InstanceView;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.platform.ScopeDirectory;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.UUID;

/**
 * Every log line of the packages that serve a caller, and the only logger
 * they hold (DEC-0051, C4; C13.1 to C13.3).
 *
 * <p>A line carries the platform's vocabulary and nothing else: the call, the
 * reason code, a count, the reference and the class names of an unforeseen
 * failure. Each line is one
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

    /**
     * INFO, one line per completed write (C13.3): the call, the object's
     * technical address and the scope's id. Never the scope's name, the
     * selector, a title, a label, a value or a text (DEC-0051 C1, C7; D23).
     */
    public static void written(VerbName call, TechnicalAddress object, ScopeId scope) {
        LOG.infof("written: %s on %s in scope %s", call, object, scope);
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
     * The reason code of a refusal, out of one of the service's closed sets:
     * the domain's, or the surface's.
     */
    public static final class Reason {

        private final Enum<?> code;

        private Reason(Enum<?> code) {
            this.code = code;
        }

        public static Reason of(DispatchException.Reason reason) {
            return new Reason(reason);
        }

        public static Reason of(RefusalCode code) {
            return new Reason(code);
        }

        @Override
        public String toString() {
            return code.name();
        }
    }

    /**
     * The technical address of an object a write changed: its surrogate in
     * the store, which no caller chose and which names nothing of the tenant.
     */
    public static final class TechnicalAddress {

        private final String kind;
        private final long id;

        private TechnicalAddress(String kind, long id) {
            this.kind = kind;
            this.id = id;
        }

        /** The instance an act on it answered. */
        public static TechnicalAddress of(InstanceView written) {
            return new TechnicalAddress("instance", written.id());
        }

        /** The task a call on it answered, or deleted. */
        public static TechnicalAddress of(TaskView written) {
            return new TechnicalAddress("task", written.id());
        }

        @Override
        public String toString() {
            return kind + " " + id;
        }
    }

    /** The id of a scope the directory resolved, never its name. */
    public static final class ScopeId {

        private final UUID id;

        private ScopeId(UUID id) {
            this.id = id;
        }

        public static ScopeId of(ScopeDirectory.ScopeAccess resolved) {
            return new ScopeId(resolved.scopeId());
        }

        @Override
        public String toString() {
            return id.toString();
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
