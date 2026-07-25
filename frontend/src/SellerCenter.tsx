import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  BadgeCheck,
  CalendarClock,
  Check,
  ChevronLeft,
  CircleAlert,
  ClipboardList,
  Clock3,
  ExternalLink,
  FileCheck2,
  Image as ImageIcon,
  Loader2,
  PackageOpen,
  Pencil,
  Plus,
  Send,
  ShieldCheck,
  Store,
  WalletCards,
  X
} from 'lucide-react';
import { api } from './api';
import { formatMoney } from './EventCard';
import { SellerOrders, SellerSettlements } from './SellerOperations';
import type {
  Identity,
  ListingStatus,
  SaleEventInput,
  SaleType,
  SellerListing,
  SellerListingInput,
  SellerProfile,
  SellerVerificationStatus
} from './types';

type Notify = (message: string, tone?: 'success' | 'error' | 'info') => void;

const listingStatus: Record<ListingStatus, string> = {
  DRAFT: '작성 중',
  PENDING_REVIEW: '상품 검수 중',
  ACTIVE: '판매 가능',
  REJECTED: '보완 필요',
  PAUSED: '판매 중지',
  ENDED: '판매 종료'
};

const verificationStatus: Record<SellerVerificationStatus, string> = {
  UNVERIFIED: '인증 필요',
  PENDING_REVIEW: '인증 검수 중',
  VERIFIED: '인증 완료',
  REJECTED: '인증 보완 필요'
};

const saleTypeLabels: Record<SaleType, string> = {
  FIXED_PRICE: '일반 판매',
  DROP: '한정 수량',
  RAFFLE: '래플',
  AUCTION: '실시간 경매'
};

const emptyListing: SellerListingInput = {
  name: '',
  description: '',
  price: 0,
  category: 'FASHION',
  quantity: 1,
  imageUrl: '',
  itemCondition: 'GOOD',
  brand: '',
  tags: '',
  authenticityNote: '',
  defectDescription: ''
};

