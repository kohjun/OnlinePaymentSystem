import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdminOperations } from './AdminOperations';
import { api } from './api';

vi.mock('./api', () => ({
  api: {
    adminOperationQueues: vi.fn(),
    adminOperationAudit: vi.fn(),
    systemHealth: vi.fn(),
    distributionReadiness: vi.fn(),
    inspectInventoryReconciliation: vi.fn(),
    executeAdminOperation: vi.fn(),
    repairInventoryReconciliation: vi.fn()
  }
}));

describe('AdminOperations', () => {
  it('executes a server-declared queue action through a confirmation dialog', async () => {
    vi.mocked(api.adminOperationQueues).mockResolvedValue({
      generatedAt: '2026-07-18T10:00:00', totalOpen: 1,
      queues: [{ queue: 'listingReviews', label: '판매글 검수', count: 1, items: [{
        id: 'LIST-1', type: 'MARKETPLACE_LISTING', status: 'PENDING_REVIEW', title: '검수 상품',
        ownerId: 'SELLER-1', metadata: { productId: 'PROD-1' },
        actions: [{ action: 'approve', label: '승인', tone: 'success', noteRequired: false }]
      }] }]
    });
    vi.mocked(api.adminOperationAudit).mockResolvedValue({ generatedAt: '2026-07-18T10:00:00', events: [] });
    vi.mocked(api.systemHealth).mockResolvedValue({ status: 'UP', timestamp: Date.now() });
    vi.mocked(api.distributionReadiness).mockResolvedValue({
      status: 'READY', releasable: true, mode: 'DEMO', brandName: 'EverySale', releaseChannel: 'local',
      checks: [], blockingIssues: [], warnings: [], timestamp: Date.now()
    });
    vi.mocked(api.inspectInventoryReconciliation).mockResolvedValue({
      status: 'CONSISTENT', mismatchCount: 0, mismatches: [], message: '일치'
    });
    vi.mocked(api.executeAdminOperation).mockResolvedValue({ status: 'SUCCESS' });

    render(<AdminOperations close={vi.fn()} notify={vi.fn()} />);

    fireEvent.click(await screen.findByRole('button', { name: '승인' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '승인 확정' }));

    await waitFor(() => expect(api.executeAdminOperation).toHaveBeenCalledWith('listingReviews', 'LIST-1', 'approve', ''));
  });
});
