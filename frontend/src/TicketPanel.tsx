import { useEffect, useMemo, useState } from 'react';
import { CheckCircle2, Clock3, Loader2, LockKeyhole, Ticket, Users } from 'lucide-react';
import { api } from './api';
import type { MarketplaceEvent, QueueStatus, TicketSeatMap } from './types';

interface Props {
  event: MarketplaceEvent;
  checkoutBusy: boolean;
  checkout: (seatId: string) => Promise<void>;
  notify: (message: string, tone?: 'success' | 'error' | 'info') => void;
}

export function TicketPanel({ event, checkoutBusy, checkout, notify }: Props) {
  const [queue, setQueue] = useState<QueueStatus>();
  const [seatMap, setSeatMap] = useState<TicketSeatMap>();
  const [selectedSeatId, setSelectedSeatId] = useState<string>();
  const [seatBusy, setSeatBusy] = useState(false);
  const [now, setNow] = useState(Date.now());

  useEffect(() => {
    const clock = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(clock);
  }, []);

  useEffect(() => {
    let disposed = false;
    let timer: number | undefined;
    let firstRequest = true;
    let errorReported = false;

    async function refresh() {
      try {
        const queueStatus = firstRequest ? await api.joinQueue() : await api.queueStatus();
        firstRequest = false;
        if (disposed) return;
        setQueue(queueStatus);
        if (queueStatus.status === 'ACTIVE') {
          const latest = await api.ticketSeats(event.saleEventId);
          if (disposed) return;
          setSeatMap(latest);
          const owned = latest.seats.find(seat => seat.status === 'HELD' && seat.ownedByCurrentUser);
          setSelectedSeatId(owned?.seatId);
        }
        errorReported = false;
      } catch (error) {
        if (!disposed && !errorReported) {
          notify(error instanceof Error ? error.message : '좌석 현황을 불러오지 못했습니다.', 'error');
          errorReported = true;
        }
      } finally {
        if (!disposed) timer = window.setTimeout(refresh, queue?.status === 'WAITING' ? 1000 : 2000);
      }
    }

    void refresh();
    return () => {
      disposed = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [event.saleEventId, notify]);

  const selectedSeat = seatMap?.seats.find(seat => seat.seatId === selectedSeatId);
  const holdRemaining = useMemo(() => {
    if (!selectedSeat?.holdExpiresAt) return undefined;
    return Math.max(0, Math.ceil((new Date(selectedSeat.holdExpiresAt).getTime() - now) / 1000));
  }, [now, selectedSeat?.holdExpiresAt]);

  async function selectSeat(seatId: string) {
    setSeatBusy(true);
    try {
      if (selectedSeatId && selectedSeatId !== seatId) {
        await api.releaseTicketSeat(event.saleEventId, selectedSeatId);
      }
      await api.holdTicketSeat(event.saleEventId, seatId);
      const latest = await api.ticketSeats(event.saleEventId);
      setSeatMap(latest);
      setSelectedSeatId(seatId);
      notify('좌석을 10분 동안 선점했습니다.', 'success');
    } catch (error) {
      notify(error instanceof Error ? error.message : '좌석을 선점하지 못했습니다.', 'error');
      setSeatMap(await api.ticketSeats(event.saleEventId).catch(() => seatMap));
    } finally {
      setSeatBusy(false);
    }
  }

  if (!queue) {
    return <div className="action-panel loading-line"><Loader2 className="spin" />예매 대기열을 확인하고 있습니다</div>;
  }
  if (queue.status === 'WAITING') {
    return <div className="action-panel ticket-queue" role="status">
      <Users />
      <div><strong>접속 대기 중입니다</strong><span>현재 대기 순서 {queue.rank.toLocaleString('ko-KR')}번</span></div>
      <small>순서가 되면 좌석 선택 화면이 자동으로 열립니다.</small>
    </div>;
  }
  if (!seatMap) {
    return <div className="action-panel loading-line"><Loader2 className="spin" />좌석 현황을 불러오고 있습니다</div>;
  }

  return <div className="action-panel ticket-panel">
    <header className="ticket-panel__header">
      <div><Ticket /><div><strong>좌석 선택</strong><span>한 번에 한 좌석만 선점할 수 있습니다.</span></div></div>
      <div className="ticket-counts"><span>잔여 <strong>{seatMap.availableCount}</strong></span><span>선점 {seatMap.heldCount}</span><span>예매 {seatMap.soldCount}</span></div>
    </header>
    <div className="ticket-stage" aria-hidden="true">STAGE</div>
    <div className="seat-grid" role="grid" aria-label="좌석 선택">
      {seatMap.seats.map(seat => {
        const selected = seat.seatId === selectedSeatId && seat.ownedByCurrentUser;
        const disabled = seat.status !== 'AVAILABLE' && !selected;
        return <button key={seat.seatId} type="button" role="gridcell"
          className={`seat seat--${seat.status.toLowerCase()}${selected ? ' seat--selected' : ''}`}
          disabled={disabled || seatBusy || checkoutBusy}
          aria-label={`${seat.label}, ${seatStatusLabel(seat.status, selected)}`}
          title={`${seat.label} · ${seatStatusLabel(seat.status, selected)}`}
          onClick={() => void selectSeat(seat.seatId)}>{seat.rowLabel}{seat.seatNumber}</button>;
      })}
    </div>
    <div className="seat-legend" aria-label="좌석 상태 안내">
      <span><i className="seat-dot seat-dot--available" />선택 가능</span>
      <span><i className="seat-dot seat-dot--selected" />내 선점</span>
      <span><i className="seat-dot seat-dot--held" />다른 고객 선점</span>
      <span><i className="seat-dot seat-dot--sold" />예매 완료</span>
    </div>
    {selectedSeat && <div className="ticket-selection">
      <div><CheckCircle2 /><div><span>선택 좌석</span><strong>{selectedSeat.label}</strong></div></div>
      <span className="hold-timer"><Clock3 />{formatHoldTime(holdRemaining)}</span>
    </div>}
    <button className="primary-button" disabled={!selectedSeat || seatBusy || checkoutBusy || holdRemaining === 0}
      onClick={() => selectedSeat && void checkout(selectedSeat.seatId)}>
      {checkoutBusy ? <Loader2 className="spin" /> : <LockKeyhole />} 선택 좌석 Toss 결제
    </button>
  </div>;
}

function seatStatusLabel(status: string, selected: boolean) {
  if (selected) return '내가 선점한 좌석';
  if (status === 'AVAILABLE') return '선택 가능';
  if (status === 'HELD') return '다른 고객이 선점 중';
  return '예매 완료';
}

function formatHoldTime(seconds?: number) {
  if (seconds == null) return '10:00';
  const minutes = Math.floor(seconds / 60).toString().padStart(2, '0');
  const remainder = (seconds % 60).toString().padStart(2, '0');
  return `${minutes}:${remainder}`;
}
