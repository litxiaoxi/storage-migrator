package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.extension.Metadata;

/**
 * 改写映射的来源：历史迁移标注（中断过的任务也覆盖）+ 本次任务映射。
 */
class MigrationMappingFallbackTest {

    private static Attachment attachment(String name, String fromPermalink, String permalink) {
        return attachment(name, fromPermalink, permalink, "target", null);
    }

    private static Attachment attachment(String name, String fromPermalink, String permalink,
                                         String policyName, String fromPolicy) {
        Attachment attachment = new Attachment();
        Metadata metadata = new Metadata();
        metadata.setName(name);
        Map<String, String> annotations = new LinkedHashMap<>();
        if (fromPermalink != null) {
            annotations.put(MigrationService.SOURCE_PERMALINK_ANNO, fromPermalink);
        }
        if (fromPolicy != null) {
            annotations.put(MigrationService.SOURCE_POLICY_ANNO, fromPolicy);
        }
        metadata.setAnnotations(annotations);
        attachment.setMetadata(metadata);
        Attachment.AttachmentSpec spec = new Attachment.AttachmentSpec();
        spec.setPolicyName(policyName);
        attachment.setSpec(spec);
        Attachment.AttachmentStatus status = new Attachment.AttachmentStatus();
        status.setPermalink(permalink);
        attachment.setStatus(status);
        return attachment;
    }

    @Test
    @DisplayName("历史映射只算「同一源策略 -> 同一目标策略」的迁移")
    void scopesHistoricalMappingToSameMigration() {
        // 本次：从「评论」策略搬到「测试」策略
        Attachment recordedSameSource = attachment("same-source",
            "/upload/comments/a.jpg", "/upload/new-a.jpg", "target", "comments");
        Attachment recordedOtherSource = attachment("other-source",
            "/upload/avatars/b.jpg", "/upload/new-b.jpg", "target", "avatars");
        Attachment legacySameTarget = attachment("legacy-same-target",
            "/upload/comments/c.jpg", "/upload/new-c.jpg", "target", null);
        Attachment legacyOtherTarget = attachment("legacy-other-target",
            "/upload/comments/d.jpg", "/upload/old-d.jpg", "default-policy", null);

        assertTrue(MigrationService.belongsToMigration(recordedSameSource, "comments", "target"));
        assertFalse(MigrationService.belongsToMigration(recordedOtherSource, "comments", "target"));
        assertTrue(MigrationService.belongsToMigration(legacySameTarget, "comments", "target"));
        // 老数据没有源策略标注：只能按目标策略判断，别的策略搬走的文件不算
        assertFalse(MigrationService.belongsToMigration(legacyOtherTarget, "comments", "target"));
    }

    @Test
    @DisplayName("按附件上的来源标注还原旧地址到新地址")
    void rebuildsMappingFromAnnotations() {
        Map<String, String> mapping = MigrationService.mappingOf(List.of(
            attachment("a1", "https://old.example.com/1.webp", "/upload/x1.webp"),
            attachment("a2", "https://old.example.com/2.mp4", "/upload/x2.mp4")));

        assertEquals(Map.of(
            "https://old.example.com/1.webp", "/upload/x1.webp",
            "https://old.example.com/2.mp4", "/upload/x2.mp4"), mapping);
    }

    @Test
    @DisplayName("没有标注、没有新地址、地址没变、空地址都不进映射")
    void skipsUnusableAttachments() {
        Map<String, String> mapping = MigrationService.mappingOf(List.of(
            attachment("no-anno", null, "/upload/y1.webp"),
            attachment("no-permalink", "https://old.example.com/3.webp", null),
            attachment("same", "/upload/y2.webp", "/upload/y2.webp"),
            attachment("blank", "https://old.example.com/4.webp", " ")));

        assertTrue(mapping.isEmpty());
    }

    @Test
    @DisplayName("同一旧地址重复出现时保留第一条，结果稳定")
    void keepsFirstForDuplicateSource() {
        Map<String, String> mapping = MigrationService.mappingOf(List.of(
            attachment("a1", "https://old.example.com/5.webp", "/upload/z1.webp"),
            attachment("a2", "https://old.example.com/5.webp", "/upload/z2.webp")));

        assertEquals(Map.of("https://old.example.com/5.webp", "/upload/z1.webp"), mapping);
    }

    @Test
    @DisplayName("合并时本次任务产生的映射优先，历史映射继续保留")
    void mergePrefersCurrentTask() {
        Map<String, String> historical = new LinkedHashMap<>();
        historical.put("https://old.example.com/6.webp", "/upload/old-copy.webp");
        historical.put("https://old.example.com/7.webp", "/upload/keep.webp");

        Map<String, String> current = Map.of(
            "https://old.example.com/6.webp", "/upload/new-copy.webp");

        Map<String, String> merged = MigrationService.mergeMapping(historical, current);

        assertEquals(2, merged.size());
        assertEquals("/upload/new-copy.webp", merged.get("https://old.example.com/6.webp"));
        assertEquals("/upload/keep.webp", merged.get("https://old.example.com/7.webp"));
    }

    @Test
    @DisplayName("两侧为空时合并结果为空")
    void mergeHandlesEmpty() {
        assertTrue(MigrationService.mergeMapping(Map.of(), Map.of()).isEmpty());
        assertEquals(Map.of("a", "b"), MigrationService.mergeMapping(Map.of(), Map.of("a", "b")));
    }
}
