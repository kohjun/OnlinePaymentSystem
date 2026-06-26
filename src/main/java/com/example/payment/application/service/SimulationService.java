package com.example.payment.application.service;

import com.example.payment.domain.model.EventConfig;
import com.example.payment.domain.repository.ProductRepository;
import com.example.payment.domain.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimulationService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final Map<String, EventConfig> inMemoryEvents = new ConcurrentHashMap<>();
    
    // Simulation runner states
    private final AtomicBoolean isSimulationRunning = new AtomicBoolean(false);
    private ExecutorService testExecutor = null;
    private long simulationStartTime = 0;
    private long simulationEndTime = 0;
    private int simulationDurationSeconds = 0;
    private int simulationVus = 0;

    // Simulation metrics
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger successRequests = new AtomicInteger(0);
    private final AtomicInteger conflictRequests = new AtomicInteger(0);
    private final AtomicInteger errorRequests = new AtomicInteger(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    // Initializer to load default configurations
    public void initDefaultEvents() {
        if (inMemoryEvents.isEmpty()) {
            // 1. Ticketing Event (Standard Concert)
            inMemoryEvents.put("CONCERT-IU", EventConfig.builder()
                    .eventId("CONCERT-IU")
                    .title("아이유 월드투어 콘서트 - 서울")
                    .type("TICKETING")
                    .totalInventory(108)
                    .price(120000.0)
                    .extraData(Map.of("seatsCount", 108))
                    .build());

            // 2. Draw Event (Limited Sneakers)
            inMemoryEvents.put("DRAW-NIKE", EventConfig.builder()
                    .eventId("DRAW-NIKE")
                    .title("나이키 에어 조던 1 레트로 한정판 드로우")
                    .type("DRAW")
                    .totalInventory(10)
                    .price(239000.0)
                    .extraData(Map.of("itemName", "Air Jordan 1 Retro High"))
                    .build());

            // 3. Auction Event (Luxury Rolex)
            inMemoryEvents.put("AUCTION-ROLEX", EventConfig.builder()
                    .eventId("AUCTION-ROLEX")
                    .title("빈티지 롤렉스 서브마리너 경매")
                    .type("AUCTION")
                    .totalInventory(1)
                    .price(8500000.0) // Start price
                    .extraData(Map.of("startPrice", 8500000.0, "minBidIncrement", 100000.0))
                    .build());

            // Set active event to Concert by default if none is set in Redis
            if (Boolean.FALSE.equals(redisTemplate.hasKey("active_event_id"))) {
                redisTemplate.opsForValue().set("active_event_id", "CONCERT-IU");
            }
        }

        // Dynamically load products registered in PostgreSQL database
        try {
            List<com.example.payment.domain.model.inventory.Product> dbProducts = productRepository.findAll();
            for (com.example.payment.domain.model.inventory.Product prod : dbProducts) {
                if (!inMemoryEvents.containsKey(prod.getId())) {
                    int totalQty = 100;
                    Optional<com.example.payment.domain.model.inventory.Inventory> invOpt = inventoryRepository.findById(prod.getId());
                    if (invOpt.isPresent()) {
                        totalQty = invOpt.get().getTotalQuantity();
                    }
                    inMemoryEvents.put(prod.getId(), EventConfig.builder()
                            .eventId(prod.getId())
                            .title(prod.getName())
                            .type(prod.getCategory()) // "TICKETING", "DRAW", "AUCTION"
                            .totalInventory(totalQty)
                            .price(prod.getPrice().doubleValue())
                            .extraData(Map.of())
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to load dynamically registered products from PostgreSQL", e);
        }
    }

    public List<EventConfig> getAllEvents() {
        initDefaultEvents();
        return new ArrayList<>(inMemoryEvents.values());
    }

    public EventConfig getEvent(String eventId) {
        initDefaultEvents();
        return inMemoryEvents.get(eventId);
    }

    public void createEvent(EventConfig config) {
        initDefaultEvents();
        inMemoryEvents.put(config.getEventId(), config);
    }

    public String getActiveEventId() {
        initDefaultEvents();
        Object activeId = redisTemplate.opsForValue().get("active_event_id");
        if (activeId != null && inMemoryEvents.containsKey(activeId.toString())) {
            return activeId.toString();
        }
        return "CONCERT-IU";
    }

    public void setActiveEventId(String eventId) {
        initDefaultEvents();
        if (inMemoryEvents.containsKey(eventId)) {
            redisTemplate.opsForValue().set("active_event_id", eventId);
            log.info("Active B2B Event changed to: {}", eventId);
            resetEventInventory(eventId);
        }
    }

    public void resetEventInventory(String eventId) {
        EventConfig config = inMemoryEvents.get(eventId);
        if (config == null) return;

        log.info("Resetting B2B Event inventory: {}", eventId);
        if ("TICKETING".equals(config.getType())) {
            // Delete all seat locks in Redis
            Set<String> keys = redisTemplate.keys("locked_seat:*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            // Reset DB or other states via API or directly if needed
        } else if ("DRAW".equals(config.getType())) {
            // Clear locked items
            Set<String> keys = redisTemplate.keys("locked_seat:DRAW-*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } else if ("AUCTION".equals(config.getType())) {
            // Reset highest bid and history
            redisTemplate.delete("auction_highest_bid:" + eventId);
            redisTemplate.delete("auction_highest_bidder:" + eventId);
            redisTemplate.delete("auction_history:" + eventId);
        }
    }

    // High Concurrency Safe Auction Bidding Logic
    public Map<String, Object> processAuctionBid(String eventId, String customerId, double bidAmount) {
        EventConfig config = getEvent(eventId);
        if (config == null || !"AUCTION".equals(config.getType())) {
            return Map.of("status", "FAILED", "message", "유효한 경매 상품이 아닙니다.");
        }

        String lockKey = "auction_lock:" + eventId;
        // Simple 1-second Redis lock to serialize bidding
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "LOCK", 1, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(acquired)) {
            try {
                String bidKey = "auction_highest_bid:" + eventId;
                String bidderKey = "auction_highest_bidder:" + eventId;
                
                Object currentBidObj = redisTemplate.opsForValue().get(bidKey);
                double currentBid = (currentBidObj != null) ? Double.parseDouble(currentBidObj.toString()) : config.getPrice();
                
                double minIncrement = 100000.0;
                if (config.getExtraData() != null && config.getExtraData().containsKey("minBidIncrement")) {
                    minIncrement = ((Number) config.getExtraData().get("minBidIncrement")).doubleValue();
                }

                if (bidAmount >= currentBid + minIncrement) {
                    redisTemplate.opsForValue().set(bidKey, String.valueOf(bidAmount));
                    redisTemplate.opsForValue().set(bidderKey, customerId);
                    
                    // Add entry to history list
                    String historyKey = "auction_history:" + eventId;
                    String historyEntry = customerId + " - " + String.format("%,.0f", bidAmount) + "원 (" + LocalTime.now().toString().substring(0, 8) + ")";
                    redisTemplate.opsForList().leftPush(historyKey, historyEntry);
                    redisTemplate.opsForList().trim(historyKey, 0, 9); // Keep top 10 bids
                    
                    log.info("New highest bid accepted: eventId={}, customerId={}, bidAmount={}", eventId, customerId, bidAmount);
                    return Map.of(
                            "status", "SUCCESS",
                            "message", "입찰 성공",
                            "highestBid", bidAmount,
                            "highestBidder", customerId
                    );
                } else {
                    return Map.of(
                            "status", "REJECTED",
                            "message", "현재 최고가에 입찰 단위(" + String.format("%,.0f", minIncrement) + "원) 이상을 더한 금액만 입찰 가능합니다.",
                            "highestBid", currentBid
                    );
                }
            } finally {
                redisTemplate.delete(lockKey);
            }
        } else {
            return Map.of(
                    "status", "CONFLICT",
                    "message", "현재 다른 사용자의 입찰 요청이 밀려 처리 중입니다. 잠시 후 다시 시도해 주세요."
            );
        }
    }

    public List<String> getAuctionHistory(String eventId) {
        String historyKey = "auction_history:" + eventId;
        List<Object> range = redisTemplate.opsForList().range(historyKey, 0, 9);
        List<String> history = new ArrayList<>();
        if (range != null) {
            for (Object obj : range) {
                history.add(obj.toString());
            }
        }
        return history;
    }

    public Map<String, Object> getAuctionStatus(String eventId) {
        String bidKey = "auction_highest_bid:" + eventId;
        String bidderKey = "auction_highest_bidder:" + eventId;
        
        Object bid = redisTemplate.opsForValue().get(bidKey);
        Object bidder = redisTemplate.opsForValue().get(bidderKey);
        
        EventConfig config = getEvent(eventId);
        double startPrice = (config != null) ? config.getPrice() : 0.0;
        
        double currentHighest = (bid != null) ? Double.parseDouble(bid.toString()) : startPrice;
        String currentBidder = (bidder != null) ? bidder.toString() : "없음 (입찰 대기 중)";
        
        return Map.of(
                "highestBid", currentHighest,
                "highestBidder", currentBidder,
                "history", getAuctionHistory(eventId)
        );
    }

    // Background Java-based Load Generator
    public synchronized Map<String, Object> startSimulation(int vus, int durationSeconds) {
        if (isSimulationRunning.get()) {
            return Map.of("status", "FAILED", "message", "이미 다른 시뮬레이션 부하 테스트가 작동 중입니다.");
        }

        log.info("Starting SaaS simulation: VUs={}, Duration={}s", vus, durationSeconds);
        isSimulationRunning.set(true);
        simulationVus = vus;
        simulationDurationSeconds = durationSeconds;
        simulationStartTime = System.currentTimeMillis();
        simulationEndTime = simulationStartTime + (durationSeconds * 1000L);

        // Reset metrics
        totalRequests.set(0);
        successRequests.set(0);
        conflictRequests.set(0);
        errorRequests.set(0);
        totalLatencyMs.set(0);

        testExecutor = Executors.newFixedThreadPool(vus);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        String activeEventId = getActiveEventId();
        EventConfig activeEvent = getEvent(activeEventId);
        String eventType = (activeEvent != null) ? activeEvent.getType() : "TICKETING";
        double basePrice = (activeEvent != null) ? activeEvent.getPrice() : 120000.0;

        // Spawn virtual user threads
        for (int i = 0; i < vus; i++) {
            testExecutor.submit(() -> {
                Random rand = new Random();
                while (isSimulationRunning.get() && System.currentTimeMillis() < simulationEndTime) {
                    long requestStartTime = System.nanoTime();
                    String customerId = "SLA-CUST-" + rand.nextInt(10000);
                    
                    HttpRequest request = null;
                    try {
                        if ("TICKETING".equals(eventType)) {
                            String seatId;
                            if ("CONCERT-IU".equals(activeEventId)) {
                                // 아이유 콘서트: 3개 등급(VIP, R, S) 총 108석
                                String[] prefixes = {"V", "R", "S"};
                                int[] maxSeats = {24, 36, 48};
                                int pick = rand.nextInt(3);
                                seatId = prefixes[pick] + "-" + (rand.nextInt(maxSeats[pick]) + 1);
                            } else {
                                // 일반/커스텀 티켓팅 상품: 단일 등급(S) 총 재고 수량만큼의 좌석
                                int totalQty = (activeEvent != null) ? activeEvent.getTotalInventory() : 50;
                                seatId = "S-" + (rand.nextInt(totalQty) + 1);
                            }
                            
                            request = HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:8080/api/system/seats/lock?seatId=" + seatId + "&customerId=" + customerId))
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .timeout(Duration.ofSeconds(3))
                                    .build();
                        } else if ("DRAW".equals(eventType)) {
                            // Hit the single raffle item lock
                            String seatId = "DRAW-NIKE-1";
                            request = HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:8080/api/system/seats/lock?seatId=" + seatId + "&customerId=" + customerId))
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .timeout(Duration.ofSeconds(3))
                                    .build();
                        } else if ("AUCTION".equals(eventType)) {
                            // Bid dynamic amounts
                            double highestBid = getAuctionHighestBid(activeEventId, basePrice);
                            double bidAmount = highestBid + 100000.0 + (rand.nextInt(5) * 100000.0);
                            
                            request = HttpRequest.newBuilder()
                                    .uri(URI.create("http://localhost:8080/api/simulation/bid?eventId=" + activeEventId + "&customerId=" + customerId + "&bidAmount=" + bidAmount))
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .timeout(Duration.ofSeconds(3))
                                    .build();
                        }

                        if (request != null) {
                            totalRequests.incrementAndGet();
                            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                            long requestEndTime = System.nanoTime();
                            long latency = TimeUnit.NANOSECONDS.toMillis(requestEndTime - requestStartTime);
                            totalLatencyMs.addAndGet(latency);

                            int status = response.statusCode();
                            if (status == 200) {
                                successRequests.incrementAndGet();
                            } else if (status == 409) {
                                conflictRequests.incrementAndGet();
                            } else {
                                errorRequests.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errorRequests.incrementAndGet();
                    }

                    // Simulated sleep delay of 20-50ms between iterations per VU
                    try {
                        Thread.sleep(20 + rand.nextInt(30));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // Async monitor to stop simulation on time
        CompletableFuture.runAsync(() -> {
            try {
                long sleepTime = simulationEndTime - System.currentTimeMillis();
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                stopSimulationInternal();
            }
        });

        return Map.of("status", "SUCCESS", "message", "가상 동시성 시뮬레이션 부하 테스트가 성공적으로 가동되었습니다.");
    }

    private double getAuctionHighestBid(String eventId, double fallbackPrice) {
        Object bid = redisTemplate.opsForValue().get("auction_highest_bid:" + eventId);
        return (bid != null) ? Double.parseDouble(bid.toString()) : fallbackPrice;
    }

    public synchronized void stopSimulation() {
        stopSimulationInternal();
    }

    private void stopSimulationInternal() {
        if (isSimulationRunning.compareAndSet(true, false)) {
            log.info("Stopping SaaS simulation.");
            if (testExecutor != null) {
                testExecutor.shutdownNow();
                try {
                    testExecutor.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                testExecutor = null;
            }
        }
    }

    public Map<String, Object> getSimulationStatus() {
        boolean running = isSimulationRunning.get();
        long elapsedMs = running ? System.currentTimeMillis() - simulationStartTime : 0;
        double elapsedSec = elapsedMs / 1000.0;
        
        int total = totalRequests.get();
        int success = successRequests.get();
        int conflict = conflictRequests.get();
        int error = errorRequests.get();
        long latency = totalLatencyMs.get();
        
        double tps = (elapsedSec > 0.5) ? total / elapsedSec : 0.0;
        double avgLatency = (total > 0) ? (double) latency / total : 0.0;

        return Map.of(
                "isRunning", running,
                "tps", tps,
                "totalCount", total,
                "successCount", success,
                "conflictCount", conflict,
                "errorCount", error,
                "avgLatencyMs", avgLatency,
                "elapsedSeconds", Math.round(elapsedSec),
                "durationSeconds", simulationDurationSeconds,
                "vus", running ? simulationVus : 0
        );
    }
}
