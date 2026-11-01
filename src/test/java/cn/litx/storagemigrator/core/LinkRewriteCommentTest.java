package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.content.Comment;

/**
 * 评论 / 回复正文的链接改写：raw（Markdown 原文）与 content（渲染后的 HTML）都要处理。
 */
class LinkRewriteCommentTest {

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

    @Test
    @DisplayName("raw 与 content 都会被改写，同一张图只算 1 处引用")
    void rewritesBothFields() {
        Comment.CommentSpec spec = spec(
            "看图 ![pic](/upload/a.webp)",
            "<p>看图 <img src=\"/upload/a.webp\"></p>");

        int hits = LinkRewriteService.rewriteCommentContent(spec,
            mapping("/upload/a.webp", "https://cdn.example.com/a.webp"));

        assertEquals(1, hits);
        assertEquals("看图 ![pic](https://cdn.example.com/a.webp)", spec.getRaw());
        assertEquals("<p>看图 <img src=\"https://cdn.example.com/a.webp\"></p>", spec.getContent());
        assertEquals(true, spec.getApproved());
        assertEquals(false, spec.getTop());
    }

    @Test
    @DisplayName("绝对地址引用同样能改（旧站整段换成新地址）")
    void rewritesAbsoluteUrl() {
        Comment.CommentSpec spec = spec("",
            "<img src=\"https://old.example.com/upload/b.webp\">");

        int hits = LinkRewriteService.rewriteCommentContent(spec,
            mapping("https://old.example.com/upload/b.webp", "/upload/b.webp"));

        assertEquals(1, hits);
        assertEquals("<img src=\"/upload/b.webp\">", spec.getContent());
    }

    @Test
    @DisplayName("没有旧地址时不做任何改动")
    void leavesUntouched() {
        Comment.CommentSpec spec = spec("今天不错", "<p>今天不错</p>");
        assertEquals(0, LinkRewriteService.rewriteCommentContent(spec,
            mapping("/upload/a.webp", "/upload/new.webp")));
        assertEquals("今天不错", spec.getRaw());
        assertEquals("<p>今天不错</p>", spec.getContent());
        assertEquals(0, LinkRewriteService.countCommentHits(spec,
            mapping("/upload/a.webp", "/upload/new.webp")));
    }

    @Test
    @DisplayName("正文提取覆盖 raw 与 content，标题是去掉标签后的摘要")
    void buildsTextAndTitle() {
        Comment.CommentSpec spec = spec("**加了图片**", "<p><strong>加了图片</strong></p>");
        String text = LinkRewriteService.commentText(spec);
        assertTrue(text.contains("**加了图片**"));
        assertTrue(text.contains("<p><strong>加了图片</strong></p>"));

        String title = LinkRewriteService.commentTitle(spec);
        assertFalse(title.contains("<p>"));
        assertFalse(title.contains("**"));
        assertTrue(title.startsWith("加了图片"));
    }

    @Test
    @DisplayName("空评论与空 spec 不报错")
    void handlesEmpty() {
        assertEquals(0, LinkRewriteService.rewriteCommentContent(null, Map.of("a", "b")));
        assertEquals(0, LinkRewriteService.countCommentHits(null, Map.of("a", "b")));
        assertEquals("评论", LinkRewriteService.commentTitle(spec("", "")));
        assertEquals(null, LinkRewriteService.commentText(null));
    }

    @Test
    @DisplayName("超长评论摘要会截断，避免预检列表被刷屏")
    void truncatesLongTitle() {
        String longText = "这是一条很长的评论".repeat(10);
        String title = LinkRewriteService.commentTitle(spec(longText, ""));
        assertTrue(title.endsWith("…"));
        assertTrue(title.length() <= 25);
    }
}
