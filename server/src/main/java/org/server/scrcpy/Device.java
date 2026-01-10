package org.server.scrcpy;

import org.server.scrcpy.device.Point;
import android.os.Build;
import android.os.RemoteException;
import android.os.IBinder;
import android.view.IRotationWatcher;
import android.view.InputEvent;

import org.server.scrcpy.wrappers.ServiceManager;
import org.server.scrcpy.wrappers.DisplayControl;
import org.server.scrcpy.wrappers.SurfaceControl;

public final class Device {

    // private final ServiceManager serviceManager = new ServiceManager();
    private ScreenInfo screenInfo;
    private RotationListener rotationListener;

    private static final boolean USE_ANDROID_15_DISPLAY_POWER = false;

    public Device(Options options) {
        screenInfo = computeScreenInfo(options.getMaxSize());
        registerRotationWatcher(new IRotationWatcher.Stub() {
            @Override
            public void onRotationChanged(int rotation) throws RemoteException {
                synchronized (Device.this) {
                    screenInfo = screenInfo.withRotation(rotation);

                    // notify
                    if (rotationListener != null) {
                        rotationListener.onRotationChanged(rotation);
                    }
                }
            }
        });
    }

    public static String getDeviceName() {
        return Build.MODEL;
    }

    public synchronized ScreenInfo getScreenInfo() {
        return screenInfo;
    }

    @SuppressWarnings("checkstyle:MagicNumber")
    private ScreenInfo computeScreenInfo(int maxSize) {
        // Compute the video size and the padding of the content inside this video.
        // Principle:
        // - scale down the great side of the screen to maxSize (if necessary);
        // - scale down the other side so that the aspect ratio is preserved;
        // - round this value to the nearest multiple of 8 (H.264 only accepts multiples of 8)
        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo();
        boolean rotated = (displayInfo.getRotation() & 1) != 0;
        Size deviceSize = displayInfo.getSize();
        int w = deviceSize.getWidth() & ~7; // in case it's not a multiple of 8
        int h = deviceSize.getHeight() & ~7;
        if (maxSize > 0) {
            if (BuildConfig.DEBUG && maxSize % 8 != 0) {
                throw new AssertionError("Max size must be a multiple of 8");
            }
            boolean portrait = h > w;
            int major = portrait ? h : w;
            int minor = portrait ? w : h;
            if (major > maxSize) {
                int minorExact = minor * maxSize / major;
                // +4 to round the value to the nearest multiple of 8
                minor = (minorExact + 4) & ~7;
                major = maxSize;
            }
            w = portrait ? minor : major;
            h = portrait ? major : minor;
        }
        Size videoSize = new Size(w, h);
        return new ScreenInfo(deviceSize, videoSize, rotated);
    }

    public Point getPhysicalPoint(Position position) {
        @SuppressWarnings("checkstyle:HiddenField") // it hides the field on purpose, to read it with a lock
                ScreenInfo screenInfo = getScreenInfo(); // read with synchronization
        Size videoSize = screenInfo.getVideoSize();
        Size clientVideoSize = position.getScreenSize();
        if (!videoSize.equals(clientVideoSize)) {
            // The client sends a click relative to a video with wrong dimensions,
            // the device may have been rotated since the event was generated, so ignore the event
            return null;
        }
        Size deviceSize = screenInfo.getDeviceSize();
        Point point = position.getPoint();
        int scaledX = point.getX() * deviceSize.getWidth() / videoSize.getWidth();
        int scaledY = point.getY() * deviceSize.getHeight() / videoSize.getHeight();
        return new Point(scaledX, scaledY);
    }

    public boolean injectInputEvent(InputEvent inputEvent, int mode) {
        return ServiceManager.getInputManager().injectInputEvent(inputEvent, mode);
    }

    public boolean isScreenOn() {
        return ServiceManager.getPowerManager().isScreenOn(0);
    }

    public boolean setDisplayPower(boolean on) {
        return setDisplayPower(0, on);
    }

    private boolean setDisplayPower(int displayId, boolean on) {
        if (USE_ANDROID_15_DISPLAY_POWER && Build.VERSION.SDK_INT >= 35) {
            return ServiceManager.getDisplayManager().requestDisplayPower(displayId, on);
        }

        boolean applyToMultiPhysicalDisplays = Build.VERSION.SDK_INT >= 29;

        if (applyToMultiPhysicalDisplays
                && Build.VERSION.SDK_INT >= 34
                && Build.BRAND.equalsIgnoreCase("honor")
                && SurfaceControl.hasGetBuildInDisplayMethod()) {
            applyToMultiPhysicalDisplays = false;
        }

        int mode = on ? SurfaceControl.POWER_MODE_NORMAL : SurfaceControl.POWER_MODE_OFF;
        if (applyToMultiPhysicalDisplays) {
            boolean useDisplayControl = Build.VERSION.SDK_INT >= 34 && !SurfaceControl.hasGetPhysicalDisplayIdsMethod();

            long[] physicalDisplayIds = useDisplayControl ? DisplayControl.getPhysicalDisplayIds() : SurfaceControl.getPhysicalDisplayIds();
            if (physicalDisplayIds == null) {
                Ln.e("Could not get physical display ids");
                return false;
            }

            boolean allOk = true;
            for (long physicalDisplayId : physicalDisplayIds) {
                IBinder binder = useDisplayControl ? DisplayControl.getPhysicalDisplayToken(physicalDisplayId)
                        : SurfaceControl.getPhysicalDisplayToken(physicalDisplayId);
                allOk &= SurfaceControl.setDisplayPowerMode(binder, mode);
            }
            return allOk;
        }

        IBinder d = SurfaceControl.getBuiltInDisplay();
        if (d == null) {
            Ln.e("Could not get built-in display");
            return false;
        }
        return SurfaceControl.setDisplayPowerMode(d, mode);
    }

    public void registerRotationWatcher(IRotationWatcher rotationWatcher) {
        ServiceManager.getWindowManager().registerRotationWatcher(rotationWatcher);
    }

    public synchronized void setRotationListener(RotationListener rotationListener) {
        this.rotationListener = rotationListener;
    }

    public Point NewgetPhysicalPoint(Point point) {
        @SuppressWarnings("checkstyle:HiddenField") // it hides the field on purpose, to read it with a lock
                ScreenInfo screenInfo = getScreenInfo(); // read with synchronization
        Size videoSize = screenInfo.getVideoSize();
//        Size clientVideoSize = position.getScreenSize();

        Size deviceSize = screenInfo.getDeviceSize();
//        Point point = position.getPoint();
        int scaledX = point.getX() * deviceSize.getWidth() / videoSize.getWidth();
        int scaledY = point.getY() * deviceSize.getHeight() / videoSize.getHeight();
        return new Point(scaledX, scaledY);
    }


    public interface RotationListener {
        void onRotationChanged(int rotation);
    }

}
