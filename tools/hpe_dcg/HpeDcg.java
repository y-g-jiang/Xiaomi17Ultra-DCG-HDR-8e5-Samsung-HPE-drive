package org.jc.hpe;

import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.Arrays;

public final class HpeDcg {
    public static final String CAMERA_ID = "4";
    public static final int WIDTH = 4080, HEIGHT = 3072, ISO = 20;
    private static final String MODE = "org.codeaurora.qcamera3.sensor_meta_data.current_mode";
    private static final String DCG = "org.codeaurora.qcamera3.sessionParameters.EnableHDRDCGMode";
    private static final String EXPOSURES = "org.codeaurora.qcamera3.sessionParameters.numHDRexposure";
    private static final String LOFIC = "com.xiaomi.sessionparams.enableLofic";

    private HpeDcg() {}

    public static CameraCharacteristics characteristics(CameraManager manager) throws Exception {
        CameraCharacteristics c = manager.getCameraCharacteristics(CAMERA_ID);
        android.util.Range<Integer> iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        android.util.Size size = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (iso == null || iso.getLower() != ISO || size == null ||
                size.getWidth() != WIDTH || size.getHeight() != HEIGHT)
            throw new IOException("HPE camera mismatch");
        return c;
    }

    @SuppressWarnings("MissingPermission")
    public static void open(CameraManager manager, CameraDevice.StateCallback callback,
                            Handler handler) throws Exception {
        characteristics(manager);
        manager.openCamera(CAMERA_ID, callback, handler);
    }

