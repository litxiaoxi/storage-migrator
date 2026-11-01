package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.User;

/**
 * 设置项（ConfigMap）与瞬间内容改写的纯逻辑测试。
 */
class LinkRewriteExtraTargetsTest {

    private static Map<String, String> mapping(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> momentData(String raw, String html, String... mediaUrls) {
        List<Map<String, Object>> media = new ArrayList<>();
        for (String url : mediaUrls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("url", url);
            item.put("type", "PHOTO");
            media.add(item);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("raw", raw);
        content.put("html", html);
        content.put("medium", media);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("content", content);
        spec.put("approved", true);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("apiVersion", "moment.halo.run/v1alpha1");
        data.put("kind", "Moment");
        data.put("spec", spec);
        return data;
    }

    @Test
    @DisplayName("瞬间的 raw / html / media 都会被改写，其它字段原样保留")
    @SuppressWarnings("unchecked")
    void rewritesMomentFields() {
        Map<String, Object> data = momentData(
            "![pic](/upload/FzlMgAkU.webp)",
            "<video src=\"/upload/YdxoFJqw.mp4\"></video><img src=\"/upload/FzlMgAkU.webp\">",
            "/upload/FzlMgAkU.webp");
        Map<String, String> mapping = mapping(
            "/upload/FzlMgAkU.webp", "https://cdn.example.com/aaa.webp",
            "/upload/YdxoFJqw.mp4", "https://cdn.example.com/bbb.mp4");

        int hits = LinkRewriteService.rewriteMomentData(data, mapping);

        // FzlMgAkU 在 raw / html / media 里各一份，只算 1 处；YdxoFJqw 1 处
        assertEquals(2, hits);
        Map<String, Object> spec = (Map<String, Object>) data.get("spec");
        Map<String, Object> content = (Map<String, Object>) spec.get("content");
        assertEquals("![pic](https://cdn.example.com/aaa.webp)", content.get("raw"));
        assertEquals("<video src=\"https://cdn.example.com/bbb.mp4\"></video>"
            + "<img src=\"https://cdn.example.com/aaa.webp\">", content.get("html"));
        List<Map<String, Object>> media = (List<Map<String, Object>>) content.get("medium");
        assertEquals("https://cdn.example.com/aaa.webp", media.get(0).get("url"));
        assertEquals("PHOTO", media.get(0).get("type"));
        assertEquals(true, spec.get("approved"));
    }

    @Test
    @DisplayName("瞬间里没有旧地址时不做任何改动")
    void leavesMomentUntouched() {
        Map<String, Object> data = momentData("今天天气不错", "<p>今天天气不错</p>");
        int hits = LinkRewriteService.rewriteMomentData(data,
            mapping("/upload/FzlMgAkU.webp", "https://cdn.example.com/aaa.webp"));
        assertEquals(0, hits);
        assertTrue(LinkRewriteService.momentText(data).contains("今天天气不错"));
    }

    @Test
    @DisplayName("瞬间正文提取覆盖 raw、html 与媒体地址")
    void extractsMomentText() {
        Map<String, Object> data = momentData("raw-text", "<p>html-text</p>", "/upload/a.webp");
        String text = LinkRewriteService.momentText(data);
        assertTrue(text.contains("raw-text"));
        assertTrue(text.contains("html-text"));
        assertTrue(text.contains("/upload/a.webp"));
    }

    @Test
    @DisplayName("媒体字段写作 media 时同样能改（兼容不同版本）")
    @SuppressWarnings("unchecked")
    void handlesAltMediaKey() {
        Map<String, Object> data = momentData("", "");
        Map<String, Object> spec = (Map<String, Object>) data.get("spec");
        Map<String, Object> content = (Map<String, Object>) spec.get("content");
        List<Map<String, Object>> media = new ArrayList<>();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("url", "/upload/FzlMgAkU.webp");
        media.add(item);
        content.put("media", media);

        int hits = LinkRewriteService.rewriteMomentData(data,
            mapping("/upload/FzlMgAkU.webp", "https://cdn.example.com/aaa.webp"));

        assertEquals(1, hits);
        assertEquals("https://cdn.example.com/aaa.webp", media.get(0).get("url"));
        assertTrue(LinkRewriteService.momentText(data).contains("https://cdn.example.com/aaa.webp"));
    }

    @Test
    @DisplayName("ConfigMap 类型与标题可区分系统设置、主题配置、插件设置")
    void classifiesConfigMaps() {
        assertEquals(LinkRewriteService.TYPE_SETTING, LinkRewriteService.typeOfConfigMap("system"));
        assertEquals(LinkRewriteService.TYPE_THEME,
            LinkRewriteService.typeOfConfigMap("theme-hao-configMap"));
        assertEquals(LinkRewriteService.TYPE_SETTING,
            LinkRewriteService.typeOfConfigMap("plugin-moments-configmap"));

        assertEquals("系统设置", LinkRewriteService.configMapTitle("system"));
        assertEquals("主题配置（hao）", LinkRewriteService.configMapTitle("theme-hao-configMap"));
        assertEquals("插件设置（moments-configmap）",
            LinkRewriteService.configMapTitle("plugin-moments-configmap"));
    }

    @Test
    @DisplayName("替换处数统计与实际替换一致")
    void countsReplacements() {
        Map<String, String> mapping = mapping("/upload/x.png", "/upload/y.png");
        String text = "a /upload/x.png b /upload/x.png c https://other.com/upload/x.png";
        LinkRewriteService.Replacement replacement = LinkRewriteService.replace(text, mapping);
        assertEquals(2, replacement.count());
        assertEquals("a /upload/y.png b /upload/y.png c https://other.com/upload/x.png",
            replacement.text());
        assertFalse(replacement.text().contains("/upload/x.png\""));
    }

    @Test
    @DisplayName("用户头像的旧地址会被改写，昵称等其它字段不动")
    void rewritesUserAvatar() {
        User.UserSpec spec = new User.UserSpec();
        spec.setDisplayName("八哥");
        spec.setAvatar("/upload/FzlMgAkU.webp");

        int hits = LinkRewriteService.rewriteUserAvatar(spec,
            mapping("/upload/FzlMgAkU.webp", "https://cdn.example.com/avatar.webp"));

        assertEquals(1, hits);
        assertEquals("https://cdn.example.com/avatar.webp", spec.getAvatar());
        assertEquals("八哥", spec.getDisplayName());
    }

    @Test
    @DisplayName("头像没有被引用时保持不变，空头像不会报错")
    void leavesUserAvatarUntouched() {
        Map<String, String> mapping = mapping("/upload/FzlMgAkU.webp",
            "https://cdn.example.com/avatar.webp");

        User.UserSpec other = new User.UserSpec();
        other.setAvatar("/upload/other.webp");
        assertEquals(0, LinkRewriteService.rewriteUserAvatar(other, mapping));
        assertEquals("/upload/other.webp", other.getAvatar());

        User.UserSpec empty = new User.UserSpec();
        assertEquals(0, LinkRewriteService.rewriteUserAvatar(empty, mapping));
        assertEquals(0, LinkRewriteService.rewriteUserAvatar(null, mapping));
    }
}
