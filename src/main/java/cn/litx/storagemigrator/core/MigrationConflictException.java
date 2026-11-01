package cn.litx.storagemigrator.core;

/**
 * 同一对存储策略上已有迁移任务在执行时抛出。
 *
 * <p>两个任务同时搬同一批文件会各自上传一份副本（目标策略的附件快照是在任务启动时取的，
 * 彼此看不见对方的产物），结果就是文件翻倍、用户头像被后完成的任务抢改。
 * 因此直接在创建任务阶段拒绝，由调用方决定重试时机。</p>
 */
public class MigrationConflictException extends RuntimeException {

    public MigrationConflictException(String message) {
        super(message);
    }
}
