package cn.litx.storagemigrator.dto;

import java.util.Map;

/**
 * 仅执行链接改写（不搬文件）的请求体。
 *
 * @param urlMapping 旧地址 -> 新地址 的映射
 * @param dryRun 只统计不写入
 */
public record RewriteRequest(Map<String, String> urlMapping, Boolean dryRun) {

    public boolean isDryRun() {
        return Boolean.TRUE.equals(dryRun);
    }
}
