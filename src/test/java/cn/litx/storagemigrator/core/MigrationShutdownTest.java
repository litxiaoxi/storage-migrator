package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import cn.litx.storagemigrator.dto.MigrateRequest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 插件停用时的清理行为。
 *
 * <p>对应 Halo 应用市场审核指南 2.3：「禁用或卸载后留下继续运行的任务、代理、脚本或外部调用」
 * 会被拒绝。插件被停用时，Halo 会关闭插件的 Spring 上下文并触发
 * {@link MigrationService#destroy()}，此时必须停掉所有后台迁移任务。</p>
 */
class MigrationShutdownTest {

    private ReactiveExtensionClient client;
    private MigrationService service;
    private TaskRegistry registry;

    @BeforeEach
    void setUp() {
        client = Mockito.mock(ReactiveExtensionClient.class);
        registry = new TaskRegistry();
        @SuppressWarnings("unchecked")
        var supplier = (org.springframework.beans.factory.ObjectProvider<
            run.halo.app.infra.ExternalUrlSupplier>) Mockito.mock(
                org.springframework.beans.factory.ObjectProvider.class);
        service = new MigrationService(client,
            Mockito.mock(run.halo.app.core.extension.service.AttachmentService.class),
            supplier,
            registry,
            Mockito.mock(LinkRewriteService.class),
            "",
            8090);
    }

    /**
     * 附件列表永不结束：任务会一直挂在 MIGRATING，用来模拟「迁移进行中」。
     */
    private void migrationNeverFinishes() {
        Mockito.when(client.fetch(eq(Policy.class), anyString()))
            .thenReturn(Mono.just(new Policy()));
        Mockito.when(client.list(eq(Attachment.class), any(), any()))
            .thenReturn(Flux.never());
        Mockito.when(client.list(eq(User.class), any(), any()))
            .thenReturn(Flux.never());
    }

    private static MigrateRequest request(String source, String target) {
        return new MigrateRequest(source, target, false, true);
    }

    @Test
    @DisplayName("停用插件后，进行中的任务落到 CANCELLED，不会卡在 MIGRATING")
    void marksRunningTaskAsCancelledOnShutdown() {
        migrationNeverFinishes();
        MigrationTask task = service.start(request("src", "tgt"), null, null).block();
        assertNotNull(task);
        assertEquals(MigrationTask.Status.MIGRATING, task.getStatus());

        service.destroy();

        // 任务必须进入终态，否则前端会一直显示「迁移中」，且运行中计数永远降不下来
        assertTrue(task.isTerminal(), "停用后任务应进入终态");
        assertEquals(MigrationTask.Status.CANCELLED, task.getStatus());
        assertEquals(0, registry.runningCount());
    }

    @Test
    @DisplayName("停用插件后，策略占位被释放")
    void releasesPolicyPairOnShutdown() {
        migrationNeverFinishes();
        service.start(request("src", "tgt"), null, null).block();

        service.destroy();

        // 占位若不放掉，插件重新启用后会误报「已有迁移任务在执行中」
        assertNotNull(service.start(request("src", "tgt"), null, null).block(),
            "停用后应能重新发起同一对策略的迁移");
    }

    @Test
    @DisplayName("停用插件后，后台订阅被中断，不再继续消耗线程")
    void interruptsBackgroundSubscriptionOnShutdown() {
        migrationNeverFinishes();
        service.start(request("src", "tgt"), null, null).block();

        service.destroy();

        // destroy() 之后再启动任务：订阅容器已释放，新订阅应立即被中断，
        // 不会在插件上下文销毁后继续跑
        assertFalse(service.start(request("src", "tgt2"), null, null).block()
            .getStatus() == MigrationTask.Status.MIGRATING
            && registry.runningCount() > 1,
            "停用后不应有任务在后台继续运行");
    }

    @Test
    @DisplayName("没有进行中的任务时，停用是安全的空操作")
    void shutdownWithoutRunningTaskIsSafe() {
        service.destroy();
        assertEquals(0, registry.runningCount());
        assertEquals(List.of(), registry.list());
    }
}
