package cn.litx.storagemigrator.core;

import cn.litx.storagemigrator.dto.TaskView;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 内存任务表。插件重启后任务记录丢失（迁移产物已落库，不会丢失）。
 */
@Component
public class TaskRegistry {

    private static final int MAX_TASKS = 50;

    private final Map<String, MigrationTask> tasks = new ConcurrentHashMap<>();

    public void put(MigrationTask task) {
        tasks.put(task.getId(), task);
        trim();
    }

    public Optional<MigrationTask> get(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public List<TaskView> list() {
        return tasks.values().stream()
            .map(MigrationTask::view)
            .sorted(Comparator.comparing(TaskView::startedAt).reversed())
            .collect(Collectors.toList());
    }

    private void trim() {
        if (tasks.size() <= MAX_TASKS) {
            return;
        }
        tasks.values().stream()
            .filter(t -> t.getStatus() == MigrationTask.Status.SUCCEEDED
                || t.getStatus() == MigrationTask.Status.FAILED)
            .sorted(Comparator.comparing(t -> t.view().startedAt()))
            .limit((long) tasks.size() - MAX_TASKS)
            .forEach(t -> tasks.remove(t.getId()));
    }
}
