package com.example.payment.application.service;

import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Service
@Slf4j
public class MarketplaceRealtimeService {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> auctionStreams = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> raffleStreams = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();
    private final MarketplaceRealtimePublisher realtimePublisher;
    private final MeterRegistry meterRegistry;
    private final String instanceId;

    @Autowired
    public MarketplaceRealtimeService(ObjectProvider<MarketplaceRealtimePublisher> publisherProvider,
                                      ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this(publisherProvider.getIfAvailable(), UUID.randomUUID().toString(), meterRegistryProvider.getIfAvailable());
    }

    MarketplaceRealtimeService(MarketplaceRealtimePublisher realtimePublisher) {
        this(realtimePublisher, UUID.randomUUID().toString(), null);
    }

    MarketplaceRealtimeService(MarketplaceRealtimePublisher realtimePublisher, String instanceId) {
        this(realtimePublisher, instanceId, null);
    }

    private MarketplaceRealtimeService(MarketplaceRealtimePublisher realtimePublisher,
                                       String instanceId,
                                       MeterRegistry meterRegistry) {
        this.realtimePublisher = realtimePublisher;
        this.instanceId = instanceId;
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge("everysale.marketplace.sse.auction.subscribers", auctionStreams,
                    streams -> streams.values().stream().mapToInt(List::size).sum());
            meterRegistry.gauge("everysale.marketplace.sse.raffle.subscribers", raffleStreams,
                    streams -> streams.values().stream().mapToInt(List::size).sum());
        }
    }

    @Value("${app.marketplace.realtime.stream-timeout-ms:1800000}")
    private long streamTimeoutMs = 30L * 60L * 1000L;

    @Value("${app.marketplace.realtime.reconnect-ms:1000}")
    private long reconnectMs = 1_000L;

    @Value("${app.marketplace.realtime.max-subscribers-per-event:500}")
    private int maxSubscribersPerEvent = 500;

    public SseEmitter streamAuction(String eventId, Supplier<?> initialStatus) {
        return register(auctionStreams, eventId, "auction-status", initialStatus);
    }

    public SseEmitter streamRaffle(String eventId, Supplier<?> initialStatus) {
        return register(raffleStreams, eventId, "raffle-status", initialStatus);
    }

    public void publishAuction(String eventId, String eventName, Object payload) {
        publish(auctionStreams, eventId, eventName, payload);
        increment("everysale.marketplace.realtime.published", "stream", "AUCTION", "event", eventName);
        broadcast("AUCTION", eventId, eventName, payload);
    }

    public void publishRaffle(String eventId, String eventName, Object payload) {
        publish(raffleStreams, eventId, eventName, payload);
        increment("everysale.marketplace.realtime.published", "stream", "RAFFLE", "event", eventName);
        broadcast("RAFFLE", eventId, eventName, payload);
    }

    public void publishRemote(MarketplaceRealtimeEnvelope envelope) {
        if (envelope == null || instanceId.equals(envelope.originInstanceId())) {
            return;
        }
        if ("AUCTION".equals(envelope.streamType())) {
            publish(auctionStreams, envelope.eventId(), envelope.eventName(), envelope.payload());
        } else if ("RAFFLE".equals(envelope.streamType())) {
            publish(raffleStreams, envelope.eventId(), envelope.eventName(), envelope.payload());
        } else {
            log.warn("Ignoring unsupported marketplace realtime stream type: {}", envelope.streamType());
        }
    }

    @Scheduled(fixedDelayString = "${app.marketplace.realtime.heartbeat-ms:15000}")
    public void sendHeartbeat() {
        heartbeat(auctionStreams);
        heartbeat(raffleStreams);
    }

    int activeAuctionSubscribers(String eventId) {
        return subscriberCount(auctionStreams, eventId);
    }

    int activeRaffleSubscribers(String eventId) {
        return subscriberCount(raffleStreams, eventId);
    }

    private SseEmitter register(Map<String, CopyOnWriteArrayList<SseEmitter>> streams,
                                String eventId,
                                String eventName,
                                Supplier<?> initialStatus) {
        SseEmitter emitter = createEmitter(streamTimeoutMs);
        CopyOnWriteArrayList<SseEmitter> eventStreams =
                streams.computeIfAbsent(eventId, ignored -> new CopyOnWriteArrayList<>());
        synchronized (eventStreams) {
            if (eventStreams.size() >= maxSubscribersPerEvent) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Realtime subscriber limit exceeded for event " + eventId);
            }
            eventStreams.add(emitter);
        }
        increment("everysale.marketplace.sse.connected", "stream", eventName);
        Runnable cleanup = () -> remove(streams, eventId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .id(nextEventId(eventId))
                    .name(eventName)
                    .reconnectTime(reconnectMs)
                    .data(initialStatus.get()));
        } catch (Exception e) {
            remove(streams, eventId, emitter);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void publish(Map<String, CopyOnWriteArrayList<SseEmitter>> streams,
                         String eventId,
                         String eventName,
                         Object payload) {
        List<SseEmitter> emitters = streams.get(eventId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .id(nextEventId(eventId))
                        .name(eventName)
                        .reconnectTime(reconnectMs)
                        .data(payload));
            } catch (IOException | IllegalStateException e) {
                log.debug("Removing closed marketplace SSE stream: eventId={}, eventName={}", eventId, eventName);
                remove(streams, eventId, emitter);
            }
        }
    }

    private void broadcast(String streamType, String eventId, String eventName, Object payload) {
        if (realtimePublisher == null) {
            return;
        }
        try {
            realtimePublisher.publish(new MarketplaceRealtimeEnvelope(
                    instanceId,
                    streamType,
                    eventId,
                    eventName,
                    payload,
                    Instant.now()
            ));
        } catch (RuntimeException e) {
            // Local subscribers have already received the event; reconciliation/status reads remain authoritative.
            log.warn("Marketplace realtime broadcast failed after local delivery: streamType={}, eventId={}, eventName={}",
                    streamType, eventId, eventName);
            increment("everysale.marketplace.realtime.broadcast.failed", "stream", streamType, "event", eventName);
        }
    }

    SseEmitter createEmitter(long timeoutMs) {
        return new SseEmitter(timeoutMs);
    }

    private void heartbeat(Map<String, CopyOnWriteArrayList<SseEmitter>> streams) {
        streams.forEach((eventId, emitters) -> {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(nextEventId(eventId))
                            .comment("keepalive"));
                } catch (IOException | IllegalStateException e) {
                    remove(streams, eventId, emitter);
                }
            }
        });
    }

    private int subscriberCount(Map<String, CopyOnWriteArrayList<SseEmitter>> streams, String eventId) {
        List<SseEmitter> emitters = streams.get(eventId);
        return emitters != null ? emitters.size() : 0;
    }

    private String nextEventId(String eventId) {
        return eventId + "-" + System.currentTimeMillis() + "-" + eventSequence.incrementAndGet();
    }

    private void remove(Map<String, CopyOnWriteArrayList<SseEmitter>> streams,
                        String eventId,
                        SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = streams.get(eventId);
        if (emitters == null) {
            return;
        }
        if (emitters.remove(emitter)) {
            increment("everysale.marketplace.sse.disconnected");
        }
        if (emitters.isEmpty()) {
            streams.remove(eventId, emitters);
        }
    }

    private void increment(String metricName, String... tags) {
        if (meterRegistry != null) {
            meterRegistry.counter(metricName, tags).increment();
        }
    }
}
