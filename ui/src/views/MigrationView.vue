<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import axios from 'axios'
import PolicySelect from '../components/PolicySelect.vue'
import StorageMigratorIcon from '../components/StorageMigratorIcon.vue'

interface PolicyVo {
  name: string
  displayName: string
  templateName: string
  attachmentCount: number
  totalSize: number
}

interface TaskView {
  id: string
  status: 'PENDING' | 'MIGRATING' | 'REWRITING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  sourcePolicy: string
  targetPolicy: string
  deleteSource: boolean
  rewriteLinks: boolean
  total: number
  processed: number
  succeeded: number
  failed: number
  skipped: number
  currentFile: string
  errors: string[]
  urlMapping: Record<string, string>
  rewriteSummary: string
  bytesDownloaded: number
  bytesUploaded: number
  cancelRequested: boolean
  startedAt: string
  finishedAt: string | null
}

interface PreviewHit {
  url: string
  count: number
}

type ImpactType = 'POST' | 'PAGE' | 'MOMENT' | 'COMMENT' | 'THEME' | 'SETTING' | 'USER'

interface PreviewImpact {
  type: ImpactType
  name: string
  title: string
  contentHits: number
  coverHits: number
  hits: PreviewHit[]
}

interface PreviewVo {
  sourcePolicy: string
  targetPolicy: string
  attachmentTotal: number
  attachmentBytes: number
  reusableCount: number
  pendingUploadCount: number
  referencedAttachments: number
  unreferencedAttachments: number
  postsScanned: number
  pagesScanned: number
  momentsScanned: number
  commentsScanned: number
  configMapsScanned: number
  usersScanned: number
  postsAffected: number
  pagesAffected: number
  momentsAffected: number
  commentsAffected: number
  themesAffected: number
  settingsAffected: number
  usersAffected: number
  totalContentHits: number
  totalCoverHits: number
  items: PreviewImpact[]
}

const API = '/apis/api.console.halo.run/v1alpha1/storage-migrator'

const policies = ref<PolicyVo[]>([])
const loadingPolicies = ref(true)
const source = ref('')
const target = ref('')
const deleteSource = ref(false)
const rewriteLinks = ref(true)
const previewing = ref(false)
const confirming = ref(false)
const errorMessage = ref('')
const preview = ref<PreviewVo | null>(null)
const task = ref<TaskView | null>(null)
const cancelling = ref(false)
/** 实时下行网速（源 -> 本机，字节/秒），滑动窗口差分，消除采样锯齿。 */
const downloadSpeed = ref(0)
/** 实时上行网速（本机 -> 目标，字节/秒），滑动窗口差分。 */
const uploadSpeed = ref(0)

let pollTimer: ReturnType<typeof setInterval> | undefined
/** 网速采样窗口（约 2 秒）：速率按窗口首尾差分，单次采样的突发不会拉出尖峰。 */
const SPEED_WINDOW = 2
let samples: { t: number; down: number; up: number }[] = []

const targetOptions = computed(() => policies.value.filter((p) => p.name !== source.value))
const running = computed(
  () => task.value !== null && ['PENDING', 'MIGRATING', 'REWRITING'].includes(task.value.status),
)

/** 下拉项：主标题是策略名，副标题是文件数与占用。 */
const sourceOptions = computed(() =>
  policies.value.map((p) => ({
    value: p.name,
    label: p.displayName || p.name,
    hint: `${p.attachmentCount} 个文件 · ${formatSize(p.totalSize)}`,
  })),
)

const targetItems = computed(() =>
  targetOptions.value.map((p) => ({
    value: p.name,
    label: p.displayName || p.name,
    hint: `${p.attachmentCount} 个文件 · ${formatSize(p.totalSize)}`,
  })),
)

const totalHits = computed(() =>
  preview.value ? preview.value.totalContentHits + preview.value.totalCoverHits : 0,
)

const TYPE_LABELS: Record<ImpactType, string> = {
  POST: '文章',
  PAGE: '页面',
  MOMENT: '瞬间',
  COMMENT: '评论',
  THEME: '主题配置',
  SETTING: '系统设置',
  USER: '用户头像',
}

