const labels: Record<string, string> = {
  REQUESTED: '취소 접수',
  REFUND_PROCESSING: '환불 처리 중',
  REFUND_UNKNOWN: '환불 결과 확인 중',
  REFUND_FAILED: '환불 확인 필요',
  COMPLETED: '취소·환불 완료'
};

export function cancellationLabel(status: string) {
  return labels[status] ?? '취소 상태 확인 중';
}
