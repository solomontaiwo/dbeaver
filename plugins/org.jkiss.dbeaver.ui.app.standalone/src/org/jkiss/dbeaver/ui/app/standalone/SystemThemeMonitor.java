/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.app.standalone;

import org.eclipse.e4.ui.css.swt.theme.IThemeEngine;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.DBeaverPreferences;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.RuntimeUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Monitors the OS/system theme and switches the DBeaver Eclipse theme
 * (light or dark) automatically when the "auto" theme mode is active.
 *
 * <p>Theme mode values (stored in {@link DBeaverPreferences#UI_THEME_MODE}):
 * <ul>
 *   <li>{@code "auto"}  – follow the OS dark/light setting (default)</li>
 *   <li>{@code "light"} – always use the light Eclipse theme</li>
 *   <li>{@code "dark"}  – always use the dark Eclipse theme</li>
 * </ul>
 */
public class SystemThemeMonitor {

    private static final Log log = Log.getLog(SystemThemeMonitor.class);

    /** Preference value: follow the OS appearance setting. */
    public static final String THEME_MODE_AUTO = DBeaverPreferences.UI_THEME_MODE_AUTO;
    /** Preference value: always use the light theme. */
    public static final String THEME_MODE_LIGHT = DBeaverPreferences.UI_THEME_MODE_LIGHT;
    /** Preference value: always use the dark theme. */
    public static final String THEME_MODE_DARK = DBeaverPreferences.UI_THEME_MODE_DARK;

    /** Eclipse E4 CSS theme ID for the default (light) theme. */
    private static final String ECLIPSE_THEME_LIGHT = "org.eclipse.e4.ui.css.theme.e4_default";
    /** Eclipse E4 CSS theme ID for the dark theme. */
    private static final String ECLIPSE_THEME_DARK = "org.eclipse.e4.ui.css.theme.e4_dark";

    private final Display display;
    private boolean lastDark;

    private SystemThemeMonitor(Display display) {
        this.display = display;
    }

    /**
     * Installs the monitor on the given {@code display}.  Must be called from
     * the SWT UI thread after the workbench has been started.
     */
    public static void install(Display display) {
        SystemThemeMonitor monitor = new SystemThemeMonitor(display);
        monitor.applyTheme();
        monitor.startListening();
    }

    // -------------------------------------------------------------------------
    // Theme application
    // -------------------------------------------------------------------------

    /**
     * Reads the current {@link DBeaverPreferences#UI_THEME_MODE} preference
     * and applies the corresponding Eclipse theme.
     */
    private void applyTheme() {
        String mode = getThemeMode();
        boolean useDark;
        if (THEME_MODE_DARK.equals(mode)) {
            useDark = true;
        } else if (THEME_MODE_LIGHT.equals(mode)) {
            useDark = false;
        } else {
            // "auto" – follow the OS
            useDark = isOsDarkMode();
        }
        lastDark = useDark;
        switchEclipseTheme(useDark);
    }

    private static String getThemeMode() {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        String mode = store.getString(DBeaverPreferences.UI_THEME_MODE);
        if (mode == null || mode.isEmpty()) {
            mode = THEME_MODE_AUTO;
        }
        return mode;
    }

    // -------------------------------------------------------------------------
    // Eclipse theme switching
    // -------------------------------------------------------------------------

    private static void switchEclipseTheme(boolean dark) {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench == null) {
            return;
        }
        IThemeEngine engine = workbench.getService(IThemeEngine.class);
        if (engine == null) {
            return;
        }
        String targetId = dark ? ECLIPSE_THEME_DARK : ECLIPSE_THEME_LIGHT;
        org.eclipse.e4.ui.css.swt.theme.ITheme current = engine.getActiveTheme();
        if (current != null && targetId.equals(current.getId())) {
            // Theme is already correct – nothing to do
            return;
        }
        try {
            engine.setTheme(targetId, true);
        } catch (Exception e) {
            log.warn("Failed to switch Eclipse theme to '" + targetId + "'", e);
        }
    }

    // -------------------------------------------------------------------------
    // OS dark-mode detection
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the OS is currently set to dark mode.
     *
     * <p>Detection strategy:
     * <ul>
     *   <li>Windows – reads the {@code AppsUseLightTheme} registry value.</li>
     *   <li>macOS – queries {@code defaults read -g AppleInterfaceStyle}.</li>
     *   <li>Linux – queries {@code gsettings get org.gnome.desktop.interface color-scheme},
     *       falling back to the {@code GTK_THEME} environment variable.</li>
     * </ul>
     */
    public static boolean isOsDarkMode() {
        try {
            if (RuntimeUtils.isWindows()) {
                return isWindowsDarkMode();
            } else if (RuntimeUtils.isMacOS()) {
                return isMacOsDarkMode();
            } else {
                return isLinuxDarkMode();
            }
        } catch (Exception e) {
            log.debug("Could not detect OS dark mode", e);
            return false;
        }
    }

    private static boolean isWindowsDarkMode() throws Exception {
        Process process = Runtime.getRuntime().exec(new String[]{
            "reg", "query",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme"
        });
        String output = readProcessOutput(process);
        // Example outputs:
        // "AppsUseLightTheme    REG_DWORD    0x0"   → dark mode
        // "AppsUseLightTheme    REG_DWORD    0x1"   → light mode
        // "AppsUseLightTheme    REG_DWORD    0x01"  → light mode

        if (output != null) {
            String valueHex = null;
            String[] tokens = output.trim().split("\\s+");
            for (String token : tokens) {
                if (token.startsWith("0x") || token.startsWith("0X")) {
                    valueHex = token;
                }
            }
            if (valueHex != null && valueHex.length() > 2) {
                try {
                    int value = Integer.parseInt(valueHex.substring(2), 16);
                    // 0 → dark mode, non-zero (typically 1) → light mode
                    return value == 0;
                } catch (NumberFormatException ignored) {
                    // fall through to default (light mode)
                }
            }
        }
        // Default to light mode if detection fails
        return false;
    }

    private static boolean isMacOsDarkMode() throws Exception {
        Process process = Runtime.getRuntime().exec(new String[]{
            "defaults", "read", "-g", "AppleInterfaceStyle"
        });
        String output = readProcessOutput(process).trim();
        return "Dark".equalsIgnoreCase(output);
    }

    private static boolean isLinuxDarkMode() throws Exception {
        // Try gsettings first (GNOME)
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                "gsettings", "get",
                "org.gnome.desktop.interface", "color-scheme"
            });
            String output = readProcessOutput(process).trim().toLowerCase();
            if (output.contains("prefer-dark") || output.contains("dark")) {
                return true;
            }
            if (!output.isEmpty()) {
                return false;
            }
        } catch (Exception ignored) {
            // gsettings not available
        }

        // Fallback: check GTK_THEME environment variable
        String gtkTheme = System.getenv("GTK_THEME");
        if (gtkTheme != null && gtkTheme.toLowerCase().contains("dark")) {
            return true;
        }

        // Fallback: KDE / Plasma
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                "kreadconfig5", "--group", "General", "--key", "ColorScheme"
            });
            String output = readProcessOutput(process).trim().toLowerCase();
            if (output.contains("dark")) {
                return true;
            }
        } catch (Exception ignored) {
            // kreadconfig5 not available
        }

        return false;
    }

    private static String readProcessOutput(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0 && sb.length() == 0) {
            // No useful output and non-zero exit – treat as "unknown"
            return "";
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Event listeners
    // -------------------------------------------------------------------------

    private void startListening() {
        // SWT.Settings is fired by SWT when OS appearance settings change
        // (works on Windows and macOS; GTK fires it too on theme change).
        display.addListener(SWT.Settings, event -> onOsSettingsChanged());

        // Also listen for preference changes so that selecting a different
        // theme mode in Preferences takes effect immediately.
        DBWorkbench.getPlatform().getPreferenceStore()
            .addPropertyChangeListener(evt -> {
                if (DBeaverPreferences.UI_THEME_MODE.equals(evt.getProperty())) {
                    display.asyncExec(this::applyTheme);
                }
            });
    }

    private void onOsSettingsChanged() {
        String mode = getThemeMode();
        if (!THEME_MODE_AUTO.equals(mode)) {
            // Not in auto mode – user has explicitly chosen a theme
            return;
        }
        boolean dark = isOsDarkMode();
        if (dark == lastDark) {
            return;
        }
        lastDark = dark;
        switchEclipseTheme(dark);
    }
}
