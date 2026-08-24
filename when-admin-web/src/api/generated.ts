// Generated from openapi/when-v1.yaml. Do not add transport fields outside that contract.
export type MessageStatus = 'PENDING' | 'DELIVERING' | 'DELIVERED' | 'FAILED' | 'CANCELLED'
export type SinkType = 'HTTP' | 'KAFKA' | 'FILE'

export interface Envelope<T> { code: string; message: string; request_id: string; data: T }
export interface MessageSummary {
  message_id: string; status: MessageStatus; created_at: number; deliver_at: number
  delivered_at: number; sink_type: SinkType; business_tag?: string
  retry_count: number; last_error?: string
}
export interface Attempt {
  attempt_id: string; started_at: number; finished_at: number
  result: string; error_code?: string; error_summary?: string; duration_ms: number
}
export interface MessageDetails extends MessageSummary { attempts: Attempt[] }
export interface MessagePage {
  items: MessageSummary[]; next_cursor?: string; has_more: boolean; index_updated_at: number
}
export interface TimeWheel {
  tw_id: string; master: string; slave: string; status: string; sync_state: string
  assignment_version: number; message_count: number
}
export interface ClusterNode {
  node_id: string; host: string; grpc_port: number; start_time: number
  load: number; ready: boolean; controller: boolean
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public requestId: string) {
    super(message)
  }
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<Envelope<T>> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init.headers || {}) }
  })
  const envelope = await response.json() as Envelope<T>
  if (!response.ok) throw new ApiError(response.status, envelope.code, envelope.message, envelope.request_id)
  return envelope
}
