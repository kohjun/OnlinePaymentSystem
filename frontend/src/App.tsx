import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertCircle, Check, ChevronLeft, ChevronRight, Loader2, MapPin, Maximize2, Minus, PackageCheck, Plus, Search, ShieldCheck, ShoppingBag, Store, Trash2, UserRound, X } from 'lucide-react';
import { api } from './api';
import { EventCard, formatMoney } from './EventCard';
import { EventDetail } from './EventDetail';
import { SellerCenter } from './SellerCenter';
import { AdminOperations } from './AdminOperations';
import type { Identity, MarketplaceEvent, MarketplaceOrder, Page, SaleType, ShippingAddress } from './types';

const filters: Array<{ value: '' | SaleType; label: string }> = [
  { value: '', label: '전체' }, { value: 'FIXED_PRICE', label: '일반 판매' }, { value: 'DROP', label: '한정 수량' },
  { value: 'RAFFLE', label: '래플' }, { value: 'AUCTION', label: '실시간 경매' }
];

export function App() {
  const [identity, setIdentity] = useState<Identity>();
  const [events, setEvents] = useState<Page<MarketplaceEvent>>();
  const [selected, setSelected] = useState<MarketplaceEvent>();
  const [saleType, setSaleType] = useState<'' | SaleType>('');
  const [keyword, setKeyword] = useState('');
  const [searchValue, setSearchValue] = useState('');
  const [sort, setSort] = useState('startsAt');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [orders, setOrders] = useState<MarketplaceOrder[]>();
  const [addressBookOpen, setAddressBookOpen] = useState(false);
  const [sellerCenterOpen, setSellerCenterOpen] = useState(false);
  const [adminOpen, setAdminOpen] = useState(false);
  const [notice, setNotice] = useState<{ message: string; tone: 'success' | 'error' | 'info' }>();

  const notify = useCallback((message: string, tone: 'success' | 'error' | 'info' = 'info') => {
    setNotice({ message, tone });
    window.setTimeout(() => setNotice(current => current?.message === message ? undefined : current), 6000);
  }, []);

  useEffect(() => {
    void api.me().then(setIdentity).catch(error => notify(error.message, 'error'));
    const params = new URLSearchParams(window.location.search);
    if (params.get('tossResult') === 'success') {
      const intentId = params.get('intentId');
      const paymentKey = params.get('paymentKey');
      const orderId = params.get('orderId');
      const amount = Number(params.get('amount'));
      if (intentId && paymentKey && orderId && Number.isFinite(amount)) {
        void api.confirmToss({ intentId, paymentKey, orderId, amount })
          .then(result => notify(result.status === 'SUCCESS' ? '결제가 완료되었습니다.' : '결제 승인 결과를 확인 중입니다.', result.status === 'SUCCESS' ? 'success' : 'info'))
          .catch(error => notify(error.message, 'error'));
      }
      window.history.replaceState({}, '', '/app/');
    } else if (params.get('tossResult') === 'fail') {
      const intentId = params.get('intentId');
      if (intentId) {
        void api.cancelToss(intentId).catch(error => notify(error.message, 'error'));
      }
      notify(params.get('message') ?? '결제가 취소되었거나 승인되지 않았습니다.', 'error');
      window.history.replaceState({}, '', '/app/');
    }
  }, [notify]);

  useEffect(() => {
    setLoading(true);
    const params = new URLSearchParams({ sort, page: String(page), size: '24' });
    if (saleType) params.set('saleType', saleType);
    if (keyword) params.set('keyword', keyword);
    void api.events(params).then(setEvents).catch(error => notify(error.message, 'error')).finally(() => setLoading(false));
  }, [keyword, page, saleType, sort, notify]);

  const displayName = identity?.buyerProfile?.displayName ?? identity?.user?.displayName ?? identity?.customerId ?? '고객';
  const liveCount = useMemo(() => events?.content.filter(event => event.status === 'LIVE').length ?? 0, [events]);

  async function openOrders() {
    if (!identity) return;
    setSelected(undefined);
    setAddressBookOpen(false);
    setSellerCenterOpen(false);
    setAdminOpen(false);
    setLoading(true);
    try { setOrders(await api.orders(identity.customerId)); } catch (error) { notify((error as Error).message, 'error'); }
    finally { setLoading(false); }
  }

  function openAddressBook() {
    setSelected(undefined);
    setOrders(undefined);
    setSellerCenterOpen(false);
    setAdminOpen(false);
    setAddressBookOpen(true);
  }

  function openSellerCenter() {
    setSelected(undefined);
    setOrders(undefined);
    setAddressBookOpen(false);
    setAdminOpen(false);
    setSellerCenterOpen(true);
  }

  function openAdmin() {
    setSelected(undefined);
    setOrders(undefined);
    setAddressBookOpen(false);
    setSellerCenterOpen(false);
    setAdminOpen(true);
  }

  if (!identity) return <div className="boot-screen"><Loader2 className="spin" /><strong>에브리세일 마켓을 준비하고 있습니다</strong></div>;

  return <div className="app-shell">
    {window.electronAPI && <div className="electron-titlebar">
      <div><strong>EVERYSALE</strong><span>에브리세일 마켓플레이스</span></div>
      <div className="electron-titlebar__controls">
        <button type="button" onClick={() => window.electronAPI?.minimizeWindow()} title="최소화"><Minus /></button>
        <button type="button" onClick={() => window.electronAPI?.maximizeWindow()} title="최대화"><Maximize2 /></button>
        <button className="electron-close" type="button" onClick={() => window.electronAPI?.closeWindow()} title="닫기"><X /></button>
      </div>
    </div>}
    <header className="topbar">
      <button className="brand" type="button" onClick={() => { setSelected(undefined); setOrders(undefined); setAddressBookOpen(false); setSellerCenterOpen(false); setAdminOpen(false); }}><span>EVERYSALE</span><strong>에브리세일</strong></button>
      <form className="search-box" onSubmit={event => { event.preventDefault(); setPage(0); setKeyword(searchValue.trim()); }} role="search">
        <Search size={18} /><input value={searchValue} onChange={event => setSearchValue(event.target.value)} placeholder="상품, 브랜드, 판매자 검색" aria-label="마켓 검색" />
        {searchValue && <button type="button" onClick={() => { setSearchValue(''); setKeyword(''); }} title="검색어 지우기"><X size={16} /></button>}
      </form>
      <nav aria-label="사용자 메뉴">
        {identity.roles.includes('ADMIN') && <button type="button" onClick={openAdmin}><ShieldCheck />운영 관리</button>}
        <button type="button" onClick={openSellerCenter}><Store />상품 판매</button>
        <button type="button" onClick={openAddressBook}><MapPin />배송지</button>
        <button type="button" onClick={() => void openOrders()}><ShoppingBag />구매 내역</button>
        <span className="account-chip"><UserRound />{displayName}</span>
      </nav>
    </header>

    <main>
      {selected ? <EventDetail event={selected} identity={identity} onClose={() => setSelected(undefined)} notify={notify} />
        : adminOpen ? <AdminOperations close={() => setAdminOpen(false)} notify={notify} />
          : sellerCenterOpen ? <SellerCenter identity={identity} close={() => setSellerCenterOpen(false)} notify={notify} onIdentityChange={setIdentity} />
          : addressBookOpen ? <AddressBook close={() => setAddressBookOpen(false)} notify={notify} />
        : orders ? <Orders orders={orders} close={() => setOrders(undefined)} notify={notify} refresh={openOrders} />
          : <>
            <section className="market-toolbar">
              <div><h1>마켓플레이스</h1><p><span className="live-dot" />지금 거래 가능한 상품 {liveCount}개</p></div>
              <select value={sort} onChange={event => { setSort(event.target.value); setPage(0); }} aria-label="상품 정렬">
                <option value="startsAt">새로 시작한 순</option><option value="endingSoon">마감 임박 순</option>
                <option value="priceAsc">낮은 가격 순</option><option value="priceDesc">높은 가격 순</option>
              </select>
            </section>
            <div className="segment-control" role="tablist" aria-label="판매 방식">
              {filters.map(filter => <button key={filter.value || 'ALL'} role="tab" aria-selected={saleType === filter.value}
                onClick={() => { setSaleType(filter.value); setPage(0); }}>{filter.label}</button>)}
            </div>
            {loading ? <div className="content-state"><Loader2 className="spin" />상품을 불러오는 중입니다</div>
              : events?.content.length ? <section className="event-grid" aria-live="polite">{events.content.map(event => <EventCard key={event.saleEventId} event={event} onOpen={() => setSelected(event)} />)}</section>
                : <div className="content-state"><Search />조건에 맞는 상품이 없습니다</div>}
            {events && events.totalPages > 1 && <nav className="pagination" aria-label="상품 페이지">
              <button className="icon-button" disabled={events.first} onClick={() => setPage(value => value - 1)} title="이전 페이지"><ChevronLeft /></button>
              <span>{events.number + 1} / {events.totalPages}</span>
              <button className="icon-button" disabled={events.last} onClick={() => setPage(value => value + 1)} title="다음 페이지"><ChevronRight /></button>
            </nav>}
          </>}
    </main>

    {notice && <div className={`toast toast--${notice.tone}`} role={notice.tone === 'error' ? 'alert' : 'status'}>
      {notice.tone === 'error' ? <AlertCircle /> : <PackageCheck />}<span>{notice.message}</span><button onClick={() => setNotice(undefined)} title="알림 닫기"><X /></button>
    </div>}
  </div>;
}

