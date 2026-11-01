package cn.litx.storagemigrator.core;

import cn.litx.storagemigrator.dto.MigrateRequest;
import cn.litx.storagemigrator.dto.PolicyVo;
import cn.litx.storagemigrator.dto.PreviewVo;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.infra.ExternalUrlSupplier;

/**
 * 存储策略迁移核心逻辑。
 *
 * <p>实现方式：通过附件的 permalink（对任何存储策略都公开可访问）拉取原始字节，
 * 再以目标策略重新上传，从而生成一条归属新策略的附件记录；移动模式下再删除旧记录。</p>
 */
@Component
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private static final int PAGE_SIZE = 100;
    /** 临时文件读取缓冲（阶段二上传的字节源）。 */
    private static final int READ_BUFFER_SIZE = 8192;
    private static final MediaType FALLBACK_MEDIA_TYPE = MediaType.APPLICATION_OCTET_STREAM;

    /** 用户头像相关的注解：当前绑定的附件名、以及上一个（待清理）的附件名。 */
    private static final List<String> AVATAR_ANNOTATIONS = List.of(
        User.AVATAR_ATTACHMENT_NAME_ANNO, User.LAST_AVATAR_ATTACHMENT_NAME_ANNO);

    /**
     * 迁移产生的附件上会标注来源附件名，保证任务重复执行时能命中同一份副本。
     *
     * <p>不能只靠文件名去重：s3os 等策略会重写文件名（如 {@code ${random-num:8}.png}），
     * 源文件 {@code test-image.png} 在目标策略里叫 {@code 80215383.png}，按文件名永远匹配不上，
     * 重复执行就会反复上传、产生多份副本。</p>
     */
    static final String SOURCE_ATTACHMENT_ANNO = "storage-migrator/from-attachment";
    static final String SOURCE_PERMALINK_ANNO = "storage-migrator/from-permalink";
    static final String SOURCE_POLICY_ANNO = "storage-migrator/from-policy";

    private final ReactiveExtensionClient client;
    private final AttachmentService attachmentService;
    private final ObjectProvider<ExternalUrlSupplier> externalUrlSupplier;
    private final TaskRegistry registry;
    private final LinkRewriteService linkRewriteService;
    private final WebClient webClient;
    private final String configuredExternalUrl;
    private final int serverPort;

    /**
     * 正在执行的「源策略 -> 目标策略」组合。
     *
     * <p>并发迁移同一对策略会各自上传一份副本：目标策略的附件快照（{@link TargetIndex}）是任务启动时取的，
     * 两个任务互相看不见对方的产物，91 个文件就会变成 181 个，用户头像也会被后完成的任务抢改到另一份副本上。
     * 所以同一组合同时只允许一个任务。</p>
     */
    private final Set<MigrationPair> activeMigrations = ConcurrentHashMap.newKeySet();

    public MigrationService(ReactiveExtensionClient client,
                            AttachmentService attachmentService,
                            ObjectProvider<ExternalUrlSupplier> externalUrlSupplier,
                            TaskRegistry registry,
                            LinkRewriteService linkRewriteService,
                            @Value("${halo.external-url:}") String configuredExternalUrl,
                            @Value("${server.port:8090}") int serverPort) {
        this.client = client;
        this.attachmentService = attachmentService;
        this.externalUrlSupplier = externalUrlSupplier;
        this.registry = registry;
        this.linkRewriteService = linkRewriteService;
        this.webClient = WebClient.builder().build();
        this.configuredExternalUrl = configuredExternalUrl;
        this.serverPort = serverPort;
    }

    /**
     * 列出所有存储策略及附件统计。
     *
     * @return 策略列表
     */
    public Flux<PolicyVo> listPolicies() {
        return client.list(Policy.class, p -> true, nameComparator())
            .concatMap(policy -> {
                String name = policy.getMetadata().getName();
                return client.list(Attachment.class,
                        a -> name.equals(policyNameOf(a)), nameComparator())
                    .reduce(new long[] {0L, 0L},
                        (acc, a) -> {
                            acc[0]++;
                            acc[1] += a.getSpec().getSize() == null ? 0L : a.getSpec().getSize();
                            return acc;
                        })
                    .map(acc -> new PolicyVo(name, policy.getSpec().getDisplayName(),
                        policy.getSpec().getTemplateName(), acc[0], acc[1]));
            });
    }

    /**
     * 创建并异步执行迁移任务。
     *
     * @param request 迁移参数
     * @param securityContext 发起请求的安全上下文（AttachmentService.upload 要求已认证）
     * @return 任务
     */
    public Mono<MigrationTask> start(MigrateRequest request, SecurityContext securityContext,
                                     String requestBaseUrl) {
        String source = trimToNull(request.sourcePolicy());
        String target = trimToNull(request.targetPolicy());
        if (source == null || target == null) {
            return Mono.error(new IllegalArgumentException("请选择源存储策略与目标存储策略"));
        }
        if (source.equals(target)) {
            return Mono.error(new IllegalArgumentException("源策略与目标策略不能相同"));
        }
        return client.fetch(Policy.class, source)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("源存储策略不存在: " + source)))
            .flatMap(src -> client.fetch(Policy.class, target)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("目标存储策略不存在: " + target))))
            .map(tgt -> {
                var pair = new MigrationPair(source, target);
                if (!activeMigrations.add(pair)) {
                    throw new MigrationConflictException(
                        "已有「" + source + " -> " + target + "」的迁移任务在执行中，请等待其完成后再发起");
                }
                var task = new MigrationTask(UUID.randomUUID().toString(), source, target,
                    request.shouldDeleteSource(), request.shouldRewriteLinks());
                registry.put(task);
                runAsync(task, securityContext, requestBaseUrl);
                return task;
            });
    }

    /**
     * 迁移前的影响面预检：不传文件、不改数据，只算清楚「会动多少文件」和「会改哪些文章 / 页面、各改几处」。
     *
     * <p>预检不需要知道目标策略生成的新地址（S3 会把文件名重写成随机串），
     * 影响面只取决于源附件的 permalink，所以能提前算准。</p>
     *
     * @param rawSource 源策略名
     * @param rawTarget 目标策略名
     * @return 预检结果
     */
    public Mono<PreviewVo> preview(String rawSource, String rawTarget) {
        String source = trimToNull(rawSource);
        String target = trimToNull(rawTarget);
        if (source == null || target == null) {
            return Mono.error(new IllegalArgumentException("请选择源存储策略与目标存储策略"));
        }
        if (source.equals(target)) {
            return Mono.error(new IllegalArgumentException("源策略与目标策略不能相同"));
        }
        return client.fetch(Policy.class, source)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("源存储策略不存在: " + source)))
            .flatMap(src -> client.fetch(Policy.class, target)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("目标存储策略不存在: " + target))))
            .flatMap(tgt -> {
                TargetIndex targetIndex = new TargetIndex();
                return client.list(Attachment.class,
                        a -> target.equals(policyNameOf(a)), nameComparator())
                    .doOnNext(targetIndex::put)
                    .then(client.list(Attachment.class,
                            a -> source.equals(policyNameOf(a)), nameComparator())
                        .collectList())
                    .flatMap(sources -> previewOf(source, target, sources, targetIndex));
            });
    }

    private Mono<PreviewVo> previewOf(String source, String target, List<Attachment> sources,
                                      TargetIndex targetIndex) {
        int reusable = 0;
        List<String> sourceUrls = new ArrayList<>();
        for (Attachment attachment : sources) {
            String permalink = permalinkOf(attachment);
            if (permalink != null && !permalink.isBlank()) {
                sourceUrls.add(permalink);
            }
            if (targetIndex.find(displayNameOf(attachment), attachment.getMetadata().getName()) != null) {
                reusable++;
            }
        }
        long bytes = sources.stream()
            .mapToLong(a -> a.getSpec().getSize() == null ? 0L : a.getSpec().getSize())
            .sum();
        int reusableCount = reusable;
        // 预检必须和真实改写同源：真实改写会连带补写「同一迁移」遗留的旧地址，
        // 但不会去动别的策略搬走的文件，所以这里也只取同一源策略 / 目标策略的历史映射。
        return migratedUrlMapping(source, target)
            .map(historical -> {
                Set<String> urls = new LinkedHashSet<>(historical.keySet());
                urls.addAll(sourceUrls);
                return new ArrayList<>(urls);
            })
            .flatMap(urls -> linkRewriteService.preview(urls)
                .map(result -> buildPreview(source, target, sources.size(), bytes, reusableCount,
                    sourceUrls, urls, result)));
    }

    /**
     * 把「扫描结果」拼成对外的预检响应。
     *
     * @param source 源策略名
     * @param target 目标策略名
     * @param attachmentTotal 源策略附件数
     * @param bytes 源策略附件总字节数
     * @param reusableCount 目标策略已有副本的数量
     * @param sourceUrls 源策略附件的地址（用于统计「无引用」）
     * @param allUrls 参与本次改写的全部旧地址（源策略 + 历史迁移）
     * @param result 链接扫描结果
     * @return 预检响应
     */
    static PreviewVo buildPreview(String source, String target, int attachmentTotal, long bytes,
                                  int reusableCount, List<String> sourceUrls, List<String> allUrls,
                                  LinkRewriteService.PreviewResult result) {
        List<PreviewVo.ImpactVo> items = result.impacts().stream()
            .sorted(Comparator
                .comparingInt((LinkRewriteService.Impact i) -> i.contentHits() + i.coverHits())
                .reversed()
                .thenComparing(LinkRewriteService.Impact::type)
                .thenComparing(LinkRewriteService.Impact::title))
            .map(i -> new PreviewVo.ImpactVo(i.type(), i.name(), i.title(), i.contentHits(),
                i.coverHits(), i.hits().entrySet().stream()
                .map(e -> new PreviewVo.HitVo(e.getKey(), e.getValue()))
                .toList()))
            .toList();

        int postsAffected = countType(result, LinkRewriteService.TYPE_POST);
        int pagesAffected = countType(result, LinkRewriteService.TYPE_PAGE);
        int momentsAffected = countType(result, LinkRewriteService.TYPE_MOMENT);
        int commentsAffected = countType(result, LinkRewriteService.TYPE_COMMENT);
        int themesAffected = countType(result, LinkRewriteService.TYPE_THEME);
        int settingsAffected = countType(result, LinkRewriteService.TYPE_SETTING);
        int usersAffected = countType(result, LinkRewriteService.TYPE_USER);
        int contentHits = result.impacts().stream().mapToInt(LinkRewriteService.Impact::contentHits).sum();
        int coverHits = result.impacts().stream().mapToInt(LinkRewriteService.Impact::coverHits).sum();
        Set<String> referenced = new HashSet<>();
        result.impacts().forEach(i -> referenced.addAll(i.hits().keySet()));

        // 「被引用」按本次参与改写的全部旧地址算（含历史迁移的地址，评论 / 头像常落在这里）；
        // 「无引用」只数源策略里那些没被任何内容引用的附件。
        Set<String> distinctAllUrls = new LinkedHashSet<>(allUrls);
        long referencedCount = distinctAllUrls.stream().filter(referenced::contains).count();
        Set<String> distinctSourceUrls = new LinkedHashSet<>(sourceUrls);
        long unreferencedSources = distinctSourceUrls.stream().filter(u -> !referenced.contains(u)).count();

        return new PreviewVo(source, target, attachmentTotal, bytes, reusableCount,
            attachmentTotal - reusableCount, (int) referencedCount,
            (int) unreferencedSources,
            result.postsScanned(), result.pagesScanned(), result.momentsScanned(),
            result.commentsScanned(), result.configMapsScanned(), result.usersScanned(),
            postsAffected, pagesAffected, momentsAffected, commentsAffected, themesAffected,
            settingsAffected, usersAffected, contentHits, coverHits, items);
    }

    private static int countType(LinkRewriteService.PreviewResult result, String type) {
        return (int) result.impacts().stream().filter(i -> type.equals(i.type())).count();
    }

    private void runAsync(MigrationTask task, SecurityContext securityContext, String requestBaseUrl) {
        task.setStatus(MigrationTask.Status.MIGRATING);
        var pair = new MigrationPair(task.getSourcePolicy(), task.getTargetPolicy());
        run(task, requestBaseUrl)
            // 无论成功、失败还是被取消，都要放掉这一对策略，否则后续迁移会被永远挡住
            .doFinally(signal -> activeMigrations.remove(pair))
            // 迁移在后台线程执行，需要显式把请求的认证信息带进 Reactor 上下文，
            // 否则 AttachmentService.upload 会抛 401。
            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(
                Mono.justOrEmpty(securityContext)))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                ignored -> {
                },
                e -> {
                    log.error("迁移任务 {} 失败", task.getId(), e);
                    task.finish(MigrationTask.Status.FAILED,
                        e.getMessage() == null ? e.toString() : e.getMessage());
                },
                () -> {
                    if (!task.isTerminal()) {
                        task.finish(MigrationTask.Status.SUCCEEDED, null);
                    }
                });
    }

    private Mono<Void> run(MigrationTask task, String requestBaseUrl) {
        TargetIndex targetIndex = new TargetIndex();
        return preloadTargetIndex(task, targetIndex)
            .then(countSource(task))
            .thenMany(migrateAttachments(task, targetIndex, requestBaseUrl))
            .then(Mono.defer(() -> {
                // 取消后不再继续迁移：跳过历史遗留的补齐改写（本次已迁移文件的引用在迁移时已逐文件改好）
                if (task.isCancelRequested() || !task.isRewriteLinks()) {
                    return Mono.empty();
                }
                // 逐文件改写已覆盖本次任务的映射；这里只补早期任务没改到的历史遗留映射
                // （上一次任务被打断时那批文件的引用可能还没改，重跑要能补上）。
                return rewriteAll(task);
            }))
            // 头像必须单独处理：它的权威来源是 User 上的 avatar-attachment-name 注解（存附件名），
            // spec.avatar 只是 Halo 的 UserReconciler 按该附件反写出来的结果。
            // 取消场景也要跑：移动模式下已删源的头像若不改注解，reconciler 会永远重试、头像永久失效。
            .then(Mono.defer(() -> remapAvatarBindings(task)))
            .then(Mono.fromRunnable(() -> {
                if (task.isCancelRequested()) {
                    task.finish(MigrationTask.Status.CANCELLED, "任务已取消，已迁移的附件保留");
                }
            }));
    }

    /**
     * 迁移完单个文件后立即改写它在内容里的引用。
     *
     * <p>逐文件改写而非最后统一改写：任务中途取消时，已迁移文件的引用已经同步改好；
     * 用户也能实时看到改写进度。</p>
     *
     * @param task 当前任务
     * @param from 旧地址
     * @param to 新地址
     * @return 完成信号
     */
    private Mono<Void> rewriteOneLink(MigrationTask task, String from, String to) {
        if (!task.isRewriteLinks() || from == null || to == null || from.equals(to)) {
            return Mono.empty();
        }
        return linkRewriteService.rewrite(Map.of(from, to), false)
            .doOnNext(task::mergeRewrite)
            .then();
    }

    /**
     * 改写「历史迁移遗留」的引用：本次任务涉及的文件已在迁移时逐文件改写过，
     * 这里只补早期任务（含旧版统一改写口径下被取消的）没改到的映射。
     *
     * @param task 当前任务
     * @return 完成信号（没有遗留映射时直接完成）
     */
    private Mono<Void> rewriteAll(MigrationTask task) {
        return migratedUrlMapping(task.getSourcePolicy(), task.getTargetPolicy())
            .flatMap(historical -> {
                Map<String, String> leftover = new LinkedHashMap<>(historical);
                task.getUrlMapping().keySet().forEach(leftover::remove);
                if (leftover.isEmpty()) {
                    return Mono.<Void>empty();
                }
                log.info("[storage-migrator] 补齐历史遗留映射 {} 条（本次任务已逐文件改写 {} 条）",
                    leftover.size(), task.getUrlMapping().size());
                task.setStatus(MigrationTask.Status.REWRITING);
                return linkRewriteService.rewrite(leftover, false)
                    .doOnNext(result -> {
                        String prefix = task.getRewriteFiles() > 0
                            ? "已随迁移改写 " + task.getRewriteFiles() + " 个文件的引用；补齐历史引用："
                            : "";
                        task.setRewriteSummary(prefix + result.summary());
                    })
                    .then();
            });
    }

    /**
     * 重建历史映射：已迁移附件上都带着 {@code storage-migrator/from-permalink}，
     * 配合它当前的 permalink 就能还原出「旧地址 -> 新地址」，不依赖源附件是否还残留。
     *
     * <p><b>只取「这次任务该管」的那部分</b>：同一源策略、同一目标策略的历史迁移。
     * 否则预检会把别的策略里早就搬走的文件也算进来 —— 例如只搬 1 个文件，却显示 2 处引用。
     * 判断依据是附件上的 {@code storage-migrator/from-policy} 标注；老版本迁移出来的附件没有这个标注，
     * 退化用「当前所在策略 == 本次目标策略」推断。</p>
     *
     * @param sourcePolicy 本次任务的源策略
     * @param targetPolicy 本次任务的目标策略
     * @return 旧地址 -> 新地址
     */
    Mono<Map<String, String>> migratedUrlMapping(String sourcePolicy, String targetPolicy) {
        return client.list(Attachment.class, a -> sourcePermalinkOf(a) != null, nameComparator())
            .filter(a -> belongsToMigration(a, sourcePolicy, targetPolicy))
            .collectList()
            .map(MigrationService::mappingOf);
    }

    /**
     * 这份已迁移的附件是否属于「同一源策略 -> 同一目标策略」的那次迁移。
     *
     * @param attachment 已迁移的附件
     * @param sourcePolicy 本次任务的源策略
     * @param targetPolicy 本次任务的目标策略
     * @return 是否属于本次任务的改写范围
     */
    static boolean belongsToMigration(Attachment attachment, String sourcePolicy, String targetPolicy) {
        String recorded = annotationOf(attachment, SOURCE_POLICY_ANNO);
        if (recorded != null && !recorded.isBlank()) {
            return recorded.equals(sourcePolicy);
        }
        // 老版本没有记录源策略：只能按「文件现在落在本次的目标策略里」推断
        return targetPolicy != null && targetPolicy.equals(policyNameOf(attachment));
    }

    /**
     * 从一批已迁移附件里还原「旧地址 -> 新地址」。
     *
     * @param attachments 带来源标注的附件
     * @return 旧地址 -> 新地址（同一旧地址只保留第一条）
     */
    static Map<String, String> mappingOf(List<Attachment> attachments) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (Attachment attachment : attachments) {
            String from = sourcePermalinkOf(attachment);
            String to = permalinkOf(attachment);
            if (from == null || to == null || to.isBlank() || from.equals(to)) {
                continue;
            }
            mapping.putIfAbsent(from, to);
        }
        return mapping;
    }

    /**
     * 合并映射：本次任务产生的条目优先（S3 之类会重写文件名，同一次任务里的结果更可信）。
     *
     * @param historical 历史迁移重建的映射
     * @param current 本次任务产生的映射
     * @return 合并后的映射
     */
    static Map<String, String> mergeMapping(Map<String, String> historical,
                                            Map<String, String> current) {
        Map<String, String> merged = new LinkedHashMap<>(historical);
        merged.putAll(current);
        return merged;
    }

    /**
     * 把用户头像绑定的附件名换成迁移后的新附件，并顺带把 {@code spec.avatar} 改成新地址。
     *
     * <p>为什么不能只改 {@code spec.avatar}：Halo 的 {@code UserReconciler.handleAvatar} 会按
     * {@code halo.run/avatar-attachment-name} 找到附件、算出 permalink 再写回 {@code spec.avatar}；
     * 移动模式下源附件记录已被删除，注解还指着旧 UUID 时 reconciler 会一直 requeue
     * {@code Avatar permalink(...) is not available yet}，头像就再也显示不出来。</p>
     *
     * @param task 当前任务（提供 源附件名 -> 新附件名 的映射）
     * @return 完成信号
     */
    private Mono<Void> remapAvatarBindings(MigrationTask task) {
        Map<String, String> nameMapping = task.getAttachmentNameMapping();
        if (nameMapping.isEmpty()) {
            return Mono.empty();
        }
        return client.list(User.class, u -> u.getSpec() != null, nameComparator())
            .concatMap(user -> {
                int hits = remapAvatarAnnotations(user.getMetadata().getAnnotations(), nameMapping);
                if (hits == 0) {
                    return Mono.just(0);
                }
                return syncAvatarPermalink(user)
                    .flatMap(updated -> updateUser(updated, nameMapping))
                    .thenReturn(1)
                    .doOnNext(x -> log.info("[storage-migrator] 用户 {} 的头像已指向新附件", userTitle(user)))
                    .onErrorResume(e -> {
                        log.warn("[storage-migrator] 更新用户 {} 的头像绑定失败", userTitle(user), e);
                        return Mono.just(0);
                    });
            })
            .reduce(0, Integer::sum)
            .doOnNext(count -> log.info("[storage-migrator] 头像绑定修复完成，涉及 {} 个用户", count))
            .then();
    }

    /**
     * 写回用户，版本冲突时重新拉一次再写。
     *
     * <p>Halo 的 {@code UserReconciler} 等组件也在写用户，读到的版本号随时可能过期，
     * 一次 {@code OptimisticLockingFailureException} 就会让头像绑定整个跳过
     * （注解留旧、头像地址留旧）。所以冲突后重新取最新版本再写一次。</p>
     *
     * @param user 待写入的用户
     * @param nameMapping 源附件名 -> 新附件名（重新拉取后要重放一次）
     * @return 写回后的用户
     */
    private Mono<User> updateUser(User user, Map<String, String> nameMapping) {
        return client.update(user)
            .onErrorResume(OptimisticLockingFailureException.class, e -> {
                log.info("[storage-migrator] 用户 {} 被并发修改，重新拉取后再写回", userTitle(user));
                return client.fetch(User.class, user.getMetadata().getName())
                    .flatMap(fresh -> {
                        remapAvatarAnnotations(fresh.getMetadata().getAnnotations(), nameMapping);
                        return syncAvatarPermalink(fresh).flatMap(client::update);
                    });
            });
    }

    /**
     * 把 {@code spec.avatar} 同步成当前绑定附件的实际地址。
     *
     * <p>只改注解还不够：{@code spec.avatar} 才是页面真正渲染的地址，而 Halo 的
     * {@code UserReconciler.handleAvatar} 不会把它改回来（实测清空后也不会回写），
     * 于是迁移完成后头像会一直指向老地址 —— 移动模式下老地址已经 404，头像直接裂图。</p>
     *
     * <p>附件上如果还没有 permalink（刚创建、reconciler 还没跑），
     * 用 {@code attachmentService.getPermalink} 现算一个。</p>
     *
     * @param user 用户（注解已改到新附件）
     * @return 头像地址已同步的用户
     */
    private Mono<User> syncAvatarPermalink(User user) {
        var annotations = user.getMetadata().getAnnotations();
        String attachmentName = annotations == null
            ? null : annotations.get(User.AVATAR_ATTACHMENT_NAME_ANNO);
        if (attachmentName == null || attachmentName.isBlank()) {
            return Mono.just(user);
        }
        return client.fetch(Attachment.class, attachmentName)
            .flatMap(this::resolvePermalink)
            .map(attachment -> {
                String permalink = permalinkOf(attachment);
                if (permalink != null && !permalink.isBlank()) {
                    user.getSpec().setAvatar(permalink);
                }
                return user;
            })
            .defaultIfEmpty(user)
            .onErrorResume(e -> {
                log.warn("[storage-migrator] 读取头像附件 {} 的地址失败（保留原头像）",
                    attachmentName, e);
                return Mono.just(user);
            });
    }

    /**
     * 把用户头像绑定的附件名换成迁移后的新附件名（原地修改注解）。
     *
     * @param annotations 用户 metadata.annotations
     * @param nameMapping 源附件名 -> 新附件名
     * @return 改动的注解条数
     */
    static int remapAvatarAnnotations(Map<String, String> annotations,
                                      Map<String, String> nameMapping) {
        if (annotations == null || annotations.isEmpty() || nameMapping.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (String key : AVATAR_ANNOTATIONS) {
            String current = annotations.get(key);
            String mapped = current == null ? null : nameMapping.get(current);
            if (mapped != null && !mapped.equals(current)) {
                annotations.put(key, mapped);
                hits++;
            }
        }
        return hits;
    }

    private static String userTitle(User user) {
        String displayName = user.getSpec() == null ? null : user.getSpec().getDisplayName();
        return displayName == null || displayName.isBlank()
            ? user.getMetadata().getName() : displayName;
    }

    private Mono<Void> preloadTargetIndex(MigrationTask task, TargetIndex index) {
        return client.list(Attachment.class,
                a -> task.getTargetPolicy().equals(policyNameOf(a)), nameComparator())
            .doOnNext(index::put)
            .then();
    }

    private Mono<Long> countSource(MigrationTask task) {
        return client.list(Attachment.class,
                a -> task.getSourcePolicy().equals(policyNameOf(a)), nameComparator())
            .count()
            .doOnNext(count -> task.setTotal(count.intValue()));
    }

    private Flux<Void> migrateAttachments(MigrationTask task, TargetIndex targetIndex,
                                          String requestBaseUrl) {
        return client.list(Attachment.class,
                a -> task.getSourcePolicy().equals(policyNameOf(a)), nameComparator())
            // 取消在文件边界生效：当前文件完成后不再开始下一个，已迁移的不回退
            .takeWhile(attachment -> !task.isCancelRequested())
            .concatMap(attachment -> migrateOne(task, attachment, targetIndex, requestBaseUrl));
    }

    private Mono<Void> migrateOne(MigrationTask task, Attachment attachment,
                                  TargetIndex targetIndex, String requestBaseUrl) {
        String fileName = displayNameOf(attachment);
        task.setCurrentFile(fileName);
        String oldPermalink = permalinkOf(attachment);
        if (oldPermalink == null || oldPermalink.isBlank()) {
            task.markSkipped();
            return Mono.empty();
        }

        // 目标策略已有同一份文件：直接复用，保证任务可重复执行
        Attachment existing = targetIndex.find(fileName, attachment.getMetadata().getName());
        if (existing != null) {
            return reuseExisting(task, attachment, existing);
        }
        // 索引是任务启动时的快照。真到上传这一步再查一次，防止期间别的任务（或另一次执行）
        // 已经把这份文件搬过来了 —— 少了这一步，并发任务就会各传一份。
        return findExistingInTarget(task, attachment)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(found -> found.isPresent()
                ? reuseExisting(task, attachment, found.get())
                : uploadNew(task, attachment, targetIndex, requestBaseUrl));
    }

    /**
     * 复用目标策略里已经存在的那份附件：只登记映射，不再上传。
     *
     * @param task 当前任务
     * @param attachment 源附件
     * @param existing 目标策略里已有的副本
     * @return 完成信号
     */
    private Mono<Void> reuseExisting(MigrationTask task, Attachment attachment, Attachment existing) {
        String fileName = displayNameOf(attachment);
        String existingPermalink = permalinkOf(existing);
        String oldPermalink = permalinkOf(attachment);
        if (existingPermalink != null) {
            task.addMapping(oldPermalink, existingPermalink);
        }
        task.addNameMapping(attachment.getMetadata().getName(), existing.getMetadata().getName());
        // 复用的副本同样立即改写引用（中断重跑时靠这一步补齐改写）
        Mono<Void> rewrite = existingPermalink == null
            ? Mono.empty()
            : rewriteOneLink(task, oldPermalink, existingPermalink);
        // 必须走 client.delete：AttachmentService.delete 只删存储对象，不删记录
        Mono<Void> tail = task.isDeleteSource() ? client.delete(attachment).then() : Mono.empty();
        return rewrite.then(tail)
            .doOnSuccess(x -> task.markSucceeded())
            .doOnError(e -> task.markFailed(fileName, e))
            .onErrorResume(e -> Mono.empty())
            .then();
    }

    /**
     * 上传前再查一次目标策略里是否已有这份文件。
     *
     * <p>按「来源附件名」优先匹配（目标策略可能重写了文件名，按名字匹配不上），
     * 退回按显示名匹配。两者都命中不到才说明确实需要上传。</p>
     *
     * @param task 当前任务
     * @param attachment 源附件
     * @return 已存在的副本，没有则为空
     */
    private Mono<Attachment> findExistingInTarget(MigrationTask task, Attachment attachment) {
        String displayName = displayNameOf(attachment);
        String sourceName = attachment.getMetadata().getName();
        return client.list(Attachment.class,
                a -> task.getTargetPolicy().equals(policyNameOf(a))
                    && (sourceName.equals(sourceAttachmentOf(a))
                        || displayName.equals(displayNameOf(a))),
                nameComparator())
            .next();
    }

    /**
     * 把源附件上传到目标策略，登记映射，移动模式下再删掉源附件。
     *
     * <p>采用「源 -> 本地临时文件 -> 目标」两阶段搬运，而不是把下载流直接接给上传：
     * Halo 的上传实现（如 LocalAttachmentUploadHandler 的大小校验 / 类型探测 / 写入重试）
     * 会多次订阅内容流，直连下载流会让同一个文件反复下载、网速计数成倍虚高；
     * 而且上下行计数挂在同一条流上必然相等。隔一层临时文件后下载只发生一次，
     * 上下行在临时文件两侧独立计量。</p>
     *
     * @param task 当前任务
     * @param attachment 源附件
     * @param targetIndex 目标策略附件索引
     * @param requestBaseUrl 站点源地址
     * @return 完成信号
     */
    private Mono<Void> uploadNew(MigrationTask task, Attachment attachment, TargetIndex targetIndex,
                                 String requestBaseUrl) {
        String fileName = displayNameOf(attachment);
        String oldPermalink = permalinkOf(attachment);
        MediaType mediaType = resolveMediaType(attachment);
        String groupName = attachment.getSpec().getGroupName();

        return Mono.fromCallable(() -> Files.createTempFile("storage-migrator-", ".part"))
            .flatMap(temp -> downloadToFile(oldPermalink, requestBaseUrl, task, temp)
                .then(Mono.defer(() -> attachmentService.upload(task.getTargetPolicy(), groupName,
                    fileName, countedUploadSource(task, temp), mediaType)))
                .flatMap(this::resolvePermalink)
                .flatMap(created -> markOrigin(created, attachment, task.getSourcePolicy()))
                .flatMap(created -> {
                    String newPermalink = permalinkOf(created);
                    if (newPermalink == null || newPermalink.isBlank()) {
                        task.markFailed(fileName, new IllegalStateException("目标策略未返回可访问地址"));
                        return Mono.empty();
                    }
                    task.addMapping(oldPermalink, newPermalink);
                    task.addNameMapping(attachment.getMetadata().getName(), created.getMetadata().getName());
                    targetIndex.put(created);
                    // 该文件的引用立即改写，不等全部迁移完；改完再删源（移动模式），保证引用先落地
                    Mono<Void> rewrite = rewriteOneLink(task, oldPermalink, newPermalink);
                    if (!task.isDeleteSource()) {
                        return rewrite.then(Mono.just(created));
                    }
                    // 同上：记录与存储对象一起删，避免留下孤儿附件
                    return rewrite.then(client.delete(attachment).thenReturn(created));
                })
                .doOnSuccess(created -> task.markSucceeded())
                .doOnError(e -> task.markFailed(fileName, e))
                .onErrorResume(e -> Mono.empty())
                .doFinally(sig -> deleteTempFileQuietly(temp))
                .then());
    }

    /**
     * 阶段一：把源文件完整落到本地临时文件，只下载一次，字节计入下行。
     */
    private Mono<Void> downloadToFile(String permalink, String requestBaseUrl,
                                      MigrationTask task, Path temp) {
        Flux<DataBuffer> body = download(permalink, requestBaseUrl)
            .doOnNext(buf -> task.addDownloadedBytes(buf.readableByteCount()));
        return DataBufferUtils.write(body, temp);
    }

    /**
     * 阶段二的字节源：从临时文件读出送往目标策略，字节计入上行。
     *
     * <p>上传实现可能多次订阅（校验、写入重试），每次订阅都从头重读临时文件。
     * 用「订阅内偏移的高水位」计数：同一个文件的每个字节至多计入一次上行，
     * 重复订阅不会把网速放大。</p>
     */
    private Flux<DataBuffer> countedUploadSource(MigrationTask task, Path temp) {
        AtomicLong counted = new AtomicLong();
        return Flux.defer(() -> {
            long[] offset = {0L};
            return DataBufferUtils.read(temp, DefaultDataBufferFactory.sharedInstance, READ_BUFFER_SIZE)
                .doOnNext(buf -> {
                    long end = offset[0] + buf.readableByteCount();
                    offset[0] = end;
                    long prev = counted.getAndUpdate(cur -> Math.max(cur, end));
                    if (end > prev) {
                        task.addUploadedBytes(end - prev);
                    }
                });
        });
    }

    private void deleteTempFileQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("[storage-migrator] 临时文件清理失败 {}", temp, e);
        }
    }

    /**
     * 在新建附件上标注来源，供下次执行时按来源去重（目标策略可能改了文件名），
     * 也用于判断「这份已迁移的文件当初是从哪个策略搬过来的」。
     *
     * @param created 新建的附件
     * @param source 源附件
     * @param sourcePolicy 源策略名
     * @return 更新后的附件
     */
    private Mono<Attachment> markOrigin(Attachment created, Attachment source, String sourcePolicy) {
        var metadata = created.getMetadata();
        var annotations = metadata.getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
            metadata.setAnnotations(annotations);
        }
        annotations.put(SOURCE_ATTACHMENT_ANNO, source.getMetadata().getName());
        if (sourcePolicy != null && !sourcePolicy.isBlank()) {
            annotations.put(SOURCE_POLICY_ANNO, sourcePolicy);
        }
        String originPermalink = permalinkOf(source);
        if (originPermalink != null && !originPermalink.isBlank()) {
            annotations.put(SOURCE_PERMALINK_ANNO, originPermalink);
        }
        return client.update(created)
            .onErrorResume(e -> {
                log.warn("[storage-migrator] 写入来源标注失败（不影响本次迁移）: {}",
                    created.getMetadata().getName(), e);
                return Mono.just(created);
            });
    }

    /**
     * 一次迁移的方向（源策略 -> 目标策略），用于并发互斥。
     *
     * @param source 源策略名
     * @param target 目标策略名
     */
    private record MigrationPair(String source, String target) {
    }

    /**
     * 目标策略附件的索引：按文件名 + 按来源附件名。
     */
    private static final class TargetIndex {

        private final Map<String, Attachment> byDisplayName = new ConcurrentHashMap<>();
        private final Map<String, Attachment> bySourceAttachment = new ConcurrentHashMap<>();

        void put(Attachment attachment) {
            String displayName = displayNameOf(attachment);
            if (displayName != null) {
                byDisplayName.putIfAbsent(displayName, attachment);
            }
            String source = sourceAttachmentOf(attachment);
            if (source != null) {
                bySourceAttachment.putIfAbsent(source, attachment);
            }
        }

        Attachment find(String displayName, String sourceAttachmentName) {
            if (sourceAttachmentName != null) {
                Attachment hit = bySourceAttachment.get(sourceAttachmentName);
                if (hit != null) {
                    return hit;
                }
            }
            return displayName == null ? null : byDisplayName.get(displayName);
        }
    }

    private Mono<Attachment> resolvePermalink(Attachment attachment) {
        if (permalinkOf(attachment) != null) {
            return Mono.just(attachment);
        }
        return attachmentService.getPermalink(attachment)
            .map(uri -> {
                var status = attachment.getStatus();
                if (status == null) {
                    status = new Attachment.AttachmentStatus();
                    attachment.setStatus(status);
                }
                status.setPermalink(uri.toString());
                return attachment;
            })
            .defaultIfEmpty(attachment);
    }

    private Flux<DataBuffer> download(String permalink, String requestBaseUrl) {
        URI target = absoluteUri(permalink, requestBaseUrl);
        log.info("[storage-migrator] 抓取源文件 {} -> {} (requestBase={}, configured={}, supplier={})",
            permalink, target, requestBaseUrl, configuredExternalUrl, externalUrl());
        return webClient.get()
            .uri(target)
            .accept(MediaType.ALL)
            .retrieve()
            .bodyToFlux(DataBuffer.class);
    }

    private URI absoluteUri(String permalink, String requestBaseUrl) {
        if (permalink.startsWith("http://") || permalink.startsWith("https://")) {
            return URI.create(permalink);
        }
        String base = firstNonBlank(requestBaseUrl, configuredExternalUrl, externalUrl(),
            "http://localhost:" + serverPort);
        base = ensurePort(base);
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + (permalink.startsWith("/") ? permalink : "/" + permalink));
    }

    /**
     * 外部地址可能没带端口（例如 http://localhost），此时补上本机实际监听端口，
     * 否则请求会打到 80 端口。
     */
    private String ensurePort(String base) {
        try {
            URI uri = URI.create(base);
            if (uri.getPort() > 0 || uri.getHost() == null) {
                return base;
            }
            String host = uri.getHost();
            // IPv6 主机在 URI.getHost() 里带方括号或含冒号，需一并识别为本机
            boolean localHost = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || host.startsWith("[")
                || host.contains(":");
            if (!localHost) {
                return base;
            }
            String plainHost = host.startsWith("[") ? host : host;
            if (plainHost.contains(":") && !plainHost.startsWith("[")) {
                plainHost = "[" + plainHost + "]";
            }
            return uri.getScheme() + "://" + plainHost + ":" + serverPort;
        } catch (Exception e) {
            return base;
        }
    }

    private String externalUrl() {
        ExternalUrlSupplier supplier = externalUrlSupplier.getIfAvailable();
        if (supplier == null) {
            return null;
        }
        try {
            URI uri = supplier.get();
            return uri == null ? null : uri.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "http://localhost:8090";
    }

    private static MediaType resolveMediaType(Attachment attachment) {
        String mediaType = attachment.getSpec().getMediaType();
        if (mediaType == null || mediaType.isBlank()) {
            return FALLBACK_MEDIA_TYPE;
        }
        try {
            return MediaType.parseMediaType(mediaType);
        } catch (Exception e) {
            return FALLBACK_MEDIA_TYPE;
        }
    }

    private static String policyNameOf(Attachment attachment) {
        return attachment.getSpec() == null ? null : attachment.getSpec().getPolicyName();
    }

    private static String displayNameOf(Attachment attachment) {
        if (attachment.getSpec() == null) {
            return attachment.getMetadata().getName();
        }
        String displayName = attachment.getSpec().getDisplayName();
        return displayName == null || displayName.isBlank()
            ? attachment.getMetadata().getName() : displayName;
    }

    private static String permalinkOf(Attachment attachment) {
        return attachment.getStatus() == null ? null : attachment.getStatus().getPermalink();
    }

    private static String sourceAttachmentOf(Attachment attachment) {
        return annotationOf(attachment, SOURCE_ATTACHMENT_ANNO);
    }

    private static String sourcePermalinkOf(Attachment attachment) {
        return annotationOf(attachment, SOURCE_PERMALINK_ANNO);
    }

    private static String annotationOf(Attachment attachment, String key) {
        var metadata = attachment.getMetadata();
        var annotations = metadata == null ? null : metadata.getAnnotations();
        return annotations == null ? null : annotations.get(key);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static <E extends run.halo.app.extension.Extension> Comparator<E> nameComparator() {
        return Comparator.comparing(e -> e.getMetadata().getName());
    }
}
