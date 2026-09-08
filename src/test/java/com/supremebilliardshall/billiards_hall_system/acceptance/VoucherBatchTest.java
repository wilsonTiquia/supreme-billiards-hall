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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * Making the giveaway: fifty two-hour codes expiring at the end of the month.
 *
 * The two properties that matter are that the codes are DISTINCT and that each carries the
 * batch's minutes and expiry SNAPSHOTTED onto its own row. The second is what stops an owner
 * editing a batch next month from changing what a code printed last month is worth, and it is
 * invisible until the day somebody edits one — so it is asserted here rather than trusted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VoucherBatchTest {

    private static final int BATCH_SIZE = 50;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    private UUID branchId;
    private UUID ownerId;
    private UUID cashierId;

    @BeforeEach
    void setUp() {
        Branch branch = new Branch();
        branch.setCode("VBAT");
        branch.setName("Voucher Batch Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser owner = new AppUser();
        owner.setBranchId(branchId);
        owner.setUsername("batch-owner-" + UUID.randomUUID());
        owner.setPasswordHash("unused");
        owner.setFullName("Batch Owner");
        owner.setRole(UserRole.ADMIN);
        owner.setIsActive(true);
        ownerId = appUserRepository.saveAndFlush(owner).getId();

        AppUser cashier = new AppUser();
        cashier.setBranchId(branchId);
        cashier.setUsername("batch-cashier-" + UUID.randomUUID());
        cashier.setPasswordHash("unused");
        cashier.setFullName("Batch Cashier");
        cashier.setRole(UserRole.EMPLOYEE);
        cashier.setIsActive(true);
        cashierId = appUserRepository.saveAndFlush(cashier).getId();
    }

    @Test
    void fiftyCodesAreDistinctUnredeemedAndCarryTheBatchsTermsSnapshotted() throws Exception {
        LocalDate expiry = LocalDate.now().plusMonths(2);
        JsonNode batch = body(createBatch(2, BATCH_SIZE, expiry, "October Facebook draw")
                .andExpect(status().isOk())).get("data");

        // The owner typed hours; minutes are what is stored, and the label is what reads back.
        assertThat(batch.get("minutes").asInt()).isEqualTo(120);
        assertThat(batch.get("hoursLabel").asText()).isEqualTo("2 hours");
        assertThat(batch.get("quantity").asInt()).isEqualTo(BATCH_SIZE);
        assertThat(batch.get("outstanding").asLong()).isEqualTo(BATCH_SIZE);
        assertThat(batch.get("redeemed").asLong()).isZero();

        JsonNode codes = batch.get("codes");
        assertThat(codes).hasSize(BATCH_SIZE);

        Set<String> distinct = new HashSet<>();
        for (JsonNode voucher : codes) {
            distinct.add(voucher.get("code").asText());
            assertThat(voucher.get("status").asText()).isEqualTo("OUTSTANDING");
            assertThat(voucher.get("redeemedAt").isNull()).isTrue();
            // Snapshotted onto the voucher, not read off the batch at redemption.
            assertThat(voucher.get("minutes").asInt()).isEqualTo(120);
            assertThat(voucher.get("expiresOn").asText()).isEqualTo(expiry.toString());
            // The display form: SB, then two groups of three, out of an alphabet with no
            // 0/O and no 1/I/L in it.
            assertThat(voucher.get("code").asText()).matches("SB-[2-9A-HJ-NP-TV-Z]{3}-[2-9A-HJ-NP-TV-Z]{3}");
        }
        assertThat(distinct).hasSize(BATCH_SIZE);

        // And the stored rows agree with what was handed back.
        assertThat(asOwner(() -> voucherRepository.findAll().size())).isEqualTo(BATCH_SIZE);
    }

    // The counts the owner reads, and the only one that is a liability rather than history.
    @Test
    void theBatchListReportsWhatIsStillInTheWild() throws Exception {
        createBatch(2, 5, LocalDate.now().plusMonths(1), "Draw").andExpect(status().isOk());

        JsonNode batches = body(mockMvc.perform(get("/api/v1/voucher-batches")
                .with(user(owner()))).andExpect(status().isOk())).get("data");
        JsonNode batch = batches.get(0);
        assertThat(batch.get("issued").asLong()).isEqualTo(5);
        assertThat(batch.get("outstanding").asLong()).isEqualTo(5);
        assertThat(batch.get("redeemed").asLong()).isZero();
        assertThat(batch.get("expired").asLong()).isZero();
        // The list never carries codes: a screen showing every code of every batch leaks the
        // whole giveaway to anyone who can see it over a shoulder.
        assertThat(batch.get("codes").isNull()).isTrue();
    }

    /*
     * THE SECURITY BOUNDARY, and the reason these routes are admin-only at all: a staff member
     * who can read a list of unredeemed codes can redeem them. Reading is the privilege here,
     * not writing.
     */
    @Test
    void anEmployeeCannotReachAnyOfTheVoucherAdminRoutes() throws Exception {
        mockMvc.perform(post("/api/v1/voucher-batches")
                        .with(user(cashier())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":2,\"quantity\":5,\"expiresOn\":\""
                                + LocalDate.now().plusMonths(1) + "\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/voucher-batches").with(user(cashier())))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/vouchers").with(user(cashier())))
                .andExpect(status().isForbidden());
    }

    // Hours in, minutes stored, and a fractional minute refused rather than rounded: 0.71 hours
    // is 42.6 minutes, and a voucher worth two thirds of a minute more than intended is a figure
    // nobody can account for later.
    @Test
    void aFractionalMinuteIsRefusedRatherThanRounded() throws Exception {
        assertThat(body(createBatch(1.5, 1, LocalDate.now().plusMonths(1), null)
                .andExpect(status().isOk())).get("data").get("minutes").asInt()).isEqualTo(90);

        JsonNode refused = body(createBatch(0.71, 1, LocalDate.now().plusMonths(1), null)
                .andExpect(status().isConflict()));
        assertThat(refused.get("message").asText()).contains("42").contains("43");
    }

    // The batch ceiling, refused by bean validation rather than by the database.
    @Test
    void aBatchAboveTheCeilingIsRefused() throws Exception {
        createBatch(2, 501, LocalDate.now().plusMonths(1), null).andExpect(status().isBadRequest());
        // And an expiry in the past: a code worthless the moment it is printed is a mistake,
        // not a giveaway.
        createBatch(2, 5, LocalDate.now().minusDays(1), null).andExpect(status().isBadRequest());
    }

    // ---- fixtures ------------------------------------------------------------------

    private ResultActions createBatch(double hours, int quantity, LocalDate expiresOn, String note)
            throws Exception {
        String body = "{\"hours\":" + hours + ",\"quantity\":" + quantity
                + ",\"expiresOn\":\"" + expiresOn + "\""
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
        return mockMvc.perform(post("/api/v1/voucher-batches")
                .with(user(owner())).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private AppUserDetails owner() {
        return new AppUserDetails(ownerId, branchId, "batch-owner",
                "unused", "Batch Owner", UserRole.ADMIN, true);
    }

    private AppUserDetails cashier() {
        return new AppUserDetails(cashierId, branchId, "batch-cashier",
                "unused", "Batch Cashier", UserRole.EMPLOYEE, true);
    }

    private <T> T asOwner(java.util.function.Supplier<T> work) {
        org.springframework.security.core.context.SecurityContext context =
                org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        owner(), "unused", java.util.List.of()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