function typeLabel(type: ImpactType): string {
  return TYPE_LABELS[type] ?? type
}

/** 预检汇总句：只列出真正会被改动的类别。 */
const affectedSummary = computed(() => {
  const current = preview.value
  if (!current) {
    return []
  }
  const parts: string[] = []
  if (current.postsAffected) {
    parts.push(`${current.postsAffected} 篇文章`)
  }
  if (current.pagesAffected) {
    parts.push(`${current.pagesAffected} 个页面`)
  }
  if (current.momentsAffected) {
    parts.push(`${current.momentsAffected} 条瞬间`)
  }
  if (current.commentsAffected) {
    parts.push(`${current.commentsAffected} 条评论`)
  }
  if (current.themesAffected) {
    parts.push(`${current.themesAffected} 项主题配置`)
  }
  if (current.settingsAffected) {
    parts.push(`${current.settingsAffected} 项系统设置`)
  }
  if (current.usersAffected) {
    parts.push(`${current.usersAffected} 个用户头像`)
  }
  return parts
})

function policyLabel(name: string): string {
  return policies.value.find((p) => p.name === name)?.displayName || name
}

const progress = computed(() => {
  const current = task.value
  if (!current || current.total === 0) {
    return 0
  }
  return Math.min(100, Math.round((current.processed / current.total) * 100))
})

const statusText = computed(() => {
  switch (task.value?.status) {
    case 'PENDING':
      return '准备中'
    case 'MIGRATING':
      return task.value.cancelRequested ? '正在收尾（取消请求已生效）' : '正在迁移文件'
    case 'REWRITING':
      return '正在改写链接引用'
    case 'SUCCEEDED':
      return '迁移完成'
    case 'FAILED':
      return '迁移失败'
    case 'CANCELLED':
      return '已取消'
    default:
      return ''
  }
})

const mappingEntries = computed(() => Object.entries(task.value?.urlMapping ?? {}))

/** 状态指示点配色：运行中蓝、完成绿、失败红、取消灰。 */
const statusDotClass = computed(() => {
  switch (task.value?.status) {
    case 'PENDING':
    case 'MIGRATING':
    case 'REWRITING':
      return 'sm-dot--running'
    case 'SUCCEEDED':
      return 'sm-dot--ok'
    case 'FAILED':
      return 'sm-dot--fail'
    default:
      return 'sm-dot--idle'
  }
})

function formatSize(bytes: number): string {
  if (!bytes) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB']
  let index = 0
  let value = bytes
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024
    index++
  }
  return `${value.toFixed(index === 0 ? 0 : 1)} ${units[index]}`
}

/** 网速展示：B/s、KB/s、MB/s。 */
function formatSpeed(bytesPerSec: number): string {
  return `${formatSize(bytesPerSec)}/s`
}

/** 取消迁移：后端在下一个文件边界停止，已迁移的附件保留、不回退。 */
async function cancelMigration() {
  if (!task.value || cancelling.value) {
    return
  }
  cancelling.value = true
  errorMessage.value = ''
  try {
    const { data } = await axios.post<TaskView>(`${API}/tasks/${task.value.id}/cancel`)
    task.value = data
    if (!['PENDING', 'MIGRATING', 'REWRITING'].includes(data.status)) {
      stopPolling()
      cancelling.value = false
      await loadPolicies()
    }
  } catch (e) {
    errorMessage.value = `取消失败：${describeError(e)}`
    cancelling.value = false
  }
}

async function loadPolicies() {
  loadingPolicies.value = true
  errorMessage.value = ''
  try {
    const { data } = await axios.get<PolicyVo[]>(`${API}/policies`)
    policies.value = data
    const first = data[0]
    if (!source.value && first) {
      source.value = first.name
    }
    if (!target.value) {
      const candidate = data.find((p) => p.name !== source.value)
      target.value = candidate ? candidate.name : ''
    }
  } catch (e) {
    errorMessage.value = `读取存储策略失败：${describeError(e)}`
  } finally {
    loadingPolicies.value = false
  }
}

function describeError(e: unknown): string {
  if (axios.isAxiosError(e)) {
    const data = e.response?.data as { message?: string } | undefined
    return data?.message || e.message
  }
  return e instanceof Error ? e.message : String(e)
}

