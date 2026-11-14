/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

/**
 * The permissions of this service: what a call checks about its caller, and
 * the only thing it checks (DEC-0052).
 *
 * <p>Each is named {@code dispatch.<object>.<act>} and says nothing of a role:
 * a role is the platform's way of granting a set of these, and which role
 * grants which is decided outside the service. The service reads the
 * permissions off the identity it is handed and stores none.
 *
 * <p>{@code instance.start}, {@code instance.conclude} and {@code
 * instance.withdraw} are in the catalogue and checked by no call yet: they
 * belong to the instances of a declared process, which this service does not
 * carry yet.
 */
public enum DispatchPermission {

    TASK_COMMISSION(Name.TASK_COMMISSION),
    TASK_EXECUTE(Name.TASK_EXECUTE),
    TASK_DECIDE(Name.TASK_DECIDE),
    TASK_CONFIRM(Name.TASK_CONFIRM),
    TASK_WITHDRAW(Name.TASK_WITHDRAW),
    TASK_TAG(Name.TASK_TAG),
    INSTANCE_START(Name.INSTANCE_START),
    INSTANCE_CONCLUDE(Name.INSTANCE_CONCLUDE),
    INSTANCE_WITHDRAW(Name.INSTANCE_WITHDRAW),
    INSTANCE_READ(Name.INSTANCE_READ),
    INSTANCE_READ_METADATA(Name.INSTANCE_READ_METADATA),
    INSTANCE_READ_TEXT(Name.INSTANCE_READ_TEXT),
    INSTANCE_READ_TEXT_AS_HOLDER(Name.INSTANCE_READ_TEXT_AS_HOLDER),
    INSTANCE_READ_TEXT_AS_COMMISSIONER(Name.INSTANCE_READ_TEXT_AS_COMMISSIONER);

    /**
     * The names as constants, for the annotations that check them: an
     * annotation takes a constant, and an enum constant's field is none.
     */
    public static final class Name {

        public static final String TASK_COMMISSION = "dispatch.task.commission";
        public static final String TASK_EXECUTE = "dispatch.task.execute";
        public static final String TASK_DECIDE = "dispatch.task.decide";
        public static final String TASK_CONFIRM = "dispatch.task.confirm";
        public static final String TASK_WITHDRAW = "dispatch.task.withdraw";
        public static final String TASK_TAG = "dispatch.task.tag";
        public static final String INSTANCE_START = "dispatch.instance.start";
        public static final String INSTANCE_CONCLUDE = "dispatch.instance.conclude";
        public static final String INSTANCE_WITHDRAW = "dispatch.instance.withdraw";
        public static final String INSTANCE_READ = "dispatch.instance.read";
        public static final String INSTANCE_READ_METADATA = "dispatch.instance.read-metadata";
        public static final String INSTANCE_READ_TEXT = "dispatch.instance.read-text";
        public static final String INSTANCE_READ_TEXT_AS_HOLDER =
            "dispatch.instance.read-text-as-holder";
        public static final String INSTANCE_READ_TEXT_AS_COMMISSIONER =
            "dispatch.instance.read-text-as-commissioner";

        private Name() {
        }
    }

    private final String wireName;

    DispatchPermission(String wireName) {
        this.wireName = wireName;
    }

    /** The permission's name, as an identity carries it. */
    public String wireName() {
        return wireName;
    }
}
