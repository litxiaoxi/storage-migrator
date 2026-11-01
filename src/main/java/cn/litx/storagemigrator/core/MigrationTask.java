package cn.litx.storagemigrator.core;

import cn.litx.storagemigrator.dto.TaskView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 迁移任务的运行态，所有字段线程安全。
 */
public class MigrationTask {

    /** 任务状态。 */
    public enum Status {
        PENDING, MIGRATING, REWRITING, SUCCEEDED, FAILED, CANCELLED
    }

    private final String id;
    private final String sourcePolicy;
    private final String targetPolicy;
    private final boolean deleteSource;
    private final boolean rewriteLinks;

    private volatile Status status = Status.PENDING;
    private volatile int total;
    private volatile String currentFile = "";
    private volatile String rewriteSummary = "";
    private volatile String fatalMessage;
    private volatile Instant finishedAt;
    private volatile boolean cancelRequested;

    private final Instant startedAt = Instant.now();
    private final AtomicInteger processed = new AtomicInteger();
    private final AtomicInteger succeeded = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    /** 已下载字节数（源 -> 本机），前端用两次轮询差分算下行网速。 */
    private final AtomicLong bytesDownloaded = new AtomicLong();
    /** 已上传字节数（本机 -> 目标），前端用两次轮询差分算上行网速。 */
    private final AtomicLong bytesUploaded = new AtomicLong();

    /** 已随迁移逐文件改写的文件数。 */
    private final AtomicInteger rewriteFiles = new AtomicInteger();
    /** 逐文件改写累计的内容条目改动数。 */
    private final AtomicInteger rewriteHits = new AtomicInteger();
    /** 逐文件改写累计的快照改动数。 */
    private final AtomicInteger rewriteSnapshots = new AtomicInteger();

    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final Map<String, String> urlMapping = new ConcurrentHashMap<>();

    /**
     * 源附件记录名 -> 目标附件记录名。
     *
     * <p>用户头像的权威来源是 {@code halo.run/avatar-attachment-name} 注解里存的**附件名**，
     * 而不是 {@code spec.avatar}（后者由 Halo 的 UserReconciler 反写）。所以移动模式下删掉源附件后，
     * 必须把注解换成新附件的名字，否则 reconciler 会一直重试「avatar permalink not available」。</p>
     */
    private final Map<String, String> attachmentNameMapping = new ConcurrentHashMap<>();

    private static final int MAX_ERRORS = 200;

    public MigrationTask(String id, String sourcePolicy, String targetPolicy,
                         boolean deleteSource, boolean rewriteLinks) {
        this.id = id;
        this.sourcePolicy = sourcePolicy;
        this.targetPolicy = targetPolicy;
        this.deleteSource = deleteSource;
        this.rewriteLinks = rewriteLinks;
    }

    public String getId() {
        return id;
    }

    public String getSourcePolicy() {
        return sourcePolicy;
    }

    public String getTargetPolicy() {
        return targetPolicy;
    }

    public boolean isDeleteSource() {
        return deleteSource;
    }

    public boolean isRewriteLinks() {
        return rewriteLinks;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getTotal() {
        return total;
    }

    public void setCurrentFile(String currentFile) {
        this.currentFile = currentFile == null ? "" : currentFile;
    }

    public void addMapping(String from, String to) {
        if (from != null && to != null && !from.equals(to)) {
            urlMapping.put(from, to);
        }
    }

    public Map<String, String> getUrlMapping() {
        return urlMapping;
    }

    public void addNameMapping(String from, String to) {
        if (from != null && to != null && !from.equals(to)) {
            attachmentNameMapping.put(from, to);
        }
    }

    public Map<String, String> getAttachmentNameMapping() {
        return attachmentNameMapping;
    }

    public void markSucceeded() {
        this.processed.incrementAndGet();
        this.succeeded.incrementAndGet();
    }

    public void markSkipped() {
        this.processed.incrementAndGet();
        this.skipped.incrementAndGet();
    }

    public void markFailed(String fileName, Throwable e) {
        this.processed.incrementAndGet();
        this.failed.incrementAndGet();
        if (errors.size() < MAX_ERRORS) {
            var msg = e == null ? "unknown error" : (e.getMessage() == null ? e.toString() : e.getMessage());
            errors.add(fileName + ": " + msg);
        }
    }

    public void setRewriteSummary(String summary) {
        this.rewriteSummary = summary;
    }

    /**
     * 请求取消：任务在下一个文件边界停止，已迁移的附件保留，不回退。
     *
     * <p>已进入终态的任务不接受取消。</p>
     *
     * @return 是否接受取消
     */
    public boolean requestCancel() {
        synchronized (this) {
            if (isTerminal()) {
                return false;
            }
            this.cancelRequested = true;
            return true;
        }
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /** 是否已进入终态（成功 / 失败 / 取消）。 */
    public boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED;
    }

    /** 累计已下载字节数（源 -> 本机），供前端差分计算下行网速。 */
    public void addDownloadedBytes(long bytes) {
        if (bytes > 0) {
            bytesDownloaded.addAndGet(bytes);
        }
    }

    /** 累计已上传字节数（本机 -> 目标），供前端差分计算上行网速。 */
    public void addUploadedBytes(long bytes) {
        if (bytes > 0) {
            bytesUploaded.addAndGet(bytes);
        }
    }

    public long getBytesDownloaded() {
        return bytesDownloaded.get();
    }

    public long getBytesUploaded() {
        return bytesUploaded.get();
    }

    /** 已随迁移逐文件改写的文件数。 */
    public int getRewriteFiles() {
        return rewriteFiles.get();
    }

    /**
     * 累计一次「迁移完单个文件后立即改写其引用」的结果，实时刷新改写摘要。
     *
     * @param r 本次单文件改写的统计
     */
    public void mergeRewrite(LinkRewriteService.RewriteResult r) {
        if (r == null) {
            return;
        }
        rewriteFiles.incrementAndGet();
        rewriteHits.addAndGet(r.postsUpdated() + r.pagesUpdated() + r.momentsUpdated()
            + r.configMapsUpdated() + r.commentsUpdated() + r.usersUpdated());
        rewriteSnapshots.addAndGet(r.snapshotsUpdated());
        rewriteSummary = "已随迁移改写 " + rewriteFiles.get() + " 个文件的引用，改动 "
            + rewriteHits.get() + " 处（快照 " + rewriteSnapshots.get() + " 个）";
    }

    public void finish(Status finalStatus, String message) {
        this.fatalMessage = message;
        this.finishedAt = Instant.now();
        this.status = finalStatus;
    }

    /**
     * 生成对外视图。
     *
     * @return 不可变视图
     */
    public TaskView view() {
        return new TaskView(id, status.name(), sourcePolicy, targetPolicy, deleteSource, rewriteLinks,
            total, processed.get(), succeeded.get(), failed.get(), skipped.get(), currentFile,
            List.copyOf(errors), Map.copyOf(urlMapping),
            rewriteSummary == null ? "" : rewriteSummary,
            bytesDownloaded.get(), bytesUploaded.get(), cancelRequested,
            startedAt, finishedAt);
    }

    public String getFatalMessage() {
        return fatalMessage;
    }
}
