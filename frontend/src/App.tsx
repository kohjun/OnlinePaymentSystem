import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertCircle, Bell, Check, CheckCheck, ChevronLeft, ChevronRight, Heart, LogIn, LogOut, Loader2, MapPin, Maximize2, Minus, PackageCheck, Plus, Search, ShieldCheck, ShoppingBag, Store, Trash2, UserRound, X } from 'lucide-react';
import { ApiError, api, authToken, completeOidcCallbackIfPresent } from './api';
import { EventCard, formatMoney } from './EventCard';
import { EventDetail } from './EventDetail';
import { SellerCenter } from './SellerCenter';
import { AdminOperations } from './AdminOperations';
import { AuthPanel } from './AuthPanel';
import { CheckoutRecovery } from './CheckoutRecovery';
import { cancellationLabel } from './cancellationStatus';
import type { Identity, MarketplaceEvent, MarketplaceNotification, MarketplaceOrder, NotificationInbox, Page, SaleType, ShippingAddress, WishlistItem } from './types';

const filters: Array<{ value: '' | SaleType; label: string }> = [
  { value: '', label: '전체' }, { value: 'FIXED_PRICE', label: '일반 판매' }, { value: 'DROP', label: '한정 수량' },
  { value: 'RAFFLE', label: '래플' }, { value: 'AUCTION', label: '실시간 경매' }
];

