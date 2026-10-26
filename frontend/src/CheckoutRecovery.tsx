import { useEffect, useState } from 'react';
import { CheckCircle2, CircleAlert, Loader2, RefreshCw, X } from 'lucide-react';
import { api } from './api';
import type { CheckoutStatus } from './types';

export function CheckoutRecovery({ customerId }: { customerId: string }) {
  const [result, setResult] = useState<CheckoutStatus>();
  const [error, setError] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const storageKey = `everysale.pendingCheckout:${customerId}`;

  useEffect(() => {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const params = new URLSearchParams(window.location.search);
    let intentId = sessionStorage.getItem(storageKey);
    let initial: Promise<unknown> = Promise.resolve();
    const redirect = params.get('tossResult');
    if (redirect) {
      const returnedIntent = params.get('intentId');
      const paymentKey = params.get('paymentKey');
      const orderId = params.get('orderId');
      const amountText = params.get('amount');
      const amount = Number(amountText);
      if (returnedIntent) {
        intentId = returnedIntent;
        sessionStorage.setItem(storageKey, returnedIntent);
        if (redirect === 'success' && paymentKey && orderId && amountText && Number.isFinite(amount) && amount >= 0) {
          initial = api.confirmToss({ intentId: returnedIntent, paymentKey, orderId, amount });
        } else if (redirect === 'fail') {
          initial = api.cancelToss(returnedIntent);
        }
      }
      // Only the opaque intent reference survives navigation, never the provider payment key.
      window.history.replaceState({}, '', window.location.pathname);
    }
    if (!intentId) return;
    setResult({ status: 'PENDING', terminal: false, phase: 'PAYMENT_RECONCILING' });
    async function poll() {
      if (stopped || !intentId) return;
      let delay = 5;
      try {
        const next = await api.tossIntentStatus(intentId);
        if (stopped) return;
        setError(false);
        setResult(next);
        if (next.terminal) {
          sessionStorage.removeItem(storageKey);
          return;
        }
        delay = Math.min(900, Math.max(3, next.retryAfterSeconds ?? 5));
      } catch {
        if (stopped) return;
        setError(true);
        delay = 15;
      }
      timer = setTimeout(() => void poll(), delay * 1000);
    }
    void initial.catch(() => { if (!stopped) setError(true); }).finally(() => void poll());
    return () => { stopped = true; clearTimeout(timer); };
  }, [storageKey, refresh]);

  if (!result) return null;
  const title = error ? '거래 상태에 연결할 수 없습니다'
    : result.status === 'SUCCESS' ? '결제가 완료되었습니다'
      : result.terminal ? '거래가 종료되었습니다'
        : result.phase === 'MANUAL_REVIEW' ? '운영팀 확인이 필요한 거래입니다'
          : result.phase === 'REFUND_RECONCILING' ? '환불 결과를 확인하고 있습니다'
            : result.phase === 'AWAITING_AUTHENTICATION' ? '결제 인증이 아직 확인되지 않았습니다'
              : '결제 결과를 확인하고 있습니다';
  return <section className="checkout-recovery" aria-live="polite" aria-label="거래 상태">
    {result.status === 'SUCCESS' ? <CheckCircle2 /> : result.terminal || error ? <CircleAlert /> : <Loader2 className="spin" />}
    <div><strong>{title}</strong>{!result.terminal && <p>거래가 확인될 때까지 재결제하지 마세요.</p>}</div>
    {!result.terminal && <button className="icon-button" title="거래 상태 새로고침" onClick={() => setRefresh(value => value + 1)}><RefreshCw /></button>}
    {result.terminal && <button className="icon-button" title="거래 상태 닫기" onClick={() => setResult(undefined)}><X /></button>}
  </section>;
}
