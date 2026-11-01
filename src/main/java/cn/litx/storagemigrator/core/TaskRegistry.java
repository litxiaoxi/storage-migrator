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

    /**
     * 取消所有未进入终态的任务。
     *
     * <p>插件被禁用或卸载时调用：让后台迁移在当前文件边界停下，
     * 已迁移的附件保留、不回退（与手动取消语义一致）。</p>
     *
     * @return 被成功取消的任务数
     */
    public int cancelAllRunning() {
        int cancelled = 0;
        for (MigrationTask task : tasks.values()) {
            if (task.requestCancel()) {
                cancelled++;
            }
        }
        return cancelled;
    }

    /**
     * 当前未进入终态的任务数。
     *
     * @return 仍在执行的任务数
     */
    public int runningCount() {
        return (int) tasks.values().stream()
            .filter(t -> !t.isTerminal())
            .count();
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
