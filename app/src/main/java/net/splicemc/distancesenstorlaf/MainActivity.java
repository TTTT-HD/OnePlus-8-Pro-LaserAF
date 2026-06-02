package net.splicemc.distancesenstorlaf;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "DistanceSensor";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private static final int MAX_VALID_DISTANCE = 8190;
    private static final int SMOOTHING_WINDOW_SIZE = 10;
    private static final long OUT_OF_RANGE_DELAY_MS = 1000;
    
    private TextView statusTextView;
    private Button smoothingButton;
    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private boolean isSmoothingEnabled = false;
    private final ArrayList<Integer> distanceHistory = new ArrayList<>();
    
    private long lastValidTimestamp = 0;
    private Integer lastValidDistance = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusTextView = findViewById(R.id.statusTextView);
        smoothingButton = findViewById(R.id.smoothingButton);
        textureView = findViewById(R.id.textureView);

        smoothingButton.setOnClickListener(v -> {
            isSmoothingEnabled = !isSmoothingEnabled;
            smoothingButton.setText(isSmoothingEnabled ? "Smoothing: ON" : "Smoothing: OFF");
            synchronized (distanceHistory) {
                distanceHistory.clear();
            }
        });

        if (checkCameraPermission()) {
            startBackgroundThread();
            if (textureView.isAvailable()) {
                openCamera();
            } else {
                textureView.setSurfaceTextureListener(textureListener);
            }
        } else {
            requestCameraPermission();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("CameraBackground");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startBackgroundThread();
                openCamera();
            } else {
                statusTextView.setText("Camera permission denied");
            }
        }
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}
        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) { return true; }
        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
    };

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0]; // Default to first camera
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(640, 480);
            Surface surface = new Surface(texture);

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        captureSession = session;
                        try {
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                            captureSession.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, backgroundHandler);
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        statusTextView.setText("Configuration failed");
                    }
                }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            processCaptureResult(result);
        }
    };

    private void processCaptureResult(TotalCaptureResult result) {
        // Search for vendor tags in the result
        List<CaptureResult.Key<?>> keys = result.getKeys();
        Integer rawDistance = null;
        
        for (CaptureResult.Key<?> key : keys) {
            if (key.getName().equals("com.oneplus.camera2.metadata.TOF_Value")) {
                Object value = result.get(key);
                if (value instanceof int[]) {
                    int[] vals = (int[]) value;
                    if (vals.length > 0) {
                        rawDistance = vals[0];
                    }
                } else if (value instanceof Integer) {
                    rawDistance = (Integer) value;
                }
                break;
            }
        }

        final Integer displayDistance;
        long currentTime = System.currentTimeMillis();

        if (rawDistance != null && rawDistance <= MAX_VALID_DISTANCE) {
            lastValidTimestamp = currentTime;
            if (isSmoothingEnabled) {
                synchronized (distanceHistory) {
                    distanceHistory.add(rawDistance);
                    if (distanceHistory.size() > SMOOTHING_WINDOW_SIZE) {
                        distanceHistory.remove(0);
                    }
                    int sum = 0;
                    for (int d : distanceHistory) sum += d;
                    lastValidDistance = sum / distanceHistory.size();
                }
            } else {
                lastValidDistance = rawDistance;
            }
            displayDistance = lastValidDistance;
        } else {
            // Raw distance is null or > MAX_VALID_DISTANCE
            if (currentTime - lastValidTimestamp < OUT_OF_RANGE_DELAY_MS) {
                // Use last valid value during delay to prevent flickering
                displayDistance = lastValidDistance;
            } else {
                displayDistance = -1; // Out of range for real
            }
        }

        runOnUiThread(() -> {
            if (displayDistance != null) {
                if (displayDistance == -1) {
                    statusTextView.setText("Distance: Out of Range");
                } else {
                    statusTextView.setText(String.format("Distance: %d mm", displayDistance));
                }
            } else {
                statusTextView.setText("Distance: Scanning...");
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
        }
    }
}