function Orders({ orders, close, notify, refresh }: { orders: MarketplaceOrder[]; close: () => void; notify: (message: string, tone?: 'success' | 'error' | 'info') => void; refresh: () => Promise<void> }) {
  const [action, setAction] = useState<{ orderId: string; type: 'dispute' | 'review' }>();
  const [reason, setReason] = useState('');
  const [rating, setRating] = useState(5);

  async function confirm(orderId: string) {
    try { await api.confirmDelivery(orderId); notify('구매가 확정되었습니다.', 'success'); await refresh(); }
    catch (error) { notify((error as Error).message, 'error'); }
  }

  async function submitAction() {
    if (!action) return;
    try {
      if (action.type === 'dispute') await api.openDispute(action.orderId, reason);
      else await api.reviewSeller(action.orderId, rating, reason);
      notify(action.type === 'dispute' ? '분쟁 조정 요청이 접수되었습니다.' : '판매자 후기가 등록되었습니다.', 'success');
      setAction(undefined); setReason(''); await refresh();
    } catch (error) { notify((error as Error).message, 'error'); }
  }

  return <section className="orders-view">
    <header><button className="icon-button" onClick={close} title="마켓으로"><ChevronLeft /></button><div><h1>구매 내역</h1><p>결제와 배송 상태를 한곳에서 확인합니다.</p></div></header>
    {orders.length === 0 ? <div className="content-state"><ShoppingBag />아직 구매 내역이 없습니다</div>
      : <div className="orders-table" role="table">{orders.map(order => <article key={order.marketplaceOrderId} role="row">
        <div><span>{new Date(order.createdAt).toLocaleDateString('ko-KR')}</span><strong>{order.title ?? order.marketplaceOrderId}</strong></div>
        <div><span>결제</span><strong>{order.status}</strong>{order.seatId && <small className="ticket-order-seat">{order.seatId}</small>}</div>
        <div><span>{order.seatId ? '티켓' : '배송'}</span><strong>{order.seatId ? '발급 완료' : order.fulfillmentStatus}</strong></div>
        <div className="order-price"><strong>{formatMoney(order.amount)}</strong><div className="order-buttons">
          {(order.fulfillmentStatus === 'SHIPPED' || order.fulfillmentStatus === 'DELIVERED') && !order.buyerConfirmedAt && <button onClick={() => void confirm(order.marketplaceOrderId)}>구매 확정</button>}
          {!order.disputedAt && <button onClick={() => setAction({ orderId: order.marketplaceOrderId, type: 'dispute' })}>분쟁 요청</button>}
          {order.buyerConfirmedAt && <button onClick={() => setAction({ orderId: order.marketplaceOrderId, type: 'review' })}>후기</button>}
        </div></div>
        {action?.orderId === order.marketplaceOrderId && <div className="order-action">
          {action.type === 'review' && <label>평점<select value={rating} onChange={event => setRating(Number(event.target.value))}><option value="5">5점</option><option value="4">4점</option><option value="3">3점</option><option value="2">2점</option><option value="1">1점</option></select></label>}
          <label>{action.type === 'dispute' ? '요청 사유' : '후기'}<textarea maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} /></label>
          <div><button onClick={() => setAction(undefined)}>취소</button><button className="primary-button" onClick={() => void submitAction()}>등록</button></div>
        </div>}
      </article>)}</div>}
  </section>;
}

