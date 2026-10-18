/*
 * Flint Spark Launcher (FSL), a modified version of Hello Minecraft! Launcher.
 * Copyright (C) 2026 FSL contributors
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
package org.jackhuang.hmcl.ui.fsl;

import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Locale;

/// Small text formatting helpers used by FSL UI components.
@NotNullByDefault
public final class FslFormat {

    private FslFormat() {
    }

    /// Formats a count compactly: Chinese UI uses 万 / 亿, other languages use K / M / B.
    ///
    /// @param count a non-negative number, such as a download count
    /// @return the compact text, for example `1.2万` or `12.3M`
    public static String compactCount(long count) {
        if (I18n.isUseChinese()) {
            if (count >= 100_000_000L) return trim(count / 100_000_000.0) + "亿";
            if (count >= 10_000L) return trim(count / 10_000.0) + "万";
            return Long.toString(count);
        }
        if (count >= 1_000_000_000L) return trim(count / 1_000_000_000.0) + "B";
        if (count >= 1_000_000L) return trim(count / 1_000_000.0) + "M";
        if (count >= 1_000L) return trim(count / 1_000.0) + "K";
        return Long.toString(count);
    }

    /// Formats `value` with one decimal place, dropping a trailing `.0`.
    private static String trim(double value) {
        String text = String.format(Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
