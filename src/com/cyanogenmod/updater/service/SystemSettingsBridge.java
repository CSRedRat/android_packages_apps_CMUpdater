package com.cyanogenmod.updater.service;

import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * This is an reflection based method of getting system properties. It is slightly less efficient than
 * a direct call, but due to SystemProperties being a private API the advantage of using this is that
 * the project can still be compiled using the normal Android SDK without the need for a full from-device
 * framework jar.
 */
public class SystemSettingsBridge {
    /**
     * The tag for log messages
     */
    private final static String TAG = "UpdaterSSB";

    /**
     * The method to get a system property.
     */
    private static final Method sGetMethod;

    static {
        Method temp = null;
        try {
            Class settingsClass = Class.forName("android.os.SystemProperties");
            temp = settingsClass.getMethod("get", new Class[] {String.class, String.class});
        } catch(ClassNotFoundException cnfe) {
            Log.e(TAG, "Unable to access SystemProperties", cnfe);
        } catch(NoSuchMethodException nsme) {
            Log.e(TAG, "Unable to access SystemProperties", nsme);
        }
        sGetMethod = temp;
    }

    /**
     * Get a system property.
     *
     * @param property The name of the property to get.
     * @return The value of the property, or defaultValue if the value has not been set or can't be fetched.
     */
    public static String getSystemProperty(final String property, final String defaultValue) {
        if(sGetMethod == null) {
            throw new RuntimeException("Unable to get system values");
        }

        try {
            return (String) sGetMethod.invoke(null, new Object[]{property, defaultValue});
        } catch (IllegalAccessException e) {
            Log.e(TAG, "Failure setting property", e);
        } catch (InvocationTargetException e) {
            Log.e(TAG, "Failure setting property", e);
        }

        return defaultValue;
    }
}
