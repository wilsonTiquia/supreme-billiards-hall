package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.setup.SetupItemResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.UserRole;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.SetupRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SetupServiceImpl implements SetupService {
    private final SetupRepository repository;
    private final AppUserRepository users;
    private final BranchContext branch;
    private final AuditService audit;
    private final CategoryService categories;
    private final PoolTableService tables;
    private final CustomerTypeService customerTypes;
    private final ExpenseCategoryService expenses;
    private final AuthService auth;

    public SetupServiceImpl(SetupRepository repository, AppUserRepository users, BranchContext branch,
                            AuditService audit, CategoryService categories, PoolTableService tables,
                            CustomerTypeService customerTypes, ExpenseCategoryService expenses, AuthService auth) {
        this.repository = repository;
        this.users = users;
        this.branch = branch;
        this.audit = audit;
        this.categories = categories;
        this.tables = tables;
        this.customerTypes = customerTypes;
        this.expenses = expenses;
        this.auth = auth;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SetupItemResponseDTO> list(SetupKind kind) {
        var rows = repository.list(kind);
        if (kind == SetupKind.STAFF) {
            long admins = users.findAll().stream().filter(u -> u.getRole() == UserRole.ADMIN
                    && u.getArchivedAt() == null && Boolean.TRUE.equals(u.getIsActive())).count();
            for (var row : rows) {
                if (row.getId().equals(branch.getCurrentUserId())) row.setBlockedReason("You cannot remove your own account.");
                else if (admins <= 1) {
                    var person = users.findById(row.getId()).orElseThrow();
                    if (person.getRole() == UserRole.ADMIN && person.getArchivedAt() == null
                            && Boolean.TRUE.equals(person.getIsActive())) {
                        row.setBlockedReason("Add another administrator before removing the last one.");
                    }
                }
            }
        }
        return rows;
    }

    private void guardStaff(SetupKind kind, UUID id) {
        if (kind != SetupKind.STAFF) return;
        if (repository.list(kind).stream().noneMatch(row -> row.getId().equals(id))) {
            throw new ResourceNotFoundException("Setup item", id);
        }
        if (id.equals(branch.getCurrentUserId())) throw new BusinessRuleException("You cannot remove your own account.");
        // Same lock order as AuthService: lock active administrators before the target row.
        var admins = users.findActiveAdminsForUpdate(UserRole.ADMIN);
        if (admins.stream().anyMatch(u -> u.getId().equals(id)) && admins.size() <= 1) {
            throw new BusinessRuleException("Add another administrator before removing the last one.");
        }
    }

    @Override
    @Transactional
    public void delete(SetupKind kind, UUID id) {
        guardStaff(kind, id);
        var item = repository.lock(kind, id);
        if (!item.isCanDelete()) throw new BusinessRuleException(item.getDeletionReason());
        repository.delete(kind, id);
        audit.record(kind.getAction() + "_DELETED", kind.getTable(), id, snapshot(item), null, null);
    }

    @Override
    @Transactional
    public void archive(SetupKind kind, UUID id) {
        guardStaff(kind, id);
        var item = repository.lock(kind, id);
        if (item.getArchivedAt() != null) throw new BusinessRuleException("That item is already archived.");
        switch (kind) {
            case CATEGORIES -> categories.deleteCategory(id);
            case TABLES -> tables.deleteTable(id);
            case CUSTOMER_TYPES -> customerTypes.deleteCustomerType(id);
            case EXPENSE_CATEGORIES -> expenses.deleteExpenseCategory(id);
            case STAFF -> auth.archiveUser(id);
            case VOUCHERS -> {
                repository.archiveBatch(id);
                audit.record("VOUCHER_BATCH_ARCHIVED", kind.getTable(), id, snapshot(item),
                        snapshot(repository.lock(kind, id)), null);
            }
        }
    }

    @Override
    @Transactional
    public void restore(SetupKind kind, UUID id) {
        var item = repository.lock(kind, id);
        if (item.getArchivedAt() == null) throw new BusinessRuleException("That item is not archived.");
        if (repository.nameTaken(kind, id)) {
            throw new BusinessRuleException("Cannot restore '" + item.getName()
                    + "': its name or username is now in use. Rename or archive the active item, then try again.");
        }
        repository.restore(kind, id);
        audit.record(kind.getAction() + "_RESTORED", kind.getTable(), id, snapshot(item),
                snapshot(repository.lock(kind, id)), null);
    }

    private Map<String, Object> snapshot(SetupItemResponseDTO item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", item.getName());
        result.put("archivedAt", item.getArchivedAt());
        return result;
    }
}
