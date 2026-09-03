package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Bill;
import com.supremebilliardshall.billiards_hall_system.entity.BillStatus;
import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.Payment;
import com.supremebilliardshall.billiards_hall_system.entity.PaymentMethod;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BillRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.PaymentRepository;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// H2 — a payment photo used to be any bytes under any name, served back to the ADMIN with a
// content type probed from the (attacker-chosen) extension. An .html "photo" therefore ran as
// the ADMIN. These prove the upload now accepts only real images and refuses everything else.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// The rollback undoes the row but not the file, so the test writes to a scratch directory.
@TestPropertySource(properties = "supreme.payment-photo.path=${java.io.tmpdir}/supreme-payment-photo-test")
class PaymentPhotoSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private BillRepository billRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    // The eight-byte PNG signature followed by a little filler — enough for the magic check.
    private static final byte[] REAL_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D};

    @Test
    void anHtmlFileDeclaredAsHtmlIsRejected() throws Exception {
        Fixture f = givenPayment();

        MockMultipartFile html = new MockMultipartFile(
                "file", "receipt.html", "text/html",
                "<script>alert(document.cookie)</script>".getBytes());

        String body = mockMvc.perform(multipart("/api/v1/payments/{id}/photo", f.paymentId)
                        .file(html)
                        .with(user(principal(f.branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("JPEG, PNG or WebP");
    }

    @Test
    void anHtmlPayloadDisguisedAsAPngIsRejected() throws Exception {
        Fixture f = givenPayment();

        // The dangerous case: the browser claims image/png, so a declared-type check alone
        // would pass it. The bytes are HTML, so the magic-number check catches it.
        MockMultipartFile disguised = new MockMultipartFile(
                "file", "receipt.png", "image/png",
                "<script>alert(document.cookie)</script>".getBytes());

        String body = mockMvc.perform(multipart("/api/v1/payments/{id}/photo", f.paymentId)
                        .file(disguised)
                        .with(user(principal(f.branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("not a real");
    }

    @Test
    void aRealPngIsAccepted() throws Exception {
        Fixture f = givenPayment();

        MockMultipartFile png = new MockMultipartFile("file", "gcash.png", "image/png", REAL_PNG);

        mockMvc.perform(multipart("/api/v1/payments/{id}/photo", f.paymentId)
                        .file(png)
                        .with(user(principal(f.branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isOk());
    }

    @Test
    void anUnauthenticatedUploadIsRejected() throws Exception {
        Fixture f = givenPayment();

        MockMultipartFile png = new MockMultipartFile("file", "gcash.png", "image/png", REAL_PNG);

        mockMvc.perform(multipart("/api/v1/payments/{id}/photo", f.paymentId)
                        .file(png))
                .andExpect(status().isUnauthorized());
    }

    private record Fixture(UUID branchId, UUID paymentId) {
    }

    private Fixture givenPayment() {
        Branch branch = new Branch();
        branch.setCode("PP-" + UUID.randomUUID().toString().substring(0, 8));
        branch.setName("Payment Photo Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        UUID branchId = branchRepository.saveAndFlush(branch).getId();

        AppUser user = new AppUser();
        user.setBranchId(branchId);
        user.setUsername("pp-teller-" + UUID.randomUUID());
        user.setPasswordHash("unused");
        user.setFullName("Teller");
        user.setRole(UserRole.EMPLOYEE);
        user.setIsActive(true);
        UUID userId = appUserRepository.saveAndFlush(user).getId();

        Bill bill = new Bill();
        bill.setBranchId(branchId);
        bill.setStatus(BillStatus.OPEN);
        bill.setOpenedBy(userId);
        bill.setSubtotalTime(BigDecimal.ZERO);
        bill.setSubtotalItems(BigDecimal.ZERO);
        bill.setTotalAmount(BigDecimal.ZERO);
        bill.setTotalCost(BigDecimal.ZERO);
        UUID billId = billRepository.saveAndFlush(bill).getId();

        Payment payment = new Payment();
        payment.setBranchId(branchId);
        payment.setBillId(billId);
        payment.setMethod(PaymentMethod.GCASH);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setReferenceNo("REF-" + UUID.randomUUID());
        payment.setIdempotencyKey(UUID.randomUUID().toString());
        payment.setTakenBy(userId);
        UUID paymentId = paymentRepository.saveAndFlush(payment).getId();

        return new Fixture(branchId, paymentId);
    }

    private AppUserDetails principal(UUID branchId, UserRole role) {
        return new AppUserDetails(UUID.randomUUID(), branchId, "tester",
                "unused", "Tester", role, true);
    }
}
