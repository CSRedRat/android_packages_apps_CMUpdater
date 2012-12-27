/*
 * Copyright (C) 2012 The CyanogenMod Project
 *
 * * Licensed under the GNU GPLv2 license
 *
 * The text of the license can be found in the LICENSE file
 * or at https://www.gnu.org/licenses/gpl-2.0.txt
 */

package com.cyanogenmod.updater.utils;

import android.util.Log;

import com.cyanogenmod.updater.customization.Customization;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class SysUtils {
    private static final String TAG = "SysUtils";

    /**
     * Returns (if available) a human-readable string containing current mod version
     * @return a human-readable string containing current mod version
     */
    public static String getModVersion() {
        String modVer = getSystemProperty(Customization.SYS_PROP_MOD_VERSION);
        modVer = modVer.replaceAll("([0-9.]+?)-.+","$1");
        return (modVer == null || modVer.length() == 0 ? "Unknown" : modVer);
    }

    /**
     * Returns a SystemProperty
     * @param propName The Property to retrieve
     * @return The Property, or NULL if not found
     */
    public static String getSystemProperty(String propName) {
        String line;
        BufferedReader input = null;
        try {
            Process p = Runtime.getRuntime().exec("getprop " + propName);
            input = new BufferedReader(new InputStreamReader(p.getInputStream()), 1024);
            line = input.readLine();
            input.close();
        } catch (IOException ex) {
            Log.e(TAG, "Unable to read sysprop " + propName, ex);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    Log.e(TAG, "Exception while closing InputStream", e);
                }
            }
        }
        return line;
    }

    /**
     * Compare two version numbers
     *
     * @return <0 if first < second, 0 if first == second, >0 if first > second
     */

    public static int compareVersionNumbers(String first, String second) {
        int dash = first.indexOf('-');
        if(dash != -1) {
            first = first.substring(0, dash);
        }

        dash = second.indexOf('-');
        if(dash != -1) {
            second = second.substring(0, dash);
        }

        int currentIdx[] = {0,0};
        int newIdx[] = new int[2];

        for(int i = 0 ; i < 3 ; i++) {
            newIdx[0] = first.indexOf('.', currentIdx[0]);
            newIdx[1] = second.indexOf('.', currentIdx[1]);
            if(newIdx[0] >= 0 && newIdx[1] == -1) {
                return Integer.MAX_VALUE;
            }
            if(newIdx[0] == -1 && newIdx[1] >= 0) {
                return Integer.MIN_VALUE;
            }
            if(newIdx[0] == -1 && newIdx[1] == -1) {
                newIdx[0] = first.length();
                newIdx[1] = second.length();
            }

            int firstValue = Integer.parseInt(first.substring(currentIdx[0], newIdx[0]));
            int secondValue = Integer.parseInt(second.substring(currentIdx[1], newIdx[1]));

            if(firstValue != secondValue) {
                return firstValue - secondValue;
            }

            currentIdx[0] = newIdx[0]+1;
            currentIdx[1] = newIdx[1]+1;
        }

        return 0;
    }

}
