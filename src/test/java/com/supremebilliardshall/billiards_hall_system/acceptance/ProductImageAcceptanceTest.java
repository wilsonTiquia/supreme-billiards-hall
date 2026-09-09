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

    /*
     * A text file wearing image/png. Refused on its bytes, not on what it calls itself.
     *
     * The declared content type is the one thing on the upload the sender chooses freely, and
     * this route used to take it at its word: the file was stored, and served straight back
     * under an image content type, so the catalogue grew a tile that was a 200 with nothing in
     * it. The payment-photo route had checked the signature since it was written; this is the
     * same check, now shared, so the pair cannot drift again.
     *
     * The size cap is untouched and still runs first -- see the oversized test above, which
     * declares image/png too and must keep getting the size message rather than this one.
     */
    @Test
    void aTextFileDeclaredAsAPngIsRefusedOnItsBytes() throws Exception {
        Product product = givenProduct();

        MockMultipartFile liar = new MockMultipartFile(
                "file", "tile.png", "image/png", "this is not a picture".getBytes());

        String body = mockMvc.perform(multipart("/api/v1/products/{id}/image", product.getId())
                        .file(liar)
                        .with(user(principal(product.getBranchId(), UserRole.ADMIN))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("whatever its name says");

        // And nothing was stored, so the grid has no tile to serve.
        mockMvc.perform(get("/api/v1/products/{id}/image", product.getId())
                        .with(user(principal(product.getBranchId(), UserRole.ADMIN))))
                .andExpect(status().isNotFound());
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

    /*
     * A real PNG signature, because the route now checks it.
     *
     * This said "pretend-png-bytes" and both upload tests passed on it, which is the whole
     * shape of the bug: the route believed the declared content type, so no fixture ever had
     * to be an image. Nothing here decodes the file -- the eight signature bytes are what the
     * service reads and what a text file cannot fake.
     */
    private MockMultipartFile imageFile(String contentType) {
        return new MockMultipartFile("file", "tile.png", contentType, pngBytes());
    }

    private static byte[] pngBytes() {
        byte[] png = new byte[64];
        System.arraycopy(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, 0, png, 0, 8);
        return png;
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
