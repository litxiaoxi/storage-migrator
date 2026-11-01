package cn.litx.storagemigrator.dto;

/**
 * 存储策略视图。
 *
 * @param name 策略 metadata.name
 * @param displayName 策略显示名
 * @param templateName 策略模板（local / s3 / ...）
 * @param attachmentCount 该策略下附件数量
 * @param totalSize 该策略下附件总字节数
 */
public record PolicyVo(String name,
                       String displayName,
                       String templateName,
                       long attachmentCount,
                       long totalSize) {
}
