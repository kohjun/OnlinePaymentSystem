import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  BadgeCheck,
  Archive,
  CalendarClock,
  Check,
  ChevronLeft,
  CircleAlert,
  ClipboardList,
  Clock3,
  Download,
  ExternalLink,
  FileCheck2,
  FileText,
  Image as ImageIcon,
  Images,
  Loader2,
  PackageOpen,
  Pencil,
  PauseCircle,
  Play,
  Plus,
  Send,
  ShieldCheck,
  Store,
  Trash2,
  Upload,
  WalletCards,
  X
} from 'lucide-react';
import { api } from './api';
import { formatMoney } from './EventCard';
import { SellerOrders, SellerSettlements } from './SellerOperations';
import type {
  Identity,
  ListingMedia,
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
    maxQuantityPerCustomer: listing.availableQuantity,
    startsAt: localDateTime(1),
    endsAt: localDateTime(25),
    minBidIncrement: 1000,
    reservePrice: Number(listing.price),
    activationMode: 'SCHEDULED',
    publishImmediately: false
  };
}

function eventStatusLabel(status?: string) {
  if (status === 'LIVE') return '판매 중';
  if (status === 'SCHEDULED') return '판매 예정';
  if (status === 'ACTIVATION_FAILED') return '시작 실패';
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
  const [existingMedia, setExistingMedia] = useState<ListingMedia[]>([]);
  const [pendingProductImages, setPendingProductImages] = useState<File[]>([]);
  const [pendingEvidence, setPendingEvidence] = useState<File[]>([]);
  const [removedMediaIds, setRemovedMediaIds] = useState<Set<string>>(new Set());
  const [mediaLoading, setMediaLoading] = useState(false);

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
    setExistingMedia([]);
    setPendingProductImages([]);
    setPendingEvidence([]);
    setRemovedMediaIds(new Set());
  }

  async function openEditListing(listing: SellerListing) {
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
    setPendingProductImages([]);
    setPendingEvidence([]);
    setRemovedMediaIds(new Set());
    setMediaLoading(true);
    try {
      setExistingMedia(await api.listingMedia(listing.listingId));
    } catch (error) {
      setExistingMedia([]);
      notify((error as Error).message, 'error');
    } finally {
      setMediaLoading(false);
    }
  }

  function addMediaFiles(type: 'PRODUCT_IMAGE' | 'AUTHENTICITY_EVIDENCE', selected: FileList | null) {
    if (!selected?.length) return;
    const currentExisting = existingMedia.filter(media => media.mediaType === type && !removedMediaIds.has(media.mediaId)).length;
    const currentPending = type === 'PRODUCT_IMAGE' ? pendingProductImages : pendingEvidence;
    const slots = Math.max(0, 3 - currentExisting - currentPending.length);
    const allowed = type === 'PRODUCT_IMAGE'
      ? new Set(['image/jpeg', 'image/png', 'image/webp'])
      : new Set(['image/jpeg', 'image/png', 'image/webp', 'application/pdf']);
    const maxBytes = (type === 'PRODUCT_IMAGE' ? 10 : 15) * 1024 * 1024;
    const candidates = Array.from(selected).filter(file => {
      if (!allowed.has(file.type) || file.size <= 0 || file.size > maxBytes) {
        notify(`${file.name}: 지원하지 않는 형식이거나 파일 크기 제한을 초과했습니다.`, 'error');
        return false;
      }
      return true;
    });
    if (candidates.length > slots) notify('각 첨부 영역에는 최대 3개까지 등록할 수 있습니다.', 'error');
    const accepted = candidates.slice(0, slots);
    if (type === 'PRODUCT_IMAGE') setPendingProductImages([...pendingProductImages, ...accepted]);
    else setPendingEvidence([...pendingEvidence, ...accepted]);
  }

  function removeExistingMedia(mediaId: string) {
    setRemovedMediaIds(previous => new Set(previous).add(mediaId));
  }

  async function saveListing(submit: boolean) {
    if (!listingForm.name.trim() || !listingForm.category || listingForm.price <= 0 || listingForm.quantity <= 0) return;
    setBusy(true);
    try {
      const saved = editing
        ? await api.updateSellerListing(editing.listingId, listingForm)
        : await api.createSellerListing(listingForm);
      if (!editing) setEditing(saved);
      for (const mediaId of removedMediaIds) await api.deleteListingMedia(saved.listingId, mediaId);
      if (pendingProductImages.length) await api.uploadListingMedia(saved.listingId, 'PRODUCT_IMAGE', pendingProductImages);
      if (pendingEvidence.length) await api.uploadListingMedia(saved.listingId, 'AUTHENTICITY_EVIDENCE', pendingEvidence);
      const activeEdit = saved.status === 'ACTIVE';
      if (submit && !activeEdit) await api.submitSellerListing(saved.listingId);
      notify(activeEdit ? '판매 중 상품 정보가 반영되었습니다.'
        : submit ? '상품을 저장하고 검수 요청했습니다.' : '상품 초안을 저장했습니다.', 'success');
      setListingFormOpen(false);
      setEditing(undefined);
      setExistingMedia([]);
      setPendingProductImages([]);
      setPendingEvidence([]);
      setRemovedMediaIds(new Set());
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
    if (!eventListing || !eventForm || eventForm.price <= 0 || eventForm.stockQuantity <= 0
        || eventForm.maxQuantityPerCustomer <= 0
        || eventForm.maxQuantityPerCustomer > eventForm.stockQuantity) return;
    if (eventForm.endsAt && eventForm.startsAt && eventForm.endsAt <= eventForm.startsAt) {
      notify('판매 종료 시각은 시작 시각보다 늦어야 합니다.', 'error');
      return;
    }
    setBusy(true);
    try {
      await api.createSaleEvent(eventListing.listingId, {
        ...eventForm,
        startsAt: eventForm.startsAt ? new Date(eventForm.startsAt).toISOString() : undefined,
        endsAt: eventForm.saleType === 'FIXED_PRICE' && !eventForm.endsAt
          ? undefined
          : eventForm.endsAt ? new Date(eventForm.endsAt).toISOString() : undefined,
        publishImmediately: eventForm.activationMode === 'IMMEDIATE',
        minBidIncrement: eventForm.saleType === 'AUCTION' ? eventForm.minBidIncrement : undefined,
        reservePrice: eventForm.saleType === 'AUCTION' ? eventForm.reservePrice : undefined
      });
      notify(eventForm.activationMode === 'IMMEDIATE' ? '판매를 시작했습니다.' : '판매 일정을 저장했습니다.', 'success');
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

  async function changeListingLifecycle(listing: SellerListing, action: 'pause' | 'resume' | 'archive') {
    const prompt = action === 'pause'
      ? '판매를 중지하시겠습니까? 진행 중인 결제가 있거나 경매·래플이 시작된 경우에는 중지할 수 없습니다.'
      : action === 'resume'
        ? '상품을 다시 판매 가능한 상태로 전환하시겠습니까? 새 판매 일정을 설정해야 마켓에 노출됩니다.'
        : '상품을 보관 처리하시겠습니까? 보관 후에는 다시 판매할 수 없습니다.';
    if (!window.confirm(prompt)) return;
    setBusy(true);
    try {
      if (action === 'pause') await api.pauseSellerListing(listing.listingId);
      else if (action === 'resume') await api.resumeSellerListing(listing.listingId);
      else await api.archiveSellerListing(listing.listingId);
      notify(action === 'pause' ? '판매를 안전하게 중지했습니다.'
        : action === 'resume' ? '상품을 판매 가능한 상태로 전환했습니다.' : '상품을 보관 처리했습니다.', 'success');
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
      mediaLoading={mediaLoading}
      existingMedia={existingMedia.filter(media => !removedMediaIds.has(media.mediaId))}
      pendingProductImages={pendingProductImages}
      pendingEvidence={pendingEvidence}
      onChange={setListingForm}
      onAddMedia={addMediaFiles}
      onRemoveExistingMedia={removeExistingMedia}
      onRemovePendingProduct={index => setPendingProductImages(files => files.filter((_, itemIndex) => itemIndex !== index))}
      onRemovePendingEvidence={index => setPendingEvidence(files => files.filter((_, itemIndex) => itemIndex !== index))}
      onCancel={() => { setListingFormOpen(false); setEditing(undefined); setExistingMedia([]); setPendingProductImages([]); setPendingEvidence([]); setRemovedMediaIds(new Set()); }}
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
            <div><span>판매 상태</span><strong>{eventStatusLabel(listing.saleEventStatus)}</strong>{listing.saleType && <small>{saleTypeLabels[listing.saleType]}</small>}{listing.saleEventStatus === 'SCHEDULED' && listing.startsAt && <small>시작 {new Date(listing.startsAt).toLocaleString('ko-KR')}</small>}{listing.saleEventStatus === 'ACTIVATION_FAILED' && <small>{listing.activationFailureReason ?? '운영팀 확인이 필요합니다.'}</small>}</div>
            <div className="seller-list__actions">
              {(listing.status === 'DRAFT' || listing.status === 'REJECTED') && <>
                <button className="icon-button" onClick={() => void openEditListing(listing)} title="상품 수정"><Pencil /></button>
                <button className="secondary-button" disabled={busy} onClick={() => void submitListing(listing.listingId)}><FileCheck2 />검수 제출</button>
              </>}
              {listing.status === 'ACTIVE' && <button className="icon-button" onClick={() => void openEditListing(listing)} title="판매 상품 정보 수정"><Pencil /></button>}
              {listing.status === 'ACTIVE' && <button className="icon-button" disabled={busy} onClick={() => void changeListingLifecycle(listing, 'pause')} title="판매 중지"><PauseCircle /></button>}
              {listing.status === 'PAUSED' && <>
                <button className="secondary-button" disabled={busy} onClick={() => void changeListingLifecycle(listing, 'resume')}><Play />판매 재개</button>
                <button className="icon-button" disabled={busy} onClick={() => void changeListingLifecycle(listing, 'archive')} title="상품 보관"><Archive /></button>
              </>}
              {(listing.status === 'DRAFT' || listing.status === 'REJECTED') &&
                <button className="icon-button" disabled={busy} onClick={() => void changeListingLifecycle(listing, 'archive')} title="상품 보관"><Archive /></button>}
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

function ListingForm({ value, editing, busy, mediaLoading, existingMedia, pendingProductImages, pendingEvidence,
  onChange, onAddMedia, onRemoveExistingMedia, onRemovePendingProduct, onRemovePendingEvidence, onCancel, onSave }: {
  value: SellerListingInput;
  editing?: SellerListing;
  busy: boolean;
  mediaLoading: boolean;
  existingMedia: ListingMedia[];
  pendingProductImages: File[];
  pendingEvidence: File[];
  onChange: (value: SellerListingInput) => void;
  onAddMedia: (type: 'PRODUCT_IMAGE' | 'AUTHENTICITY_EVIDENCE', files: FileList | null) => void;
  onRemoveExistingMedia: (mediaId: string) => void;
  onRemovePendingProduct: (index: number) => void;
  onRemovePendingEvidence: (index: number) => void;
  onCancel: () => void;
  onSave: (submit: boolean) => Promise<void>;
}) {
  const valid = value.name.trim() && value.category && value.price > 0 && value.quantity > 0;
  const commerceLocked = editing?.status === 'ACTIVE';
  const set = <K extends keyof SellerListingInput>(key: K, next: SellerListingInput[K]) => onChange({ ...value, [key]: next });
  return <section className="seller-editor">
    <header><div><h2>{commerceLocked ? '판매 상품 정보 수정' : editing ? '상품 수정' : '상품 등록'}</h2><span>{commerceLocked
      ? '거래 중에는 가격·수량·카테고리·상품 상태가 고정됩니다.'
      : '구매자가 판단하는 데 필요한 상품 정보를 입력합니다.'}</span></div><button className="icon-button" onClick={onCancel} title="등록 화면 닫기"><X /></button></header>
    <div className="seller-editor__grid">
      <label className="span-2">상품명<input required maxLength={255} value={value.name} onChange={event => set('name', event.target.value)} /></label>
      <label>카테고리<select disabled={commerceLocked} value={value.category} onChange={event => set('category', event.target.value)}>
        <option value="FASHION">패션</option><option value="COLLECTIBLE">컬렉터블</option><option value="ELECTRONICS">전자기기</option>
        <option value="CONCERT">공연·티켓</option><option value="BEAUTY">뷰티</option><option value="HOME_LIVING">홈·리빙</option><option value="OTHER">기타</option>
      </select></label>
      <label>상품 상태<select disabled={commerceLocked} value={value.itemCondition} onChange={event => set('itemCondition', event.target.value)}>
        <option value="NEW">새 상품</option><option value="LIKE_NEW">사용감 거의 없음</option><option value="GOOD">상태 양호</option>
        <option value="FAIR">사용감 있음</option><option value="DIGITAL_TICKET">디지털 티켓</option>
      </select></label>
      <label>판매 기준가<input disabled={commerceLocked} required type="number" min="1" step="1" value={value.price || ''} onChange={event => set('price', Number(event.target.value))} /></label>
      <label>수량<input disabled={commerceLocked} required type="number" min="1" step="1" value={value.quantity} onChange={event => set('quantity', Number(event.target.value))} /></label>
      <label>브랜드<input maxLength={255} value={value.brand} onChange={event => set('brand', event.target.value)} /></label>
      <label>검색 태그<input maxLength={1000} value={value.tags} onChange={event => set('tags', event.target.value)} placeholder="쉼표로 구분" /></label>
      <MediaPicker
        className="span-2"
        title="상품 이미지"
        description="JPEG, PNG, WebP · 파일당 10MB · 최대 3장"
        mediaType="PRODUCT_IMAGE"
        accept="image/jpeg,image/png,image/webp"
        icon="image"
        loading={mediaLoading}
        existing={existingMedia.filter(media => media.mediaType === 'PRODUCT_IMAGE')}
        pending={pendingProductImages}
        onAdd={onAddMedia}
        onRemoveExisting={onRemoveExistingMedia}
        onRemovePending={onRemovePendingProduct}
      />
      <label className="span-2">상품 설명<textarea maxLength={1000} value={value.description} onChange={event => set('description', event.target.value)} /></label>
      <label>정품·소유 증빙<textarea maxLength={1000} value={value.authenticityNote} onChange={event => set('authenticityNote', event.target.value)} /></label>
      <label>하자 및 유의사항<textarea maxLength={1000} value={value.defectDescription} onChange={event => set('defectDescription', event.target.value)} /></label>
      <MediaPicker
        className="span-2"
        title="정품·소유 증빙 첨부"
        description="JPEG, PNG, WebP, PDF · 파일당 15MB · 최대 3개"
        mediaType="AUTHENTICITY_EVIDENCE"
        accept="image/jpeg,image/png,image/webp,application/pdf"
        icon="document"
        loading={mediaLoading}
        existing={existingMedia.filter(media => media.mediaType === 'AUTHENTICITY_EVIDENCE')}
        pending={pendingEvidence}
        onAdd={onAddMedia}
        onRemoveExisting={onRemoveExistingMedia}
        onRemovePending={onRemovePendingEvidence}
      />
    </div>
    {value.itemCondition === 'DIGITAL_TICKET' && <div className="editor-note"><CircleAlert />등록 수량만큼 좌석이 생성되며, 결제 시 구매자가 좌석을 선택합니다.</div>}
    <footer><button className="secondary-button" onClick={onCancel}>취소</button>{commerceLocked
      ? <button className="primary-button" disabled={busy || mediaLoading || !valid} onClick={() => void onSave(false)}><Check />변경사항 저장</button>
      : <><button className="secondary-button" disabled={busy || mediaLoading || !valid} onClick={() => void onSave(false)}><Check />초안 저장</button><button className="primary-button" disabled={busy || mediaLoading || !valid} onClick={() => void onSave(true)}><FileCheck2 />저장 후 검수 제출</button></>}</footer>
  </section>;
}

function MediaPicker({ className, title, description, mediaType, accept, icon, loading, existing, pending,
  onAdd, onRemoveExisting, onRemovePending }: {
  className?: string;
  title: string;
  description: string;
  mediaType: 'PRODUCT_IMAGE' | 'AUTHENTICITY_EVIDENCE';
  accept: string;
  icon: 'image' | 'document';
  loading: boolean;
  existing: ListingMedia[];
  pending: File[];
  onAdd: (type: 'PRODUCT_IMAGE' | 'AUTHENTICITY_EVIDENCE', files: FileList | null) => void;
  onRemoveExisting: (mediaId: string) => void;
  onRemovePending: (index: number) => void;
}) {
  const count = existing.length + pending.length;
  return <section className={`media-picker ${className ?? ''}`}>
    <header><div>{icon === 'image' ? <Images /> : <FileText />}<span><strong>{title}</strong><small>{description}</small></span></div><span>{count}/3</span></header>
    {loading ? <div className="media-picker__loading"><Loader2 className="spin" />첨부 파일 확인 중</div> : <>
      {count > 0 && <div className="media-picker__items">
        {existing.map(media => <ExistingMedia key={media.mediaId} media={media} onRemove={() => onRemoveExisting(media.mediaId)} />)}
        {pending.map((file, index) => <PendingMedia key={`${file.name}-${file.lastModified}-${index}`} file={file} onRemove={() => onRemovePending(index)} />)}
      </div>}
      {count < 3 && <label className="media-upload-button"><Upload /><span>파일 선택</span><input type="file" multiple accept={accept} onChange={event => { onAdd(mediaType, event.target.files); event.target.value = ''; }} /></label>}
    </>}
  </section>;
}

function ExistingMedia({ media, onRemove }: { media: ListingMedia; onRemove: () => void }) {
  const [preview, setPreview] = useState<string>();
  const [error, setError] = useState<string>();
  const isImage = media.contentType.startsWith('image/');

  useEffect(() => {
    if (!isImage) return;
    let disposed = false;
    let objectUrl: string | undefined;
    void api.listingMediaContent(media.listingId, media.mediaId)
      .then(blob => {
        if (disposed) return;
        objectUrl = URL.createObjectURL(blob);
        setPreview(objectUrl);
      })
      .catch(() => {
        if (!disposed) setError('미리보기를 불러오지 못했습니다.');
      });
    return () => {
      disposed = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [isImage, media.listingId, media.mediaId]);

  async function download() {
    try {
      setError(undefined);
      const blob = await api.listingMediaContent(media.listingId, media.mediaId);
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = objectUrl;
      link.download = media.fileName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(objectUrl);
    } catch {
      setError('첨부 파일을 내려받지 못했습니다.');
    }
  }

  return <article>
    {preview ? <img src={preview} alt="" /> : isImage ? <ImageIcon /> : <FileText />}
    <div><strong>{media.fileName}</strong><small>{error ?? formatFileSize(media.sizeBytes)}</small></div>
    {!isImage && <button className="icon-button" type="button" onClick={() => void download()} title="증빙 내려받기"><Download /></button>}
    <button className="icon-button" type="button" onClick={onRemove} title="첨부 삭제"><Trash2 /></button>
  </article>;
}

function PendingMedia({ file, onRemove }: { file: File; onRemove: () => void }) {
  const [preview, setPreview] = useState<string>();
  useEffect(() => {
    if (!file.type.startsWith('image/')) return;
    const url = URL.createObjectURL(file);
    setPreview(url);
    return () => URL.revokeObjectURL(url);
  }, [file]);
  return <article className="media-picker__pending">
    {preview ? <img src={preview} alt="" /> : <FileText />}
    <div><strong>{file.name}</strong><small>{formatFileSize(file.size)} · 저장 대기</small></div>
    <button className="icon-button" type="button" onClick={onRemove} title="선택 취소"><Trash2 /></button>
  </article>;
}

function formatFileSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
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
      <label>판매 방식<select value={value.saleType} onChange={event => {
        const saleType = event.target.value as SaleType;
        onChange({
          ...value,
          saleType,
          stockQuantity: saleType === 'AUCTION' ? 1 : value.stockQuantity,
          maxQuantityPerCustomer: saleType === 'FIXED_PRICE' ? value.stockQuantity : 1
        });
      }}>
        <option value="FIXED_PRICE">일반 판매</option><option value="DROP">한정 수량</option><option value="RAFFLE">래플</option><option value="AUCTION">실시간 경매</option>
      </select></label>
      <label>판매가<input type="number" min="1" step="1" value={value.price} onChange={event => set('price', Number(event.target.value))} /></label>
      <label>판매 수량<input type="number" min="1" max={listing.availableQuantity} step="1" value={value.stockQuantity} onChange={event => {
        const stockQuantity = Number(event.target.value);
        onChange({ ...value, stockQuantity, maxQuantityPerCustomer: Math.min(value.maxQuantityPerCustomer, stockQuantity) });
      }} /></label>
      <label>고객별 구매 한도<input type="number" min="1" max={value.stockQuantity} step="1" value={value.maxQuantityPerCustomer} disabled={value.saleType === 'RAFFLE' || value.saleType === 'AUCTION'} onChange={event => set('maxQuantityPerCustomer', Number(event.target.value))} /></label>
      <label>시작 시각<input type="datetime-local" value={value.startsAt ?? ''} onChange={event => set('startsAt', event.target.value || undefined)} /></label>
      <label>종료 시각<input type="datetime-local" value={value.endsAt ?? ''} onChange={event => set('endsAt', event.target.value || undefined)} /></label>
      {value.saleType === 'AUCTION' && <><label>최소 입찰 단위<input type="number" min="1" step="1" value={value.minBidIncrement ?? ''} onChange={event => set('minBidIncrement', Number(event.target.value))} /></label><label>낙찰 하한가<input type="number" min="1" step="1" value={value.reservePrice ?? ''} onChange={event => set('reservePrice', Number(event.target.value))} /></label></>}
      <label>활성화 방식<select value={value.activationMode} onChange={event => {
        const activationMode = event.target.value as SaleEventInput['activationMode'];
        onChange({
          ...value,
          activationMode,
          publishImmediately: activationMode === 'IMMEDIATE',
          startsAt: activationMode === 'IMMEDIATE' ? localDateTime(0) : value.startsAt
        });
      }}><option value="SCHEDULED">예약 시작</option><option value="IMMEDIATE">즉시 시작</option></select></label>
    </div>
    <footer><button className="secondary-button" onClick={onCancel}>취소</button><button className="primary-button" disabled={busy || value.price <= 0 || value.stockQuantity <= 0 || value.stockQuantity > listing.availableQuantity || value.maxQuantityPerCustomer <= 0 || value.maxQuantityPerCustomer > value.stockQuantity} onClick={() => void onSave()}><CalendarClock />판매 일정 저장</button></footer>
  </section>;
}
