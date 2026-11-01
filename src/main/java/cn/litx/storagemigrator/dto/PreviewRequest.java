package cn.litx.storagemigrator.dto;

/**
 * 迁移前预检请求。
 *
 * @param sourcePolicy 源存储策略名
 * @param targetPolicy 目标存储策略名
 */
public record PreviewRequest(String sourcePolicy, String targetPolicy) {
}
