package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.Product;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.ProductRepository;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The image is the one asset the counter reads and the owner writes, so the two halves of that
// split are what this asserts: an employee can SEE a picture and cannot replace one.
//
// The rejections are asserted on the message, not only the status. A file that comes back with
// "conflict" and nothing else tells the operator holding a 4 MB phone photo nothing at all.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
// The rollback undoes the row but not the file, so the test writes to a scratch directory. The
// real one is business data and the nightly backup would carry every test image forever.
@TestPropertySource(properties = "supreme.product-image.path=${java.io.tmpdir}/supreme-product-image-test")
class ProductImageAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void adminUploadsAndAnEmployeeCanSeeTheImage() throws Exception {
        Product product = givenProduct();
        UUID branchId = product.getBranchId();

        mockMvc.perform(multipart("/api/v1/products/{id}/image", product.getId())
                        .file(imageFile("image/png"))
                        .with(user(principal(branchId, UserRole.ADMIN))))
                .andExpect(status().isOk());

        // The counter, not the owner: this is the route that differs from payment photos.
        mockMvc.perform(get("/api/v1/products/{id}/image", product.getId())
                        .with(user(principal(branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_PNG_VALUE))
                // The grid asks for every tile on every poll; without these it asks for bytes.
                .andExpect(header().exists("ETag"))
                .andExpect(header().string("Cache-Control", "max-age=86400, private"));
    }

    @Test
    void anEmployeeCannotUploadOrRemoveAnImage() throws Exception {
        Product product = givenProduct();
        UUID branchId = product.getBranchId();

        mockMvc.perform(multipart("/api/v1/products/{id}/image", product.getId())
                        .file(imageFile("image/png"))
                        .with(user(principal(branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/products/{id}/image", product.getId())
                        .with(user(principal(branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOversizedFileIsRefusedWithTheLimitNamed() throws Exception {
        Product product = givenProduct();

        MockMultipartFile tooBig = new MockMultipartFile(
                "file", "huge.png", "image/png", new byte[3 * 1024 * 1024]);

        String body = mockMvc.perform(multipart("/api/v1/products/{id}/image", product.getId())
                        .file(tooBig)
                        .with(user(principal(product.getBranchId(), UserRole.ADMIN))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("3.0 MB").contains("2 MB");
    }

    @Test
    void aFileThatIsNotAnImageIsRefusedWithTheFormatsNamed() throws Exception {
        Product product = givenProduct();

        MockMultipartFile notAnImage = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "not a photo".getBytes());

        String body = mockMvc.perform(multipart("/api/v1/products/{id}/image", product.getId())
                        .file(notAnImage)
                        .with(user(principal(product.getBranchId(), UserRole.ADMIN))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("JPEG, PNG or WebP");
    }

    // Images are optional, so "no image" is an ordinary answer and not an error condition the
    // grid has to special-case beyond rendering its placeholder.
    @Test
    void aProductWithNoImageListsWithANullChecksumAndAnswers404ForTheBytes() throws Exception {
        Product product = givenProduct();
        UUID branchId = product.getBranchId();

        String body = mockMvc.perform(get("/api/v1/products?activeOnly=false")
                        .with(user(principal(branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"imageSha256\":null");
        // The field this change removed must not have come back with it.
        assertThat(body).doesNotContain("sku");

        mockMvc.perform(get("/api/v1/products/{id}/image", product.getId())
                        .with(user(principal(branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isNotFound());
    }

    private MockMultipartFile imageFile(String contentType) {
        return new MockMultipartFile("file", "tile.png", contentType, "pretend-png-bytes".getBytes());
    }

    private Product givenProduct() {
        Branch branch = new Branch();
        branch.setCode("IMGTEST");
        branch.setName("Product Image Test Branch");
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        Branch savedBranch = branchRepository.save(branch);

        Product product = new Product();
        product.setBranchId(savedBranch.getId());
        product.setName("San Miguel Pale Pilsen");
        product.setSellingPrice(new BigDecimal("90.00"));
        product.setAvgCost(new BigDecimal("52.5000"));
        product.setQtyOnHand(new BigDecimal("48.000"));
        product.setIsActive(true);
        return productRepository.save(product);
    }

    private AppUserDetails principal(UUID branchId, UserRole role) {
        return new AppUserDetails(UUID.randomUUID(), branchId, "tester",
                "unused", "Tester", role, true);
    }
}
