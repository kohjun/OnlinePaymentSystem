import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Activity,
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  ClipboardCheck,
  Database,
  History,
  Loader2,
  RefreshCw,
  ServerCog,
  ShieldCheck,
  X
} from 'lucide-react';
import { api } from './api';
import { formatMoney } from './EventCard';
import type { AdminAuditEvent, AdminOperationsAudit, AdminOperationsQueues, AdminQueueAction, AdminQueueItem } from './types';
import type { DistributionReadiness, InventoryInspection, SystemHealth } from './types';

type Notify = (message: string, tone?: 'success' | 'error' | 'info') => void;

const metadataLabels: Record<string, string> = {
  sellerId: '판매자', productId: '상품', listingId: '판매글', saleEventId: '판매 이벤트',
  targetType: '대상 유형', targetId: '대상', details: '상세', evidenceRef: '증빙 참조',
  note: '제출 메모', bankCode: '은행 코드', accountLast4: '계좌 끝자리', paymentId: '결제',
  gatewayName: 'PG', failureReason: '실패 사유', fulfillmentStatus: '배송 상태', disputeReason: '분쟁 사유',
  sourceType: '원천 유형', sourceId: '원천 ID', grossAmount: '총액', platformFee: '수수료'
};

export function AdminOperations({ close, notify }: { close: () => void; notify: Notify }) {
  const [queues, setQueues] = useState<AdminOperationsQueues>();
  const [audit, setAudit] = useState<AdminOperationsAudit>();
  const [selectedQueue, setSelectedQueue] = useState('');
  const [view, setView] = useState<'QUEUES' | 'SYSTEM' | 'AUDIT'>('QUEUES');
  const [loading, setLoading] = useState(true);
  const [actionTarget, setActionTarget] = useState<{ queue: string; item: AdminQueueItem; action: AdminQueueAction }>();
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [health, setHealth] = useState<SystemHealth>();
  const [readiness, setReadiness] = useState<DistributionReadiness>();
  const [inventory, setInventory] = useState<InventoryInspection>();
  const [repairConfirm, setRepairConfirm] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [nextQueues, nextAudit, nextHealth, nextReadiness, nextInventory] = await Promise.all([
        api.adminOperationQueues(), api.adminOperationAudit(), api.systemHealth(),
        api.distributionReadiness(), api.inspectInventoryReconciliation()
      ]);
      setQueues(nextQueues);
      setAudit(nextAudit);
      setHealth(nextHealth);
      setReadiness(nextReadiness);
      setInventory(nextInventory);
      setSelectedQueue(current => nextQueues.queues.some(queue => queue.queue === current)
        ? current
        : nextQueues.queues.find(queue => queue.count > 0)?.queue ?? nextQueues.queues[0]?.queue ?? '');
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setLoading(false); }
  }, [notify]);

  useEffect(() => { void load(); }, [load]);

  const currentQueue = useMemo(() => queues?.queues.find(queue => queue.queue === selectedQueue), [queues, selectedQueue]);
  const failedAuditCount = useMemo(() => audit?.events.filter(event => event.outcome === 'FAILED').length ?? 0, [audit]);

  async function executeAction() {
    if (!actionTarget || (actionTarget.action.noteRequired && !note.trim())) return;
    setBusy(true);
    try {
      await api.executeAdminOperation(actionTarget.queue, actionTarget.item.id, actionTarget.action.action, note.trim());
      notify(`${actionTarget.action.label} 처리가 완료되었습니다.`, 'success');
      setActionTarget(undefined);
      setNote('');
      await load();
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  async function repairInventory() {
    setBusy(true);
    try {
      const result = await api.repairInventoryReconciliation();
      notify(result.message, result.status === 'SUCCESS' ? 'success' : 'error');
      setRepairConfirm(false);
      await load();
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  return <section className="admin-view">
    <header className="admin-view__header">
      <button className="icon-button" onClick={close} title="마켓으로"><ChevronLeft /></button>
      <div><h1>운영 관리</h1><p>검수, 분쟁, 환불과 정산 작업을 감사 기록과 함께 처리합니다.</p></div>
      <button className="icon-button" disabled={loading} onClick={() => void load()} title="운영 데이터 새로고침"><RefreshCw className={loading ? 'spin' : ''} /></button>
    </header>

    <div className="admin-summary">
      <div><ClipboardCheck /><span>처리 대기</span><strong>{queues?.totalOpen ?? 0}</strong></div>
      <div><Activity /><span>운영 큐</span><strong>{queues?.queues.length ?? 0}</strong></div>
      <div className={failedAuditCount ? 'admin-summary--alert' : ''}><AlertTriangle /><span>최근 실패</span><strong>{failedAuditCount}</strong></div>
      <div><ShieldCheck /><span>마지막 동기화</span><strong>{queues?.generatedAt ? new Date(queues.generatedAt).toLocaleTimeString('ko-KR') : '-'}</strong></div>
    </div>

    <nav className="seller-tabs admin-tabs" aria-label="운영 관리 메뉴">
      <button aria-current={view === 'QUEUES' ? 'page' : undefined} onClick={() => setView('QUEUES')}><ClipboardCheck />처리 큐</button>
      <button aria-current={view === 'SYSTEM' ? 'page' : undefined} onClick={() => setView('SYSTEM')}><ServerCog />서비스 상태</button>
      <button aria-current={view === 'AUDIT' ? 'page' : undefined} onClick={() => setView('AUDIT')}><History />감사 기록</button>
    </nav>

    {loading && !queues ? <div className="content-state"><Loader2 className="spin" />운영 데이터를 불러오는 중입니다</div>
      : view === 'QUEUES' ? <div className="admin-workspace">
        <nav className="admin-queue-nav" aria-label="운영 큐">
          {queues?.queues.map(queue => <button key={queue.queue} aria-current={selectedQueue === queue.queue ? 'page' : undefined} onClick={() => setSelectedQueue(queue.queue)}><span>{queue.label}</span><strong>{queue.count}</strong></button>)}
        </nav>
        <section className="admin-queue-panel">
          <header><div><h2>{currentQueue?.label ?? '처리 큐'}</h2><span>전체 {currentQueue?.count ?? 0}건</span></div></header>
          {!currentQueue?.items.length ? <div className="admin-empty"><CheckCircle2 /><strong>대기 중인 작업이 없습니다</strong></div>
            : <div className="admin-item-list">{currentQueue.items.map(item => <article key={item.id}>
              <header><div><span>{item.type}</span><strong>{item.title}</strong><small>{item.id} · {item.ownerId || '-'}</small></div><div><span>{item.status}</span>{item.amount != null && <strong>{formatMoney(item.amount)}</strong>}<small>{item.createdAt ? new Date(item.createdAt).toLocaleString('ko-KR') : '-'}</small></div></header>
              <dl>{Object.entries(item.metadata ?? {}).map(([key, value]) => <div key={key}><dt>{metadataLabels[key] ?? key}</dt><dd>{formatMetadata(value)}</dd></div>)}</dl>
              <footer>{item.actions.map(action => <button key={action.action} className={`admin-action admin-action--${action.tone}`} onClick={() => { setActionTarget({ queue: currentQueue.queue, item, action }); setNote(''); }}>{action.label}</button>)}</footer>
            </article>)}</div>}
        </section>
      </div> : view === 'SYSTEM' ? <SystemOperations health={health} readiness={readiness} inventory={inventory} onRepair={() => setRepairConfirm(true)} />
        : <AuditLog events={audit?.events ?? []} />}

    {actionTarget && <div className="modal-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget && !busy) setActionTarget(undefined); }}>
      <section className="modal admin-action-modal" role="dialog" aria-modal="true" aria-labelledby="admin-action-title">
        <header><div><span>{actionTarget.item.title}</span><h2 id="admin-action-title">{actionTarget.action.label}</h2></div><button className="icon-button" disabled={busy} onClick={() => setActionTarget(undefined)} title="작업 닫기"><X /></button></header>
        <p>대상 ID <strong>{actionTarget.item.id}</strong></p>
        <label>처리 메모{actionTarget.action.noteRequired && ' (필수)'}<textarea maxLength={2000} value={note} onChange={event => setNote(event.target.value)} /></label>
        <footer><button className="secondary-button" disabled={busy} onClick={() => setActionTarget(undefined)}>취소</button><button className={`admin-action admin-action--${actionTarget.action.tone}`} disabled={busy || (actionTarget.action.noteRequired && !note.trim())} onClick={() => void executeAction()}>{busy ? <Loader2 className="spin" /> : <ShieldCheck />}{actionTarget.action.label} 확정</button></footer>
      </section>
    </div>}
    {repairConfirm && <div className="modal-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget && !busy) setRepairConfirm(false); }}>
      <section className="modal admin-action-modal" role="dialog" aria-modal="true" aria-labelledby="inventory-repair-title">
        <header><div><span>불일치 {inventory?.mismatchCount ?? 0}건</span><h2 id="inventory-repair-title">재고 정합성 복구</h2></div><button className="icon-button" disabled={busy} onClick={() => setRepairConfirm(false)} title="복구 작업 닫기"><X /></button></header>
        <p>Postgres 원장을 기준으로 Redis 재고 카운터를 다시 맞춥니다.</p>
        <footer><button className="secondary-button" disabled={busy} onClick={() => setRepairConfirm(false)}>취소</button><button className="admin-action admin-action--danger" disabled={busy} onClick={() => void repairInventory()}>{busy ? <Loader2 className="spin" /> : <Database />}복구 실행</button></footer>
      </section>
    </div>}
  </section>;
}

function SystemOperations({ health, readiness, inventory, onRepair }: {
  health?: SystemHealth;
  readiness?: DistributionReadiness;
  inventory?: InventoryInspection;
  onRepair: () => void;
}) {
  return <section className="system-operations">
    <div className="system-status-strip">
      <div><Activity /><span>애플리케이션</span><strong className={health?.status === 'UP' ? 'audit-success' : 'audit-failed'}>{health?.status ?? '-'}</strong></div>
      <div><ShieldCheck /><span>배포 준비</span><strong className={readiness?.releasable ? 'audit-success' : 'audit-failed'}>{readiness?.status ?? '-'}</strong><small>{readiness?.mode} · {readiness?.releaseChannel}</small></div>
      <div><Database /><span>재고 정합성</span><strong className={inventory?.mismatchCount ? 'audit-failed' : 'audit-success'}>{inventory?.status ?? '-'}</strong><small>불일치 {inventory?.mismatchCount ?? 0}건</small></div>
    </div>
    <header className="seller-section-header"><div><h2>배포 준비 점검</h2><span>운영 필수 구성과 외부 의존성 상태</span></div></header>
    <div className="readiness-list" role="table" aria-label="배포 준비 점검">
      {readiness?.checks.map(check => <article key={check.id} role="row"><span className={`readiness-state readiness-state--${check.status.toLowerCase()}`}>{check.status}</span><div><strong>{check.name}</strong><small>{check.message}</small></div><span>{check.blocking ? '필수' : '권고'}</span></article>)}
    </div>
    <header className="seller-section-header inventory-heading"><div><h2>재고 불일치 점검</h2><span>{inventory?.message ?? '점검 결과가 없습니다.'}</span></div>{Boolean(inventory?.mismatchCount) && <button className="admin-action admin-action--danger" onClick={onRepair}><Database />정합성 복구</button>}</header>
    {inventory?.mismatches.length ? <div className="inventory-mismatch-list" role="table" aria-label="재고 불일치 목록">{inventory.mismatches.map(item => <article key={item.productId} role="row"><strong>{item.productId}</strong><span>{item.reason}</span><div><small>Postgres</small><strong>가용 {item.postgresAvailable ?? '-'} / 예약 {item.postgresReserved ?? '-'}</strong></div><div><small>Redis</small><strong>가용 {item.redisAvailable ?? '-'} / 예약 {item.redisReserved ?? '-'}</strong></div></article>)}</div>
      : <div className="admin-empty inventory-empty"><CheckCircle2 /><strong>확인된 재고 불일치가 없습니다</strong></div>}
  </section>;
}

function AuditLog({ events }: { events: AdminAuditEvent[] }) {
  return events.length === 0 ? <div className="admin-empty"><History /><strong>감사 기록이 없습니다</strong></div>
    : <div className="admin-audit-list" role="table" aria-label="운영 감사 기록">
      {events.map(event => <article key={event.eventId} role="row">
        <div><span>{new Date(event.createdAt).toLocaleString('ko-KR')}</span><strong>{event.action}</strong><small>{event.eventId}</small></div>
        <div><span>작업자</span><strong>{event.actorId}</strong><small>{event.actorRoles || '-'}</small></div>
        <div><span>대상</span><strong>{event.queue}</strong><small>{event.resourceId}</small></div>
        <div><span>결과</span><strong className={event.outcome === 'FAILED' ? 'audit-failed' : 'audit-success'}>{event.outcome}</strong><small>{event.reason || '-'}</small></div>
      </article>)}
    </div>;
}

function formatMetadata(value: unknown) {
  if (value == null) return '-';
  if (typeof value === 'number') return value.toLocaleString('ko-KR');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}
