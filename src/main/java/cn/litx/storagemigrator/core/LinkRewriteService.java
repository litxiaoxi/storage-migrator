package cn.litx.storagemigrator.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.content.PatchUtils;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.core.extension.content.Snapshot;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionUtil;
import run.halo.app.extension.GroupVersionKind;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.Unstructured;
import run.halo.app.infra.ExternalUrlSupplier;

/**
 * 链接引用改写。
 *
 * <p>Halo 的文章 / 独立页面正文并不直接存在 Post 上，而是存放在 Snapshot 的 patch 链里：
 * baseSnapshot 的 rawPatch / contentPatch 就是全文，其余快照存的是相对 base 的 diff。
 * 因此改写流程为：还原全文 -> 字符串替换 -> 重新对 base 求 diff。</p>
 *
 * <p>除正文与封面外，以下位置同样可能引用旧地址，一并处理：</p>
 * <ul>
 *   <li>系统设置 / 插件设置 / 主题配置：都存放在 ConfigMap 的 data 里（JSON 字符串）；</li>
 *   <li>瞬间：由 moments 插件提供，扩展类型为 {@code moment.halo.run/v1alpha1 Moment}，
 *       内容在 {@code spec.content.raw} / {@code spec.content.html} / {@code spec.content.medium[].url}。</li>
 *   <li>用户头像：{@code User} 的 {@code spec.avatar}（可能是相对路径，也可能是完整地址）。</li>
 * </ul>
 */
@Component
public class LinkRewriteService {

    private static final Logger log = LoggerFactory.getLogger(LinkRewriteService.class);

    /** 影响面 / 改写目标的类型标识。 */
    public static final String TYPE_POST = "POST";
    public static final String TYPE_PAGE = "PAGE";
    public static final String TYPE_MOMENT = "MOMENT";
    public static final String TYPE_COMMENT = "COMMENT";
    public static final String TYPE_THEME = "THEME";
    public static final String TYPE_SETTING = "SETTING";
    public static final String TYPE_USER = "USER";

    /** moments 插件的扩展类型；插件未安装时查不到 scheme，直接跳过。 */
    private static final GroupVersionKind MOMENT_GVK =
        new GroupVersionKind("moment.halo.run", "v1alpha1", "Moment");

    /** 存储策略自身的 ConfigMap（endpoint / 密钥），不参与链接改写。 */
    private static final String POLICY_OWNER_LABEL = "storage.halo.run/policy-owner";

    /**
     * 瞬间内容里媒体数组的字段名。
     *
     * <p>moments 插件实际用的是 {@code medium}（{@code spec.content.medium[].url}），
     * 但文档与早期版本里常见 {@code media}，两个都试一遍。</p>
     */
    private static final String MEDIA_KEY = "medium";
    private static final String ALT_MEDIA_KEY = "media";

    private static final String THEME_CONFIG_SUFFIX = "-configMap";

    private final ReactiveExtensionClient client;
    private final ObjectProvider<ExternalUrlSupplier> externalUrlSupplier;
    private final ObjectProvider<SchemeManager> schemeManager;
    private final String configuredExternalUrl;

    public LinkRewriteService(ReactiveExtensionClient client,
                              ObjectProvider<ExternalUrlSupplier> externalUrlSupplier,
                              ObjectProvider<SchemeManager> schemeManager,
                              @Value("${halo.external-url:}") String configuredExternalUrl) {
        this.client = client;
        this.externalUrlSupplier = externalUrlSupplier;
        this.schemeManager = schemeManager;
        this.configuredExternalUrl = configuredExternalUrl;
    }

