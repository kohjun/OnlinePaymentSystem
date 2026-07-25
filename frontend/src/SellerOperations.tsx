import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Banknote,
  Check,
  CircleAlert,
  Clock3,
  Loader2,
  PackageCheck,
  RefreshCw,
  ShieldCheck,
  Truck,
  WalletCards
} from 'lucide-react';
import { api } from './api';
import { formatMoney } from './EventCard';
import type {
  FulfillmentStatus,
  MarketplaceOrder,
  SellerPayout,
  SellerPayoutAccount,
  SellerPayoutAccountInput,
  SellerPayoutStatus
} from './types';

type Notify = (message: string, tone?: 'success' | 'error' | 'info') => void;

const fulfillmentLabels: Record<FulfillmentStatus, string> = {
  NOT_READY: '결제 대기',
  READY_TO_FULFILL: '발송 준비',
  PROCESSING: '포장 중',
  SHIPPED: '배송 중',
  DELIVERED: '배송 완료',
  CANCELLED: '처리 취소'
};

const payoutLabels: Record<SellerPayoutStatus, string> = {
  HELD: '구매 확정 대기',
  READY_FOR_RELEASE: '정산 예정',
  DISPUTED: '분쟁 보류',
  RELEASED: '정산 완료',
  CANCELLED: '정산 취소',
  RECOVERY_REQUIRED: '회수 필요',
  RECOVERED: '회수 완료'
};

export function SellerOrders({ sellerId, notify }: { sellerId: string; notify: Notify }) {
  const [orders, setOrders] = useState<MarketplaceOrder[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyOrderId, setBusyOrderId] = useState<string>();
  const [filter, setFilter] = useState<'ALL' | 'ACTION' | 'DISPUTED'>('ACTION');
  const [shipping, setShipping] = useState<{ orderId: string; carrier: string; trackingNumber: string }>();

  const load = useCallback(async () => {
    setLoading(true);
    try { setOrders(await api.sellerOrders(sellerId)); }
    catch (error) { notify((error as Error).message, 'error'); }
    finally { setLoading(false); }
  }, [notify, sellerId]);

  useEffect(() => { void load(); }, [load]);

  const visibleOrders = useMemo(() => orders.filter(order => {
    if (filter === 'DISPUTED') return Boolean(order.disputedAt);
    if (filter === 'ACTION') return !order.disputedAt && (order.fulfillmentStatus === 'READY_TO_FULFILL' || order.fulfillmentStatus === 'PROCESSING');
    return true;
  }), [filter, orders]);

  async function update(orderId: string, status: 'PROCESSING' | 'SHIPPED', carrier?: string, trackingNumber?: string) {
    setBusyOrderId(orderId);
    try {
      await api.updateFulfillment(sellerId, orderId, status, carrier, trackingNumber);
      notify(status === 'PROCESSING' ? '주문 포장을 시작했습니다.' : '송장 정보를 등록했습니다.', 'success');
      setShipping(undefined);
      await load();
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusyOrderId(undefined); }
  }

  return <section className="seller-operations">
    <header className="seller-section-header">
      <div><h2>판매 주문</h2><span>결제 완료 주문의 포장과 배송 상태를 처리합니다.</span></div>
      <div className="seller-section-actions">
        <select value={filter} onChange={event => setFilter(event.target.value as typeof filter)} aria-label="판매 주문 필터">
          <option value="ACTION">처리 필요</option><option value="ALL">전체 주문</option><option value="DISPUTED">분쟁 주문</option>
        </select>
        <button className="icon-button" onClick={() => void load()} title="주문 새로고침"><RefreshCw /></button>
      </div>
    </header>
    {loading ? <div className="content-state"><Loader2 className="spin" />판매 주문을 불러오는 중입니다</div>
      : visibleOrders.length === 0 ? <div className="seller-empty"><PackageCheck /><strong>현재 처리할 주문이 없습니다</strong></div>
        : <div className="seller-order-list" role="table" aria-label="판매 주문 목록">
          {visibleOrders.map(order => <article key={order.marketplaceOrderId} role="row">
            <div className="seller-order-main"><span>{new Date(order.createdAt).toLocaleString('ko-KR')}</span><strong>{order.productId || order.title || order.marketplaceOrderId}</strong><small>{order.marketplaceOrderId} · 구매자 {order.customerId || '-'}</small></div>
            <div><span>결제 / 수량</span><strong>{formatMoney(order.amount)}</strong><small>{order.quantity ?? 1}개 · {order.status}</small></div>
            <div><span>배송 상태</span><strong className={`status-text status-text--${order.fulfillmentStatus.toLowerCase()}`}>{fulfillmentLabels[order.fulfillmentStatus as FulfillmentStatus] ?? order.fulfillmentStatus}</strong>{order.trackingNumber && <small>{order.trackingCarrier} {order.trackingNumber}</small>}</div>
            <div className="seller-order-address"><span>배송지</span><strong>{order.shippingRecipientName || (order.seatId ? '디지털 티켓' : '-')}</strong><small>{order.seatId || [order.shippingPostalCode, order.shippingAddress].filter(Boolean).join(' ') || '-'}</small></div>
            <div className="seller-list__actions">
              {order.disputedAt ? <span className="seller-order-alert"><CircleAlert />분쟁 처리 중</span>
                : order.fulfillmentStatus === 'READY_TO_FULFILL' ? <button className="secondary-button" disabled={busyOrderId === order.marketplaceOrderId} onClick={() => void update(order.marketplaceOrderId, 'PROCESSING')}><PackageCheck />포장 시작</button>
                  : order.fulfillmentStatus === 'PROCESSING' ? <button className="secondary-button" onClick={() => setShipping({ orderId: order.marketplaceOrderId, carrier: '', trackingNumber: '' })}><Truck />송장 등록</button>
                    : null}
            </div>
            {shipping?.orderId === order.marketplaceOrderId && <div className="shipping-editor">
              <label>택배사<input value={shipping.carrier} onChange={event => setShipping({ ...shipping, carrier: event.target.value })} placeholder="예: CJ대한통운" /></label>
              <label>송장 번호<input value={shipping.trackingNumber} onChange={event => setShipping({ ...shipping, trackingNumber: event.target.value })} /></label>
              <button className="secondary-button" onClick={() => setShipping(undefined)}>취소</button>
              <button className="primary-button" disabled={busyOrderId === order.marketplaceOrderId || !shipping.carrier.trim() || !shipping.trackingNumber.trim()} onClick={() => void update(order.marketplaceOrderId, 'SHIPPED', shipping.carrier.trim(), shipping.trackingNumber.trim())}><Truck />배송 시작</button>
            </div>}
          </article>)}
        </div>}
  </section>;
}

