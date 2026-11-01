package cn.litx.storagemigrator.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import cn.litx.storagemigrator.dto.MigrateRequest;
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
 * 并发保护：同一对存储策略同时只允许一个迁移任务。
 *
 * <p>并发的两个任务各自持有任务启动时拍下的目标策略快照，互相看不见对方的产物，
 * 于是同一批文件会被上传两遍（实测 91 个变 181 个），用户头像也会被后完成的任务抢改。</p>
 */
class MigrationConcurrencyTest {

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

    private void policiesNeverFinish() {
        Mockito.when(client.fetch(eq(Policy.class), anyString()))
            .thenReturn(Mono.just(new Policy()));
        Mockito.when(client.list(eq(Attachment.class), any(), any()))
            .thenReturn(Flux.never());
        Mockito.when(client.list(eq(User.class), any(), any()))
            .thenReturn(Flux.never());
    }

    private void policiesFinishImmediately() {
        Mockito.when(client.fetch(eq(Policy.class), anyString()))
            .thenReturn(Mono.just(new Policy()));
        Mockito.when(client.list(eq(Attachment.class), any(), any()))
            .thenReturn(Flux.empty());
        Mockito.when(client.list(eq(User.class), any(), any()))
            .thenReturn(Flux.empty());
    }

    private static MigrateRequest request(String source, String target) {
        return new MigrateRequest(source, target, false, true);
    }

    @Test
    @DisplayName("同一对策略上已经有任务在跑时，第二个任务直接被拒")
    void rejectsSecondTaskOnSamePair() {
        policiesNeverFinish();
        assertNotNull(service.start(request("src", "tgt"), null, null).block());

        MigrationConflictException error = assertThrows(MigrationConflictException.class,
            () -> service.start(request("src", "tgt"), null, null).block());
        assertEquals("已有「src -> tgt」的迁移任务在执行中，请等待其完成后再发起",
            error.getMessage());
    }

    @Test
    @DisplayName("方向不同或其它策略组合不受影响")
    void allowsDifferentPairs() {
        policiesNeverFinish();
        assertNotNull(service.start(request("src", "tgt"), null, null).block());

        // 反向迁移是另一个方向，不该被挡
        assertNotNull(service.start(request("tgt", "src"), null, null).block());
        assertNotNull(service.start(request("src", "other"), null, null).block());
    }

    @Test
    @DisplayName("任务结束后同一对策略可以再次发起")
    void releasesPairAfterFinish() throws InterruptedException {
        policiesFinishImmediately();
        MigrationTask first = service.start(request("src", "tgt"), null, null).block();
        assertEquals(MigrationTask.Status.MIGRATING, first.getStatus());

        // 后台线程跑完（附件与用户列表都是空的，很快结束）
        long deadline = System.currentTimeMillis() + 5000;
        while (first.getStatus() == MigrationTask.Status.MIGRATING
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(MigrationTask.Status.SUCCEEDED, first.getStatus());

        MigrationTask second = service.start(request("src", "tgt"), null, null).block();
        assertEquals(MigrationTask.Status.MIGRATING, second.getStatus());
    }
}
