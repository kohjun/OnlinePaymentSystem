package com.example.payment.application.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MarketplaceRealtimeServiceTest {

    @Test
    void auctionStreamReceivesInitialUpdatePublishedEventAndHeartbeat() throws Exception {
        MarketplaceRealtimeService service = spy(new MarketplaceRealtimeService((MarketplaceRealtimePublisher) null));
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicReference<Runnable> completion = new AtomicReference<>();
        doReturn(emitter).when(service).createEmitter(anyLong());
        doAnswer(invocation -> {
            completion.set(invocation.getArgument(0));
            return null;
        }).when(emitter).onCompletion(any(Runnable.class));

        service.streamAuction("EVT-AUCTION", () -> "initial");
        service.publishAuction("EVT-AUCTION", "bid-placed", "updated");
        service.sendHeartbeat();

        assertEquals(1, service.activeAuctionSubscribers("EVT-AUCTION"));
        verify(emitter, times(3)).send(any(SseEmitter.SseEventBuilder.class));

        completion.get().run();
        assertEquals(0, service.activeAuctionSubscribers("EVT-AUCTION"));
    }

    @Test
    void raffleAndAuctionSubscribersAreIsolatedByEventType() {
        MarketplaceRealtimeService service = spy(new MarketplaceRealtimeService((MarketplaceRealtimePublisher) null));
        SseEmitter auctionEmitter = mock(SseEmitter.class);
        SseEmitter raffleEmitter = mock(SseEmitter.class);
        doReturn(auctionEmitter, raffleEmitter).when(service).createEmitter(anyLong());

        service.streamAuction("EVT-1", () -> "auction");
        service.streamRaffle("EVT-1", () -> "raffle");

        assertEquals(1, service.activeAuctionSubscribers("EVT-1"));
        assertEquals(1, service.activeRaffleSubscribers("EVT-1"));
    }

    @Test
    void eventPublishedByOneInstanceReachesSubscriberOnAnotherInstance() throws Exception {
        InMemoryRealtimeBus bus = new InMemoryRealtimeBus();
        MarketplaceRealtimeService first = new MarketplaceRealtimeService(bus, "INSTANCE-A");
        MarketplaceRealtimeService second = spy(new MarketplaceRealtimeService(bus, "INSTANCE-B"));
        SseEmitter secondEmitter = mock(SseEmitter.class);
        doReturn(secondEmitter).when(second).createEmitter(anyLong());
        bus.subscribe(first);
        bus.subscribe(second);

        second.streamAuction("EVT-DISTRIBUTED", () -> "initial");
        first.publishAuction("EVT-DISTRIBUTED", "bid-placed", "remote-update");

        verify(secondEmitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    private static class InMemoryRealtimeBus implements MarketplaceRealtimePublisher {
        private final List<MarketplaceRealtimeService> subscribers = new ArrayList<>();

        void subscribe(MarketplaceRealtimeService service) {
            subscribers.add(service);
        }

        @Override
        public void publish(MarketplaceRealtimeEnvelope envelope) {
            subscribers.forEach(service -> service.publishRemote(envelope));
        }
    }
}
