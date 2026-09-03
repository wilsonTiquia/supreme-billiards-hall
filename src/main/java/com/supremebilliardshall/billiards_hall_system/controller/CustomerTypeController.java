package com.supremebilliardshall.billiards_hall_system.controller;

import com.supremebilliardshall.billiards_hall_system.dto.APIResponse;
import com.supremebilliardshall.billiards_hall_system.dto.customertype.CustomerTypeRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.customertype.CustomerTypeResponseDTO;
import com.supremebilliardshall.billiards_hall_system.service.CustomerTypeService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

// Admin manages the list; an employee reads it for the session-start dropdown.
@RestController
@RequestMapping("/api/v1/customer-types")
public class CustomerTypeController {

    private final CustomerTypeService customerTypeService;

    public CustomerTypeController(CustomerTypeService customerTypeService) {
        this.customerTypeService = customerTypeService;
    }

    @GetMapping
    public ResponseEntity<APIResponse<List<CustomerTypeResponseDTO>>> getAll() {
        List<CustomerTypeResponseDTO> customerTypesDto = customerTypeService.getAllCustomerTypes();
        return ResponseEntity.
                ok(APIResponse.success(
                        customerTypesDto,
                        "All Customer types fetched successfully"));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CustomerTypeResponseDTO>> createCustomerType(@Valid @RequestBody CustomerTypeRequestDTO customerTypeRequestDTO) {
        CustomerTypeResponseDTO savedCustomerType = customerTypeService.createCustomerType(customerTypeRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        savedCustomerType,
                        "Customer type created successfully"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CustomerTypeResponseDTO>> updateCustomerType(@PathVariable UUID id,
                                                                                   @Valid @RequestBody CustomerTypeRequestDTO customerTypeRequestDTO) {
        CustomerTypeResponseDTO updatedCustomerType = customerTypeService.updateCustomerType(id, customerTypeRequestDTO);
        return ResponseEntity.
                ok(APIResponse.success(
                        updatedCustomerType,
                        "Customer type updated successfully"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<APIResponse<CustomerTypeResponseDTO>> deleteCustomerType(@PathVariable UUID id) {
        customerTypeService.deleteCustomerType(id);
        return ResponseEntity.ok(
                APIResponse.success(null, "Customer type archived successfully with id: " + id)
        );
    }

}
