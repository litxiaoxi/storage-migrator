package cn.litx.storagemigrator;

import cn.litx.storagemigrator.core.MigrationService;
import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 存储策略迁移插件。
 *
 * <p>核心能力：把附件从一个存储策略搬运到另一个存储策略，并改写文章 / 独立页面里引用旧地址的链接。</p>
 *
 * @author litx
 */
@Component
public class StorageMigratorPlugin extends BasePlugin {

    private final MigrationService migrationService;

    public StorageMigratorPlugin(PluginContext pluginContext, MigrationService migrationService) {
        super(pluginContext);
        this.migrationService = migrationService;
    }

    /**
     * 插件被停用或卸载时停止后台迁移任务。
     *
     * <p>pf4j 在停用插件时必然调用本方法（{@code DefaultSpringPlugin.stop()} 会转发到插件实例），
     * 时机早于 Spring 上下文关闭，是最可靠的清理点。</p>
     *
     * <p>{@link MigrationService} 同时实现了 {@code DisposableBean}，上下文关闭时也会再兜一次，
     * 两条路径都幂等，重复调用无副作用。</p>
     */
    @Override
    public void stop() {
        try {
            migrationService.shutdown();
        } finally {
            super.stop();
        }
    }
}
