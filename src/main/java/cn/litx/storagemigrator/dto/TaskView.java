package cn.litx.storagemigrator.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 迁移任务的对外视图。
 *
 * @param id 任务 ID
 * @param status 任务状态
 * @param sourcePolicy 源策略
 * @param targetPolicy 目标策略
 * @param deleteSource 是否移动（删除源）
 * @param rewriteLinks 是否改写链接
 * @param total 附件总数
 * @param processed 已处理数
 * @param succeeded 成功数
 * @param failed 失败数
 * @param skipped 跳过数
 * @param currentFile 当前处理文件
 * @param errors 失败明细
 * @param urlMapping 旧地址 -> 新地址
 * @param rewriteSummary 链接改写摘要
 * @param bytesDownloaded 已下载字节数（源 -> 本机，供前端差分算下行网速）
 * @param bytesUploaded 已上传字节数（本机 -> 目标，供前端差分算上行网速）
 * @param cancelRequested 是否已请求取消
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 */
public record TaskView(String id,
                       String status,
                       String sourcePolicy,
                       String targetPolicy,
                       boolean deleteSource,
                       boolean rewriteLinks,
                       int total,
                       int processed,
                       int succeeded,
                       int failed,
                       int skipped,
                       String currentFile,
                       List<String> errors,
                       Map<String, String> urlMapping,
                       String rewriteSummary,
                       long bytesDownloaded,
                       long bytesUploaded,
                       boolean cancelRequested,
                       Instant startedAt,
                       Instant finishedAt) {
}
