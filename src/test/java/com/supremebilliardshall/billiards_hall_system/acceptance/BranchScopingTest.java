package com.supremebilliardshall.billiards_hall_system.acceptance;

import com.supremebilliardshall.billiards_hall_system.entity.Branch;
import com.supremebilliardshall.billiards_hall_system.entity.Category;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.CategoryRepository;
import com.supremebilliardshall.billiards_hall_system.security.AppUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// BACKEND-SPEC.md section 4, acceptance test 8: "A user in branch A cannot read or write
// anything in branch B."
//
// The failure this guards against is silent: a forgotten branch predicate returns rows that
// look entirely plausible. So the assertions are about what is absent from a response, and
// about a known-good id from the other branch being invisible rather than merely unauthorised.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BranchScopingTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BranchRepository branchRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    private UUID branchA;
    private UUID branchB;
    private UUID categoryInA;

    @BeforeEach
    void setUp() {
        branchA = givenBranch("SCOPEA", "Scope Test Branch A");
        branchB = givenBranch("SCOPEB", "Scope Test Branch B");
        categoryInA = givenCategory(branchA, "Branch A Only Beer");
        givenCategory(branchB, "Branch B Only Beer");
    }

    @Test
    void listOnlyEverReturnsTheCallersOwnBranch() throws Exception {
        String seenByA = categoriesAsUserIn(branchA);
        assertThat(seenByA).contains("Branch A Only Beer");
        assertThat(seenByA).doesNotContain("Branch B Only Beer");

        String seenByB = categoriesAsUserIn(branchB);
        assertThat(seenByB).contains("Branch B Only Beer");
        assertThat(seenByB).doesNotContain("Branch A Only Beer");
    }

    @Test
    void anotherBranchesRowIsNotReadableEvenWithItsId() throws Exception {
        // Branch B's admin holds a real, valid id — it simply must not exist for them.
        mockMvc.perform(put("/api/v1/categories/" + categoryInA)
                        .with(user(principal(branchB, UserRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed From Branch B\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherBranchesRowIsNotWritableEvenWithItsId() throws Exception {
        mockMvc.perform(delete("/api/v1/categories/" + categoryInA)
                        .with(user(principal(branchB, UserRole.ADMIN))))
                .andExpect(status().isNotFound());

        // And it is still there, unarchived, for the branch that owns it.
        assertThat(categoriesAsUserIn(branchA)).contains("Branch A Only Beer");
    }

    // The inherited CRUD is the opt-out half of the mechanism: no service calls findAll(),
    // so without this the override could be deleted and every HTTP test would still pass.
    @Test
    void inheritedFindAllIsScopedWithoutAnyCallerAskingForIt() {
        actAs(branchA);
        assertThat(categoryRepository.findAll())
                .extracting(Category::getName)
                .contains("Branch A Only Beer")
                .doesNotContain("Branch B Only Beer");

        actAs(branchB);
        assertThat(categoryRepository.findById(categoryInA)).isEmpty();

        SecurityContextHolder.clearContext();
    }

    private void actAs(UUID branchId) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal(branchId, UserRole.EMPLOYEE), "unused", List.of()));
        SecurityContextHolder.setContext(context);
    }

    private String categoriesAsUserIn(UUID branchId) throws Exception {
        return mockMvc.perform(get("/api/v1/categories")
                        .with(user(principal(branchId, UserRole.EMPLOYEE))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private UUID givenBranch(String code, String name) {
        Branch branch = new Branch();
        branch.setCode(code);
        branch.setName(name);
        branch.setNextReceiptNo(1L);
        branch.setIsActive(true);
        return branchRepository.save(branch).getId();
    }

    private UUID givenCategory(UUID branchId, String name) {
        Category category = new Category();
        category.setBranchId(branchId);
        category.setName(name);
        category.setSortOrder(1);
        return categoryRepository.save(category).getId();
    }

    private AppUserDetails principal(UUID branchId, UserRole role) {
        return new AppUserDetails(UUID.randomUUID(), branchId, "tester",
                "unused", "Tester", role, true);
    }
}
