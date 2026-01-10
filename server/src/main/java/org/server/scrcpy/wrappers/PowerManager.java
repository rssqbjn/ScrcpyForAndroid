package org.server.scrcpy.wrappers;

import android.os.Build;
import android.os.IInterface;

import org.server.scrcpy.Ln;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
public final class PowerManager {
    private final IInterface manager;
    private Method isDisplayInteractiveMethod;
    private Method isInteractiveMethod;
    private Method isScreenOnMethod;

    public PowerManager(IInterface manager) {
        this.manager = manager;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                isDisplayInteractiveMethod = manager.getClass().getMethod("isDisplayInteractive", int.class);
            }
        } catch (NoSuchMethodException ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                isInteractiveMethod = manager.getClass().getMethod("isInteractive");
            }
        } catch (NoSuchMethodException ignored) {
        }
        try {
            isScreenOnMethod = manager.getClass().getMethod("isScreenOn");
        } catch (NoSuchMethodException ignored) {
        }
    }

    public boolean isScreenOn(int displayId) {
        if (Build.VERSION.SDK_INT >= 34 && isDisplayInteractiveMethod != null) {
            try {
                return (Boolean) isDisplayInteractiveMethod.invoke(manager, displayId);
            } catch (InvocationTargetException | IllegalAccessException e) {
                Ln.e("PowerManager.isDisplayInteractive failed", e);
            }
        }
        if (isInteractiveMethod != null) {
            try {
                return (Boolean) isInteractiveMethod.invoke(manager);
            } catch (InvocationTargetException | IllegalAccessException e) {
                Ln.e("PowerManager.isInteractive failed", e);
            }
        }
        if (isScreenOnMethod != null) {
            try {
                return (Boolean) isScreenOnMethod.invoke(manager);
            } catch (InvocationTargetException | IllegalAccessException e) {
                Ln.e("PowerManager.isScreenOn failed", e);
            }
        }
        return false;
    }

    public boolean isScreenOn() {
        return isScreenOn(0);
    }
}
