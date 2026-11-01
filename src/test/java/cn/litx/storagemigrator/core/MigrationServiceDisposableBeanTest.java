package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import cn.litx.storagemigrator.dto.MigrateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.attachment.Policy;
import run.halo.app.extension.ReactiveExtensionClient;

/**
 * 验证 Spring 上下文关闭时，{@link MigrationService} 的清理逻辑确实被触发。
 *
 * <p>Halo 停用插件时会关闭插件的 Spring 上下文（{@code DefaultSpringPlugin.stop()} →
 * {@code ConfigurableApplicationContext.close()}），Spring 在关闭过程中会销毁 bean，
 * 从而调用 {@code DisposableBean.destroy()}。本测试直接验证这条链路成立，
 * 不依赖 Halo 实例与登录凭据。</p>
 */
class MigrationServiceDisposableBeanTest {

    @Test
    @DisplayName("Spring 上下文关闭时，DisposableBean.destroy() 被调用并停掉后台任务")
    void destroyIsInvokedOnContextClose() {
        ReactiveExtensionClient client = Mockito.mock(ReactiveExtensionClient.class);
        Mockito.when(client.fetch(eq(Policy.class), anyString()))
            .thenReturn(Mono.just(new Policy()));
        // 附件列表永不结束：任务会一直挂在 MIGRATING
        Mockito.when(client.list(eq(Attachment.class), any(), any()))
            .thenReturn(Flux.never());
        Mockito.when(client.list(eq(User.class), any(), any()))
            .thenReturn(Flux.never());

        TaskRegistry registry = new TaskRegistry();
        @SuppressWarnings("unchecked")
        var supplier = (org.springframework.beans.factory.ObjectProvider<
            run.halo.app.infra.ExternalUrlSupplier>) Mockito.mock(
                org.springframework.beans.factory.ObjectProvider.class);
        MigrationService service = new MigrationService(client,
            Mockito.mock(run.halo.app.core.extension.service.AttachmentService.class),
            supplier,
            registry,
            Mockito.mock(LinkRewriteService.class),
            "",
            8090);

        MigrationTask[] started = new MigrationTask[1];
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(MigrationService.class, () -> service);
            context.refresh();

            // 起一个跑不完的任务
            started[0] = service.start(
                new MigrateRequest("src", "tgt", false, true), null, null).block();
            assertNotNull(started[0]);
            assertEquals(MigrationTask.Status.MIGRATING, started[0].getStatus());
            assertEquals(1, registry.runningCount());
        }
        // 上下文已关闭

        // Spring 关闭上下文时销毁 bean，destroy() 必然被调用 -> 任务落到终态
        MigrationTask task = started[0];
        assertTrue(task.isTerminal(), "上下文关闭后任务应进入终态");
        assertEquals(MigrationTask.Status.CANCELLED, task.getStatus());
        assertEquals(0, registry.runningCount());
    }
}
