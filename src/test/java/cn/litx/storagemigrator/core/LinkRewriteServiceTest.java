package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 链接改写的纯逻辑测试：替换必须落在 URL 边界上，不能命中更长地址里的子串。
 */
class LinkRewriteServiceTest {

    private static Map<String, String> mapping(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("Markdown / HTML 里的相对地址正常改写")
    void rewritesRelativeReferences() {
        String text = "<p><img src=\"/upload/attachments2/test-image.png\" /></p>"
            + "\n![test](/upload/attachments2/test-image.png)\n";
        String result = LinkRewriteService.applyMappings(text,
            mapping("/upload/attachments2/test-image.png",
                "https://litx.cn-nb1.rains3.com/80215383.png"));
        assertFalse(result.contains("/upload/attachments2/test-image.png"));
        assertEquals(2, countOf(result, "https://litx.cn-nb1.rains3.com/80215383.png"));
    }

    @Test
    @DisplayName("不污染第三方域名下相同路径的链接")
    void doesNotCorruptForeignHost() {
        String text = "还有直接链接 https://example.com/upload/attachments2/test-image.png 。";
        String result = LinkRewriteService.applyMappings(text,
            mapping("/upload/attachments2/test-image.png",
                "https://litx.cn-nb1.rains3.com/80215383.png"));
        assertEquals(text, result);
    }

    @Test
    @DisplayName("站点自身绝对地址整段替换，而不是拼成 example.comhttps://")
    void rewritesSiteAbsoluteUrl() {
        Map<String, String> expanded = LinkRewriteService.expandMapping(
            mapping("/upload/attachments2/test-image.png", "https://litx.cn-nb1.rains3.com/80215383.png"),
            "http://localhost:8090");

        assertEquals(2, expanded.size());
        String text = "<a href=\"http://localhost:8090/upload/attachments2/test-image.png\">下载</a>";
        String result = LinkRewriteService.applyMappings(text, expanded);
        assertEquals("<a href=\"https://litx.cn-nb1.rains3.com/80215383.png\">下载</a>", result);
    }

    @Test
    @DisplayName("缩略图查询参数保留在新地址后面")
    void keepsQueryString() {
        String text = "![t](/upload/attachments2/test-image.png?imageView2/0/w/120)";
        String result = LinkRewriteService.applyMappings(text,
            mapping("/upload/attachments2/test-image.png",
                "https://litx.cn-nb1.rains3.com/80215383.png"));
        assertEquals("![t](https://litx.cn-nb1.rains3.com/80215383.png?imageView2/0/w/120)", result);
    }

    @Test
    @DisplayName("长 key 优先：完整地址先替换，相对路径不会二次命中")
    void prefersLongestKey() {
        Map<String, String> map = mapping(
            "/upload/a.png", "/upload/b.png",
            "https://cdn.example.com/upload/a.png", "https://cdn.example.com/upload/b.png");
        String text = "x https://cdn.example.com/upload/a.png y /upload/a.png z";
        assertEquals("x https://cdn.example.com/upload/b.png y /upload/b.png z",
            LinkRewriteService.applyMappings(text, map));
    }

    @Test
    @DisplayName("文件名相近的不同文件不被误改")
    void doesNotTouchLookAlikeFile() {
        String text = "![x](/upload/attachments2/test-image.png.bak)";
        String result = LinkRewriteService.applyMappings(text,
            mapping("/upload/attachments2/test-image.png", "/upload/new.png"));
        assertEquals(text, result);
    }

    @Test
    @DisplayName("URL 编码过的中文文件名同样能改写")
    void rewritesEncodedPath() {
        String text = "![图](/upload/attachments2/%E4%B8%AD%E6%96%87.png)";
        String result = LinkRewriteService.applyMappings(text,
            mapping("/upload/attachments2/中文.png", "https://cdn.example.com/abc.png"));
        assertEquals("![图](https://cdn.example.com/abc.png)", result);
    }

    @Test
    @DisplayName("边界判断：紧贴域名的路径不是边界")
    void boundaryDetection() {
        String text = "https://example.com/upload/x.png";
        int start = text.indexOf("/upload/x.png");
        assertFalse(LinkRewriteService.isUrlBoundary(text, start, start + "/upload/x.png".length()));

        String standalone = "src=\"/upload/x.png\"";
        int at = standalone.indexOf("/upload/x.png");
        assertTrue(LinkRewriteService.isUrlBoundary(
            standalone, at, at + "/upload/x.png".length()));
    }

    // ---------- 预检计数 ----------

    @Test
    @DisplayName("预检：相对路径与站点绝对地址算同一处引用")
    void countMatchesMergesAbsoluteAndRelative() {
        String text = "<img src=\"/upload/a.png\" />\n![x](http://localhost:8090/upload/a.png)";
        Map<String, Integer> counts = LinkRewriteService.countMatches(
            text, java.util.List.of("/upload/a.png"), "http://localhost:8090");
        assertEquals(Map.of("/upload/a.png", 2), counts);
    }

    @Test
    @DisplayName("预检：第三方域名下的同路径不计入")
    void countMatchesIgnoresForeignHost() {
        String text = "https://example.com/upload/a.png";
        Map<String, Integer> counts = LinkRewriteService.countMatches(
            text, java.util.List.of("/upload/a.png"), "http://localhost:8090");
        assertTrue(counts.isEmpty());
    }

    @Test
    @DisplayName("预检：多个地址各自计数，未命中的不出现")
    void countMatchesPerUrl() {
        String text = "/upload/a.png /upload/a.png /upload/b.png";
        Map<String, Integer> counts = LinkRewriteService.countMatches(
            text, java.util.List.of("/upload/a.png", "/upload/b.png", "/upload/c.png"), null);
        assertEquals(2, counts.get("/upload/a.png"));
        assertEquals(1, counts.get("/upload/b.png"));
        assertFalse(counts.containsKey("/upload/c.png"));
    }

    @Test
    @DisplayName("预检：封面单独计数时只数一次")
    void countMatchesCoverString() {
        Map<String, Integer> counts = LinkRewriteService.countMatches(
            "/upload/attachments2/test-image.png", java.util.List.of("/upload/attachments2/test-image.png"), null);
        assertEquals(Map.of("/upload/attachments2/test-image.png", 1), counts);
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