    /**
     * 改写结果。
     *
     * @param postsScanned 扫描的文章数
     * @param postsUpdated 改动的文章数
     * @param pagesScanned 扫描的独立页面数
     * @param pagesUpdated 改动的独立页面数
     * @param snapshotsUpdated 改动的快照数
     * @param momentsScanned 扫描的瞬间数
     * @param momentsUpdated 改动的瞬间数
     * @param configMapsScanned 扫描的设置项（ConfigMap）数
     * @param configMapsUpdated 改动的设置项数
     * @param commentsScanned 扫描的评论（含回复）数
     * @param commentsUpdated 改动的评论数
     * @param usersScanned 扫描的用户数
     * @param usersUpdated 改动的用户头像数
     * @param details 处理明细
     */
    public record RewriteResult(int postsScanned,
                                int postsUpdated,
                                int pagesScanned,
                                int pagesUpdated,
                                int snapshotsUpdated,
                                int momentsScanned,
                                int momentsUpdated,
                                int configMapsScanned,
                                int configMapsUpdated,
                                int commentsScanned,
                                int commentsUpdated,
                                int usersScanned,
                                int usersUpdated,
                                List<String> details) {

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("文章 ").append(postsScanned).append(" 篇（改动 ").append(postsUpdated).append("）");
            sb.append("，独立页面 ").append(pagesScanned).append(" 个（改动 ").append(pagesUpdated).append("）");
            if (momentsScanned > 0) {
                sb.append("，瞬间 ").append(momentsScanned).append(" 条（改动 ")
                    .append(momentsUpdated).append("）");
            }
            if (configMapsScanned > 0) {
                sb.append("，设置项 ").append(configMapsScanned).append(" 个（改动 ")
                    .append(configMapsUpdated).append("）");
            }
            if (commentsScanned > 0) {
                sb.append("，评论 ").append(commentsScanned).append(" 条（改动 ")
                    .append(commentsUpdated).append("）");
            }
            if (usersScanned > 0) {
                sb.append("，用户头像 ").append(usersScanned).append(" 个（改动 ")
                    .append(usersUpdated).append("）");
            }
            sb.append("，快照改动 ").append(snapshotsUpdated).append(" 个");
            return sb.toString();
        }
    }

    /**
     * 单条内容的影响面。
     *
     * @param type 类型：POST / PAGE / MOMENT / THEME / SETTING / USER
     * @param name metadata.name
     * @param title 标题（文章标题、设置名称、瞬间摘要、用户昵称）
     * @param contentHits 正文 / 配置内容里会被替换的处数
     * @param coverHits 封面 / 头像里会被替换的处数（文章、页面、用户）
     * @param hits 每个旧地址各命中几次
     */
    public record Impact(String type, String name, String title, int contentHits, int coverHits,
                         Map<String, Integer> hits) {
    }

    /**
     * 预检结果。
     *
     * @param postsScanned 扫描的文章数
     * @param pagesScanned 扫描的独立页面数
     * @param momentsScanned 扫描的瞬间数
     * @param commentsScanned 扫描的评论（含回复）数
     * @param configMapsScanned 扫描的设置项数
     * @param usersScanned 扫描的用户数
     * @param impacts 会被改动的条目（没有命中的不返回）
     */
    public record PreviewResult(int postsScanned, int pagesScanned, int momentsScanned,
                                int commentsScanned, int configMapsScanned, int usersScanned,
                                List<Impact> impacts) {

        /** 兼容不关心评论 / 用户头像统计的调用方。 */
        public PreviewResult(int postsScanned, int pagesScanned, int momentsScanned,
                             int configMapsScanned, List<Impact> impacts) {
            this(postsScanned, pagesScanned, momentsScanned, 0, configMapsScanned, 0, impacts);
        }
    }

    /**
     * 文本替换结果。
     *
     * @param text 替换后的文本
     * @param count 实际替换的处数
     */
    record Replacement(String text, int count) {
    }

    /**
     * 按映射改写所有引用位置。
     *
     * @param rawMapping 旧地址 -> 新地址
     * @param dryRun 只统计不落库
     * @return 改写结果
     */
    public Mono<RewriteResult> rewrite(Map<String, String> rawMapping, boolean dryRun) {
        Map<String, String> mapping = expandMapping(normalize(rawMapping));
        if (mapping.isEmpty()) {
            return Mono.just(new RewriteResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                List.of("映射为空，未执行任何改写")));
        }
        List<String> details = new ArrayList<>();

        Flux<Post> posts = client.list(Post.class, p -> !isTrue(p.getSpec().getDeleted()), nameComparator());
        Flux<SinglePage> pages = client.list(SinglePage.class, p -> !isTrue(p.getSpec().getDeleted()),
            nameComparator());

        var postsScanned = new AtomicInteger();
        var postsUpdated = new AtomicInteger();
        var pagesScanned = new AtomicInteger();
        var pagesUpdated = new AtomicInteger();
        var snapshotsUpdated = new AtomicInteger();
        var momentsScanned = new AtomicInteger();
        var momentsUpdated = new AtomicInteger();
        var configMapsScanned = new AtomicInteger();
        var configMapsUpdated = new AtomicInteger();
        var commentsScanned = new AtomicInteger();
        var commentsUpdated = new AtomicInteger();
        var usersScanned = new AtomicInteger();
        var usersUpdated = new AtomicInteger();

        Mono<Void> postSeq = posts.concatMap(post -> {
            postsScanned.incrementAndGet();
            return rewriteContent(post.getMetadata().getName(),
                post.getSpec().getBaseSnapshot(), post.getSpec().getHeadSnapshot(),
                post.getSpec().getReleaseSnapshot(), mapping, dryRun)
                // 封面独立判断：正文没有引用旧地址时，封面也可能引用了
                .flatMap(contentChanges -> updateCover(post, mapping, dryRun)
                    .map(coverChanges -> new int[] {contentChanges, coverChanges}))
                .doOnNext(counts -> {
                    if (counts[0] > 0) {
                        snapshotsUpdated.addAndGet(counts[0]);
                    }
                    if (counts[0] > 0 || counts[1] > 0) {
                        postsUpdated.incrementAndGet();
                    }
                })
                .doOnError(e -> log.warn("改写文章 {} 失败", post.getMetadata().getName(), e))
                .onErrorResume(e -> Mono.just(new int[] {0, 0}))
                .then();
        }).then();

        Mono<Void> pageSeq = pages.concatMap(page -> {
            pagesScanned.incrementAndGet();
            return rewriteContent(page.getMetadata().getName(),
                page.getSpec().getBaseSnapshot(), page.getSpec().getHeadSnapshot(),
                page.getSpec().getReleaseSnapshot(), mapping, dryRun)
                .flatMap(contentChanges -> updatePageCover(page, mapping, dryRun)
                    .map(coverChanges -> new int[] {contentChanges, coverChanges}))
                .doOnNext(counts -> {
                    if (counts[0] > 0) {
                        snapshotsUpdated.addAndGet(counts[0]);
                    }
                    if (counts[0] > 0 || counts[1] > 0) {
                        pagesUpdated.incrementAndGet();
                    }
                })
                .doOnError(e -> log.warn("改写独立页面 {} 失败", page.getMetadata().getName(), e))
                .onErrorResume(e -> Mono.just(new int[] {0, 0}))
                .then();
        }).then();

        Mono<Void> configSeq = configMaps()
            .concatMap(configMap -> {
                configMapsScanned.incrementAndGet();
                return rewriteConfigMap(configMap, mapping, dryRun)
                    .doOnNext(hits -> {
                        if (hits > 0) {
                            configMapsUpdated.incrementAndGet();
                            details.add("设置项 " + configMap.getMetadata().getName()
                                + " 改写 " + hits + " 处");
                        }
                    })
                    .doOnError(e -> log.warn("改写设置项 {} 失败", configMap.getMetadata().getName(), e))
                    .onErrorResume(e -> Mono.just(0))
                    .then();
            })
            .then();

        Mono<Void> momentSeq = moments()
            .concatMap(unstructured -> {
                momentsScanned.incrementAndGet();
                return rewriteMoment(unstructured, mapping, dryRun)
                    .doOnNext(hits -> {
                        if (hits > 0) {
                            momentsUpdated.incrementAndGet();
                            details.add("瞬间 " + unstructured.getMetadata().getName()
                                + " 改写 " + hits + " 处");
                        }
                    })
                    .doOnError(e -> log.warn("改写瞬间 {} 失败",
                        unstructured.getMetadata().getName(), e))
                    .onErrorResume(e -> Mono.just(0))
                    .then();
            })
            .then();

        Mono<Void> commentSeq = rewriteComments(Comment.class, mapping, dryRun,
            commentsScanned, commentsUpdated, details, "评论");
        Mono<Void> replySeq = rewriteComments(Reply.class, mapping, dryRun,
            commentsScanned, commentsUpdated, details, "回复");

        Mono<Void> userSeq = users()
            .concatMap(user -> {
                usersScanned.incrementAndGet();
                return rewriteUser(user, mapping, dryRun)
                    .doOnNext(hits -> {
                        if (hits > 0) {
                            usersUpdated.incrementAndGet();
                            details.add("用户头像 " + userTitle(user) + " 改写 " + hits + " 处");
                        }
                    })
                    .doOnError(e -> log.warn("改写用户 {} 的头像失败",
                        user.getMetadata().getName(), e))
                    .onErrorResume(e -> Mono.just(0))
                    .then();
            })
            .then();

        return postSeq.then(pageSeq).then(configSeq).then(momentSeq).then(commentSeq).then(replySeq)
            .then(userSeq)
            .then(Mono.fromSupplier(() -> {
                if (dryRun) {
                    details.add("[预演] 未写入任何更改");
                }
                return new RewriteResult(postsScanned.get(), postsUpdated.get(), pagesScanned.get(),
                    pagesUpdated.get(), snapshotsUpdated.get(), momentsScanned.get(),
                    momentsUpdated.get(), configMapsScanned.get(), configMapsUpdated.get(),
                    commentsScanned.get(), commentsUpdated.get(),
                    usersScanned.get(), usersUpdated.get(), details);
            }));
    }

    /**
     * 预检：统计这批旧地址会改到哪些内容、各改多少处，全程只读。
     *
     * <p>统计口径与真实改写完全一致（同样的 URL 边界判断、站点绝对地址展开、URL 编码变体），
     * 所以「预检说改 3 处」和「执行后改了 3 处」是一回事。</p>
     *
     * @param oldUrls 待替换的旧地址（源策略附件的 permalink）
     * @return 扫描数与受影响的条目
     */
    public Mono<PreviewResult> preview(Collection<String> oldUrls) {
        List<String> urls = oldUrls == null ? List.of()
            : oldUrls.stream().filter(u -> u != null && !u.isBlank()).distinct().toList();
        if (urls.isEmpty()) {
            return Mono.just(new PreviewResult(0, 0, 0, 0, 0, 0, List.of()));
        }
        String origin = origin();
        var postsScanned = new AtomicInteger();
        var pagesScanned = new AtomicInteger();
        var momentsScanned = new AtomicInteger();
        var commentsScanned = new AtomicInteger();
        var configMapsScanned = new AtomicInteger();
        var usersScanned = new AtomicInteger();

        Mono<List<Impact>> posts = client.list(Post.class,
                p -> !isTrue(p.getSpec().getDeleted()), nameComparator())
            .concatMap(post -> {
                postsScanned.incrementAndGet();
                return impactOf(TYPE_POST, post.getMetadata().getName(), post.getSpec().getTitle(),
                    post.getSpec().getBaseSnapshot(), post.getSpec().getHeadSnapshot(),
                    post.getSpec().getCover(), urls, origin);
            })
            .collectList();

        Mono<List<Impact>> pages = client.list(SinglePage.class,
                p -> !isTrue(p.getSpec().getDeleted()), nameComparator())
            .concatMap(page -> {
                pagesScanned.incrementAndGet();
                return impactOf(TYPE_PAGE, page.getMetadata().getName(), page.getSpec().getTitle(),
                    page.getSpec().getBaseSnapshot(), page.getSpec().getHeadSnapshot(),
                    page.getSpec().getCover(), urls, origin);
            })
            .collectList();

        Mono<List<Impact>> configs = configMaps()
            .concatMap(configMap -> {
                configMapsScanned.incrementAndGet();
                String name = configMap.getMetadata().getName();
                String text = joinValues(configMap.getData());
                return impactOfText(typeOfConfigMap(name), name, configMapTitle(name), text,
                    null, urls, origin);
            })
            .collectList();

        Mono<List<Impact>> moments = moments()
            .concatMap(unstructured -> {
                momentsScanned.incrementAndGet();
                String name = unstructured.getMetadata().getName();
                return impactOfTexts(TYPE_MOMENT, name, momentTitle(unstructured.getData()),
                    momentParts(unstructured.getData()), null, urls, origin);
            })
            .collectList();

        Mono<List<Impact>> userImpacts = users()
            .concatMap(user -> {
                usersScanned.incrementAndGet();
                var spec = user.getSpec();
                String avatar = spec == null ? null : spec.getAvatar();
                return impactOfText(TYPE_USER, user.getMetadata().getName(), userTitle(user),
                    null, avatar, urls, origin);
            })
            .collectList();

        Mono<List<Impact>> commentImpacts = previewComments(Comment.class, urls, origin, commentsScanned)
            .zipWith(previewComments(Reply.class, urls, origin, commentsScanned))
            .map(tuple -> {
                List<Impact> impacts = new ArrayList<>(tuple.getT1());
                impacts.addAll(tuple.getT2());
                return impacts;
            });

        return posts.zipWith(pages).map(tuple -> {
            List<Impact> impacts = new ArrayList<>(tuple.getT1());
            impacts.addAll(tuple.getT2());
            return impacts;
        }).zipWith(configs).map(tuple -> {
            List<Impact> impacts = new ArrayList<>(tuple.getT1());
            impacts.addAll(tuple.getT2());
            return impacts;
        }).zipWith(moments).map(tuple -> {
            List<Impact> impacts = new ArrayList<>(tuple.getT1());
            impacts.addAll(tuple.getT2());
            return impacts;
        }).zipWith(commentImpacts).map(tuple -> {
            List<Impact> impacts = new ArrayList<>(tuple.getT1());
            impacts.addAll(tuple.getT2());
            return impacts;
        }).zipWith(userImpacts).map(tuple -> {
            List<Impact> impacts = new ArrayList<>(tuple.getT1());
            impacts.addAll(tuple.getT2());
            return new PreviewResult(postsScanned.get(), pagesScanned.get(), momentsScanned.get(),
                commentsScanned.get(), configMapsScanned.get(), usersScanned.get(), impacts);
        });
    }

    private Mono<Impact> impactOf(String type, String name, String title, String baseName,
                                  String headName, String cover, List<String> urls, String origin) {
        return contentTexts(baseName, headName)
            .flatMap(texts -> impactOfTexts(type, name, title, texts, cover, urls, origin));
    }

    private Mono<Impact> impactOfText(String type, String name, String title, String text,
                                      String cover, List<String> urls, String origin) {
        return impactOfTexts(type, name, title, List.of(nullSafe(text)), cover, urls, origin);
    }

    /**
     * 统计一条内容的引用处数。
     *
     * <p>{@code texts} 是同一份内容的多种表示（Markdown 原文 / 渲染后的 HTML / 瞬间的媒体地址），
     * 一份内容里同一张图会在这些表示里各出现一次，所以按「每个旧地址在多份文本里的最大出现次数」
     * 合并，而不是相加 —— 否则一张图会被算成两处引用。改写时会把这些表示全部替换。</p>
     */
    private Mono<Impact> impactOfTexts(String type, String name, String title, List<String> texts,
                                       String cover, List<String> urls, String origin) {
        Map<String, Integer> contentHits = countReferences(texts, urls, origin);
        Map<String, Integer> coverHits = countMatches(cover, urls, origin);
        int contentTotal = sum(contentHits);
        int coverTotal = sum(coverHits);
        if (contentTotal == 0 && coverTotal == 0) {
            return Mono.empty();
        }
        Map<String, Integer> merged = new LinkedHashMap<>(contentHits);
        coverHits.forEach((url, count) -> merged.merge(url, count, Integer::sum));
        return Mono.just(new Impact(type, name, nullSafe(title), contentTotal, coverTotal, merged));
    }

    /**
     * 取「当前正文」的两种表示：Markdown 原文与渲染后的 HTML（草稿与 base 不同则用草稿全文）。
     *
     * <p>两者是同一份内容的两种形态，统计引用处数时要合并（不重复计数），改写时都要动。</p>
     */
    private Mono<List<String>> contentTexts(String baseName, String headName) {
        if (baseName == null || baseName.isBlank()) {
            return Mono.just(List.of());
        }
        return client.fetch(Snapshot.class, baseName)
            .flatMap(base -> {
                String baseRaw = nullSafe(base.getSpec().getRawPatch());
                String baseContent = nullSafe(base.getSpec().getContentPatch());
                if (headName == null || headName.isBlank() || headName.equals(baseName)) {
                    return Mono.just(List.of(baseRaw, baseContent));
                }
                return client.fetch(Snapshot.class, headName)
                    .map(head -> List.of(
                        PatchUtils.applyPatch(baseRaw, nullSafe(head.getSpec().getRawPatch())),
                        PatchUtils.applyPatch(baseContent,
                            nullSafe(head.getSpec().getContentPatch()))))
                    .defaultIfEmpty(List.of(baseRaw, baseContent));
            })
            .defaultIfEmpty(List.of())
            .onErrorResume(e -> {
                log.warn("预检读取快照 {} 失败", baseName, e);
                return Mono.just(List.of());
            });
    }

    /**
     * 统计每个旧地址在文本里出现几次，口径与 {@link #applyMappings} 一致：
     * 内容先被替换成唯一占位符，再数占位符，避免边界规则和编码变体被统计逻辑漏掉。
     *
     * @param text 正文、封面地址或配置内容
     * @param urls 旧地址集合
     * @param origin 站点地址（用于把「站点绝对地址」算成同一处引用），可为 null
     * @return 旧地址 -> 出现次数，未命中的不返回
     */
    static Map<String, Integer> countMatches(String text, Collection<String> urls, String origin) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (text == null || text.isEmpty() || urls == null || urls.isEmpty()) {
            return counts;
        }
        Map<String, String> tokenByUrl = new LinkedHashMap<>();
        Map<String, String> urlByToken = new LinkedHashMap<>();
        int index = 0;
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            String token = "\u0001sm" + index++ + "\u0001";
            tokenByUrl.put(url, token);
            urlByToken.put(token, url);
        }
        String marked = applyMappings(text, expandMapping(tokenByUrl, origin));
        urlByToken.forEach((token, url) -> {
            int count = 0;
            int from = 0;
            while ((from = marked.indexOf(token, from)) >= 0) {
                count++;
                from += token.length();
            }
            if (count > 0) {
                counts.put(url, count);
            }
        });
        return counts;
    }

    /**
     * 统计一组「同一内容的多份表示」里的引用处数。
     *
     * <p>同一张图通常既写在 Markdown 原文里、又出现在渲染后的 HTML 里，属于**一处引用**。
     * 所以这里按「每个旧地址在各份文本里的最大出现次数」合并，而不是把各份文本的次数相加。</p>
     *
     * @param texts 同一内容的多份表示（如 raw 与 content）
     * @param urls 旧地址集合
     * @param origin 站点地址，可为 null
     * @return 旧地址 -> 引用处数，未命中的不返回
     */
    static Map<String, Integer> countReferences(Collection<String> texts, Collection<String> urls,
                                                String origin) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        if (texts == null || texts.isEmpty()) {
            return merged;
        }
        for (String text : texts) {
            countMatches(text, urls, origin).forEach((url, count) -> merged.merge(url, count, Math::max));
        }
        return merged;
    }

    private static int sum(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ------------------------------------------------------------------
    // 文章 / 页面
    // ------------------------------------------------------------------

    private Mono<Integer> rewriteContent(String subjectName, String baseName, String headName,
                                         String releaseName, Map<String, String> mapping,
                                         boolean dryRun) {
        if (baseName == null || baseName.isBlank()) {
            return Mono.just(0);
        }
        return client.fetch(Snapshot.class, baseName)
            .flatMap(base -> {
                var baseSpec = base.getSpec();
                String oldBaseRaw = nullSafe(baseSpec.getRawPatch());
                String oldBaseContent = nullSafe(baseSpec.getContentPatch());
                String newBaseRaw = applyMappings(oldBaseRaw, mapping);
                String newBaseContent = applyMappings(oldBaseContent, mapping);
                boolean baseChanged = !newBaseRaw.equals(oldBaseRaw)
                    || !newBaseContent.equals(oldBaseContent);

                int baseCount = 0;
                Mono<Void> baseUpdate = Mono.empty();
                if (baseChanged) {
                    baseCount = 1;
                    if (!dryRun) {
                        baseUpdate = updateSnapshot(base, newBaseRaw, newBaseContent).then();
                    }
                }

                Set<String> others = new LinkedHashSet<>();
                if (headName != null && !headName.isBlank()) {
                    others.add(headName);
                }
                if (releaseName != null && !releaseName.isBlank()) {
                    others.add(releaseName);
                }
                others.remove(baseName);

                Mono<Integer> rest = Flux.fromIterable(others)
                    .concatMap(name -> client.fetch(Snapshot.class, name)
                        .flatMap(snapshot -> {
                            String fullRaw = PatchUtils.applyPatch(oldBaseRaw,
                                nullSafe(snapshot.getSpec().getRawPatch()));
                            String fullContent = PatchUtils.applyPatch(oldBaseContent,
                                nullSafe(snapshot.getSpec().getContentPatch()));
                            String newRaw = applyMappings(fullRaw, mapping);
                            String newContent = applyMappings(fullContent, mapping);
                            if (newRaw.equals(fullRaw) && newContent.equals(fullContent)) {
                                return Mono.just(0);
                            }
                            if (dryRun) {
                                return Mono.just(1);
                            }
                            // 以新的 base 全文为基准重新求 diff
                            String rawPatch = PatchUtils.diffToJsonPatch(newBaseRaw, newRaw);
                            String contentPatch = PatchUtils.diffToJsonPatch(newBaseContent, newContent);
                            return updateSnapshot(snapshot, rawPatch, contentPatch).thenReturn(1);
                        })
                        .onErrorResume(e -> {
                            log.warn("处理快照 {} 失败", name, e);
                            return Mono.just(0);
                        }))
                    .reduce(0, Integer::sum);

                int finalBaseCount = baseCount;
                return baseUpdate.then(rest).map(c -> c + finalBaseCount);
            })
            .defaultIfEmpty(0)
            .onErrorResume(e -> {
                log.warn("读取 base 快照 {} 失败（{}）", baseName, subjectName, e);
                return Mono.just(0);
            });
    }

    private Mono<Snapshot> updateSnapshot(Snapshot snapshot, String rawPatch, String contentPatch) {
        var spec = snapshot.getSpec();
        if (spec == null) {
            spec = new Snapshot.SnapShotSpec();
            snapshot.setSpec(spec);
        }
        spec.setRawPatch(rawPatch);
        spec.setContentPatch(contentPatch);
        spec.setLastModifyTime(Instant.now());
        var annotations = snapshot.getMetadata().getAnnotations();
        if (annotations != null) {
            annotations.remove(Snapshot.PATCHED_RAW_ANNO);
            annotations.remove(Snapshot.PATCHED_CONTENT_ANNO);
        }
        return client.update(snapshot);
    }

    private Mono<Integer> updateCover(Post post, Map<String, String> mapping, boolean dryRun) {
        String cover = post.getSpec().getCover();
        String newCover = applyMappings(cover, mapping);
        if (Objects.equals(cover, newCover)) {
            return Mono.just(0);
        }
        if (dryRun) {
            return Mono.just(1);
        }
        post.getSpec().setCover(newCover);
        return client.update(post).thenReturn(1);
    }

    private Mono<Integer> updatePageCover(SinglePage page, Map<String, String> mapping, boolean dryRun) {
        String cover = page.getSpec().getCover();
        String newCover = applyMappings(cover, mapping);
        if (Objects.equals(cover, newCover)) {
            return Mono.just(0);
        }
        if (dryRun) {
            return Mono.just(1);
        }
        page.getSpec().setCover(newCover);
        return client.update(page).thenReturn(1);
    }

    // ------------------------------------------------------------------
    // 系统设置 / 主题配置（ConfigMap）
    // ------------------------------------------------------------------

    /**
     * 所有参与改写的 ConfigMap：系统设置、插件设置、主题配置。
     *
     * <p>存储策略自己的 ConfigMap（带 {@code storage.halo.run/policy-owner} 标签）要排除，
     * 那里放的是 endpoint 与密钥，不能动。</p>
     */
    private Flux<ConfigMap> configMaps() {
        return client.list(ConfigMap.class, cm -> {
            var labels = cm.getMetadata().getLabels();
            return labels == null || !labels.containsKey(POLICY_OWNER_LABEL);
        }, nameComparator());
    }

    private Mono<Integer> rewriteConfigMap(ConfigMap configMap, Map<String, String> mapping,
                                           boolean dryRun) {
        Map<String, String> data = configMap.getData();
        if (data == null || data.isEmpty()) {
            return Mono.just(0);
        }
        Map<String, String> updated = new LinkedHashMap<>(data);
        int hits = 0;
        for (var entry : data.entrySet()) {
            Replacement replacement = replace(entry.getValue(), mapping);
            if (replacement.count() > 0) {
                updated.put(entry.getKey(), replacement.text());
                hits += replacement.count();
            }
        }
        if (hits == 0) {
            return Mono.just(0);
        }
        if (dryRun) {
            return Mono.just(hits);
        }
        configMap.setData(updated);
        return client.update(configMap).thenReturn(hits);
    }

    /** 主题配置的 ConfigMap 形如 {@code theme-hao-configMap}。 */
    static String typeOfConfigMap(String name) {
        return name != null && name.startsWith("theme-") ? TYPE_THEME : TYPE_SETTING;
    }

    static String configMapTitle(String name) {
        if (name == null) {
            return "";
        }
        if ("system".equals(name)) {
            return "系统设置";
        }
        if (name.startsWith("theme-")) {
            String theme = name.substring("theme-".length());
            if (theme.endsWith(THEME_CONFIG_SUFFIX)) {
                theme = theme.substring(0, theme.length() - THEME_CONFIG_SUFFIX.length());
            }
            return "主题配置（" + theme + "）";
        }
        if (name.startsWith("plugin-")) {
            String plugin = name.substring("plugin-".length());
            return "插件设置（" + plugin + "）";
        }
        return name;
    }

    private static String joinValues(Map<String, String> data) {
        if (data == null || data.isEmpty()) {
            return "";
        }
        return String.join("\n", data.values());
    }

    // ------------------------------------------------------------------
    // 评论 / 回复
    // ------------------------------------------------------------------

    /**
     * 评论与回复共用 {@code Comment.BaseCommentSpec}：正文同时存在于 {@code spec.raw}（Markdown 原文）
     * 与 {@code spec.content}（渲染后的 HTML）。两处都要改，否则「前台展示」和「再次编辑」会各留一份旧地址。
     */
    private <E extends Extension> Mono<Void> rewriteComments(Class<E> type, Map<String, String> mapping,
                                                             boolean dryRun, AtomicInteger scanned,
                                                             AtomicInteger updated, List<String> details,
                                                             String label) {
        return client.list(type, e -> commentSpecOf(e) != null && !ExtensionUtil.isDeleted(e),
                nameComparator())
            .concatMap(entity -> {
                scanned.incrementAndGet();
                return rewriteCommentEntity(entity, mapping, dryRun)
                    .doOnNext(hits -> {
                        if (hits > 0) {
                            updated.incrementAndGet();
                            details.add(label + " " + entity.getMetadata().getName()
                                + " 改写 " + hits + " 处");
                        }
                    })
                    .doOnError(e -> log.warn("改写{} {} 失败", label,
                        entity.getMetadata().getName(), e))
                    .onErrorResume(e -> Mono.just(0))
                    .then();
            })
            .then();
    }

    private <E extends Extension> Mono<Integer> rewriteCommentEntity(E entity,
                                                                    Map<String, String> mapping,
                                                                    boolean dryRun) {
        var spec = commentSpecOf(entity);
        if (spec == null) {
            return Mono.just(0);
        }
        if (dryRun) {
            return Mono.just(countCommentHits(spec, mapping));
        }
        int hits = rewriteCommentContent(spec, mapping);
        if (hits == 0) {
            return Mono.just(0);
        }
        return client.update(entity).thenReturn(hits);
    }

    /**
     * 改写一条评论 / 回复的 raw 与 content，其它字段（审核状态、置顶、作者等）原样保留。
     *
     * <p>两份表示都要写，避免「前台展示」和「再次编辑」各留一份旧地址；但返回值是**引用处数**
     * （同一张图在 raw 与 content 里各一份，只算一处），与预检口径一致。</p>
     *
     * @param spec 评论 spec
     * @param mapping 旧地址 -> 新地址
     * @return 引用处数
     */
    static int rewriteCommentContent(Comment.BaseCommentSpec spec, Map<String, String> mapping) {
        if (spec == null) {
            return 0;
        }
        int hits = countCommentHits(spec, mapping);
        if (hits == 0) {
            return 0;
        }
        spec.setRaw(replace(spec.getRaw(), mapping).text());
        spec.setContent(replace(spec.getContent(), mapping).text());
        return hits;
    }

    static int countCommentHits(Comment.BaseCommentSpec spec, Map<String, String> mapping) {
        if (spec == null) {
            return 0;
        }
        return countReferences(commentParts(spec), mapping.keySet(), null)
            .values().stream().mapToInt(Integer::intValue).sum();
    }

    /** 评论 / 回复正文的两种表示：Markdown 原文与渲染后的 HTML。 */
    static List<String> commentParts(Comment.BaseCommentSpec spec) {
        if (spec == null) {
            return List.of();
        }
        return List.of(nullSafe(spec.getRaw()), nullSafe(spec.getContent()));
    }

    /** 评论 / 回复的正文，用于预检摘要（raw 与 content 合起来读文字）。 */
    static String commentText(Comment.BaseCommentSpec spec) {
        if (spec == null) {
            return null;
        }
        return nullSafe(spec.getRaw()) + "\n" + nullSafe(spec.getContent());
    }

    /** 预检列表里展示的评论摘要：去掉 HTML 标签与 Markdown 标记，截断到 24 个字。 */
    static String commentTitle(Comment.BaseCommentSpec spec) {
        String text = commentText(spec);
        if (text == null) {
            return null;
        }
        String plain = text.replaceAll("<[^>]+>", " ")
            .replaceAll("[*_`#>~]", "")
            .replaceAll("\\s+", " ")
            .trim();
        if (plain.isEmpty()) {
            return "评论";
        }
        return plain.length() <= 24 ? plain : plain.substring(0, 24) + "…";
    }

    private static Comment.BaseCommentSpec commentSpecOf(Extension extension) {
        if (extension instanceof Comment comment) {
            return comment.getSpec();
        }
        if (extension instanceof Reply reply) {
            return reply.getSpec();
        }
        return null;
    }

    private <E extends Extension> Mono<List<Impact>> previewComments(Class<E> type, List<String> urls,
                                                                     String origin,
                                                                     AtomicInteger scanned) {
        return client.list(type, e -> commentSpecOf(e) != null && !ExtensionUtil.isDeleted(e),
                nameComparator())
            .concatMap(entity -> {
                scanned.incrementAndGet();
                var spec = commentSpecOf(entity);
                return impactOfTexts(TYPE_COMMENT, entity.getMetadata().getName(), commentTitle(spec),
                    commentParts(spec), null, urls, origin);
            })
            .collectList();
    }

    // ------------------------------------------------------------------
    // 用户头像
    // ------------------------------------------------------------------

    /**
     * 列出所有用户。
     *
     * <p>头像地址存在 {@code User.spec.avatar}，可能是相对路径（{@code /upload/x.png}），
     * 也可能是完整地址；迁移后旧地址失效就会变成坏图，所以要和正文一起改写。</p>
     */
    private Flux<User> users() {
        return client.list(User.class, u -> u.getSpec() != null, nameComparator())
            .onErrorResume(e -> {
                log.warn("[storage-migrator] 读取用户列表失败，跳过头像改写", e);
                return Flux.empty();
            });
    }

    private Mono<Integer> rewriteUser(User user, Map<String, String> mapping, boolean dryRun) {
        var spec = user.getSpec();
        if (spec == null) {
            return Mono.just(0);
        }
        if (dryRun) {
            return Mono.just(replace(spec.getAvatar(), mapping).count());
        }
        int hits = rewriteUserAvatar(spec, mapping);
        if (hits == 0) {
            return Mono.just(0);
        }
        return client.update(user).thenReturn(hits);
    }

    /**
     * 改写用户头像地址，只动 {@code spec.avatar}，其它字段原样保留。
     *
     * @param spec 用户 spec
     * @param mapping 旧地址 -> 新地址
     * @return 实际替换的处数
     */
    static int rewriteUserAvatar(User.UserSpec spec, Map<String, String> mapping) {
        if (spec == null) {
            return 0;
        }
        Replacement replacement = replace(spec.getAvatar(), mapping);
        if (replacement.count() > 0) {
            spec.setAvatar(replacement.text());
        }
        return replacement.count();
    }

    /** 预检列表里展示的用户名：昵称优先，没有昵称用 metadata.name。 */
    private static String userTitle(User user) {
        var spec = user.getSpec();
        String displayName = spec == null ? null : spec.getDisplayName();
        return displayName == null || displayName.isBlank()
            ? user.getMetadata().getName() : displayName;
    }

    // ------------------------------------------------------------------
    // 瞬间（moments 插件）
    // ------------------------------------------------------------------

    /**
     * 列出所有瞬间。
     *
     * <p>瞬间是插件定义的扩展类型，编译期拿不到它的类，所以先通过 SchemeManager 查出该 GVK
     * 注册的类型（插件启动时注册），再用它按类型列举；之后统一用 {@link Unstructured}
     * 读写，保证插件加的其它字段不会被丢掉。</p>
     */
    private Flux<Unstructured> moments() {
        SchemeManager manager = schemeManager.getIfAvailable();
        if (manager == null) {
            return Flux.empty();
        }
        return Mono.fromSupplier(() -> manager.fetch(MOMENT_GVK))
            .flatMapMany(found -> {
                if (found.isEmpty()) {
                    log.debug("[storage-migrator] 未检测到瞬间插件，跳过");
                    return Flux.empty();
                }
                Scheme scheme = found.get();
                return momentNames(scheme.type()).concatMap(name -> client.fetch(MOMENT_GVK, name));
            })
            .onErrorResume(e -> {
                log.warn("[storage-migrator] 读取瞬间列表失败，已跳过", e);
                return Flux.empty();
            });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Flux<String> momentNames(Class<? extends Extension> type) {
        return client.listAllNames((Class) type, ListOptions.builder().build(), Sort.unsorted())
            .onErrorResume(e -> {
                log.warn("[storage-migrator] 列举瞬间失败，已跳过", e);
                return Flux.empty();
            });
    }

    private Mono<Integer> rewriteMoment(Unstructured moment, Map<String, String> mapping,
                                        boolean dryRun) {
        Map<String, Object> data = moment.getData();
        int hits = rewriteMomentData(data, mapping);
        if (hits == 0) {
            return Mono.just(0);
        }
        if (dryRun) {
            return Mono.just(hits);
        }
        return client.update(moment).thenReturn(hits);
    }

    /**
     * 改写瞬间内容：{@code spec.content.raw}、{@code spec.content.html}、
     * {@code spec.content.media[].url}。
     *
     * <p>返回值是**引用处数**：raw 与 html 是同一份内容的两种表示，同一张图在两边各出现一次，
     * 只算一处；媒体地址单独计。</p>
     *
     * @param data 瞬间的原始结构（Unstructured 的 data）
     * @param mapping 旧地址 -> 新地址
     * @return 引用处数
     */
    @SuppressWarnings("unchecked")
    static int rewriteMomentData(Map<String, Object> data, Map<String, String> mapping) {
        Map<String, Object> content = momentContent(data);
        if (content == null) {
            return 0;
        }
        int hits = countReferences(momentParts(data), mapping.keySet(), null)
            .values().stream().mapToInt(Integer::intValue).sum();
        if (hits == 0) {
            return 0;
        }
        for (String key : new String[] {"raw", "html"}) {
            Object value = content.get(key);
            if (value instanceof String text) {
                Replacement replacement = replace(text, mapping);
                if (replacement.count() > 0) {
                    content.put(key, replacement.text());
                }
            }
        }
        for (String key : new String[] {MEDIA_KEY, ALT_MEDIA_KEY}) {
            Object media = content.get(key);
            if (!(media instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Map<String, Object> entry = (Map<String, Object>) raw;
                    Object url = entry.get("url");
                    if (url instanceof String text) {
                        Replacement replacement = replace(text, mapping);
                        if (replacement.count() > 0) {
                            entry.put("url", replacement.text());
                        }
                    }
                }
            }
        }
        return hits;
    }

    /** 瞬间的全部可读文本（raw、html、媒体地址各算一份表示），供预检统计使用。 */
    @SuppressWarnings("unchecked")
    static List<String> momentParts(Map<String, Object> data) {
        Map<String, Object> content = momentContent(data);
        if (content == null) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String key : new String[] {"raw", "html"}) {
            Object value = content.get(key);
            if (value instanceof String text && !text.isEmpty()) {
                parts.add(text);
            }
        }
        for (String key : new String[] {MEDIA_KEY, ALT_MEDIA_KEY}) {
            Object media = content.get(key);
            if (!(media instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Object url = ((Map<String, Object>) raw).get("url");
                    if (url instanceof String text && !text.isEmpty()) {
                        parts.add(text);
                    }
                }
            }
        }
        return parts;
    }

    /** 瞬间的全部可读文本（拼接成一段，供摘要 / 匹配使用）。 */
    static String momentText(Map<String, Object> data) {
        return String.join("\n", momentParts(data));
    }

    private static String momentTitle(Map<String, Object> data) {
        Map<String, Object> content = momentContent(data);
        if (content == null) {
            return "";
        }
        String text = "";
        Object raw = content.get("raw");
        if (raw instanceof String s) {
            text = stripTags(s);
        }
        if (text.isEmpty()) {
            Object html = content.get("html");
            if (html instanceof String s) {
                text = stripTags(s);
            }
        }
        if (!text.isEmpty()) {
            return text.length() > 24 ? text.substring(0, 24) + "…" : text;
        }
        // 没有文字时用媒体类型当标题，便于在预检列表里一眼看出是哪条
        String markup = (String.valueOf(content.getOrDefault("raw", ""))
            + String.valueOf(content.getOrDefault("html", ""))).toLowerCase();
        if (markup.contains("<video") || "VIDEO".equalsIgnoreCase(firstMediaType(content))) {
            return "视频瞬间";
        }
        if (markup.contains("<img") || markup.contains("![")
            || "PHOTO".equalsIgnoreCase(firstMediaType(content))) {
            return "图片瞬间";
        }
        return "含媒体的瞬间";
    }

    private static String stripTags(String text) {
        return text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").strip();
    }

    @SuppressWarnings("unchecked")
    private static String firstMediaType(Map<String, Object> content) {
        for (String key : new String[] {MEDIA_KEY, ALT_MEDIA_KEY}) {
            Object media = content.get(key);
            if (!(media instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (item instanceof Map<?, ?> raw) {
                    Object type = ((Map<String, Object>) raw).get("type");
                    if (type instanceof String value) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> momentContent(Map<String, Object> data) {
        if (data == null) {
            return null;
        }
        Object spec = data.get("spec");
        if (!(spec instanceof Map<?, ?> specMap)) {
            return null;
        }
        Object content = ((Map<String, Object>) specMap).get("content");
        if (!(content instanceof Map<?, ?> contentMap)) {
            return null;
        }
        return (Map<String, Object>) contentMap;
    }

    // ------------------------------------------------------------------
    // 文本替换
    // ------------------------------------------------------------------

    /**
     * 字符串替换。除原样匹配外，同时覆盖 URL 编码后的变体（中文文件名常见）。
     *
     * <p>替换只在「URL 边界」上生效：命中位置的前后字符不能是 URL 路径/域名的组成部分，
     * 否则 {@code https://example.com/upload/x.png} 里的子串也会被替换，拼出
     * {@code https://example.comhttps://cdn...} 这类坏链接。长 key 优先匹配，
     * 保证「站点绝对地址」先于「相对路径」被处理。</p>
     */
    static String applyMappings(String text, Map<String, String> mapping) {
        return replace(text, mapping).text();
    }

    /**
     * 执行替换并返回实际替换处数，供改写统计使用。
     */
    static Replacement replace(String text, Map<String, String> mapping) {
        if (text == null || text.isEmpty() || mapping.isEmpty()) {
            return new Replacement(text, 0);
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(mapping.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        String result = text;
        int total = 0;
        for (var entry : entries) {
            String from = entry.getKey();
            String to = entry.getValue();
            if (from == null || from.isBlank() || from.equals(to)) {
                continue;
            }
            int count = countAtBoundary(result, from);
            if (count > 0) {
                result = replaceAtBoundary(result, from, to);
                total += count;
            }
            String encoded = encodePath(from);
            if (!encoded.equals(from)) {
                int encodedCount = countAtBoundary(result, encoded);
                if (encodedCount > 0) {
                    result = replaceAtBoundary(result, encoded, to);
                    total += encodedCount;
                }
            }
            // 相对路径的映射还要能命中「站点绝对地址」写法（http://host:port/upload/x.png）。
            // 不能只靠 origin 展开：站点地址可能配不到（ExternalUrlSupplier 返回 "/"），
            // 内容里也可能写着别的域名（换过域名、本地调试的 localhost），这些都应算同一处引用。
            if (from.startsWith("/")) {
                for (String needle : encoded.equals(from) ? List.of(from) : List.of(from, encoded)) {
                    int withOrigin = countWithOrigin(result, needle);
                    if (withOrigin > 0) {
                        result = replaceWithOrigin(result, needle, to);
                        total += withOrigin;
                    }
                }
            }
        }
        return new Replacement(result, total);
    }

    /**
     * 仅在 URL 边界上替换，避免命中长地址里的子串。
     */
    static String replaceAtBoundary(String text, String from, String to) {
        if (text == null || from == null || from.isEmpty() || !text.contains(from)) {
            return text;
        }
        StringBuilder sb = null;
        int cursor = 0;
        int search = 0;
        while (true) {
            int found = text.indexOf(from, search);
            if (found < 0) {
                break;
            }
            int end = found + from.length();
            if (isUrlBoundary(text, found, end)) {
                if (sb == null) {
                    sb = new StringBuilder(text.length() + 16);
                }
                sb.append(text, cursor, found).append(to);
                cursor = end;
            }
            search = end;
        }
        if (sb == null) {
            return text;
        }
        return sb.append(text, cursor, text.length()).toString();
    }

    /** 数出会命中多少处（口径与 {@link #replaceAtBoundary} 完全一致）。 */
    static int countAtBoundary(String text, String from) {
        if (text == null || from == null || from.isEmpty()) {
            return 0;
        }
        int count = 0;
        int search = 0;
        while (true) {
            int found = text.indexOf(from, search);
            if (found < 0) {
                break;
            }
            int end = found + from.length();
            if (isUrlBoundary(text, found, end)) {
                count++;
            }
            search = end;
        }
        return count;
    }

    /**
     * 命中位置的前一个字符不能属于主机名 / 路径，后一个字符不能属于同一个路径段，
     * 否则说明命中的只是更长地址里的一段。
     */
    static boolean isUrlBoundary(String text, int start, int end) {
        if (start > 0 && isUrlChar(text.charAt(start - 1))) {
            return false;
        }
        return end >= text.length() || !isUrlChar(text.charAt(end));
    }

    private static boolean isUrlChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '~'
            || c == '%' || c == '/' || c == ':' || c == '@' || c == '+';
    }

    /** 紧贴在相对路径前面的站点地址（scheme://host[:port]）。 */
    private static final Pattern ORIGIN_BEFORE = Pattern.compile(
        "[a-zA-Z][a-zA-Z0-9+.\\-]*://(?:\\[[0-9a-fA-F:.]+\\]|[a-zA-Z0-9.\\-]+)(?::\\d+)?$");

    /**
     * 找出 needle 出现位置前面紧贴的 origin 起始下标；没有则返回 -1。
     *
     * @param text 正文
     * @param needleStart needle 在正文中的起始下标
     * @return origin 起始下标或 -1
     */
    static int originStartBefore(String text, int needleStart) {
        if (needleStart <= 0) {
            return -1;
        }
        int windowStart = Math.max(0, needleStart - 256);
        Matcher matcher = ORIGIN_BEFORE.matcher(text.substring(windowStart, needleStart));
        return matcher.find() ? windowStart + matcher.start() : -1;
    }

    /**
     * 是否是「本机 / 开发环境」的站点地址。
     *
     * <p>只对这类 origin 做「连域名一起替换」的匹配：文章、评论里经常写着
     * {@code http://localhost:8090/upload/x.png}（本地调试、换域名前的老内容），
     * 而第三方域名下的同路径链接（如别人的图床）不该被我们改掉。</p>
     *
     * @param origin 候选 origin（如 {@code http://localhost:8090}）
     * @return 是本机地址时返回 true
     */
    static boolean isSiteOrigin(String origin) {
        if (origin == null || origin.isEmpty()) {
            return false;
        }
        try {
            String host = URI.create(origin).getHost();
            if (host == null) {
                return false;
            }
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host) || "0.0.0.0".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 数出「站点绝对地址 + 相对路径」形式的命中处数（口径与 {@link #replaceWithOrigin} 一致）。
     *
     * @param text 正文
     * @param needle 相对路径
     * @return 命中处数
     */
    static int countWithOrigin(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int search = 0;
        while (true) {
            int found = text.indexOf(needle, search);
            if (found < 0) {
                break;
            }
            int end = found + needle.length();
            int start = originStartBefore(text, found);
            if (start >= 0 && isSiteOrigin(text.substring(start, found))
                && isUrlBoundary(text, start, end)) {
                count++;
            }
            search = end;
        }
        return count;
    }

    /**
     * 把「站点绝对地址 + 相对路径」整段换成新地址（连同域名端口一起替换掉）。
     *
     * <p>内容里写 {@code http://localhost:8090/upload/x.png}、映射里只有 {@code /upload/x.png} 时，
     * 按边界规则是匹配不上的（前面紧贴 {@code 8090}）；这里把前面的 origin 一起吞掉再替换。</p>
     *
     * @param text 正文
     * @param needle 相对路径
     * @param replacement 新地址
     * @return 替换后的文本
     */
    static String replaceWithOrigin(String text, String needle, String replacement) {
        if (text == null || needle == null || needle.isEmpty() || !text.contains(needle)) {
            return text;
        }
        StringBuilder sb = null;
        int cursor = 0;
        int search = 0;
        while (true) {
            int found = text.indexOf(needle, search);
            if (found < 0) {
                break;
            }
            int end = found + needle.length();
            int start = originStartBefore(text, found);
            if (start >= 0 && isSiteOrigin(text.substring(start, found))
                && isUrlBoundary(text, start, end)) {
                if (sb == null) {
                    sb = new StringBuilder(text.length());
                }
                sb.append(text, cursor, start).append(replacement);
                cursor = end;
            }
            search = end;
        }
        if (sb == null) {
            return text;
        }
        return sb.append(text, cursor, text.length()).toString();
    }

    /**
     * 逐段进行 URL 编码，保留分隔符，用于匹配 HTML 中已编码的地址。
     */
    static String encodePath(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : url.toCharArray()) {
            if (c == '/' || c == ':' || c == '?' || c == '=' || c == '&' || c == '#' || c <= 0x7F) {
                sb.append(c);
            } else {
                sb.append(URLEncoder.encode(String.valueOf(c), StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    private static Map<String, String> normalize(Map<String, String> raw) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (raw == null) {
            return mapping;
        }
        raw.forEach((k, v) -> {
            if (k != null && v != null && !k.isBlank() && !v.isBlank() && !k.equals(v)) {
                mapping.put(k.trim(), v.trim());
            }
        });
        return mapping;
    }

    /**
     * 相对路径的映射额外补一份「站点绝对地址」版本。
     *
     * <p>正文里可能直接写 {@code https://站点域名/upload/x.png}，只按相对路径匹配会因为
     * 前面紧贴域名而被边界判断挡掉；补上绝对地址后，长 key 会先整段替换掉。</p>
     */
    private Map<String, String> expandMapping(Map<String, String> mapping) {
        return expandMapping(mapping, origin());
    }

    static Map<String, String> expandMapping(Map<String, String> mapping, String origin) {
        if (origin == null || mapping.isEmpty()) {
            return mapping;
        }
        Map<String, String> expanded = new LinkedHashMap<>(mapping);
        mapping.forEach((from, to) -> {
            if (from.startsWith("/")) {
                expanded.putIfAbsent(origin + from, to);
            }
        });
        return expanded;
    }

    private String origin() {
        String fromSupplier = null;
        ExternalUrlSupplier supplier = externalUrlSupplier.getIfAvailable();
        if (supplier != null) {
            try {
                URI uri = supplier.get();
                fromSupplier = uri == null ? null : uri.toString();
            } catch (Exception e) {
                fromSupplier = null;
            }
        }
        String origin = normalizeOrigin(fromSupplier);
        if (origin == null) {
            origin = normalizeOrigin(configuredExternalUrl);
        }
        if (origin != null) {
            log.debug("[storage-migrator] 链接改写使用站点地址 {}", origin);
        }
        return origin;
    }

    private static String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) {
                return null;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return null;
            }
            int port = uri.getPort();
            return scheme + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static boolean isTrue(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private static <E extends Extension> Comparator<E> nameComparator() {
        return Comparator.comparing(e -> e.getMetadata().getName());
    }
}