function localDateTime(hoursFromNow: number) {
  const date = new Date(Date.now() + hoursFromNow * 60 * 60 * 1000);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

function eventDefaults(listing: SellerListing): SaleEventInput {
  return {
    saleType: 'FIXED_PRICE',
    price: Number(listing.price),
    stockQuantity: listing.availableQuantity,
    startsAt: localDateTime(1),
    endsAt: localDateTime(25),
    minBidIncrement: 1000,
    reservePrice: Number(listing.price),
    publishImmediately: false
  };
}

function eventStatusLabel(status?: string) {
  if (status === 'LIVE') return '판매 중';
  if (status === 'SCHEDULED') return '판매 예정';
  if (status === 'ENDED') return '판매 종료';
  if (status === 'CANCELLED') return '취소됨';
  return '판매 미설정';
}

export function SellerCenter({
  identity,
  close,
  notify,
  onIdentityChange
}: {
  identity: Identity;
  close: () => void;
  notify: Notify;
  onIdentityChange: (identity: Identity) => void;
}) {
  const [profile, setProfile] = useState<SellerProfile | undefined>(identity.sellerProfile);
  const [listings, setListings] = useState<SellerListing[]>([]);
  const [loading, setLoading] = useState(Boolean(identity.sellerProfile));
  const [busy, setBusy] = useState(false);
  const [profileName, setProfileName] = useState(identity.user?.displayName ?? identity.buyerProfile?.displayName ?? '');
  const [verification, setVerification] = useState({ evidenceRef: '', note: '' });
  const [listingForm, setListingForm] = useState<SellerListingInput>(emptyListing);
  const [editing, setEditing] = useState<SellerListing>();
  const [listingFormOpen, setListingFormOpen] = useState(false);
  const [eventListing, setEventListing] = useState<SellerListing>();
  const [eventForm, setEventForm] = useState<SaleEventInput>();
  const [section, setSection] = useState<'PRODUCTS' | 'ORDERS' | 'SETTLEMENTS'>('PRODUCTS');

  const loadListings = useCallback(async () => {
    if (!profile) return;
    setLoading(true);
    try { setListings(await api.sellerListings()); }
    catch (error) { notify((error as Error).message, 'error'); }
    finally { setLoading(false); }
  }, [notify, profile]);

  useEffect(() => { void loadListings(); }, [loadListings]);

  const counts = useMemo(() => ({
    total: listings.length,
    review: listings.filter(item => item.status === 'PENDING_REVIEW').length,
    active: listings.filter(item => item.status === 'ACTIVE').length,
    live: listings.filter(item => item.saleEventStatus === 'LIVE').length
  }), [listings]);

  async function refreshIdentity() {
    const next = await api.me();
    onIdentityChange(next);
    setProfile(next.sellerProfile);
  }

  async function createProfile() {
    if (!profileName.trim()) return;
    setBusy(true);
    try {
      const created = await api.createSellerProfile(profileName.trim());
      setProfile(created);
      await refreshIdentity();
      notify('판매자 프로필이 개설되었습니다.', 'success');
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  async function submitVerification() {
    if (!verification.evidenceRef.trim()) return;
    setBusy(true);
    try {
      const updated = await api.submitSellerVerification(verification.evidenceRef.trim(), verification.note.trim());
      setProfile(updated);
      await refreshIdentity();
      notify('판매자 인증 검수를 요청했습니다.', 'success');
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  function openCreateListing() {
    setEditing(undefined);
    setListingForm({ ...emptyListing });
    setListingFormOpen(true);
    setEventListing(undefined);
  }

  function openEditListing(listing: SellerListing) {
    setEditing(listing);
    setListingForm({
      name: listing.name,
      description: listing.description ?? '',
      price: Number(listing.price),
      category: listing.category,
      quantity: listing.totalQuantity,
      imageUrl: listing.imageUrl ?? '',
      itemCondition: listing.itemCondition,
      brand: listing.brand ?? '',
      tags: listing.tags ?? '',
      authenticityNote: listing.authenticityNote ?? '',
      defectDescription: listing.defectDescription ?? ''
    });
    setListingFormOpen(true);
    setEventListing(undefined);
  }

  async function saveListing(submit: boolean) {
    if (!listingForm.name.trim() || !listingForm.category || listingForm.price <= 0 || listingForm.quantity <= 0) return;
    setBusy(true);
    try {
      const saved = editing
        ? await api.updateSellerListing(editing.listingId, listingForm)
        : await api.createSellerListing(listingForm);
      if (submit) await api.submitSellerListing(saved.listingId);
      notify(submit ? '상품을 저장하고 검수 요청했습니다.' : '상품 초안을 저장했습니다.', 'success');
      setListingFormOpen(false);
      setEditing(undefined);
      await loadListings();
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  async function submitListing(listingId: string) {
    setBusy(true);
    try {
      await api.submitSellerListing(listingId);
      notify('상품 검수를 요청했습니다.', 'success');
      await loadListings();
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  function openEvent(listing: SellerListing) {
    setEventListing(listing);
    setEventForm(eventDefaults(listing));
    setListingFormOpen(false);
  }

  async function saveEvent() {
    if (!eventListing || !eventForm || eventForm.price <= 0 || eventForm.stockQuantity <= 0) return;
    if (eventForm.endsAt && eventForm.startsAt && eventForm.endsAt <= eventForm.startsAt) {
      notify('판매 종료 시각은 시작 시각보다 늦어야 합니다.', 'error');
      return;
    }
    setBusy(true);
    try {
      await api.createSaleEvent(eventListing.listingId, {
        ...eventForm,
        endsAt: eventForm.saleType === 'FIXED_PRICE' && !eventForm.endsAt ? undefined : eventForm.endsAt,
        minBidIncrement: eventForm.saleType === 'AUCTION' ? eventForm.minBidIncrement : undefined,
        reservePrice: eventForm.saleType === 'AUCTION' ? eventForm.reservePrice : undefined
      });
      notify(eventForm.publishImmediately ? '판매를 시작했습니다.' : '판매 일정을 저장했습니다.', 'success');
      setEventListing(undefined);
      setEventForm(undefined);
      await loadListings();
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  async function publishEvent(eventId: string) {
    setBusy(true);
    try {
      await api.publishSaleEvent(eventId);
      notify('상품 판매를 시작했습니다.', 'success');
      await loadListings();
    } catch (error) { notify((error as Error).message, 'error'); }
    finally { setBusy(false); }
  }

  if (!profile) return <section className="seller-view seller-onboarding">
    <header className="seller-view__header">
      <button className="icon-button" onClick={close} title="마켓으로"><ChevronLeft /></button>
      <div><h1>상품 등록 시작</h1><p>상점을 개설하면 바로 상품 초안을 등록할 수 있습니다.</p></div>
    </header>
    <div className="seller-onboarding__body">
      <Store />
      <div><strong>상점 이름</strong><span>상품과 주문에 표시되는 판매자 이름입니다.</span></div>
      <input maxLength={80} value={profileName} onChange={event => setProfileName(event.target.value)} placeholder="예: 에브리 빈티지" aria-label="상점 이름" />
      <button className="primary-button" disabled={busy || !profileName.trim()} onClick={() => void createProfile()}>
        {busy ? <Loader2 className="spin" /> : <Check />}상점 개설 후 상품 등록
      </button>
    </div>
  </section>;

  const canSubmitVerification = profile.verificationStatus === 'UNVERIFIED' || profile.verificationStatus === 'REJECTED';

  return <section className="seller-view">
    <header className="seller-view__header">
      <button className="icon-button" onClick={close} title="마켓으로"><ChevronLeft /></button>
      <div><h1>판매 관리</h1><p>{profile.displayName}</p></div>
      {section === 'PRODUCTS' && <button className="secondary-button" disabled={busy || profile.status === 'SUSPENDED'} onClick={openCreateListing}><Plus />상품 등록</button>}
    </header>

    <div className="seller-summary">
      <div className="seller-summary__identity"><Store /><div><strong>{profile.displayName}</strong><span>{profile.sellerId}</span></div></div>
      <div><span>판매자 상태</span><strong>{profile.status === 'ACTIVE' ? '운영 중' : profile.status === 'SUSPENDED' ? '이용 제한' : '승인 대기'}</strong></div>
      <div><span>등록 상품</span><strong>{counts.total}</strong></div>
      <div><span>검수 중</span><strong>{counts.review}</strong></div>
      <div><span>판매 가능</span><strong>{counts.active}</strong></div>
      <div><span>판매 중</span><strong>{counts.live}</strong></div>
    </div>

    <section className={`verification-strip verification-strip--${profile.verificationStatus.toLowerCase()}`}>
      {profile.verificationStatus === 'VERIFIED' ? <BadgeCheck /> : profile.verificationStatus === 'PENDING_REVIEW' ? <Clock3 /> : <ShieldCheck />}
      <div><strong>{verificationStatus[profile.verificationStatus]}</strong>
        <span>{profile.verificationStatus === 'VERIFIED' ? '판매 이벤트를 생성하고 게시할 수 있습니다.'
          : profile.verificationStatus === 'PENDING_REVIEW' ? '운영팀이 제출 자료를 확인하고 있습니다.'
            : profile.verificationStatus === 'REJECTED' ? profile.verificationNote ?? '보완 자료를 제출해 주세요.'
              : '판매 시작 전에 본인 또는 사업자 확인이 필요합니다.'}</span></div>
      {canSubmitVerification && <div className="verification-form">
        <input value={verification.evidenceRef} onChange={event => setVerification({ ...verification, evidenceRef: event.target.value })} placeholder="인증 자료 참조번호 또는 보관 URL" aria-label="인증 자료" />
        <input value={verification.note} onChange={event => setVerification({ ...verification, note: event.target.value })} placeholder="검수 메모 (선택)" aria-label="인증 메모" />
        <button className="secondary-button" disabled={busy || !verification.evidenceRef.trim()} onClick={() => void submitVerification()}><Send />인증 제출</button>
      </div>}
    </section>

    <nav className="seller-tabs" aria-label="판매 관리 메뉴">
      <button aria-current={section === 'PRODUCTS' ? 'page' : undefined} onClick={() => setSection('PRODUCTS')}><PackageOpen />상품</button>
      <button aria-current={section === 'ORDERS' ? 'page' : undefined} onClick={() => setSection('ORDERS')}><ClipboardList />주문·배송</button>
      <button aria-current={section === 'SETTLEMENTS' ? 'page' : undefined} onClick={() => setSection('SETTLEMENTS')}><WalletCards />정산</button>
    </nav>

    {section === 'PRODUCTS' && <>{listingFormOpen && <ListingForm
      value={listingForm}
      editing={editing}
      busy={busy}
      onChange={setListingForm}
      onCancel={() => { setListingFormOpen(false); setEditing(undefined); }}
      onSave={saveListing}
    />}

    {eventListing && eventForm && <EventForm
      listing={eventListing}
      value={eventForm}
      busy={busy}
      onChange={setEventForm}
      onCancel={() => { setEventListing(undefined); setEventForm(undefined); }}
      onSave={saveEvent}
    />}

    <div className="seller-list-heading"><div><h2>상품 목록</h2><span>상품 검수와 판매 상태</span></div></div>
    {loading ? <div className="content-state"><Loader2 className="spin" />상품을 불러오는 중입니다</div>
      : listings.length === 0 ? <div className="seller-empty"><PackageOpen /><strong>등록된 상품이 없습니다</strong><button className="secondary-button" onClick={openCreateListing}><Plus />첫 상품 등록</button></div>
        : <div className="seller-list" role="table" aria-label="판매 상품 목록">
          {listings.map(listing => <article key={listing.listingId} role="row">
            <div className="seller-product">
              <div className="seller-product__image">{listing.imageUrl ? <img src={listing.imageUrl} alt="" /> : <ImageIcon />}</div>
              <div><span>{listing.brand || listing.category}</span><strong>{listing.name}</strong><small>{listing.listingId}</small></div>
            </div>
            <div><span>가격 / 재고</span><strong>{formatMoney(listing.price)}</strong><small>{listing.availableQuantity} / {listing.totalQuantity}개</small></div>
            <div><span>상품 상태</span><strong className={`status-text status-text--${listing.status.toLowerCase()}`}>{listingStatus[listing.status]}</strong>{listing.reviewNote && <small>{listing.reviewNote}</small>}</div>
            <div><span>판매 상태</span><strong>{eventStatusLabel(listing.saleEventStatus)}</strong>{listing.saleType && <small>{saleTypeLabels[listing.saleType]}</small>}</div>
            <div className="seller-list__actions">
              {(listing.status === 'DRAFT' || listing.status === 'REJECTED') && <>
                <button className="icon-button" onClick={() => openEditListing(listing)} title="상품 수정"><Pencil /></button>
                <button className="secondary-button" disabled={busy} onClick={() => void submitListing(listing.listingId)}><FileCheck2 />검수 제출</button>
              </>}
              {listing.status === 'ACTIVE' && (!listing.saleEventId || listing.saleEventStatus === 'ENDED' || listing.saleEventStatus === 'CANCELLED') &&
                <button className="secondary-button" onClick={() => openEvent(listing)}><CalendarClock />판매 설정</button>}
              {listing.status === 'ACTIVE' && listing.saleEventId && listing.saleEventStatus === 'SCHEDULED' &&
                <button className="primary-button seller-publish" disabled={busy} onClick={() => void publishEvent(listing.saleEventId!)}><ExternalLink />판매 시작</button>}
              {listing.saleEventStatus === 'LIVE' && <span className="seller-live"><span />판매 중</span>}
            </div>
          </article>)}
        </div>}</>}
    {section === 'ORDERS' && <SellerOrders sellerId={profile.sellerId} notify={notify} />}
    {section === 'SETTLEMENTS' && <SellerSettlements sellerId={profile.sellerId} notify={notify} />}
  </section>;
}

function ListingForm({ value, editing, busy, onChange, onCancel, onSave }: {
  value: SellerListingInput;
  editing?: SellerListing;
  busy: boolean;
  onChange: (value: SellerListingInput) => void;
  onCancel: () => void;
  onSave: (submit: boolean) => Promise<void>;
}) {
  const valid = value.name.trim() && value.category && value.price > 0 && value.quantity > 0;
  const set = <K extends keyof SellerListingInput>(key: K, next: SellerListingInput[K]) => onChange({ ...value, [key]: next });
  return <section className="seller-editor">
    <header><div><h2>{editing ? '상품 수정' : '상품 등록'}</h2><span>구매자가 판단하는 데 필요한 상품 정보를 입력합니다.</span></div><button className="icon-button" onClick={onCancel} title="등록 화면 닫기"><X /></button></header>
    <div className="seller-editor__grid">
      <label className="span-2">상품명<input required maxLength={255} value={value.name} onChange={event => set('name', event.target.value)} /></label>
      <label>카테고리<select value={value.category} onChange={event => set('category', event.target.value)}>
        <option value="FASHION">패션</option><option value="COLLECTIBLE">컬렉터블</option><option value="ELECTRONICS">전자기기</option>
        <option value="CONCERT">공연·티켓</option><option value="BEAUTY">뷰티</option><option value="HOME_LIVING">홈·리빙</option><option value="OTHER">기타</option>
      </select></label>
      <label>상품 상태<select value={value.itemCondition} onChange={event => set('itemCondition', event.target.value)}>
        <option value="NEW">새 상품</option><option value="LIKE_NEW">사용감 거의 없음</option><option value="GOOD">상태 양호</option>
        <option value="FAIR">사용감 있음</option><option value="DIGITAL_TICKET">디지털 티켓</option>
      </select></label>
      <label>판매 기준가<input required type="number" min="1" step="1" value={value.price || ''} onChange={event => set('price', Number(event.target.value))} /></label>
      <label>수량<input required type="number" min="1" step="1" value={value.quantity} onChange={event => set('quantity', Number(event.target.value))} /></label>
      <label>브랜드<input maxLength={255} value={value.brand} onChange={event => set('brand', event.target.value)} /></label>
      <label>검색 태그<input maxLength={1000} value={value.tags} onChange={event => set('tags', event.target.value)} placeholder="쉼표로 구분" /></label>
      <label className="span-2">대표 이미지 URL<input type="url" maxLength={1000} value={value.imageUrl} onChange={event => set('imageUrl', event.target.value)} placeholder="https://" /></label>
      <label className="span-2">상품 설명<textarea maxLength={1000} value={value.description} onChange={event => set('description', event.target.value)} /></label>
      <label>정품·소유 증빙<textarea maxLength={1000} value={value.authenticityNote} onChange={event => set('authenticityNote', event.target.value)} /></label>
      <label>하자 및 유의사항<textarea maxLength={1000} value={value.defectDescription} onChange={event => set('defectDescription', event.target.value)} /></label>
    </div>
    {value.itemCondition === 'DIGITAL_TICKET' && <div className="editor-note"><CircleAlert />등록 수량만큼 좌석이 생성되며, 결제 시 구매자가 좌석을 선택합니다.</div>}
    <footer><button className="secondary-button" onClick={onCancel}>취소</button><button className="secondary-button" disabled={busy || !valid} onClick={() => void onSave(false)}><Check />초안 저장</button><button className="primary-button" disabled={busy || !valid} onClick={() => void onSave(true)}><FileCheck2 />저장 후 검수 제출</button></footer>
  </section>;
}

function EventForm({ listing, value, busy, onChange, onCancel, onSave }: {
  listing: SellerListing;
  value: SaleEventInput;
  busy: boolean;
  onChange: (value: SaleEventInput) => void;
  onCancel: () => void;
  onSave: () => Promise<void>;
}) {
  const set = <K extends keyof SaleEventInput>(key: K, next: SaleEventInput[K]) => onChange({ ...value, [key]: next });
  return <section className="seller-editor seller-event-editor">
    <header><div><h2>판매 설정</h2><span>{listing.name}</span></div><button className="icon-button" onClick={onCancel} title="판매 설정 닫기"><X /></button></header>
    <div className="seller-editor__grid seller-event-grid">
      <label>판매 방식<select value={value.saleType} onChange={event => set('saleType', event.target.value as SaleType)}>
        <option value="FIXED_PRICE">일반 판매</option><option value="DROP">한정 수량</option><option value="RAFFLE">래플</option><option value="AUCTION">실시간 경매</option>
      </select></label>
      <label>판매가<input type="number" min="1" step="1" value={value.price} onChange={event => set('price', Number(event.target.value))} /></label>
      <label>판매 수량<input type="number" min="1" max={listing.availableQuantity} step="1" value={value.stockQuantity} onChange={event => set('stockQuantity', Number(event.target.value))} /></label>
      <label>시작 시각<input type="datetime-local" value={value.startsAt ?? ''} onChange={event => set('startsAt', event.target.value || undefined)} /></label>
      <label>종료 시각<input type="datetime-local" value={value.endsAt ?? ''} onChange={event => set('endsAt', event.target.value || undefined)} /></label>
      {value.saleType === 'AUCTION' && <><label>최소 입찰 단위<input type="number" min="1" step="1" value={value.minBidIncrement ?? ''} onChange={event => set('minBidIncrement', Number(event.target.value))} /></label><label>낙찰 하한가<input type="number" min="1" step="1" value={value.reservePrice ?? ''} onChange={event => set('reservePrice', Number(event.target.value))} /></label></>}
      <label className="seller-toggle"><input type="checkbox" checked={value.publishImmediately} onChange={event => set('publishImmediately', event.target.checked)} /><span>저장 즉시 판매 시작</span></label>
    </div>
    <footer><button className="secondary-button" onClick={onCancel}>취소</button><button className="primary-button" disabled={busy || value.price <= 0 || value.stockQuantity <= 0 || value.stockQuantity > listing.availableQuantity} onClick={() => void onSave()}><CalendarClock />판매 일정 저장</button></footer>
  </section>;
}
