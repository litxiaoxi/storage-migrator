package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「站点绝对地址 + 相对路径」的匹配：内容里常写 {@code http://localhost:8090/upload/x.png}，
 * 而映射里只有相对路径，必须算成同一处引用；第三方域名下的同路径不能误改。
 */
class LinkRewriteOriginTest {

    private static Map<String, String> mapping(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("localhost 站点地址 + 相对路径会被整段替换")
    void rewritesLocalhostAbsoluteUrl() {
        String text = "<img src=\"http://localhost:8090/upload/comments/upyPuIEM.jpg\" alt=\"x\">";
        LinkRewriteService.Replacement replacement = LinkRewriteService.replace(text,
            mapping("/upload/comments/upyPuIEM.jpg", "/upload/RASRLSKr.jpg"));

        assertEquals(1, replacement.count());
        assertEquals("<img src=\"/upload/RASRLSKr.jpg\" alt=\"x\">", replacement.text());
    }

    @Test
    @DisplayName("127.0.0.1 带任意端口、https、IPv6 都算本机地址")
    void recognizesLocalOrigins() {
        assertTrue(LinkRewriteService.isSiteOrigin("http://localhost:8090"));
        assertTrue(LinkRewriteService.isSiteOrigin("http://127.0.0.1:8090"));
        assertTrue(LinkRewriteService.isSiteOrigin("https://localhost"));
        assertTrue(LinkRewriteService.isSiteOrigin("http://[::1]:8090"));
        assertFalse(LinkRewriteService.isSiteOrigin("https://example.com"));
        assertFalse(LinkRewriteService.isSiteOrigin("https://litx.cn"));
        assertFalse(LinkRewriteService.isSiteOrigin("/"));
        assertFalse(LinkRewriteService.isSiteOrigin(null));
    }

    @Test
    @DisplayName("相对路径本身（不带域名）照常替换")
    void rewritesRelativePath() {
        LinkRewriteService.Replacement replacement = LinkRewriteService.replace(
            "![pic](/upload/comments/a.jpg)",
            mapping("/upload/comments/a.jpg", "/upload/b.jpg"));

        assertEquals(1, replacement.count());
        assertEquals("![pic](/upload/b.jpg)", replacement.text());
    }

    @Test
    @DisplayName("第三方域名下的同路径不动，避免改坏别人的图床")
    void keepsThirdPartySamePath() {
        String text = "还有直接链接 https://example.com/upload/a.png 。";
        LinkRewriteService.Replacement replacement = LinkRewriteService.replace(text,
            mapping("/upload/a.png", "/upload/b.png"));

        assertEquals(0, replacement.count());
        assertEquals(text, replacement.text());
    }

    @Test
    @DisplayName("预检统计口径与改写一致：本地域名写法能被数出来")
    void countsMatchesSameAsRewrite() {
        List<String> urls = List.of("/upload/comments/upyPuIEM.jpg");
        Map<String, Integer> hits = LinkRewriteService.countMatches(
            "<img src=\"http://localhost:8090/upload/comments/upyPuIEM.jpg\">", urls, null);

        assertEquals(1, hits.get("/upload/comments/upyPuIEM.jpg"));
    }

    @Test
    @DisplayName("命中位置后面还接着路径字符时不误命中（a.png.bak 不算 a.png）")
    void rejectsLongerPathSuffix() {
        String text = "http://localhost:8090/upload/a.png.bak";
        assertEquals(0, LinkRewriteService.replace(text,
            mapping("/upload/a.png", "/upload/b.png")).count());
    }

    @Test
    @DisplayName("非本机域名的同路径不误命中")
    void rejectsOtherHost() {
        String text = "http://evil.com:8090/upload/a.png";
        assertEquals(0, LinkRewriteService.replace(text,
            mapping("/upload/a.png", "/upload/b.png")).count());
    }

    @Test
    @DisplayName("同一段里既有本地域名写法也有相对写法时都替换")
    void rewritesMixedOccurrences() {
        String text = "<img src=\"/upload/a.png\"><img src=\"http://localhost:8090/upload/a.png\">";
        LinkRewriteService.Replacement replacement = LinkRewriteService.replace(text,
            mapping("/upload/a.png", "/upload/b.png"));

        assertEquals(2, replacement.count());
        assertEquals("<img src=\"/upload/b.png\"><img src=\"/upload/b.png\">", replacement.text());
    }
}