async function runPreview() {
  if (!source.value || !target.value) {
    errorMessage.value = '请选择源存储策略和目标存储策略'
    return
  }
  if (source.value === target.value) {
    errorMessage.value = '源策略与目标策略不能相同'
    return
  }
  previewing.value = true
  errorMessage.value = ''
  preview.value = null
  try {
    const { data } = await axios.post<PreviewVo>(`${API}/preview`, {
      sourcePolicy: source.value,
      targetPolicy: target.value,
    })
    preview.value = data
  } catch (e) {
    errorMessage.value = `预检失败：${describeError(e)}`
  } finally {
    previewing.value = false
  }
}

function cancelPreview() {
  preview.value = null
}

async function confirmMigration() {
  if (!preview.value) {
    return
  }
  confirming.value = true
  errorMessage.value = ''
  task.value = null
  try {
    const { data } = await axios.post<TaskView>(`${API}/tasks`, {
      sourcePolicy: source.value,
      targetPolicy: target.value,
      deleteSource: deleteSource.value,
      rewriteLinks: rewriteLinks.value,
    })
    preview.value = null
    task.value = data
    startPolling(data.id)
  } catch (e) {
    errorMessage.value = `发起迁移失败：${describeError(e)}`
  } finally {
    confirming.value = false
  }
}

function startPolling(id: string) {
  stopPolling()
  samples = [
    {
      t: Date.now(),
      down: task.value?.bytesDownloaded ?? 0,
      up: task.value?.bytesUploaded ?? 0,
    },
  ]
  pollTimer = setInterval(async () => {
    try {
      const { data } = await axios.get<TaskView>(`${API}/tasks/${id}`)
      task.value = data
      updateSpeed(data.bytesDownloaded, data.bytesUploaded)
      if (!['PENDING', 'MIGRATING', 'REWRITING'].includes(data.status)) {
        stopPolling()
        cancelling.value = false
        downloadSpeed.value = 0
        uploadSpeed.value = 0
        await loadPolicies()
      }
    } catch {
      // 轮询失败不打断页面，下一轮继续
    }
  }, 1000)
}

/** 滑动窗口差分 + 轻量平滑：窗口约 2 秒响应快，EMA 压掉单点抖动。 */
function updateSpeed(downloaded: number, uploaded: number) {
  samples.push({ t: Date.now(), down: downloaded, up: uploaded })
  if (samples.length > SPEED_WINDOW + 1) {
    samples.shift()
  }
  const first = samples[0]
  const last = samples[samples.length - 1]
  if (!first || !last || samples.length < 2) {
    return
  }
  const dt = (last.t - first.t) / 1000
  if (dt <= 0) {
    return
  }
  const down = Math.max(0, (last.down - first.down) / dt)
  const up = Math.max(0, (last.up - first.up) / dt)
  downloadSpeed.value = downloadSpeed.value === 0 ? down : downloadSpeed.value * 0.5 + down * 0.5
  uploadSpeed.value = uploadSpeed.value === 0 ? up : uploadSpeed.value * 0.5 + up * 0.5
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = undefined
  }
}

/** 页面重新进入时恢复任务：优先展示进行中的任务，其次是最近一次已完成任务。 */
async function restoreLatestTask() {
  try {
    const { data } = await axios.get<TaskView[]>(`${API}/tasks`)
    if (!data.length) {
      return
    }
    const active = data.find((t) => ['PENDING', 'MIGRATING', 'REWRITING'].includes(t.status))
    const latest: TaskView | undefined = active ?? data[0]
    if (!latest) {
      return
    }
    task.value = latest
    if (active) {
      // 表单同步为进行中任务的配置，避免恢复后显示与实际迁移不符
      source.value = active.sourcePolicy
      target.value = active.targetPolicy
      deleteSource.value = active.deleteSource
      rewriteLinks.value = active.rewriteLinks
      startPolling(active.id)
    }
  } catch {
    // 恢复失败不影响页面主流程
  }
}

onMounted(() => {
  loadPolicies()
  restoreLatestTask()
})
onUnmounted(stopPolling)
</script>

