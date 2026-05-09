package games.beiming.website.auth.controller;

import games.beiming.website.auth.service.impl.AuthTestConnectionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthTestConnectionControllerTest {

    @Test
    void shouldReturnAuthConnectionStatus() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthTestConnectionController(new AuthTestConnectionServiceImpl()))
                .build();

        mockMvc.perform(get("/api/auth/test-connection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.service").value("auth-service"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.message").value("gateway can reach auth-service"));
    }
}
