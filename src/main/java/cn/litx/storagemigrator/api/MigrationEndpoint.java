package cn.litx.storagemigrator.api;

import cn.litx.storagemigrator.core.LinkRewriteService;
import cn.litx.storagemigrator.core.MigrationConflictException;
import cn.litx.storagemigrator.core.MigrationService;
import cn.litx.storagemigrator.core.TaskRegistry;
import cn.litx.storagemigrator.dto.MigrateRequest;
import cn.litx.storagemigrator.dto.PolicyVo;
import cn.litx.storagemigrator.dto.PreviewRequest;
import cn.litx.storagemigrator.dto.RewriteRequest;
import cn.litx.storagemigrator.dto.TaskView;
import java.net.URI;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;

/**
 * 插件对外 API。
 *
 * <p>挂载在 {@code /apis/api.console.halo.run/v1alpha1/storage-migrator} 下：</p>
 * <ul>
 *   <li>GET  /policies —— 列出存储策略</li>
 *   <li>POST /preview —— 迁移前影响面预检（只读）</li>
 *   <li>POST /tasks —— 创建迁移任务</li>
 *   <li>GET  /tasks —— 任务列表</li>
 *   <li>GET  /tasks/{id} —— 任务详情</li>
 *   <li>POST /tasks/{id}/cancel —— 取消迁移任务（已迁移的附件保留）</li>
 *   <li>POST /rewrite —— 仅改写链接（支持预演）</li>
 * </ul>
 */
@Component
public class MigrationEndpoint implements CustomEndpoint {

    private final MigrationService migrationService;
    private final LinkRewriteService linkRewriteService;
    private final TaskRegistry registry;

    public MigrationEndpoint(MigrationService migrationService,
                             LinkRewriteService linkRewriteService,
                             TaskRegistry registry) {
        this.migrationService = migrationService;
        this.linkRewriteService = linkRewriteService;
        this.registry = registry;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/storage-migrator/policies", this::policies)
            .POST("/storage-migrator/preview", this::preview)
            .POST("/storage-migrator/tasks", this::createTask)
            .GET("/storage-migrator/tasks", this::listTasks)
            .GET("/storage-migrator/tasks/{id}", this::getTask)
            .POST("/storage-migrator/tasks/{id}/cancel", this::cancelTask)
            .POST("/storage-migrator/rewrite", this::rewrite)
            .build();
    }

    private Mono<ServerResponse> policies(ServerRequest request) {
        return migrationService.listPolicies()
            .collectList()
            .flatMap(list -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(list))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> createTask(ServerRequest request) {
        String baseUrl = requestBaseUrl(request);
        return request.bodyToMono(MigrateRequest.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("请求体不能为空")))
            // 捕获当前登录上下文，供后台线程调用 AttachmentService 时使用
            .flatMap(body -> ReactiveSecurityContextHolder.getContext()
                .flatMap(ctx -> migrationService.start(body, ctx, baseUrl))
                .switchIfEmpty(Mono.defer(() -> migrationService.start(body, null, baseUrl))))
            .flatMap(task -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(task.view()))
            .onErrorResume(MigrationConflictException.class, this::conflict)
            .onErrorResume(this::badRequest);
    }

    /**
     * 迁移前预检：只读，返回会搬多少文件、会改哪些文章 / 页面以及各改几处。
     */
    private Mono<ServerResponse> preview(ServerRequest request) {
        return request.bodyToMono(PreviewRequest.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("请求体不能为空")))
            .flatMap(body -> migrationService.preview(body.sourcePolicy(), body.targetPolicy()))
            .flatMap(vo -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(vo))
            .onErrorResume(this::badRequest);
    }

    /**
     * 从当前请求还原站点源地址，用于回抓源存储中的文件。
     * 比依赖配置项更可靠（配置里可能缺端口或是相对地址）。
     */
    private String requestBaseUrl(ServerRequest request) {
        try {
            URI uri = request.uri();
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            String normalizedHost = host.contains(":") && !host.startsWith("[")
                ? "[" + host + "]" : host;
            int port = uri.getPort();
            return scheme + "://" + normalizedHost + (port > 0 ? ":" + port : "");
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<ServerResponse> listTasks(ServerRequest request) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registry.list());
    }

    private Mono<ServerResponse> getTask(ServerRequest request) {
        String id = request.pathVariable("id");
        return registry.get(id)
            .map(task -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(task.view()))
            .orElseGet(() -> ServerResponse.notFound().build());
    }

    /**
     * 取消迁移任务：在下一个文件边界停止，已迁移的附件保留、不回退。
     */
    private Mono<ServerResponse> cancelTask(ServerRequest request) {
        String id = request.pathVariable("id");
        var taskOpt = registry.get(id);
        if (taskOpt.isEmpty()) {
            return ServerResponse.notFound().build();
        }
        var task = taskOpt.get();
        if (!task.requestCancel()) {
            return ServerResponse.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "任务已结束，无法取消"));
        }
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(task.view());
    }

    private Mono<ServerResponse> rewrite(ServerRequest request) {
        return request.bodyToMono(RewriteRequest.class)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("请求体不能为空")))
            .flatMap(body -> {
                if (body.urlMapping() == null) {
                    return Mono.error(new IllegalArgumentException("缺少 urlMapping"));
                }
                return linkRewriteService.rewrite(body.urlMapping(), body.isDryRun());
            })
            .flatMap(result -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                    "summary", result.summary(),
                    "postsScanned", result.postsScanned(),
                    "postsUpdated", result.postsUpdated(),
                    "pagesScanned", result.pagesScanned(),
                    "pagesUpdated", result.pagesUpdated(),
                    "snapshotsUpdated", result.snapshotsUpdated(),
                    "details", result.details())))
            .onErrorResume(this::badRequest);
    }

    private Mono<ServerResponse> badRequest(Throwable e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        return ServerResponse.badRequest()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("message", message));
    }

    private Mono<ServerResponse> conflict(Throwable e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        return ServerResponse.status(HttpStatus.CONFLICT)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("message", message));
    }
}
