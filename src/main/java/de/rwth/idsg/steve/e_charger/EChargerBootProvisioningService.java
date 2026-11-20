/*
 * SteVe - SteckdosenVerwaltung - https://github.com/steve-community/steve
 * Copyright (C) 2013-2026 SteVe Community Team
 * All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package de.rwth.idsg.steve.e_charger;

import com.google.common.base.Strings;
import de.rwth.idsg.steve.repository.OcppTagRepository;
import de.rwth.idsg.steve.service.ChargePointServiceClient;
import de.rwth.idsg.steve.service.notification.OcppStationBooted;
import de.rwth.idsg.steve.web.dto.OcppTagForm;
import de.rwth.idsg.steve.web.dto.ocpp.ChangeConfigurationParams;
import de.rwth.idsg.steve.web.dto.ocpp.ChangeConfigurationParams.ConfigurationKeyType;
import de.rwth.idsg.steve.web.dto.ocpp.SetChargingProfileParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ocpp.cs._2015._10.RegistrationStatus;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes every station work out of the box, without per-station manual setup:
 * <ul>
 *     <li>on startup: ensures the free-vend idTag exists with unlimited concurrent transactions,</li>
 *     <li>on every accepted BootNotification: pushes free-vend and other configured keys via ChangeConfiguration,
 *     and optionally a charging profile from DB via SetChargingProfile.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EChargerBootProvisioningService {

    private static final int UNLIMITED_ACTIVE_TRANSACTIONS = -1;

    private final EChargerProperties eChargerProperties;
    private final ChargePointServiceClient chargePointServiceClient;
    private final OcppTagRepository ocppTagRepository;
    private final TaskScheduler taskScheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureFreeVendTag() {
        var freeVend = eChargerProperties.getFreeVend();
        if (!freeVend.isEnabled() || Strings.isNullOrEmpty(freeVend.getIdTag())) {
            return;
        }

        String idTag = freeVend.getIdTag();
        String note = "e_charger free-vend idTag (managed by SteVe, see e-charger.free-vend.* properties)";

        try {
            var existing = ocppTagRepository.getRecord(idTag);

            var form = new OcppTagForm();
            form.setIdTag(idTag);
            form.setMaxActiveTransactionCount(UNLIMITED_ACTIVE_TRANSACTIONS);
            form.setNote(note);

            if (existing == null) {
                ocppTagRepository.addOcppTag(form);
                log.info("e_charger: created free-vend idTag '{}'", idTag);
            } else if (existing.getMaxActiveTransactionCount() != UNLIMITED_ACTIVE_TRANSACTIONS
                    || existing.getExpiryDate() != null) {
                form.setOcppTagPk(existing.getOcppTagPk());
                form.setParentIdTag(existing.getParentIdTag());
                ocppTagRepository.updateOcppTag(form);
                log.info("e_charger: updated free-vend idTag '{}' to unlimited, non-expiring", idTag);
            }
        } catch (Exception e) {
            log.error("e_charger: failed to ensure free-vend idTag '{}'", idTag, e);
        }
    }

    @EventListener
    public void onStationBooted(OcppStationBooted event) {
        if (event.getStatus().orElse(null) != RegistrationStatus.ACCEPTED) {
            return;
        }

        String chargeBoxId = event.getChargeBoxId();
        Map<String, String> configuration = buildConfiguration();
        Integer chargingProfilePk = eChargerProperties.getBoot().getChargingProfilePk();

        if (configuration.isEmpty() && chargingProfilePk == null) {
            return;
        }

        // Delay, so the station receives and processes the BootNotification response first
        taskScheduler.schedule(
            () -> provision(chargeBoxId, configuration, chargingProfilePk),
            Instant.now().plus(eChargerProperties.getBoot().getDelay())
        );
    }

    private void provision(String chargeBoxId, Map<String, String> configuration, Integer chargingProfilePk) {
        configuration.forEach((key, value) -> {
            try {
                var params = new ChangeConfigurationParams();
                params.setChargeBoxIdList(List.of(chargeBoxId));
                params.setKeyType(ConfigurationKeyType.CUSTOM);
                params.setCustomConfKey(key);
                params.setValue(value);
                chargePointServiceClient.changeConfiguration(params);
                log.info("e_charger: sent ChangeConfiguration {}={} to '{}'", key, value, chargeBoxId);
            } catch (Exception e) {
                log.error("e_charger: ChangeConfiguration {}={} to '{}' failed", key, value, chargeBoxId, e);
            }
        });

        if (chargingProfilePk != null) {
            try {
                var params = new SetChargingProfileParams();
                params.setChargeBoxIdList(List.of(chargeBoxId));
                params.setConnectorId(0);
                params.setChargingProfilePk(chargingProfilePk);
                chargePointServiceClient.setChargingProfile(params);
                log.info("e_charger: sent SetChargingProfile (pk={}) to '{}'", chargingProfilePk, chargeBoxId);
            } catch (Exception e) {
                log.error("e_charger: SetChargingProfile (pk={}) to '{}' failed", chargingProfilePk, chargeBoxId, e);
            }
        }
    }

    private Map<String, String> buildConfiguration() {
        Map<String, String> configuration = new LinkedHashMap<>();

        var freeVend = eChargerProperties.getFreeVend();
        if (freeVend.isEnabled()) {
            configuration.put("Cst_FreeVendActive", "true");
            if (!Strings.isNullOrEmpty(freeVend.getIdTag())) {
                configuration.put("Cst_FreeVendIdTag", freeVend.getIdTag());
            }
        }

        for (String entry : eChargerProperties.getBoot().getChangeConfiguration()) {
            if (Strings.isNullOrEmpty(entry)) {
                continue;
            }
            int idx = entry.indexOf('=');
            if (idx <= 0) {
                log.warn("e_charger: ignoring malformed boot config entry '{}' (expected KEY=VALUE)", entry);
                continue;
            }
            configuration.put(entry.substring(0, idx).strip(), entry.substring(idx + 1).strip());
        }

        return configuration;
    }
}
