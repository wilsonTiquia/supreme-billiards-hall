package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.settings.SettingsRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.settings.SettingsResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.BranchSetting;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.repository.BranchSettingRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SettingsServiceImpl implements SettingsService {

    // Read by AuthServiceImpl too, so the counter can be told whether to play the transition.
    static final String CHECKOUT_ANIMATION_KEY = "checkout_animation";

    private final BranchSettingRepository branchSettingRepository;
    private final AuditService auditService;
    private final BranchContext branchContext;

    public SettingsServiceImpl(BranchSettingRepository branchSettingRepository,
                               AuditService auditService,
                               BranchContext branchContext) {
        this.branchSettingRepository = branchSettingRepository;
        this.auditService = auditService;
        this.branchContext = branchContext;
    }

    @Override
    @Transactional(readOnly = true)
    public SettingsResponseDTO getSettings() {
        return new SettingsResponseDTO(standardCashFloat(), checkoutAnimation());
    }

    @Override
    @Transactional
    public SettingsResponseDTO updateSettings(SettingsRequestDTO settingsRequestDTO) {
        BigDecimal previousFloat = standardCashFloat();
        BigDecimal nextFloat = settingsRequestDTO.getStandardCashFloat();
        boolean previousAnimation = checkoutAnimation();
        boolean nextAnimation = Boolean.TRUE.equals(settingsRequestDTO.getCheckoutAnimation());

        boolean floatChanged = previousFloat.compareTo(nextFloat) != 0;
        boolean animationChanged = previousAnimation != nextAnimation;
        if (!floatChanged && !animationChanged) {
            throw new BusinessRuleException("Nothing on this screen has changed.");
        }

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();

        if (floatChanged) {
            // jsonb, and a bare number is valid jsonb — which is how low_stock_threshold is
            // stored too. toPlainString avoids ever writing 1E+3 into a column something else
            // will parse.
            write(BusinessDayServiceImpl.STANDARD_CASH_FLOAT_KEY, nextFloat.toPlainString());
            before.put("standardCashFloat", previousFloat);
            after.put("standardCashFloat", nextFloat);
        }
        if (animationChanged) {
            write(CHECKOUT_ANIMATION_KEY, Boolean.toString(nextAnimation));
            before.put("checkoutAnimation", previousAnimation);
            after.put("checkoutAnimation", nextAnimation);
        }

        auditService.record("SETTING_CHANGED", "branch_setting", null, before, after,
                floatChanged && animationChanged ? "Standard cash float, checkout animation"
                        : floatChanged ? "Standard cash float" : "Checkout animation");

        return new SettingsResponseDTO(nextFloat, nextAnimation);
    }

    private void write(String key, String jsonValue) {
        BranchSetting setting = new BranchSetting();
        setting.setBranchId(branchContext.getCurrentBranchId());
        setting.setKey(key);
        setting.setValue(jsonValue);
        setting.setUpdatedBy(branchContext.getCurrentUserId());
        branchSettingRepository.save(setting);
    }

    private boolean checkoutAnimation() {
        return branchSettingRepository.findValueByKey(CHECKOUT_ANIMATION_KEY)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    private BigDecimal standardCashFloat() {
        return branchSettingRepository.findValueByKey(BusinessDayServiceImpl.STANDARD_CASH_FLOAT_KEY)
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
    }
}
