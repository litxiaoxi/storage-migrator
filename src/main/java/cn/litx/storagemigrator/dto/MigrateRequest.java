package cn.litx.storagemigrator.dto;

/**
 * 发起迁移任务的请求体。
 *
 * @param sourcePolicy 源存储策略名
 * @param targetPolicy 目标存储策略名
 * @param deleteSource 迁移成功后是否删除源附件（true = 移动，false = 复制保留）
 * @param rewriteLinks 迁移完成后是否自动改写文章 / 独立页面中的旧链接
 */
public record MigrateRequest(String sourcePolicy,
                             String targetPolicy,
                             Boolean deleteSource,
                             Boolean rewriteLinks) {

    public boolean shouldDeleteSource() {
        return Boolean.TRUE.equals(deleteSource);
    }

    public boolean shouldRewriteLinks() {
        return Boolean.TRUE.equals(rewriteLinks);
    }
}
