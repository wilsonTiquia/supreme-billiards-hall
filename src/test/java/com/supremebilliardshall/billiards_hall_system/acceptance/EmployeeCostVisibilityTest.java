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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BACKEND-SPEC.md section 4, acceptance test 7: "The product and bill DTOs for an EMPLOYEE
// contain no cost or profit field at all."
//
// Asserted against the raw response body rather than a parsed field, because the requirement
// is the absence of the key, not a null value. A nullable field that happens to be null today
// would pass a field-level assertion and leak the day someone forgets to null it.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EmployeeCostVisibilityTest {

    private static final List<String> COST_FIELDS =
            List.of("avgCost", "avg_cost", "unitCost", "unit_cost",
                    "purchasePrice", "purchase_price", "totalCost", "total_cost", "profit");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void employeeProductResponseContainsNoCostFieldAtAll() throws Exception {
        UUID branchId = givenBranchWithOneProduct();

        String body = mockMvc.perform(get("/api/v1/products")
                        .with(user(principal(branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("San Miguel Pale Pilsen");
        assertThat(COST_FIELDS).allSatisfy(field -> assertThat(body).doesNotContain(field));
    }

    @Test
    void adminProductResponseCarriesCost() throws Exception {
        UUID branchId = givenBranchWithOneProduct();

        String body = mockMvc.perform(get("/api/v1/products")
                        .with(user(principal(branchId, UserRole.ADMIN))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("avgCost");
    }

    private UUID givenBranchWithOneProduct() {
        Branch branch = new Branch();
        branch.setCode("COSTTEST");
        branch.setName("Cost Visibility Test Branch");
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
        productRepository.save(product);

        return savedBranch.getId();
    }

    private AppUserDetails principal(UUID branchId, UserRole role) {
        return new AppUserDetails(UUID.randomUUID(), branchId, "tester",
                "unused", "Tester", role, true);
    }
}
