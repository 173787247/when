<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { request, ApiError, type ClusterNode, type MessageDetails, type MessagePage,
  type MessageStatus, type SinkType, type TimeWheel } from './api/generated'

type LoadState = 'loading' | 'ready' | 'empty' | 'error'
const active = ref('messages')
const messageState = ref<LoadState>('loading')
const wheelState = ref<LoadState>('loading')
const nodeState = ref<LoadState>('loading')
const page = ref<MessagePage>({ items: [], has_more: false, index_updated_at: 0 })
const wheels = ref<TimeWheel[]>([])
const nodes = ref<ClusterNode[]>([])
const status = ref<MessageStatus | ''>('')
const sink = ref<SinkType | ''>('')
const tag = ref('')
const cursorHistory = ref<string[]>([])
const details = ref<MessageDetails | null>(null)
const detailsVisible = ref(false)
const writing = ref(false)
const createCount = ref(1)
const lastRequestId = ref('')
const delayed = computed(() => page.value.index_updated_at === 0 || Date.now() - page.value.index_updated_at > 60_000)

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    lastRequestId.value = error.requestId
    return `${error.code}: ${error.message}（request_id: ${error.requestId}）`
  }
  return '请求失败，请稍后重试'
}

async function loadMessages(cursor?: string) {
  messageState.value = 'loading'
  const params = new URLSearchParams({ limit: '50' })
  if (status.value) params.set('status', status.value)
  if (sink.value) params.set('sink_type', sink.value)
  if (tag.value) params.set('tag', tag.value)
  if (cursor) params.set('cursor', cursor)
  try {
    const result = await request<MessagePage>(`/admin/v1/messages?${params}`)
    page.value = result.data
    lastRequestId.value = result.request_id
    messageState.value = result.data.items.length ? 'ready' : 'empty'
  } catch (error) {
    messageState.value = 'error'
    ElMessage.error(errorMessage(error))
  }
}

async function nextPage() {
  if (!page.value.next_cursor) return
  cursorHistory.value.push(page.value.next_cursor)
  await loadMessages(page.value.next_cursor)
}

async function resetMessages() {
  cursorHistory.value = []
  await loadMessages()
}

async function openDetails(id: string) {
  detailsVisible.value = true
  details.value = null
  try {
    const result = await request<MessageDetails>(`/admin/v1/messages/${encodeURIComponent(id)}`)
    details.value = result.data
    lastRequestId.value = result.request_id
  } catch (error) {
    detailsVisible.value = false
    ElMessage.error(errorMessage(error))
  }
}

async function loadWheels() {
  wheelState.value = 'loading'
  try {
    const result = await request<{items: TimeWheel[]}>('/admin/v1/timewheels')
    wheels.value = result.data.items
    lastRequestId.value = result.request_id
    wheelState.value = wheels.value.length ? 'ready' : 'empty'
  } catch (error) { wheelState.value = 'error'; ElMessage.error(errorMessage(error)) }
}

async function createWheels() {
  if (writing.value) return
  writing.value = true
  const key = `web-${crypto.randomUUID()}`
  try {
    const result = await request('/admin/v1/timewheels', {
      method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ count: createCount.value })
    })
    lastRequestId.value = result.request_id
    ElMessage.success(`创建成功（request_id: ${result.request_id}）`)
    await loadWheels()
  } catch (error) { ElMessage.error(errorMessage(error)) }
  finally { writing.value = false }
}

async function loadNodes() {
  nodeState.value = 'loading'
  try {
    const result = await request<{items: ClusterNode[]}>('/admin/v1/cluster/nodes')
    nodes.value = result.data.items
    lastRequestId.value = result.request_id
    nodeState.value = nodes.value.length ? 'ready' : 'empty'
  } catch (error) { nodeState.value = 'error'; ElMessage.error(errorMessage(error)) }
}

function formatTime(value: number) { return value ? new Date(value).toLocaleString() : '—' }
onMounted(() => Promise.all([loadMessages(), loadWheels(), loadNodes()]))
</script>