<template>
  <div class="sm-page">
    <header class="sm-header">
      <div class="sm-header__titlebar">
        <StorageMigratorIcon class="sm-header__icon" :size="22" />
        <h1 class="sm-header__title">存储策略迁移</h1>
      </div>
      <p class="sm-header__desc">
        把附件从一个存储策略搬运到另一个存储策略，并自动改写文章、独立页面、瞬间、评论、用户头像、主题配置与系统设置中引用旧地址的链接。
      </p>
    </header>

    <div v-if="errorMessage" class="sm-alert">{{ errorMessage }}</div>

    <div class="sm-grid">
      <section class="sm-card">
        <h2 class="sm-card__title">迁移配置</h2>

        <div v-if="loadingPolicies" class="sm-empty">加载存储策略…</div>

        <template v-else>
          <label class="sm-label">源存储策略</label>
          <PolicySelect
            v-model="source"
            :options="sourceOptions"
            :disabled="running"
            placeholder="请选择源存储策略"
          />

          <label class="sm-label">目标存储策略</label>
          <PolicySelect
            v-model="target"
            :options="targetItems"
            :disabled="running"
            placeholder="请选择目标存储策略"
          />

          <label class="sm-check">
            <input v-model="deleteSource" type="checkbox" :disabled="running" />
            <span>迁移后删除源文件（移动模式，不勾选则复制保留）</span>
          </label>
          <label class="sm-check">
            <input v-model="rewriteLinks" type="checkbox" :disabled="running" />
            <span
              >迁移完成后自动改写旧链接（文章 / 页面 / 瞬间 / 评论 / 用户头像 / 主题配置 /
              系统设置）</span
            >
          </label>

          <button
            class="sm-button"
            :disabled="running || previewing || !source || !target"
            @click="runPreview"
          >
            {{ previewing ? '正在预检…' : '预检影响面' }}
          </button>

          <p class="sm-hint">
            先预检：列出会搬运多少文件、哪些内容会被改动、各改几处，确认后才真正执行。
          </p>

          <p class="sm-hint">
            迁移通过附件的公开地址回抓原始文件，再按目标策略重新上传，因此适用于任意存储类型（本地 /
            S3 / OSS 等）。新建的附件会标注来源，重复执行时自动复用，不会重复上传。
          </p>
        </template>
      </section>

      <section class="sm-card">
        <h2 class="sm-card__title">迁移进度</h2>

        <div v-if="!task" class="sm-empty">尚未开始任务</div>

        <template v-else>
          <div class="sm-progress__head">
            <span class="sm-progress__status">
              <span class="sm-dot" :class="statusDotClass"></span>
              {{ statusText }}
            </span>
            <span class="sm-progress__count">{{ task.processed }} / {{ task.total }}</span>
          </div>
          <p class="sm-hint sm-progress__route">
            {{ policyLabel(task.sourcePolicy) }} → {{ policyLabel(task.targetPolicy) }} ·
            {{ task.deleteSource ? '移动模式' : '复制模式'
            }}<template v-if="task.rewriteLinks"> · 自动改写链接</template>
          </p>
          <div class="sm-progress__track">
            <div class="sm-progress__bar" :style="{ width: progress + '%' }"></div>
          </div>

          <dl class="sm-stats">
            <div class="sm-stat">
              <dt>成功</dt>
              <dd class="sm-stat__ok">{{ task.succeeded }}</dd>
            </div>
            <div class="sm-stat">
              <dt>失败</dt>
              <dd class="sm-stat__fail">{{ task.failed }}</dd>
            </div>
            <div class="sm-stat">
              <dt>跳过</dt>
              <dd>{{ task.skipped }}</dd>
            </div>
          </dl>

          <div class="sm-net">
            <div class="sm-net__item">
              <dt>下行</dt>
              <dd :class="downloadSpeed > 0 ? 'sm-net__live' : 'sm-net__idle'">
                {{ running && downloadSpeed > 0 ? formatSpeed(downloadSpeed) : '—' }}
              </dd>
            </div>
            <div class="sm-net__item">
              <dt>上行</dt>
              <dd :class="uploadSpeed > 0 ? 'sm-net__live' : 'sm-net__idle'">
                {{ running && uploadSpeed > 0 ? formatSpeed(uploadSpeed) : '—' }}
              </dd>
            </div>
            <div class="sm-net__item">
              <dt>已搬运</dt>
              <dd>{{ formatSize(task.bytesDownloaded) }}</dd>
            </div>
          </div>

          <p v-if="task.currentFile" class="sm-hint sm-ellipsis">当前：{{ task.currentFile }}</p>
          <p v-if="task.rewriteSummary" class="sm-hint">链接改写：{{ task.rewriteSummary }}</p>
          <p v-if="task.status === 'CANCELLED'" class="sm-hint sm-cancel-note">
            已迁移的附件保留、不会回退；重新执行同一迁移会自动复用已迁移的副本并补齐剩余部分与链接改写。
          </p>

          <div v-if="task.errors.length" class="sm-errors">
            <p class="sm-errors__title">失败明细</p>
            <ul>
              <li v-for="(err, i) in task.errors" :key="i">{{ err }}</li>
            </ul>
          </div>

          <button
            v-if="running"
            class="sm-button sm-button--danger"
            :disabled="cancelling || task.cancelRequested"
            @click="cancelMigration"
          >
            {{ cancelling || task.cancelRequested ? '正在停止…' : '取消迁移' }}
          </button>
          <p v-if="running" class="sm-hint">取消后不再继续迁移，已完成的附件保留、不回退。</p>
        </template>
      </section>
    </div>

    <section v-if="mappingEntries.length" class="sm-card sm-card--wide">
      <h2 class="sm-card__title">地址变更（{{ mappingEntries.length }}）</h2>
      <div class="sm-table-wrap">
        <table class="sm-table">
          <thead>
            <tr>
              <th>旧地址</th>
              <th>新地址</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="[from, to] in mappingEntries" :key="from">
              <td class="sm-mono">{{ from }}</td>
              <td class="sm-mono sm-table__new">{{ to }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <div v-if="preview" class="sm-modal">
      <div class="sm-modal__panel">
        <header class="sm-modal__head">
          <h2 class="sm-modal__title">迁移前影响面预检</h2>
          <p class="sm-modal__sub">
            {{ policyLabel(preview.sourcePolicy) }} → {{ policyLabel(preview.targetPolicy) }}
          </p>
        </header>

        <div class="sm-modal__body">
          <dl class="sm-preview-stats">
            <div class="sm-preview-stat">
              <dt>待搬运</dt>
              <dd>{{ preview.attachmentTotal }} 个 · {{ formatSize(preview.attachmentBytes) }}</dd>
            </div>
            <div class="sm-preview-stat">
              <dt>可复用</dt>
              <dd>{{ preview.reusableCount }} 个</dd>
            </div>
            <div class="sm-preview-stat">
              <dt>需上传</dt>
              <dd>{{ preview.pendingUploadCount }} 个</dd>
            </div>
            <div class="sm-preview-stat">
              <dt>被引用</dt>
              <dd>{{ preview.referencedAttachments }} 个</dd>
            </div>
          </dl>

          <p class="sm-preview-summary">
            <template v-if="affectedSummary.length">
              将改动 <strong>{{ affectedSummary.join('、') }}</strong
              >，共 <strong>{{ totalHits }}</strong> 处引用（正文 / 配置
              {{ preview.totalContentHits }} 处 · 封面 / 头像 {{ preview.totalCoverHits }} 处）。
            </template>
            <template v-else>没有内容引用这些附件的旧地址，迁移不会改动任何内容。</template>
            <span v-if="preview.unreferencedAttachments">
              另有 {{ preview.unreferencedAttachments }} 个附件没有任何地方引用。
            </span>
          </p>

          <p class="sm-preview-scope">
            已扫描：文章 {{ preview.postsScanned }} 篇 · 独立页面
            {{ preview.pagesScanned }} 个<template v-if="preview.momentsScanned">
              · 瞬间 {{ preview.momentsScanned }} 条</template
            >
            <template v-if="preview.commentsScanned">
              · 评论 {{ preview.commentsScanned }} 条</template
            >
            · 设置项 {{ preview.configMapsScanned }} 个（含主题配置与插件设置）
            <template v-if="preview.usersScanned"> · 用户 {{ preview.usersScanned }} 个</template>
          </p>

          <p v-if="!rewriteLinks" class="sm-preview-warn">
            当前没有勾选「自动改写链接」，下面这些内容不会真的被改动。
          </p>

          <p v-if="!preview.items.length" class="sm-empty">
            文章、页面、瞬间、评论、主题配置、系统设置、用户头像里都没有引用这些附件的旧地址，迁移不会改动任何内容。
          </p>

          <ul v-else class="sm-impact">
            <li v-for="item in preview.items" :key="item.type + item.name" class="sm-impact__item">
              <div class="sm-impact__head">
                <span class="sm-impact__type">{{ typeLabel(item.type) }}</span>
                <span
                  v-if="item.title && item.title !== typeLabel(item.type)"
                  class="sm-impact__title"
                >
                  {{ item.title }}
                </span>
                <span v-else class="sm-impact__title">{{ item.name }}</span>
                <span class="sm-impact__count">{{ item.contentHits + item.coverHits }} 处</span>
              </div>
              <p class="sm-impact__meta">
                <template v-if="item.type === 'POST' || item.type === 'PAGE'">
                  正文 {{ item.contentHits }} 处<template v-if="item.coverHits">
                    · 封面 {{ item.coverHits }} 处</template
                  >
                </template>
                <template v-else-if="item.type === 'USER'"> 头像 {{ item.coverHits }} 处 </template>
                <template v-else>命中 {{ item.contentHits }} 处</template>
              </p>
              <ul class="sm-impact__hits">
                <li v-for="hit in item.hits" :key="hit.url">
                  <code class="sm-mono">{{ hit.url }}</code> × {{ hit.count }}
                </li>
              </ul>
            </li>
          </ul>
        </div>

        <footer class="sm-modal__foot">
          <button class="sm-button sm-button--ghost" :disabled="confirming" @click="cancelPreview">
            取消
          </button>
          <button class="sm-button" :disabled="confirming" @click="confirmMigration">
            {{ confirming ? '正在执行…' : '确认执行迁移' }}
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sm-page {
  padding: 16px;
  color: #0f172a;
}

.sm-header {
  margin-bottom: 16px;
}

.sm-header__titlebar {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sm-header__icon {
  flex: none;
  color: #2563eb;
}

.sm-header__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
}

.sm-header__desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: #64748b;
}