    public static ImageReader reader() {
        return ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.RAW_SENSOR, 3, 1048579L);
    }

    private static <T> void set(CaptureRequest.Builder b, String name, Class<T> type, T value) {
        b.set(new CaptureRequest.Key<T>(name, type), value);
    }

    public static CaptureRequest.Builder request(CameraDevice device, ImageReader raw,
            Surface preview, boolean rawRoute, boolean still, long exposureNs,
            float lockedFocus, float opticalRatio) throws Exception {
        if (exposureNs < 100_000L || exposureNs > 1_000_000_000L ||
                !Float.isFinite(lockedFocus) || lockedFocus < 0 ||
                !Float.isFinite(opticalRatio) || opticalRatio < 1 || opticalRatio > 4.3f)
            throw new IllegalArgumentException("Invalid HPE controls");
        CaptureRequest.Builder b = device.createCaptureRequest(still ?
                CameraDevice.TEMPLATE_STILL_CAPTURE : CameraDevice.TEMPLATE_PREVIEW);
        b.set(CaptureRequest.CONTROL_AE_MODE, rawRoute ? 0 : 1);
        b.set(CaptureRequest.SENSOR_SENSITIVITY, ISO);
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.max(33_333_333L, exposureNs));
        b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100);
        b.set(CaptureRequest.CONTROL_AF_MODE, rawRoute ?
                CaptureRequest.CONTROL_AF_MODE_OFF : CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (rawRoute) b.set(CaptureRequest.LENS_FOCUS_DISTANCE, lockedFocus);
        b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 1);
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 1f);
        b.set(CaptureRequest.FLASH_MODE, 0);
        set(b, MODE, int[].class, new int[]{rawRoute ? 6 : 4});
        set(b, DCG, int[].class, new int[]{rawRoute ? 3588 : 0});
        set(b, EXPOSURES, int[].class, new int[]{1});
        set(b, LOFIC, int[].class, new int[]{0});
        if (opticalRatio != 1f) {
            set(b, "xiaomi.zoomRatio.debug.zoomRatioDebugEnable", int[].class, new int[]{1});
            set(b, "xiaomi.zoomRatio.debug.setZoomRatioCode", float[].class, new float[]{opticalRatio});
            set(b, "com.xiaomi.camera.userZoomRatio.userZoomRatio", float[].class, new float[]{opticalRatio});
            set(b, "xiaomi.snapshot.userZoomRatio", float[].class, new float[]{opticalRatio});
        }
        if (!rawRoute) b.addTarget(preview);
        if (rawRoute || still) b.addTarget(raw.getSurface());
        return b;
    }

    public static void configure(CameraDevice device, ImageReader raw, Surface preview,
            CaptureRequest parameters, CameraCaptureSession.StateCallback callback,
            Handler handler) throws Exception {
        SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                Arrays.asList(new OutputConfiguration(preview), new OutputConfiguration(raw.getSurface())),
                command -> handler.post(command), callback);
        config.setSessionParameters(parameters);
        device.createCaptureSession(config);
    }

    public static int actualMode(CaptureResult r) {
        byte[] m = r.get(new CaptureResult.Key<byte[]>(
                "org.quic.camera2.properties_sensor.SensorCurrentMode", byte[].class));
        if (m != null && m.length == 1) return m[0] & 255;
        int[] a = r.get(new CaptureResult.Key<int[]>(MODE, int[].class));
        return a != null && a.length == 1 ? a[0] : -1;
    }

    public static float lockedFocus(CaptureResult a, CaptureResult b, CaptureResult c) throws IOException {
        Float previous = null;
        for (CaptureResult r : new CaptureResult[]{a, b, c}) {
            Float d = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
            if (actualMode(r) != 4 || !Integer.valueOf(4).equals(r.get(CaptureResult.CONTROL_AF_STATE)) ||
                    !Integer.valueOf(0).equals(r.get(CaptureResult.LENS_STATE)) ||
                    d == null || !Float.isFinite(d) || d < 0 ||
                    previous != null && Math.abs(d - previous) > .01f)
                throw new IOException("Focus not locked");
            previous = d;
        }
        if (a.getFrameNumber() >= b.getFrameNumber() || b.getFrameNumber() >= c.getFrameNumber())
            throw new IOException("Focus frames out of order");
        return previous;
    }

    public static void checkResult(CaptureResult r, long exposureNs, float focus,
                                   float opticalRatio) throws IOException {
        Long exposure = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Float actualFocus = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (actualMode(r) != 6 || !Integer.valueOf(ISO).equals(r.get(CaptureResult.SENSOR_SENSITIVITY)) ||
                exposure == null || Math.abs(exposure - exposureNs) > 20_000L ||
                actualFocus == null || !HpeRaw.matches(focus, actualFocus))
            throw new IOException("HPE result mismatch");
        if (opticalRatio != 1f) {
            float[] ratio = r.get(new CaptureResult.Key<float[]>(
                    "com.xiaomi.optical.zoom.opticalZoomCurrentRatio", float[].class));
            if (ratio == null || ratio.length != 1 || !Float.isFinite(ratio[0]) ||
                    Math.abs(ratio[0] - opticalRatio) > .02f)
                throw new IOException("Optical ratio mismatch");
        }
    }

    public static byte[] pixels(Image image, CaptureResult result, long startedTimestamp,
            long exposureNs, float focus, float opticalRatio) throws IOException {
        checkResult(result, exposureNs, focus, opticalRatio);
        Long timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP);
        if (image.getFormat() != ImageFormat.RAW_SENSOR || timestamp == null ||
                startedTimestamp <= 0 || image.getTimestamp() != timestamp || timestamp != startedTimestamp)
            throw new IOException("RAW timestamp mismatch");
        Image.Plane p = image.getPlanes()[0];
        ByteBuffer buffer = p.getBuffer().duplicate();
        byte[] data = new byte[buffer.remaining()];
        buffer.get(data);
        HpeRaw.check(data, image.getWidth(), image.getHeight(), p.getRowStride(), p.getPixelStride());
        return data;
    }

    public static void writeDng(File file, CameraCharacteristics sensor, CaptureResult result,
                                Image image, int orientation) throws IOException {
        if (actualMode(result) != 6) throw new IOException("Not HPE DCG");
        try (DngCreator d = new DngCreator(sensor, result); FileOutputStream out = new FileOutputStream(file)) {
            d.setOrientation(orientation);
            d.writeImage(out, image);
        }
        HpeRaw.whiteLevel(file);
    }
}
