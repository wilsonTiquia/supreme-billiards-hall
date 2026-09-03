package com.supremebilliardshall.billiards_hall_system.service;

import com.supremebilliardshall.billiards_hall_system.dto.settings.SettingsRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.settings.SettingsResponseDTO;

public interface SettingsService {

    SettingsResponseDTO getSettings();

    // Writes an audit row with both figures. Changing the standard float changes what every
    // future night is reconciled against, which makes it exactly the kind of edit the log exists
    // for — and nights already counted keep the float stored on their own row.
    SettingsResponseDTO updateSettings(SettingsRequestDTO settingsRequestDTO);
}