.sm-alert {
  margin-bottom: 16px;
  padding: 10px 12px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.sm-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: 1fr;
}

@media (min-width: 1024px) {
  .sm-grid {
    grid-template-columns: 1fr 1fr;
  }
}

.sm-card {
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #fff;
  padding: 18px;
}

.sm-card--wide {
  margin-top: 16px;
}

.sm-card__title {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 600;
}

.sm-label {
  display: block;
  margin-bottom: 6px;
  font-size: 13px;
  color: #475569;
}

.sm-check {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 10px;
  font-size: 13px;
  color: #334155;
  cursor: pointer;
}

.sm-check input {
  margin-top: 2px;
}

.sm-button {
  margin-top: 6px;
  padding: 9px 20px;
  border: none;
  border-radius: 6px;
  background: #0f172a;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}

.sm-button:hover:not(:disabled) {
  background: #1e293b;
}

.sm-button:disabled {
  background: #94a3b8;
  cursor: not-allowed;
}

.sm-button--danger {
  margin-top: 12px;
  background: #fff;
  border: 1px solid #fca5a5;
  color: #b91c1c;
}

.sm-button--danger:hover:not(:disabled) {
  background: #fef2f2;
}

.sm-button--danger:disabled {
  border-color: #e2e8f0;
  background: #f8fafc;
  color: #94a3b8;
}

.sm-hint {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: #94a3b8;
}

.sm-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sm-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: #94a3b8;
}