const emptyAccount: SellerPayoutAccountInput = {
  accountRef: '', bankCode: '', bankName: '', accountHolderName: '', accountLast4: '', note: ''
};

export function SellerSettlements({ sellerId, notify }: { sellerId: string; notify: Notify }) {
  const [account, setAccount] = useState<SellerPayoutAccount>();
  const [payouts, setPayouts] = useState<SellerPayout[]>([]);
  const [form, setForm] = useState<SellerPayoutAccountInput>(emptyAccount);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [nextAccount, nextPayouts] = await Promise.all([api.sellerPayoutAccount(), api.sellerPayouts(sellerId)]);
      setAccount(nextAccount);
      setPayouts(nextPayouts);
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setLoading(false); }
  }, [notify, sellerId]);

  useEffect(() => { void load(); }, [load]);

  const totals = useMemo(() => payouts.reduce((sum, payout) => {
    if (payout.status === 'RELEASED') sum.released += Number(payout.netAmount);
    else if (payout.status === 'READY_FOR_RELEASE') sum.ready += Number(payout.netAmount);
    else if (payout.status === 'HELD') sum.held += Number(payout.netAmount);
    return sum;
  }, { held: 0, ready: 0, released: 0 }), [payouts]);

  function openAccountForm() {
    setForm(account ? {
      accountRef: account.accountRef,
      bankCode: account.bankCode,
      bankName: account.bankName,
      accountHolderName: account.accountHolderName,
      accountLast4: account.accountLast4,
      note: ''
    } : { ...emptyAccount });
    setEditing(true);
  }

  async function submitAccount() {
    setBusy(true);
    try {
      const updated = await api.submitSellerPayoutAccount(form);
      setAccount(updated);
      setEditing(false);
      notify('정산 계좌 검수를 요청했습니다.', 'success');
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  if (loading) return <div className="content-state"><Loader2 className="spin" />정산 정보를 불러오는 중입니다</div>;
  const accountEditable = !account || account.status === 'REJECTED';
  const accountValid = form.accountRef.trim() && form.bankCode.trim() && form.bankName.trim() && form.accountHolderName.trim() && /^\d{4}$/.test(form.accountLast4);

  return <section className="seller-operations">
    <header className="seller-section-header"><div><h2>정산 관리</h2><span>판매 대금과 정산 계좌 검수 상태를 확인합니다.</span></div><button className="icon-button" onClick={() => void load()} title="정산 새로고침"><RefreshCw /></button></header>
    <div className="settlement-summary">
      <div><Clock3 /><span>구매 확정 대기</span><strong>{formatMoney(totals.held)}</strong></div>
      <div><Banknote /><span>정산 예정</span><strong>{formatMoney(totals.ready)}</strong></div>
      <div><Check /><span>정산 완료</span><strong>{formatMoney(totals.released)}</strong></div>
    </div>
    <section className={`payout-account payout-account--${account?.status?.toLowerCase() ?? 'empty'}`}>
      <WalletCards />
      <div><strong>{account ? `${account.bankName} · ${account.accountHolderName}` : '정산 계좌 미등록'}</strong><span>{account ? `계좌 끝자리 ****${account.accountLast4}` : '판매 대금 수령 계좌를 등록해 주세요.'}</span></div>
      <div className="payout-account__status"><ShieldCheck /><strong>{account?.status === 'VERIFIED' ? '검증 완료' : account?.status === 'PENDING_REVIEW' ? '검수 중' : account?.status === 'REJECTED' ? '보완 필요' : '등록 필요'}</strong>{account?.reviewNote && <span>{account.reviewNote}</span>}</div>
      {accountEditable && <button className="secondary-button" onClick={openAccountForm}><WalletCards />{account ? '계좌 보완' : '계좌 등록'}</button>}
    </section>
    {editing && <section className="payout-account-form">
      <label>은행 코드<input maxLength={50} value={form.bankCode} onChange={event => setForm({ ...form, bankCode: event.target.value })} /></label>
      <label>은행명<input maxLength={100} value={form.bankName} onChange={event => setForm({ ...form, bankName: event.target.value })} /></label>
      <label>예금주<input maxLength={100} value={form.accountHolderName} onChange={event => setForm({ ...form, accountHolderName: event.target.value })} /></label>
      <label>계좌 끝 4자리<input inputMode="numeric" maxLength={4} value={form.accountLast4} onChange={event => setForm({ ...form, accountLast4: event.target.value.replace(/\D/g, '').slice(0, 4) })} /></label>
      <label className="span-2">계좌 참조값<input maxLength={500} value={form.accountRef} onChange={event => setForm({ ...form, accountRef: event.target.value })} placeholder="암호화 저장소의 계좌 토큰 또는 참조번호" /></label>
      <label className="span-2">검수 메모<input maxLength={1000} value={form.note} onChange={event => setForm({ ...form, note: event.target.value })} /></label>
      <footer><button className="secondary-button" onClick={() => setEditing(false)}>취소</button><button className="primary-button" disabled={busy || !accountValid} onClick={() => void submitAccount()}><ShieldCheck />계좌 검수 요청</button></footer>
    </section>}
    <div className="seller-list-heading"><div><h2>정산 내역</h2><span>판매 대금, 수수료 및 지급 상태</span></div></div>
    {payouts.length === 0 ? <div className="seller-empty"><Banknote /><strong>정산 내역이 없습니다</strong></div>
      : <div className="payout-list" role="table" aria-label="판매자 정산 내역">
        {payouts.map(payout => <article key={payout.payoutId} role="row">
          <div><span>{new Date(payout.createdAt).toLocaleDateString('ko-KR')}</span><strong>{payout.sourceId}</strong><small>{payout.payoutId}</small></div>
          <div><span>판매 금액</span><strong>{formatMoney(payout.grossAmount)}</strong></div>
          <div><span>플랫폼 수수료</span><strong>{formatMoney(payout.platformFee)}</strong></div>
          <div><span>정산 금액</span><strong>{formatMoney(payout.netAmount)}</strong></div>
          <div><span>상태</span><strong className={`status-text status-text--${payout.status.toLowerCase()}`}>{payoutLabels[payout.status]}</strong>{payout.releasedAt && <small>{new Date(payout.releasedAt).toLocaleString('ko-KR')}</small>}</div>
        </article>)}
      </div>}
  </section>;
}
