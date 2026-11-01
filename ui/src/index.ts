import { definePlugin } from '@halo-dev/ui-shared'
import { IconPlug } from '@halo-dev/components'
import { markRaw } from 'vue'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/storage-migrator',
        name: 'StorageMigrator',
        component: () => import('./views/MigrationView.vue'),
        meta: {
          title: '存储策略迁移',
          searchable: true,
          menu: {
            name: '存储策略迁移',
            group: 'tool',
            icon: markRaw(IconPlug),
            priority: 0,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