.sm-progress__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 13px;
}

.sm-progress__status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
}

.sm-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #94a3b8;
}

.sm-dot--running {
  background: #2563eb;
  animation: sm-pulse 1.4s ease-in-out infinite;
}

.sm-dot--ok {
  background: #059669;
}

.sm-dot--fail {
  background: #dc2626;
}

.sm-dot--idle {
  background: #94a3b8;
}

@keyframes sm-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.sm-net {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  margin: 0 0 12px;
}

.sm-net__item {
  padding: 8px 0;
  border-radius: 8px;
  background: #f8fafc;
  text-align: center;
}

.sm-net__item dt {
  font-size: 12px;
  color: #64748b;
}

.sm-net__item dd {
  margin: 2px 0 0;
  font-size: 15px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.sm-net__live {
  color: #2563eb;
}

.sm-net__idle {
  color: #94a3b8;
}

.sm-cancel-note {
  padding: 8px 10px;
  border-radius: 6px;
  background: #fffbeb;
  color: #b45309;
}

.sm-progress__count {
  color: #64748b;
}

.sm-progress__route {
  margin: -2px 0 10px;
}

.sm-progress__track {
  height: 8px;
  margin-bottom: 14px;
  border-radius: 999px;
  background: #f1f5f9;
  overflow: hidden;
}

.sm-progress__bar {
  height: 100%;
  background: #0f172a;
  transition: width 0.3s ease;
}

.sm-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 0 0 12px;
}

