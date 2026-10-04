// Sync interop types for @zed-pkg/client consumers.
//
// The runtime sync engine is @zed-pkg/sync (optional peer); this module mirrors
// its wire contract — the same enums and change envelope zed-interfaces
// publishes as JSON Schema (schemas/sync-*.json) — so a client app can name a
// WriteMode or type a ChangeEvent without pulling in the engine. zed-sync
// remains the behavioral source of truth.

export type Hlc = { wall_ms: number; counter: number; actor: string };
export type SyncOp = "upsert" | "delete";

export const SyncWriteMode = {
  LOCAL_ONLY: "local_only",
  OPTIMISTIC_QUEUE: "optimistic_queue",
  OPTIMISTIC_AWAIT_ACK: "optimistic_await_ack",
  SERVER_FIRST: "server_first",
  SERVER_ONLY: "server_only",
} as const;
export type SyncWriteMode = (typeof SyncWriteMode)[keyof typeof SyncWriteMode];

export const SyncErrorPolicy = {
  THROW_ONLY: "throw_only",
  EMIT_ONLY: "emit_only",
  THROW_AND_EMIT: "throw_and_emit",
  SILENT: "silent",
} as const;
export type SyncErrorPolicy = (typeof SyncErrorPolicy)[keyof typeof SyncErrorPolicy];

export const SyncConflictResolution = {
  SERVER_WINS: "server_wins",
  LAST_WRITE_WINS: "last_write_wins",
} as const;
export type SyncConflictResolution =
  (typeof SyncConflictResolution)[keyof typeof SyncConflictResolution];

export interface SyncChangeEvent {
  table: string;
  op: SyncOp;
  id: string;
  version: Hlc;
  row?: Record<string, unknown> | null;
  at_ms: number;
  write_key?: string;
  sync_sequence?: number;
}
