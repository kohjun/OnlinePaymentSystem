package com.example.payment.presentation.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketplaceFrontendControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MarketplaceFrontendController()).build();
    }

    @Test
    void appDirectoryForwardsToReactIndex() throws Exception {
        mockMvc.perform(get("/app/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
    }

    @Test
    void appWithoutTrailingSlashForwardsToReactIndex() throws Exception {
        mockMvc.perform(get("/app"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
    }

    @Test
    void legacyEntryPointsRedirectToReactApp() throws Exception {
        for (String path : new String[]{"/", "/index.html", "/shared.html", "/seller.html"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/app/"));
        }
    }
}