function AddressBook({ close, notify }: { close: () => void; notify: (message: string, tone?: 'success' | 'error' | 'info') => void }) {
  const [addresses, setAddresses] = useState<ShippingAddress[]>([]);
  const [creating, setCreating] = useState(false);
  const [form, setForm] = useState({ label: '집', recipientName: '', contactPhone: '', postalCode: '', addressLine1: '', addressLine2: '', deliveryMemo: '', defaultAddress: true });

  const load = useCallback(() => api.addresses().then(setAddresses).catch(error => notify(error.message, 'error')), [notify]);
  useEffect(() => { void load(); }, [load]);

  async function save() {
    try {
      await api.createAddress(form);
      notify('배송지가 등록되었습니다.', 'success');
      setCreating(false);
      await load();
    } catch (error) { notify((error as Error).message, 'error'); }
  }

  return <section className="address-view">
    <header><button className="icon-button" onClick={close} title="마켓으로"><ChevronLeft /></button><div><h1>배송지 관리</h1><p>결제에 사용할 기본 배송지를 관리합니다.</p></div><button className="secondary-button" onClick={() => setCreating(value => !value)}><Plus />새 배송지</button></header>
    {creating && <div className="address-form">
      <label>배송지 이름<input value={form.label} onChange={event => setForm({ ...form, label: event.target.value })} /></label>
      <label>받는 사람<input required value={form.recipientName} onChange={event => setForm({ ...form, recipientName: event.target.value })} /></label>
      <label>연락처<input required value={form.contactPhone} onChange={event => setForm({ ...form, contactPhone: event.target.value })} /></label>
      <label>우편번호<input value={form.postalCode} onChange={event => setForm({ ...form, postalCode: event.target.value })} /></label>
      <label className="wide">기본 주소<input required value={form.addressLine1} onChange={event => setForm({ ...form, addressLine1: event.target.value })} /></label>
      <label className="wide">상세 주소<input value={form.addressLine2} onChange={event => setForm({ ...form, addressLine2: event.target.value })} /></label>
      <label className="wide">배송 메모<input value={form.deliveryMemo} onChange={event => setForm({ ...form, deliveryMemo: event.target.value })} /></label>
      <button className="primary-button wide" disabled={!form.recipientName || !form.contactPhone || !form.addressLine1} onClick={() => void save()}><Check />배송지 저장</button>
    </div>}
    <div className="address-list">{addresses.map(address => <article key={address.addressId}>
      <div><strong>{address.label || '배송지'}{address.defaultAddress && <span>기본</span>}</strong><p>{address.recipientName} · {address.contactPhone}</p><p>{address.postalCode} {address.addressLine1} {address.addressLine2}</p></div>
      <div>{!address.defaultAddress && <button className="secondary-button" onClick={() => void api.setDefaultAddress(address.addressId).then(load)}>기본 설정</button>}
        <button className="icon-button" onClick={() => void api.deleteAddress(address.addressId).then(load)} title="배송지 삭제"><Trash2 /></button></div>
    </article>)}</div>
  </section>;
}
