package cn.litx.storagemigrator;

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

    public StorageMigratorPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }
}
