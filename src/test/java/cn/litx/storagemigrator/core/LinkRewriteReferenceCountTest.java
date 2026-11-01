package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.content.Comment;

/**
 * 引用处数统计：raw（Markdown 原文）与 content / html（渲染结果）是同一份内容的两种表示，
 * 同一张图在两边各出现一次，只能算一处引用。
 */
class LinkRewriteReferenceCountTest {

    private static final String URL = "/upload/comments/lFAwEweL.jpg";

    private static final String IMG =
        "<img src=\"http://localhost:8090/upload/comments/lFAwEweL.jpg\""
            + " alt=\"lFAwEweL.jpg\" title=\"comment-next-image:\"> ";

    private static Map<String, String> mapping(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Comment.CommentSpec spec(String raw, String content) {
        Comment.CommentSpec spec = new Comment.CommentSpec();
        spec.setRaw(raw);
        spec.setContent(content);
        spec.setApproved(true);
        spec.setTop(false);
        return spec;
    }

    private static int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    @DisplayName("评论 raw 与 content 各有一份同一张图，只算 1 处")
    void countsCommentOnce() {
        Comment.CommentSpec spec = spec(IMG, IMG);

        assertEquals(1, total(LinkRewriteService.countReferences(
            LinkRewriteService.commentParts(spec), List.of(URL), null)));
        assertEquals(1, LinkRewriteService.countCommentHits(spec,
            mapping(URL, "/upload/NEW.jpg")));
    }

    @Test
    @DisplayName("同一张图在一份内容里出现两次，算 2 处")
    void countsDuplicatesInsideOneField() {
        Comment.CommentSpec spec = spec(IMG + IMG, IMG + IMG);

        assertEquals(2, total(LinkRewriteService.countReferences(
            LinkRewriteService.commentParts(spec), List.of(URL), null)));
    }

    @Test
    @DisplayName("rewriteCommentContent 仍改两份表示，但只报 1 处")
    void rewritesBothButReportsOne() {
        Comment.CommentSpec spec = spec(IMG, IMG);

        int hits = LinkRewriteService.rewriteCommentContent(spec,
            mapping(URL, "/upload/NEW.jpg"));

        assertEquals(1, hits);
        // 站内绝对地址会连域名端口一起换成新地址（新地址是相对路径）
        String expected = IMG.replace("http://localhost:8090" + URL, "/upload/NEW.jpg");
        assertEquals(expected, spec.getRaw());
        assertEquals(expected, spec.getContent());
    }

    @Test
    @DisplayName("文章 raw 与 html 各一份，也只算 1 处")
    void countsPostOnce() {
        List<String> texts = List.of(
            "![图](/upload/a.png)",
            "<p><img src=\"/upload/a.png\" alt=\"图\"></p>");

        assertEquals(1, total(LinkRewriteService.countReferences(
            texts, List.of("/upload/a.png"), null)));
    }

    @Test
    @DisplayName("只在渲染结果里出现（raw 为空）也算 1 处")
    void countsHtmlOnly() {
        List<String> texts = List.of("", "<img src=\"/upload/a.png\">");

        assertEquals(1, total(LinkRewriteService.countReferences(
            texts, List.of("/upload/a.png"), null)));
    }

    @Test
    @DisplayName("瞬间 raw、html、媒体地址指向同一张图时只算 1 处")
    void countsMomentParts() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("raw", "![图](/upload/a.png)");
        content.put("html", "<img src=\"/upload/a.png\">");
        content.put("media", List.of(Map.of("url", "/upload/a.png")));
        Map<String, Object> data = Map.of("spec", Map.of("content", content));

        assertEquals(1, total(LinkRewriteService.countReferences(
            LinkRewriteService.momentParts(data), List.of("/upload/a.png"), null)));
    }
}
