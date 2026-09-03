package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.customertype.CustomerTypeRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.customertype.CustomerTypeResponseDTO;

import java.util.List;
import java.util.UUID;

public interface CustomerTypeService {
    List<CustomerTypeResponseDTO> getAllCustomerTypes();

    CustomerTypeResponseDTO createCustomerType(CustomerTypeRequestDTO customerTypeRequestDTO);

    // Update Customer Type
    CustomerTypeResponseDTO updateCustomerType(UUID id, CustomerTypeRequestDTO customerTypeRequestDTO);

    // Archive Customer Type — never a hard delete, historical bills reference it
    void deleteCustomerType(UUID id);
}
