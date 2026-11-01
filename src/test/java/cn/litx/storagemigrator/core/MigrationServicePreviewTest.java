package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.litx.storagemigrator.dto.PreviewVo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预检响应聚合逻辑：统计口径、排序、无引用附件数。
 */
class MigrationServicePreviewTest {

    private static LinkRewriteService.Impact impact(String type, String name, String title,
                                                    int contentHits, int coverHits,
                                                    Map<String, Integer> hits) {
        return new LinkRewriteService.Impact(type, name, title, contentHits, coverHits, hits);
    }

    private static Map<String, Integer> hits(String url, int count) {
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put(url, count);
        return map;
    }

    @Test
    @DisplayName("汇总文章 / 页面数、正文与封面处数")
    void aggregatesCounts() {
        var result = new LinkRewriteService.PreviewResult(4, 3, 2, 5, List.of(
            impact("POST", "post-1", "第一篇", 2, 1, hits("/upload/a.png", 3)),
            impact("PAGE", "page-1", "关于", 1, 0, hits("/upload/b.png", 1))));

        PreviewVo vo = MigrationService.buildPreview("src", "tgt", 5, 1024L, 2,
            List.of("/upload/a.png", "/upload/b.png", "/upload/c.png"),
            List.of("/upload/a.png", "/upload/b.png", "/upload/c.png"), result);

        assertEquals(5, vo.attachmentTotal());
        assertEquals(1024L, vo.attachmentBytes());
        assertEquals(2, vo.reusableCount());
        assertEquals(3, vo.pendingUploadCount());
        assertEquals(1, vo.postsAffected());
        assertEquals(1, vo.pagesAffected());
        assertEquals(3, vo.totalContentHits());
        assertEquals(1, vo.totalCoverHits());
        assertEquals(4, vo.postsScanned());
        assertEquals(3, vo.pagesScanned());
    }

    @Test
    @DisplayName("按处数倒序，处数相同按类型再按标题")
    void sortsByHitCount() {
        var result = new LinkRewriteService.PreviewResult(3, 0, 0, 0, List.of(
            impact("POST", "p1", "少", 1, 0, hits("/upload/a.png", 1)),
            impact("POST", "p2", "多", 5, 0, hits("/upload/a.png", 5)),
            impact("PAGE", "g1", "中", 2, 0, hits("/upload/a.png", 2))));

        PreviewVo vo = MigrationService.buildPreview("src", "tgt", 1, 0L, 0, List.of("/upload/a.png"),
            List.of("/upload/a.png"), result);

        assertEquals(List.of("多", "中", "少"), vo.items().stream().map(PreviewVo.ImpactVo::title).toList());
    }

    @Test
    @DisplayName("没有引用的附件单独计数，被引用数按去重后的地址算")
    void computesReferencedAttachments() {
        var result = new LinkRewriteService.PreviewResult(2, 1, 0, 0, List.of(
            impact("POST", "p1", "A", 2, 0, hits("/upload/a.png", 2)),
            impact("PAGE", "g1", "B", 1, 0, hits("/upload/a.png", 1))));

        PreviewVo vo = MigrationService.buildPreview("src", "tgt", 4, 0L, 0,
            List.of("/upload/a.png", "/upload/b.png", "/upload/c.png", "/upload/d.png"),
            List.of("/upload/a.png", "/upload/b.png", "/upload/c.png", "/upload/d.png"),
            result);

        assertEquals(1, vo.referencedAttachments());
        assertEquals(3, vo.unreferencedAttachments());
    }

    @Test
    @DisplayName("没有任何命中时 items 为空，只能搬文件")
    void emptyImpacts() {
        var result = new LinkRewriteService.PreviewResult(2, 2, 1, 3, List.of());

        PreviewVo vo = MigrationService.buildPreview("src", "tgt", 2, 100L, 2,
            List.of("/upload/a.png", "/upload/b.png"),
            List.of("/upload/a.png", "/upload/b.png"), result);

        assertTrue(vo.items().isEmpty());
        assertEquals(0, vo.postsAffected());
        assertEquals(0, vo.pagesAffected());
        assertEquals(0, vo.totalContentHits() + vo.totalCoverHits());
        assertEquals(0, vo.referencedAttachments());
        assertEquals(2, vo.unreferencedAttachments());
        assertEquals(1, vo.momentsScanned());
        assertEquals(3, vo.configMapsScanned());
    }

    @Test
    @DisplayName("瞬间 / 主题配置 / 系统设置各自计数，不再被算成页面")
    void countsExtraTargets() {
        var result = new LinkRewriteService.PreviewResult(1, 1, 2, 6, List.of(
            impact("POST", "p1", "文章", 1, 0, hits("/upload/a.png", 1)),
            impact("MOMENT", "moment-1", "含媒体的瞬间", 2, 0, hits("/upload/a.png", 2)),
            impact("THEME", "theme-hao-configMap", "主题配置（hao）", 2, 0,
                hits("/upload/a.png", 2)),
            impact("SETTING", "system", "系统设置", 1, 0, hits("/upload/a.png", 1))));

        PreviewVo vo = MigrationService.buildPreview("src", "tgt", 1, 0L, 0, List.of("/upload/a.png"),
            List.of("/upload/a.png"), result);

        assertEquals(1, vo.postsAffected());
        assertEquals(0, vo.pagesAffected());
        assertEquals(1, vo.momentsAffected());
        assertEquals(1, vo.themesAffected());
        assertEquals(1, vo.settingsAffected());
        assertEquals(4, vo.items().size());
    }

    @Test
    @DisplayName("用户头像单独计数，命中算进封面 / 头像合计")
    void countsUserAvatar() {
        var result = new LinkRewriteService.PreviewResult(1, 0, 0, 0, 0, 3, List.of(
            impact("USER", "admin", "八哥", 0, 1, hits("/upload/a.png", 1))));

        PreviewVo vo = MigrationService.buildPreview("src", "tgt", 1, 0L, 0, List.of("/upload/a.png"),
            List.of("/upload/a.png"), result);

        assertEquals(1, vo.usersAffected());
        assertEquals(3, vo.usersScanned());
        assertEquals(1, vo.totalCoverHits());
        assertEquals(0, vo.totalContentHits());
        assertEquals(1, vo.referencedAttachments());
    }
}
