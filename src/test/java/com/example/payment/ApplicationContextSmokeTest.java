package com.example.payment;

import com.example.payment.infrastructure.util.ResourceReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ApplicationContextSmokeTest {

    @MockitoBean
    private ResourceReservationService resourceReservationService;

    @Test
    void contextLoadsWithFlywaySchema() {
    }
}
