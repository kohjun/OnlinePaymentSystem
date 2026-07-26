import { useEffect, useMemo, useState } from 'react';
import { ArrowLeft, CheckCircle2, Flag, Gavel, Loader2, LockKeyhole, Radio, ShieldCheck, Star, Ticket, Users } from 'lucide-react';
import { ApiError, api, openTossPayment } from './api';
import { formatMoney } from './EventCard';
import { TicketPanel } from './TicketPanel';
import type { AuctionStatus, Identity, MarketplaceEvent, RaffleStatus, SellerReview } from './types';

interface Props {
  event: MarketplaceEvent;
  identity: Identity;
  onClose: () => void;
  notify: (message: string, tone?: 'success' | 'error' | 'info') => void;
}

export function EventDetail({ event, identity, onClose, notify }: Props) {
  const [auction, setAuction] = useState<AuctionStatus>();
  const [raffle, setRaffle] = useState<RaffleStatus>();
  const [bidAmount, setBidAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [streamState, setStreamState] = useState<'connecting' | 'live' | 'retrying'>('connecting');
  const [tick, setTick] = useState(0);
  const [reportOpen, setReportOpen] = useState(false);
  const [reportReason, setReportReason] = useState('MISLEADING');
  const [reportDetails, setReportDetails] = useState('');
  const [reviews, setReviews] = useState<SellerReview[]>();

  useEffect(() => {
    let cancelled = false;
    // 판매자 평판은 구매 판단의 근거이므로 실패해도 상세 화면 전체를
    // 막지 않는다. 조회에 실패하면 빈 목록으로 두고 안내만 바꾼다.
    api.listSellerReviews(event.sellerId)
      .then(loaded => { if (!cancelled) setReviews(loaded); })
      .catch(() => { if (!cancelled) setReviews([]); });
    return () => { cancelled = true; };
  }, [event.sellerId]);

  useEffect(() => {
    const timer = window.setInterval(() => setTick(value => value + 1), 1000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (event.saleType === 'AUCTION') {
      void Promise.all([api.auctionStatus(event.saleEventId), api.auctionMe(event.saleEventId)])
        .then(([publicStatus, mine]) => {
          setAuction({ ...publicStatus, currentUserWinning: mine.currentUserWinning });
          setBidAmount(String(publicStatus.minNextBid));
        })
        .catch(error => notify(messageOf(error), 'error'));
      return connectStream(
        `/api/marketplace/events/${encodeURIComponent(event.saleEventId)}/auction/stream`,
        ['auction-status', 'bid-placed', 'auction-closed'],
        payload => setAuction(previous => ({ ...payload, currentUserWinning: previous?.currentUserWinning })),
        setStreamState
      );
    }
    if (event.saleType === 'RAFFLE') {
      void api.raffleStatus(event.saleEventId, identity.customerId)
        .then(setRaffle)
        .catch(error => notify(messageOf(error), 'error'));
      return connectStream(
        `/api/marketplace/events/${encodeURIComponent(event.saleEventId)}/raffle/stream`,
        ['raffle-status', 'entry-count-changed', 'raffle-drawn', 'winner-checkout-updated'],
        () => void api.raffleStatus(event.saleEventId, identity.customerId).then(setRaffle),
        setStreamState
      );
    }
  }, [event.saleEventId, event.saleType, identity.customerId, notify]);

  const secondsRemaining = useMemo(() => {
    void tick;
    if (!event.endsAt) return undefined;
    return Math.max(0, Math.floor((new Date(event.endsAt).getTime() - Date.now()) / 1000));
  }, [event.endsAt, tick]);
  const isTicketEvent = event.digitalTicket
    && (event.saleType === 'FIXED_PRICE' || event.saleType === 'DROP');

  async function perform(action: () => Promise<void>) {
    setBusy(true);
    try {
      await action();
    } catch (error) {
      const apiError = error as ApiError;
      notify(`${messageOf(error)}${apiError.correlationId ? ` · 문의 코드 ${apiError.correlationId}` : ''}`, 'error');
    } finally {
      setBusy(false);
    }
  }

  const checkout = (
    type: 'DIRECT' | 'RAFFLE_WINNER' | 'AUCTION_WINNER',
    amount: number,
    options?: { seatId?: string; digitalDelivery?: boolean }
  ) => perform(async () => {
    let shippingAddressId: string | undefined;
    if (!options?.digitalDelivery) {
      const addresses = await api.addresses();
      shippingAddressId = addresses.find(address => address.defaultAddress)?.addressId;
      if (!shippingAddressId) {
        throw new ApiError('결제 전에 기본 배송지를 등록해 주세요.', 409, 'DEFAULT_SHIPPING_ADDRESS_REQUIRED');
      }
    }
    const intent = await api.createIntent(event.saleEventId, type, amount, {
      seatId: options?.seatId,
      shippingAddressId
    });
    await openTossPayment(intent, identity.buyerProfile?.displayName ?? identity.user?.displayName ?? '에브리세일 고객');
  });

  const enterRaffle = () => perform(async () => {
    await api.enterRaffle(event.saleEventId);
    setRaffle(await api.raffleStatus(event.saleEventId, identity.customerId));
    notify('래플 응모가 완료되었습니다.', 'success');
  });

  const placeBid = () => perform(async () => {
    const amount = Number(bidAmount);
    if (!Number.isFinite(amount) || amount < (auction?.minNextBid ?? 0)) {
      throw new ApiError(`최소 입찰가는 ${formatMoney(auction?.minNextBid ?? 0)}입니다.`, 422, 'BID_TOO_LOW');
    }
    await api.bid(event.saleEventId, amount);
    const [publicStatus, mine] = await Promise.all([api.auctionStatus(event.saleEventId), api.auctionMe(event.saleEventId)]);
    setAuction({ ...publicStatus, currentUserWinning: mine.currentUserWinning });
    setBidAmount(String(publicStatus.minNextBid));
    notify('입찰이 정상적으로 반영되었습니다.', 'success');
  });

  const submitReport = () => perform(async () => {
    await api.reportListing(event.listingId, reportReason, reportDetails);
    setReportOpen(false);
    setReportDetails('');
    notify('신고가 접수되었습니다. 운영팀이 검토합니다.', 'success');
  });

  return (
    <section className="detail-view" aria-labelledby="event-detail-title">
      <header className="detail-view__header">
        <button className="icon-button" type="button" onClick={onClose} title="목록으로"><ArrowLeft /></button>
        <div><span>{event.sellerName}{event.sellerVerificationStatus === 'VERIFIED' ? ' · 본인 확인 판매자' : ' · 확인 진행 중'}</span><h1 id="event-detail-title">{event.title}</h1></div>
        {(event.saleType === 'AUCTION' || event.saleType === 'RAFFLE') && (
          <span className={`stream-state stream-state--${streamState}`}><Radio size={14} />{streamLabel(streamState)}</span>
        )}
      </header>

      <div className="detail-layout">
        <div className="detail-media">
          {event.imageUrl ? <img src={event.imageUrl} alt={event.title} /> : <div className="detail-media__fallback">EVERYSALE</div>}
        </div>
        <div className="detail-commerce">
          <div className="detail-price"><span>판매 기준가</span><strong>{formatMoney(event.price)}</strong></div>
          <p className="detail-description">{event.description || '판매자가 등록한 상세 설명을 준비 중입니다.'}</p>
          <button className="text-button" type="button" onClick={() => setReportOpen(true)}><Flag />상품 신고</button>

          <div className="trust-row">
            <span><ShieldCheck />안전 결제</span><span><LockKeyhole />거래 기록 보호</span>
            <span><CheckCircle2 />{event.sellerVerificationStatus === 'VERIFIED' ? '본인 확인 완료' : '판매자 확인 중'}</span>
          </div>

          <SellerReviewPanel sellerName={event.sellerName} reviews={reviews} />

          {(event.saleType === 'FIXED_PRICE' || event.saleType === 'DROP') && !isTicketEvent && (
            <div className="action-panel">
              <div className="stock-line"><span>구매 가능 수량</span><strong>{event.availableQuantity.toLocaleString('ko-KR')}개</strong></div>
              <button className="primary-button" disabled={busy || event.status !== 'LIVE' || event.availableQuantity < 1}
                      onClick={() => void checkout('DIRECT', event.price)}>
                {busy ? <Loader2 className="spin" /> : <LockKeyhole />} Toss로 안전하게 결제
              </button>
            </div>
          )}

          {isTicketEvent && <TicketPanel event={event} checkoutBusy={busy} notify={notify}
            checkout={seatId => checkout('DIRECT', event.price, { seatId, digitalDelivery: true })} />}

          {event.saleType === 'AUCTION' && <AuctionPanel status={auction} bidAmount={bidAmount} setBidAmount={setBidAmount}
            busy={busy} secondsRemaining={secondsRemaining} placeBid={placeBid}
            checkout={() => void checkout('AUCTION_WINNER', auction?.highestBid ?? event.price)} />}

          {event.saleType === 'RAFFLE' && <RafflePanel status={raffle} busy={busy} enter={enterRaffle}
            checkout={() => void checkout('RAFFLE_WINNER', event.price)} />}
        </div>
      </div>
      {reportOpen && <div className="modal-backdrop" role="presentation" onMouseDown={event => { if (event.target === event.currentTarget) setReportOpen(false); }}>
        <section className="modal" role="dialog" aria-modal="true" aria-labelledby="report-title">
          <header><h2 id="report-title">상품 신고</h2><button className="icon-button" onClick={() => setReportOpen(false)} title="닫기"><ArrowLeft /></button></header>
          <label>신고 사유<select value={reportReason} onChange={event => setReportReason(event.target.value)}>
            <option value="COUNTERFEIT">위조품 의심</option><option value="PROHIBITED_ITEM">판매 금지 상품</option>
            <option value="FRAUD">사기 의심</option><option value="MISLEADING">설명과 다른 정보</option><option value="OTHER">기타</option>
          </select></label>
          <label>상세 내용<textarea maxLength={2000} value={reportDetails} onChange={event => setReportDetails(event.target.value)} /></label>
          <button className="primary-button" disabled={busy} onClick={() => void submitReport()}><Flag />신고 접수</button>
        </section>
      </div>}
    </section>
  );
}

function AuctionPanel({ status, bidAmount, setBidAmount, busy, secondsRemaining, placeBid, checkout }: {
  status?: AuctionStatus; bidAmount: string; setBidAmount: (value: string) => void; busy: boolean;
  secondsRemaining?: number; placeBid: () => void; checkout: () => void;
}) {
  if (!status) return <div className="action-panel loading-line"><Loader2 className="spin" />경매 상태 확인 중</div>;
  return <div className="action-panel">
    <div className="live-metrics">
      <div><span>현재 최고가</span><strong>{formatMoney(status.highestBid)}</strong><small>{status.highestBidder ?? '첫 입찰 대기'}</small></div>
      <div><span>남은 시간</span><strong>{formatDuration(secondsRemaining ?? status.secondsRemaining)}</strong><small>마감 직전 입찰 시 자동 연장</small></div>
    </div>
    {!status.closed && <form onSubmit={event => { event.preventDefault(); placeBid(); }} className="bid-form">
      <label htmlFor="bidAmount">입찰 금액</label>
      <div><input id="bidAmount" type="number" min={status.minNextBid} step="1000" value={bidAmount}
                  onChange={event => setBidAmount(event.target.value)} /><button className="primary-button" disabled={busy}><Gavel />입찰</button></div>
      <small>최소 다음 입찰가 {formatMoney(status.minNextBid)}</small>
    </form>}
    {status.closed && status.currentUserWinning && <button className="primary-button" disabled={busy} onClick={checkout}><LockKeyhole />낙찰 상품 결제</button>}
    {status.closed && !status.currentUserWinning && <div className="result-notice">경매가 종료되었습니다.</div>}
    <div className="bid-history"><h3>최근 입찰</h3>{status.history?.slice(0, 6).map(bid => <div key={bid.bidId}><span>{bid.customerId}</span><strong>{formatMoney(bid.bidAmount)}</strong></div>)}</div>
  </div>;
}

function RafflePanel({ status, busy, enter, checkout }: { status?: RaffleStatus; busy: boolean; enter: () => void; checkout: () => void }) {
  if (!status) return <div className="action-panel loading-line"><Loader2 className="spin" />래플 상태 확인 중</div>;
  return <div className="action-panel">
    <div className="live-metrics">
      <div><span>총 응모</span><strong>{status.entryCount.toLocaleString('ko-KR')}명</strong><small><Users size={13} /> 중복 응모 방지</small></div>
      <div><span>추첨 상태</span><strong>{status.drawn ? '추첨 완료' : '응모 중'}</strong><small><Ticket size={13} /> 서버 검증 추첨</small></div>
    </div>
    {!status.drawn && !status.entered && <button className="primary-button" disabled={busy} onClick={enter}><Ticket />래플 응모</button>}
    {!status.drawn && status.entered && <div className="result-notice result-notice--success"><CheckCircle2 />응모가 완료되었습니다.</div>}
    {status.drawn && status.winner && status.checkoutStatus === 'PENDING' && <button className="primary-button" disabled={busy} onClick={checkout}><LockKeyhole />당첨 상품 결제</button>}
    {status.drawn && status.winner && status.checkoutStatus === 'COMPLETED' && <div className="result-notice result-notice--success"><CheckCircle2 />당첨 결제가 완료되었습니다.</div>}
    {status.drawn && !status.winner && <div className="result-notice">이번 추첨에는 선정되지 않았습니다.</div>}
    {status.drawSeedCommitment && <details className="audit-detail"><summary>추첨 검증 정보</summary><code>{status.drawSeedCommitment}</code><code>{status.entrySnapshotHash}</code></details>}
  </div>;
}

/**
 * 판매자 평판 표시.
 *
 * 구매자는 주문 후 판매자를 평가할 수 있었지만 그 평가를 읽을 화면이
 * 없었다. C2C에서 평판은 구매 판단의 근거이므로 상세 화면에서 바로
 * 보이게 한다. 검수를 통과한 리뷰만 서버가 내려준다.
 */
function SellerReviewPanel({ sellerName, reviews }: { sellerName: string; reviews?: SellerReview[] }) {
  if (!reviews) {
    return <div className="seller-reviews loading-line"><Loader2 className="spin" />판매자 평가 확인 중</div>;
  }

  if (reviews.length === 0) {
    return (
      <section className="seller-reviews" aria-labelledby="seller-reviews-title">
        <h2 id="seller-reviews-title">판매자 평가</h2>
        <p className="seller-reviews__empty">아직 등록된 구매자 평가가 없습니다.</p>
      </section>
    );
  }

  const average = reviews.reduce((sum, review) => sum + review.rating, 0) / reviews.length;

  return (
    <section className="seller-reviews" aria-labelledby="seller-reviews-title">
      <h2 id="seller-reviews-title">판매자 평가</h2>
      <p className="seller-reviews__summary">
        <Star size={14} aria-hidden="true" />
        <strong>{average.toFixed(1)}</strong>
        <span>{sellerName} · 구매자 평가 {reviews.length.toLocaleString('ko-KR')}건</span>
      </p>
      <ul className="seller-reviews__list">
        {reviews.slice(0, 5).map(review => (
          <li key={review.reviewId}>
            <span className="seller-reviews__rating" aria-label={`별점 ${review.rating}점`}>
              {'★'.repeat(review.rating)}{'☆'.repeat(Math.max(0, 5 - review.rating))}
            </span>
            {review.comment && <p>{review.comment}</p>}
          </li>
        ))}
      </ul>
    </section>
  );
}

function connectStream(url: string, names: string[], receive: (payload: any) => void,
                       setState: (state: 'connecting' | 'live' | 'retrying') => void) {
  const source = new EventSource(url);
  source.onopen = () => setState('live');
  source.onerror = () => setState('retrying');
  names.forEach(name => source.addEventListener(name, event => {
    setState('live');
    try { receive(JSON.parse((event as MessageEvent).data)); } catch { /* next status event repairs the view */ }
  }));
  return () => source.close();
}

function messageOf(error: unknown) { return error instanceof Error ? error.message : '요청 처리 중 오류가 발생했습니다.'; }
function streamLabel(state: string) { return state === 'live' ? '실시간 연결' : state === 'retrying' ? '재연결 중' : '연결 중'; }
function formatDuration(seconds?: number) {
  if (seconds == null) return '--:--:--';
  const h = Math.floor(seconds / 3600).toString().padStart(2, '0');
  const m = Math.floor((seconds % 3600) / 60).toString().padStart(2, '0');
  const s = Math.floor(seconds % 60).toString().padStart(2, '0');
  return `${h}:${m}:${s}`;
}