.sm-stat {
  padding: 8px 0;
  border-radius: 8px;
  background: #f8fafc;
  text-align: center;
}

.sm-stat dt {
  font-size: 12px;
  color: #64748b;
}

.sm-stat dd {
  margin: 2px 0 0;
  font-size: 15px;
  font-weight: 600;
}

.sm-stat__ok {
  color: #059669;
}

.sm-stat__fail {
  color: #dc2626;
}

.sm-errors {
  max-height: 160px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: #fef2f2;
  overflow: auto;
}

.sm-errors__title {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: #b91c1c;
}

.sm-errors ul {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  color: #dc2626;
}

.sm-table-wrap {
  max-height: 320px;
  overflow: auto;
}

.sm-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.sm-table th {
  padding: 8px 12px 8px 0;
  border-bottom: 1px solid #e2e8f0;
  text-align: left;
  font-weight: 500;
  color: #64748b;
}

.sm-table td {
  padding: 7px 12px 7px 0;
  border-bottom: 1px solid #f1f5f9;
  color: #475569;
}

.sm-table__new {
  color: #0f172a;
}

.sm-mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  word-break: break-all;
}

.sm-button--ghost {
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #334155;
}

.sm-button--ghost:hover:not(:disabled) {
  background: #f8fafc;
}

.sm-modal {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.45);
}

.sm-modal__panel {
  display: flex;
  flex-direction: column;
  width: min(720px, 100%);
  max-height: min(720px, 90vh);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 20px 50px rgba(15, 23, 42, 0.25);
  overflow: hidden;
}

.sm-modal__head {
  padding: 18px 20px 0;
}

.sm-modal__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #0f172a;
}

.sm-modal__sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
}

.sm-modal__body {
  padding: 14px 20px;
  overflow: auto;
}

.sm-modal__foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid #e2e8f0;
  background: #f8fafc;
}

.sm-preview-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin: 0 0 14px;
}

.sm-preview-stat {
  padding: 8px;
  border-radius: 8px;
  background: #f8fafc;
  text-align: center;
}

.sm-preview-stat dt {
  font-size: 11px;
  color: #64748b;
}

.sm-preview-stat dd {
  margin: 3px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.sm-preview-summary {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.7;
  color: #334155;
}

.sm-preview-summary strong {
  color: #0f172a;
  font-weight: 600;
}

.sm-preview-warn {
  margin: 0 0 12px;
  padding: 8px 10px;
  border-radius: 6px;
  background: #fffbeb;
  color: #b45309;
  font-size: 12px;
}

.sm-preview-scope {
  margin: 0 0 12px;
  font-size: 12px;
  color: #64748b;
}

.sm-impact {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.sm-impact__item {
  padding: 10px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.sm-impact__head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sm-impact__type {
  padding: 1px 6px;
  border-radius: 4px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 11px;
}

.sm-impact__title {
  flex: 1;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sm-impact__count {
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
}

.sm-impact__meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: #64748b;
}

.sm-impact__hits {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin: 6px 0 0;
  padding-left: 0;
  list-style: none;
}

.sm-impact__hits li {
  font-size: 11px;
  color: #64748b;
}
</style>
