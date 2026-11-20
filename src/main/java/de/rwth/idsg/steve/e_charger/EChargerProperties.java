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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * e_charger fleet defaults: free vending and configuration pushed to every station at boot.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "e-charger")
public class EChargerProperties {

    FreeVend freeVend = new FreeVend();
    Boot boot = new Boot();

    @Data
    public static class FreeVend {
        boolean enabled;
        String idTag = "A0000000";
    }

    @Data
    public static class Boot {
        Duration delay = Duration.ofSeconds(10);
        List<String> changeConfiguration = new ArrayList<>();
        Integer chargingProfilePk;
    }
}
