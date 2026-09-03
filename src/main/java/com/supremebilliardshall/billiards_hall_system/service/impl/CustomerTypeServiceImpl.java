package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.customertype.CustomerTypeRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.customertype.CustomerTypeResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.CustomerType;
import com.supremebilliardshall.billiards_hall_system.exception.DuplicateResourceException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.mapper.CustomerTypeMapper;
import com.supremebilliardshall.billiards_hall_system.repository.CustomerTypeRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.CustomerTypeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerTypeServiceImpl implements CustomerTypeService {

    private final CustomerTypeRepository customerTypeRepository;
    private final CustomerTypeMapper customerTypeMapper;
    private final BranchContext branchContext;
    private final AuditService auditService;

    public CustomerTypeServiceImpl(CustomerTypeRepository customerTypeRepository,
                                   CustomerTypeMapper customerTypeMapper,
                                   BranchContext branchContext,
                                   AuditService auditService) {
        this.customerTypeRepository = customerTypeRepository;
        this.customerTypeMapper = customerTypeMapper;
        this.branchContext = branchContext;
        this.auditService = auditService;
    }


    @Override
    @Transactional(readOnly = true)
    public List<CustomerTypeResponseDTO> getAllCustomerTypes() {
        return customerTypeRepository.findAllActive()
                .stream()
                .map(customerTypeMapper::toResponseDto)
                .toList();
    }

    @Override
    @Transactional
    public CustomerTypeResponseDTO createCustomerType(CustomerTypeRequestDTO customerTypeRequestDTO) {
        boolean exists = customerTypeRepository.existsByName(customerTypeRequestDTO.getName());
        if (exists) {
            throw new DuplicateResourceException("Customer type with name '" + customerTypeRequestDTO.getName() + "' already exists.");
        }

        CustomerType customerType = customerTypeMapper.toEntity(customerTypeRequestDTO);
        customerType.setBranchId(branchContext.getCurrentBranchId());
        applyDefaults(customerType);
        clearOtherDefaults(customerType);

        CustomerType savedCustomerType = customerTypeRepository.save(customerType);
        auditService.record("CUSTOMER_TYPE_CREATED", "customer_type", savedCustomerType.getId(),
                null, auditSnapshot(savedCustomerType), null);
        return customerTypeMapper.toResponseDto(savedCustomerType);
    }

    @Override
    @Transactional
    public CustomerTypeResponseDTO updateCustomerType(UUID id, CustomerTypeRequestDTO customerTypeRequestDTO) {
        CustomerType existing = customerTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer type", id));

        // check if the name is the same
        boolean existingName = customerTypeRepository.
                existsByNameAndIdNot(customerTypeRequestDTO.getName(), id);

        if (existingName) {
            throw new DuplicateResourceException("Customer type with name '" + customerTypeRequestDTO.getName() + "' already exists.");
        }

        Map<String, Object> before = auditSnapshot(existing);
        customerTypeMapper.updateEntityFromDto(customerTypeRequestDTO, existing);
        applyDefaults(existing);
        clearOtherDefaults(existing);

        CustomerType updated = customerTypeRepository.save(existing);
        auditService.record("CUSTOMER_TYPE_UPDATED", "customer_type", updated.getId(),
                before, auditSnapshot(updated), null);
        return customerTypeMapper.toResponseDto(updated);
    }

    @Override
    @Transactional
    public void deleteCustomerType(UUID id) {
        CustomerType customerType = customerTypeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer type", id));
        Map<String, Object> before = auditSnapshot(customerType);
        customerType.setArchivedAt(OffsetDateTime.now());
        CustomerType archived = customerTypeRepository.save(customerType);
        auditService.record("CUSTOMER_TYPE_ARCHIVED", "customer_type", archived.getId(),
                before, auditSnapshot(archived), null);
    }

    /*
     * allowsRateOverride is the flag that decides who may be given free table time, so it is
     * the field this log exists for. Changing it used to leave no trace at all.
     */
    private Map<String, Object> auditSnapshot(CustomerType customerType) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", customerType.getName());
        snapshot.put("allowsRateOverride", customerType.getAllowsRateOverride());
        snapshot.put("isDefault", customerType.getIsDefault());
        snapshot.put("sortOrder", customerType.getSortOrder());
        snapshot.put("archivedAt", customerType.getArchivedAt());
        return snapshot;
    }

    private void applyDefaults(CustomerType customerType) {
        if (customerType.getAllowsRateOverride() == null) {
            customerType.setAllowsRateOverride(false);
        }
        if (customerType.getIsDefault() == null) {
            customerType.setIsDefault(false);
        }
        if (customerType.getSortOrder() == null) {
            customerType.setSortOrder(0);
        }
    }

    // customer_type_one_default_key allows one default per branch, so promoting a new default
    // has to demote the old one in the same transaction rather than collide with the index.
    private void clearOtherDefaults(CustomerType customerType) {
        if (!Boolean.TRUE.equals(customerType.getIsDefault())) {
            return;
        }
        customerTypeRepository.findDefaults()
                .stream()
                .filter(other -> !other.getId().equals(customerType.getId()))
                .forEach(other -> {
                    other.setIsDefault(false);
                    customerTypeRepository.saveAndFlush(other);
                });
    }
}
