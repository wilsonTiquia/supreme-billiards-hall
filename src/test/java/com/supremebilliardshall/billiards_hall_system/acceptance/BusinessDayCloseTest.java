package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.*;
import com.supremebilliardshall.billiards_hall_system.repository.*;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The owner's nightly control. Two things have to hold: a day closes exactly once, and a
// mistyped drawer count can be corrected by the owner but never by the person who counted it.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BusinessDayCloseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private CashCountRepository cashCountRepository;

    private UUID branchId;
    private UUID employeeId;
    private UUID adminId;
    private LocalDate businessDate;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("CLOSE" + UUID.randomUUID().toString().substring(0, 6));
        branch.setName("Close Test Branch");
        branch.setNextReceiptNo(1L);
        // Inactive: an active test branch defeats the single-branch default that a global
        // admin relies on, and these rows outlive the test.
        branch.setIsActive(false);
        branchId = branchRepository.saveAndFlush(branch).getId();

        employeeId = createUser("close-employee-", UserRole.EMPLOYEE);
        adminId = createUser("close-admin-", UserRole.ADMIN);

        businessDate = branchRepository.currentBusinessDate();

        // The drawer is counted, so the only thing left is the close itself.
        CashCount cashCount = new CashCount();
        cashCount.setBranchId(branchId);
        cashCount.setBusinessDate(businessDate);
        cashCount.setCashSales(new BigDecimal("460.00"));
        // No float on this branch, so the drawer is expected to hold takings alone and the
        // arithmetic here is the same as it was before the float existed.
        cashCount.setOpeningFloat(BigDecimal.ZERO);
        cashCount.setCountedCash(new BigDecimal("455.00"));
        cashCount.setCountedBy(employeeId);
        cashCountRepository.saveAndFlush(cashCount);
    }

    @Test
    void aDayClosesOnceAndTheSecondAttemptIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());

        // Previously this returned 200 and wrote a second audit row, so nothing recorded when
        // the night actually ended.
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("was already closed"));
    }

    @Test
    void anAdminCorrectsAMistypedCountAndTheOriginalSurvives() throws Exception {
        JsonNode corrected = body(mockMvc.perform(correction(admin(), "545.00"))
                .andExpect(status().isOk())).get("data");

        assertThat(new BigDecimal(corrected.get("countedCash").asText())).isEqualByComparingTo("545.00");
        // The generated column is read back on update, not left at the old figure.
        assertThat(new BigDecimal(corrected.get("variance").asText())).isEqualByComparingTo("85.00");
    }

    // The whole point of restricting it: a cashier who can re-count until the variance reads
    // zero has defeated the only check on the drawer.
    @Test
    void anEmployeeCannotCorrectTheCount() throws Exception {
        mockMvc.perform(correction(employee(), "545.00"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theCountCannotBeCorrectedOnceTheDayIsClosed() throws Exception {
        mockMvc.perform(post("/api/v1/business-day/" + businessDate + "/close").with(user(employee())))
                .andExpect(status().isOk());

        mockMvc.perform(correction(admin(), "545.00"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("no longer be corrected"));
    }

    @Test
    void correctingToTheSameFigureIsRejected() throws Exception {
        mockMvc.perform(correction(admin(), "455.00"))
                .andExpect(status().isConflict())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("already the counted figure"));
    }

    private org.springframework.test.web.servlet.RequestBuilder correction(AppUserDetails as, String counted) {
        return put("/api/v1/business-day/" + businessDate + "/cash-count")
                .with(user(as))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedCash\":" + counted + ",\"note\":\"Miskeyed the hundreds\"}");
    }

    private UUID createUser(String prefix, UserRole role) {
        AppUser appUser = new AppUser();
        appUser.setBranchId(branchId);
        appUser.setUsername(prefix + UUID.randomUUID());
        appUser.setPasswordHash("unused");
        appUser.setFullName("Close Tester");
        appUser.setRole(role);
        appUser.setIsActive(true);
        return appUserRepository.saveAndFlush(appUser).getId();
    }

    private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private AppUserDetails employee() {
        return new AppUserDetails(employeeId, branchId, "close-employee",
                "unused", "Close Tester", UserRole.EMPLOYEE, true);
    }

    private AppUserDetails admin() {
        return new AppUserDetails(adminId, branchId, "close-admin",
                "unused", "Close Tester", UserRole.ADMIN, true);
    }
}
