package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 用户头像绑定：Halo 以 {@code halo.run/avatar-attachment-name} 注解为准，
 * 迁移后必须把注解指向新附件，否则 UserReconciler 永远拿不到 permalink。
 */
class MigrationAvatarBindingTest {

    private static final String AVATAR_ANNO = "halo.run/avatar-attachment-name";
    private static final String LAST_AVATAR_ANNO = "halo.run/last-avatar-attachment-name";

    private static Map<String, String> annotations(String name) {
        Map<String, String> map = new HashMap<>();
        map.put(AVATAR_ANNO, name);
        map.put(LAST_AVATAR_ANNO, name);
        map.put("rbac.authorization.halo.run/role-names", "[\"super-role\"]");
        return map;
    }

    @Test
    @DisplayName("头像注解指向源附件时改到新附件，其它注解不动")
    void remapsAvatarBinding() {
        Map<String, String> annotations = annotations("old-attachment");
        int hits = MigrationService.remapAvatarAnnotations(annotations,
            Map.of("old-attachment", "new-attachment"));

        assertEquals(2, hits);
        assertEquals("new-attachment", annotations.get(AVATAR_ANNO));
        assertEquals("new-attachment", annotations.get(LAST_AVATAR_ANNO));
        assertEquals("[\"super-role\"]", annotations.get("rbac.authorization.halo.run/role-names"));
    }

    @Test
    @DisplayName("头像没参与迁移时保持原样")
    void leavesUnrelatedAvatar() {
        Map<String, String> annotations = annotations("other-attachment");
        int hits = MigrationService.remapAvatarAnnotations(annotations,
            Map.of("old-attachment", "new-attachment"));

        assertEquals(0, hits);
        assertEquals("other-attachment", annotations.get(AVATAR_ANNO));
    }

    @Test
    @DisplayName("没有头像、注解为空、映射为空都不报错")
    void handlesEmptyInputs() {
        Map<String, String> noAvatar = new HashMap<>();
        noAvatar.put("rbac.authorization.halo.run/role-names", "[]");
        assertEquals(0, MigrationService.remapAvatarAnnotations(noAvatar,
            Map.of("old", "new")));
        assertFalse(noAvatar.containsKey(AVATAR_ANNO));

        assertEquals(0, MigrationService.remapAvatarAnnotations(null, Map.of("old", "new")));
        assertEquals(0, MigrationService.remapAvatarAnnotations(new HashMap<>(), Map.of()));
        assertTrue(new HashMap<String, String>().isEmpty());
    }

    @Test
    @DisplayName("只迁移了其中一个头像注解时也按各自的值改写")
    void remapsOnlyMatchingAnnotation() {
        Map<String, String> annotations = new HashMap<>();
        annotations.put(AVATAR_ANNO, "old-1");
        annotations.put(LAST_AVATAR_ANNO, "old-2");

        int hits = MigrationService.remapAvatarAnnotations(annotations,
            Map.of("old-1", "new-1", "old-2", "new-2"));

        assertEquals(2, hits);
        assertEquals("new-1", annotations.get(AVATAR_ANNO));
        assertEquals("new-2", annotations.get(LAST_AVATAR_ANNO));
    }
}
