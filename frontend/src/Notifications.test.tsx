import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { Notifications } from './App';
import type { NotificationInbox } from './types';

const inbox: NotificationInbox = {
  unreadCount: 1,
  notifications: [{
    notificationId: 'NTF-1',
    notificationType: 'PAYMENT_COMPLETED',
    title: '결제가 완료되었습니다',
    message: '주문 MORD-1이 정상적으로 접수되었습니다.',
    aggregateType: 'MARKETPLACE_ORDER',
    aggregateId: 'MORD-1',
    read: false,
    createdAt: '2026-09-07T00:00:00'
  }]
};

describe('Notifications', () => {
  it('opens a transaction notification and supports marking the inbox read', async () => {
    const openNotification = vi.fn();
    const markAll = vi.fn().mockResolvedValue(undefined);

    render(<Notifications inbox={inbox} close={vi.fn()} openNotification={openNotification} markAll={markAll} />);

    fireEvent.click(screen.getByText('결제가 완료되었습니다'));
    expect(openNotification).toHaveBeenCalledWith(inbox.notifications[0]);

    fireEvent.click(screen.getByRole('button', { name: '전체 읽음' }));
    expect(markAll).toHaveBeenCalledOnce();
  });
});
