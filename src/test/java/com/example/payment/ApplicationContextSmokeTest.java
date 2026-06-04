package com.example.payment;

import com.example.payment.infrastructure.util.ResourceReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest
class ApplicationContextSmokeTest {

    @MockBean
    private ResourceReservationService resourceReservationService;

    @Test
    void contextLoadsWithFlywaySchema() {
    }
}
