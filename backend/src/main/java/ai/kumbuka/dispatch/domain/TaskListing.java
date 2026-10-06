package ai.kumbuka.dispatch.domain;

import java.util.List;

/**
 * What {@code query} answers: the heads that matched, up to the page bound,
 * and whether the bound cut the list.
 */
public record TaskListing(List<TaskView> tasks, boolean cut) {

    public TaskListing {
        tasks = List.copyOf(tasks);
    }
}