export function App() {
  const [identity, setIdentity] = useState<Identity>();
  const [identityLoaded, setIdentityLoaded] = useState(false);
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
  const [wishlist, setWishlist] = useState<WishlistItem[]>();
  const [wishlistOpen, setWishlistOpen] = useState(false);
  const [notificationInbox, setNotificationInbox] = useState<NotificationInbox>();
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [authOpen, setAuthOpen] = useState(false);
  const [signedIn, setSignedIn] = useState(() => Boolean(authToken.get()));
  const [notice, setNotice] = useState<{ message: string; tone: 'success' | 'error' | 'info' }>();

  const notify = useCallback((message: string, tone: 'success' | 'error' | 'info' = 'info') => {
    setNotice({ message, tone });
    window.setTimeout(() => setNotice(current => current?.message === message ? undefined : current), 6000);
  }, []);

  const refreshWishlist = useCallback(() => {
    // 찜 조회 실패가 목록 화면을 막지 않게 한다. 하트가 빈 채로 보일 뿐이다.
    void api.listWishlist().then(setWishlist).catch(() => setWishlist([]));
  }, []);

  const refreshNotifications = useCallback(() => {
    void api.notificationInbox().then(setNotificationInbox).catch(() => undefined);
  }, []);

  const toggleWishlist = useCallback((saleEventId: string, wishlisted: boolean) => {
    if (!identity) {
      requireAuthentication();
      return;
    }
    const action = wishlisted
      ? api.removeFromWishlist(saleEventId)
      : api.addToWishlist(saleEventId);
    void action
      .then(() => {
        refreshWishlist();
        notify(wishlisted ? '찜을 해제했습니다.' : '찜 목록에 담았습니다.', 'success');
      })
      .catch(error => notify(error.message, 'error'));
  }, [identity, notify, refreshWishlist]);

  useEffect(() => {
    void (async () => {
      try {
        const oidcCompleted = await completeOidcCallbackIfPresent();
        if (oidcCompleted) setSignedIn(true);
        const currentIdentity = await api.me();
        setIdentity(currentIdentity);
        refreshWishlist();
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401) {
          notify(error instanceof Error ? error.message : '로그인 상태를 확인하지 못했습니다.', 'error');
        }
        setIdentity(undefined);
      } finally {
        setIdentityLoaded(true);
      }
    })();
  }, [notify, refreshWishlist]);

  useEffect(() => {
    if (!identity) return;
    refreshNotifications();
    const timer = window.setInterval(refreshNotifications, 30_000);
    return () => window.clearInterval(timer);
  }, [identity, refreshNotifications]);

  useEffect(() => {
    setLoading(true);
    const params = new URLSearchParams({ sort, page: String(page), size: '24' });
    if (saleType) params.set('saleType', saleType);
    if (keyword) params.set('keyword', keyword);
    void api.events(params).then(setEvents).catch(error => notify(error.message, 'error')).finally(() => setLoading(false));
  }, [keyword, page, saleType, sort, notify]);

  const displayName = identity?.buyerProfile?.displayName ?? identity?.user?.displayName ?? identity?.customerId ?? '고객';
  const liveCount = useMemo(() => events?.content.filter(event => event.status === 'LIVE').length ?? 0, [events]);
  const wishlistedIds = useMemo(() => new Set((wishlist ?? []).map(item => item.saleEventId)), [wishlist]);

  async function openOrders() {
    if (!identity) return requireAuthentication();
    setSelected(undefined);
    setAddressBookOpen(false);
    setSellerCenterOpen(false);
    setAdminOpen(false);
    setWishlistOpen(false);
    setNotificationsOpen(false);
    setAuthOpen(false);
    setLoading(true);
    try { setOrders(await api.orders(identity.customerId)); } catch (error) { notify((error as Error).message, 'error'); }
    finally { setLoading(false); }
  }

  function openAddressBook() {
    if (!identity) return requireAuthentication();
    setSelected(undefined);
    setOrders(undefined);
    setSellerCenterOpen(false);
    setAdminOpen(false);
    setWishlistOpen(false);
    setNotificationsOpen(false);
    setAuthOpen(false);
    setAddressBookOpen(true);
  }

  function openSellerCenter() {
    if (!identity) return requireAuthentication();
    setSelected(undefined);
    setOrders(undefined);
    setAddressBookOpen(false);
    setAdminOpen(false);
    setWishlistOpen(false);
    setNotificationsOpen(false);
    setAuthOpen(false);
    setSellerCenterOpen(true);
  }

  function openAdmin() {
    if (!identity) return requireAuthentication();
    setSelected(undefined);
    setOrders(undefined);
    setAddressBookOpen(false);
    setSellerCenterOpen(false);
    setWishlistOpen(false);
    setNotificationsOpen(false);
    setAuthOpen(false);
    setAdminOpen(true);
  }

  function openAuth() {
    setSelected(undefined);
    setOrders(undefined);
    setAddressBookOpen(false);
    setSellerCenterOpen(false);
    setAdminOpen(false);
    setWishlistOpen(false);
    setNotificationsOpen(false);
    setAuthOpen(true);
  }

  function signOut() {
    authToken.clear();
    setSignedIn(false);
    // 신원과 찜 목록을 다시 읽는다. 로그아웃하면 로컬 목 인증 기본 계정으로
    // 돌아가므로 화면에 남아 있던 이전 계정 데이터를 그대로 두면 안 된다.
    setOrders(undefined);
    setAddressBookOpen(false);
    setSellerCenterOpen(false);
    setAdminOpen(false);
    setWishlistOpen(false);
    setNotificationsOpen(false);
    void api.me().then(setIdentity).catch(() => setIdentity(undefined));
    setWishlist([]);
    setNotificationInbox(undefined);
    notify('로그아웃했습니다.', 'info');
  }

  function afterAuthenticated() {
    setSignedIn(true);
    void api.me()
      .then(currentIdentity => {
        setIdentity(currentIdentity);
        refreshWishlist();
      })
      .catch(error => {
        if (!(error instanceof ApiError) || error.status !== 401) notify(error.message, 'error');
        setIdentity(undefined);
      })
      .finally(() => setIdentityLoaded(true));
  }

  function openWishlist() {
    if (!identity) return requireAuthentication();
    setSelected(undefined);
    setOrders(undefined);
    setAddressBookOpen(false);
    setSellerCenterOpen(false);
    setAdminOpen(false);
    setAuthOpen(false);
    setWishlistOpen(true);
    setNotificationsOpen(false);
    refreshWishlist();
  }

  function openNotifications() {
    if (!identity) return requireAuthentication();
    setSelected(undefined);
    setOrders(undefined);
    setAddressBookOpen(false);
    setSellerCenterOpen(false);
    setAdminOpen(false);
    setWishlistOpen(false);
    setAuthOpen(false);
    setNotificationsOpen(true);
    refreshNotifications();
  }

  function requireAuthentication() {
    openAuth();
    notify('계속하려면 로그인해 주세요.', 'info');
  }

  async function openNotification(notification: MarketplaceNotification) {
    if (!notification.read) {
      await api.markNotificationRead(notification.notificationId);
      refreshNotifications();
    }
    if (notification.aggregateType === 'MARKETPLACE_ORDER') {
      await openOrders();
    }
  }

  if (!identityLoaded) return <div className="boot-screen"><Loader2 className="spin" /><strong>에브리세일 마켓을 준비하고 있습니다</strong></div>;

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
      <button className="brand" type="button" onClick={() => { setSelected(undefined); setOrders(undefined); setAddressBookOpen(false); setSellerCenterOpen(false); setAdminOpen(false); setWishlistOpen(false); setNotificationsOpen(false); setAuthOpen(false); }}><span>EVERYSALE</span><strong>에브리세일</strong></button>
      <form className="search-box" onSubmit={event => { event.preventDefault(); setPage(0); setKeyword(searchValue.trim()); }} role="search">
        <Search size={18} /><input value={searchValue} onChange={event => setSearchValue(event.target.value)} placeholder="상품, 브랜드, 판매자 검색" aria-label="마켓 검색" />
        {searchValue && <button type="button" onClick={() => { setSearchValue(''); setKeyword(''); }} title="검색어 지우기"><X size={16} /></button>}
      </form>
      <nav aria-label="사용자 메뉴">
        {identity?.roles.includes('ADMIN') && <button type="button" title="운영 관리" onClick={openAdmin}><ShieldCheck />운영 관리</button>}
        <button type="button" title="상품 판매" onClick={openSellerCenter}><Store />상품 판매</button>
        <button type="button" title="배송지" onClick={openAddressBook}><MapPin />배송지</button>
        <button type="button" title="찜 목록" onClick={openWishlist}><Heart />찜 목록</button>
        <button type="button" title="구매 내역" onClick={() => void openOrders()}><ShoppingBag />구매 내역</button>
        <button className="notification-trigger" type="button" onClick={openNotifications} title="거래 알림" aria-label={`거래 알림 ${notificationInbox?.unreadCount ?? 0}개`}>
          <Bell />{Boolean(notificationInbox?.unreadCount) && <span>{Math.min(notificationInbox!.unreadCount, 99)}</span>}
        </button>
        <span className="account-chip"><UserRound />{identity ? displayName : '방문자'}</span>
        {signedIn
          ? <button type="button" title="로그아웃" onClick={signOut}><LogOut />로그아웃</button>
          : <button type="button" title="로그인" onClick={openAuth}><LogIn />로그인</button>}
      </nav>
    </header>

    <main>
      {identity && <CheckoutRecovery key={identity.customerId} customerId={identity.customerId} />}
      {selected ? <EventDetail event={selected} identity={identity} onClose={() => setSelected(undefined)} onRequireAuthentication={openAuth} notify={notify} />
        : adminOpen ? <AdminOperations close={() => setAdminOpen(false)} notify={notify} />
          : sellerCenterOpen && identity ? <SellerCenter identity={identity} close={() => setSellerCenterOpen(false)} notify={notify} onIdentityChange={setIdentity} />
          : addressBookOpen ? <AddressBook close={() => setAddressBookOpen(false)} notify={notify} />
          : authOpen ? <AuthPanel close={() => setAuthOpen(false)} notify={notify} onAuthenticated={afterAuthenticated} />
          : notificationsOpen ? <Notifications inbox={notificationInbox} close={() => setNotificationsOpen(false)}
              openNotification={notification => void openNotification(notification)}
              markAll={async () => { await api.markAllNotificationsRead(); refreshNotifications(); }} />
          : wishlistOpen ? <Wishlist items={wishlist} close={() => setWishlistOpen(false)}
              onOpen={event => { setWishlistOpen(false); setSelected(event); }}
              onRemove={saleEventId => toggleWishlist(saleEventId, true)} />
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
              : events?.content.length ? <section className="event-grid" aria-live="polite">{events.content.map(event => <EventCard key={event.saleEventId} event={event}
                  onOpen={() => setSelected(event)}
                  wishlisted={wishlistedIds.has(event.saleEventId)}
                  onToggleWishlist={() => toggleWishlist(event.saleEventId, wishlistedIds.has(event.saleEventId))} />)}</section>
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

export function Notifications({ inbox, close, openNotification, markAll }: {
  inbox?: NotificationInbox;
  close: () => void;
  openNotification: (notification: MarketplaceNotification) => void;
  markAll: () => Promise<void>;
}) {
  return <section className="notifications-view" aria-labelledby="notifications-title">
    <header>
      <button className="icon-button" onClick={close} title="마켓으로"><ChevronLeft /></button>
      <div><h1 id="notifications-title">거래 알림</h1><p>주문, 배송, 정산과 분쟁 진행 상태를 확인합니다.</p></div>
      <button className="secondary-button" type="button" onClick={() => void markAll()} disabled={!inbox?.unreadCount}>
        <CheckCheck />전체 읽음
      </button>
    </header>
    {!inbox ? <div className="content-state"><Loader2 className="spin" />알림을 불러오는 중입니다</div>
      : inbox.notifications.length === 0 ? <div className="content-state"><Bell />새로운 거래 알림이 없습니다</div>
        : <div className="notification-list">{inbox.notifications.map(notification =>
          <article key={notification.notificationId} className={notification.read ? '' : 'notification-list__unread'}>
            <button type="button" onClick={() => openNotification(notification)}>
              <span className="notification-list__icon"><Bell /></span>
              <span className="notification-list__content">
                <span>{notification.notificationType.replaceAll('_', ' ')}</span>
                <strong>{notification.title}</strong>
                <small>{notification.message}</small>
              </span>
              <time dateTime={notification.createdAt}>{new Date(notification.createdAt).toLocaleString('ko-KR')}</time>
            </button>
          </article>)}</div>}
  </section>;
}

function Orders({ orders, close, notify, refresh }: { orders: MarketplaceOrder[]; close: () => void; notify: (message: string, tone?: 'success' | 'error' | 'info') => void; refresh: () => Promise<void> }) {
  const [action, setAction] = useState<{ orderId: string; type: 'cancel' | 'dispute' | 'review' }>();
  const [reason, setReason] = useState('');
  const [rating, setRating] = useState(5);

  async function confirm(orderId: string) {
    try { await api.confirmDelivery(orderId); notify('구매가 확정되었습니다.', 'success'); await refresh(); }
    catch (error) { notify((error as Error).message, 'error'); }
  }

  async function submitAction() {
    if (!action) return;
    try {
      if (action.type === 'cancel') await api.cancelOrder(action.orderId, reason);
      else if (action.type === 'dispute') await api.openDispute(action.orderId, reason);
      else await api.reviewSeller(action.orderId, rating, reason);
      notify(action.type === 'cancel' ? '취소 요청을 접수했습니다. 주문에서 환불 상태를 확인해 주세요.'
        : action.type === 'dispute' ? '분쟁 조정 요청이 접수되었습니다.' : '판매자 후기가 등록되었습니다.', 'success');
      setAction(undefined); setReason(''); await refresh();
    } catch (error) { notify((error as Error).message, 'error'); }
  }

  return <section className="orders-view">
    <header><button className="icon-button" onClick={close} title="마켓으로"><ChevronLeft /></button><div><h1>구매 내역</h1><p>결제와 배송 상태를 한곳에서 확인합니다.</p></div></header>
    {orders.length === 0 ? <div className="content-state"><ShoppingBag />아직 구매 내역이 없습니다</div>
      : <div className="orders-table" role="table">{orders.map(order => <article key={order.marketplaceOrderId} role="row">
        <div><span>{new Date(order.createdAt).toLocaleDateString('ko-KR')}</span><strong>{order.title ?? order.marketplaceOrderId}</strong></div>
        <div><span>결제</span><strong>{order.status}</strong>{order.seatId && <small className="ticket-order-seat">{order.seatId}</small>}</div>
        <div><span>{order.cancellationStatus ? '취소' : order.seatId ? '티켓' : '배송'}</span><strong>{order.cancellationStatus ? cancellationLabel(order.cancellationStatus) : order.seatId && order.status === 'PAID' ? '발급 완료' : order.fulfillmentStatus}</strong></div>
        <div className="order-price"><strong>{formatMoney(order.amount)}</strong><div className="order-buttons">
          {order.status === 'PAID' && !order.cancellationStatus && (order.fulfillmentStatus === 'SHIPPED' || order.fulfillmentStatus === 'DELIVERED') && !order.buyerConfirmedAt && <button onClick={() => void confirm(order.marketplaceOrderId)}>구매 확정</button>}
          {order.status === 'PAID' && !order.cancellationStatus && (order.fulfillmentStatus === 'READY_TO_FULFILL' || order.fulfillmentStatus === 'PROCESSING') && <button onClick={() => setAction({ orderId: order.marketplaceOrderId, type: 'cancel' })}>주문 취소</button>}
          {!order.disputedAt && <button onClick={() => setAction({ orderId: order.marketplaceOrderId, type: 'dispute' })}>분쟁 요청</button>}
          {order.buyerConfirmedAt && <button onClick={() => setAction({ orderId: order.marketplaceOrderId, type: 'review' })}>후기</button>}
        </div></div>
        {action?.orderId === order.marketplaceOrderId && <div className="order-action">
          {action.type === 'review' && <label>평점<select value={rating} onChange={event => setRating(Number(event.target.value))}><option value="5">5점</option><option value="4">4점</option><option value="3">3점</option><option value="2">2점</option><option value="1">1점</option></select></label>}
          <label>{action.type === 'review' ? '후기' : '요청 사유'}<textarea maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} /></label>
          <div><button onClick={() => setAction(undefined)}>취소</button><button className="primary-button" onClick={() => void submitAction()}>등록</button></div>
        </div>}
      </article>)}</div>}
  </section>;
}