<template>
  <el-container class="shell">
    <el-header class="header">
      <div><span class="brand">WHEN</span><span class="subtitle">延时投递管理台</span></div>
      <el-tag type="warning" effect="plain">可信内网</el-tag>
    </el-header>
    <el-main>
      <el-alert v-if="delayed" title="列表索引可能延迟；按 ID 查询仍读取消息主记录" type="warning" show-icon />
      <el-tabs v-model="active">
        <el-tab-pane label="延时消息" name="messages">
          <el-card shadow="never">
            <el-form inline>
              <el-form-item label="状态"><el-select v-model="status" clearable style="width: 160px"><el-option v-for="v in ['PENDING','DELIVERING','DELIVERED','FAILED','CANCELLED']" :key="v" :label="v" :value="v" /></el-select></el-form-item>
              <el-form-item label="Sink"><el-select v-model="sink" clearable style="width: 130px"><el-option label="HTTP" value="HTTP" /><el-option label="KAFKA" value="KAFKA" /></el-select></el-form-item>
              <el-form-item label="业务标签"><el-input v-model="tag" maxlength="128" /></el-form-item>
              <el-button type="primary" :loading="messageState === 'loading'" @click="resetMessages">查询</el-button>
            </el-form>
            <el-skeleton v-if="messageState === 'loading'" :rows="6" animated />
            <el-empty v-else-if="messageState === 'empty'" description="当前范围没有消息" />
            <el-result v-else-if="messageState === 'error'" icon="error" title="消息加载失败"><template #extra><el-button @click="resetMessages">重试</el-button></template></el-result>
            <el-table v-else :data="page.items" stripe>
              <el-table-column prop="message_id" label="Message ID" min-width="210" />
              <el-table-column prop="status" label="状态" width="130" />
              <el-table-column label="创建时间" width="190"><template #default="s">{{ formatTime(s.row.created_at) }}</template></el-table-column>
              <el-table-column label="到期时间" width="190"><template #default="s">{{ formatTime(s.row.deliver_at) }}</template></el-table-column>
              <el-table-column prop="sink_type" label="Sink" width="100" />
              <el-table-column prop="business_tag" label="业务标签" />
              <el-table-column label="操作" width="90"><template #default="s"><el-button link type="primary" @click="openDetails(s.row.message_id)">详情</el-button></template></el-table-column>
            </el-table>
            <div class="pager"><el-button :disabled="!page.has_more" @click="nextPage">下一页</el-button></div>
          </el-card>
        </el-tab-pane>
        <el-tab-pane label="时间轮" name="timewheels">
          <el-card shadow="never">
            <div class="toolbar"><el-input-number v-model="createCount" :min="1" :max="100" /><el-button type="primary" :loading="writing" :disabled="writing" @click="createWheels">创建时间轮</el-button></div>
            <el-skeleton v-if="wheelState === 'loading'" :rows="5" animated />
            <el-empty v-else-if="wheelState === 'empty'" description="尚未创建时间轮" />
            <el-result v-else-if="wheelState === 'error'" icon="error" title="时间轮加载失败"><template #extra><el-button @click="loadWheels">重试</el-button></template></el-result>
            <el-table v-else :data="wheels" stripe><el-table-column prop="tw_id" label="TW ID" /><el-table-column prop="master" label="Master" /><el-table-column prop="slave" label="Slave" /><el-table-column prop="status" label="状态" /><el-table-column prop="sync_state" label="同步" /><el-table-column prop="assignment_version" label="版本" /><el-table-column prop="message_count" label="消息数" /></el-table>
          </el-card>
        </el-tab-pane>
        <el-tab-pane label="集群节点" name="cluster">
          <el-card shadow="never">
            <el-skeleton v-if="nodeState === 'loading'" :rows="5" animated />
            <el-empty v-else-if="nodeState === 'empty'" description="暂无在线节点" />
            <el-result v-else-if="nodeState === 'error'" icon="error" title="节点加载失败"><template #extra><el-button @click="loadNodes">重试</el-button></template></el-result>
            <el-table v-else :data="nodes" stripe><el-table-column prop="node_id" label="节点" /><el-table-column prop="host" label="地址" /><el-table-column prop="grpc_port" label="gRPC" /><el-table-column prop="load" label="负载" /><el-table-column label="角色"><template #default="s">{{ s.row.controller ? 'Controller' : 'Worker' }}</template></el-table-column><el-table-column label="就绪"><template #default="s"><el-tag :type="s.row.ready ? 'success' : 'danger'">{{ s.row.ready ? 'READY' : 'NOT READY' }}</el-tag></template></el-table-column></el-table>
          </el-card>
        </el-tab-pane>
      </el-tabs>
      <div class="request-id">最近 request_id：{{ lastRequestId || '—' }}</div>
    </el-main>
  </el-container>

  <el-drawer v-model="detailsVisible" title="消息详情" size="560px">
    <el-skeleton v-if="!details" :rows="8" animated />
    <template v-else>
      <el-descriptions :column="1" border><el-descriptions-item label="Message ID">{{ details.message_id }}</el-descriptions-item><el-descriptions-item label="状态">{{ details.status }}</el-descriptions-item><el-descriptions-item label="到期时间">{{ formatTime(details.deliver_at) }}</el-descriptions-item><el-descriptions-item label="重试次数">{{ details.retry_count }}</el-descriptions-item><el-descriptions-item label="最近错误">{{ details.last_error || '—' }}</el-descriptions-item></el-descriptions>
      <h3>最近投递（最多 20 次）</h3>
      <el-timeline><el-timeline-item v-for="a in details.attempts" :key="a.attempt_id" :timestamp="formatTime(a.started_at)"><b>{{ a.result }}</b> · {{ a.error_code || '无错误码' }} · {{ a.duration_ms }}ms</el-timeline-item></el-timeline>
      <el-empty v-if="!details.attempts.length" description="暂无投递记录" />
    </template>
  </el-drawer>
</template>
