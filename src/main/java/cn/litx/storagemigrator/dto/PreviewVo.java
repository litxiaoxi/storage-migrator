package cn.litx.storagemigrator.dto;

import java.util.List;

/**
 * 迁移前的影响面预检结果（只读，不落库）。
 *
 * @param sourcePolicy 源策略名
 * @param targetPolicy 目标策略名
 * @param attachmentTotal 源策略下的附件数
 * @param attachmentBytes 源策略附件总字节数
 * @param reusableCount 目标策略里已有对应副本、可直接复用的数量
 * @param pendingUploadCount 需要真正上传的数量
 * @param referencedAttachments 被文章 / 页面 / 设置 / 瞬间引用到的源附件数
 * @param unreferencedAttachments 没有任何地方引用的源附件数
 * @param postsScanned 扫描的文章数
 * @param pagesScanned 扫描的独立页面数
 * @param momentsScanned 扫描的瞬间数
 * @param commentsScanned 扫描的评论数（含回复）
 * @param configMapsScanned 扫描的设置项（ConfigMap）数
 * @param usersScanned 扫描的用户数
 * @param postsAffected 会被改动的文章数
 * @param pagesAffected 会被改动的独立页面数
 * @param momentsAffected 会被改动的瞬间数
 * @param commentsAffected 会被改动的评论数
 * @param themesAffected 会被改动的主题配置数
 * @param settingsAffected 会被改动的系统 / 插件设置数
 * @param usersAffected 会被改动的用户头像数
 * @param totalContentHits 正文 / 配置内容合计替换处数
 * @param totalCoverHits 封面 / 头像合计替换处数
 * @param items 受影响条目，按处数降序
 */
public record PreviewVo(String sourcePolicy,
                        String targetPolicy,
                        int attachmentTotal,
                        long attachmentBytes,
                        int reusableCount,
                        int pendingUploadCount,
                        int referencedAttachments,
                        int unreferencedAttachments,
                        int postsScanned,
                        int pagesScanned,
                        int momentsScanned,
                        int commentsScanned,
                        int configMapsScanned,
                        int usersScanned,
                        int postsAffected,
                        int pagesAffected,
                        int momentsAffected,
                        int commentsAffected,
                        int themesAffected,
                        int settingsAffected,
                        int usersAffected,
                        int totalContentHits,
                        int totalCoverHits,
                        List<ImpactVo> items) {

    /**
     * 单条内容的影响面。
     *
     * @param type POST / PAGE / MOMENT / COMMENT / THEME / SETTING / USER
     * @param name metadata.name，便于跳转控制台
     * @param title 标题
     * @param contentHits 正文 / 配置内容里会被替换的处数
     * @param coverHits 封面 / 头像里会被替换的处数
     * @param hits 每个旧地址的命中次数
     */
    public record ImpactVo(String type, String name, String title,
                           int contentHits, int coverHits, List<HitVo> hits) {
    }

    /**
     * 单个旧地址的命中次数。
     *
     * @param url 旧地址
     * @param count 命中次数
     */
    public record HitVo(String url, int count) {
    }
}