/**
 * 찜 목록.
 *
 * 판매가 끝나 이벤트가 내려간 항목도 기록은 남긴다. 사용자가 담아둔 것이
 * 소리 없이 사라지면 무엇이 없어졌는지조차 알 수 없다.
 */
function Wishlist({ items, close, onOpen, onRemove }: {
  items?: WishlistItem[];
  close: () => void;
  onOpen: (event: MarketplaceEvent) => void;
  onRemove: (saleEventId: string) => void;
}) {
  if (!items) {
    return <div className="content-state"><Loader2 className="spin" />찜 목록을 불러오는 중입니다</div>;
  }

  return (
    <section className="wishlist-view" aria-labelledby="wishlist-title">
      <header>
        <button className="icon-button" type="button" onClick={close} title="마켓으로"><ChevronLeft /></button>
        <div><h1 id="wishlist-title">찜 목록</h1><p>담아둔 상품 {items.length}개</p></div>
      </header>

      {items.length === 0
        ? <div className="content-state">아직 찜한 상품이 없습니다.</div>
        : <section className="event-grid">
            {items.map(item => item.event
              ? <EventCard key={item.wishlistItemId} event={item.event}
                  onOpen={() => onOpen(item.event as MarketplaceEvent)}
                  wishlisted
                  onToggleWishlist={() => onRemove(item.saleEventId)} />
              : <article key={item.wishlistItemId} className="event-card event-card--gone">
                  <div className="event-card__body">
                    <h2>판매가 종료된 상품</h2>
                    <p className="wishlist-gone__id">{item.saleEventId}</p>
                    <button className="text-button" type="button" onClick={() => onRemove(item.saleEventId)}>
                      <Trash2 size={14} />찜 목록에서 빼기
                    </button>
                  </div>
                </article>)}
          </section>}
    </section>
  );
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
