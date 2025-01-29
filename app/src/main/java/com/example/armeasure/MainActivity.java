package com.example.armeasure;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Mat;
import org.opencv.core.MatOfKeyPoint;
import org.opencv.features2d.Feature2D;
import org.opencv.features2d.Features2d;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private PreviewView viewFinder;
    private DrawView drawView;
    private TextView distanceText;
    private TextView debugText;
    private Button resetButton;

    private Camera camera1, camera2;
    private ImageAnalysis imageAnalysis1, imageAnalysis2;
    private Mat currentFrame1, currentFrame2;
    private static final float BASELINE_DISTANCE = 20.0f; // 估计的两个相机间距，单位：mm

    private static final int PERMISSION_REQUEST_CAMERA = 1001;
    private static final String[] REQUIRED_PERMISSIONS = new String[] {
            Manifest.permission.CAMERA
    };

    // 存储两个物理相机的ID
    private String mainPhysicalId = "2";  // 根据之前检测到的物理相机ID
    private String secondPhysicalId = "3"; // 根据之前检测到的物理相机ID

    private float focalLength; // 焦距，单位：mm
    private float pixelSize;   // 像素尺寸，单位：mm

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate()");
        setContentView(R.layout.activity_main);

        // 初始化OpenCV
        Log.d(TAG, "Attempting to load OpenCV...");
        boolean openCVLoaded = OpenCVLoader.initDebug();
        Log.d(TAG, "OpenCVLoader.initDebug() returned: " + openCVLoaded);
        if (!openCVLoaded) {
            Log.e(TAG, "Unable to load OpenCV!");
            debugText.append("\nOpenCV failed to load");
        } else {
            Log.d(TAG, "OpenCV loaded successfully!");
            debugText.append("\nOpenCV loaded successfully");
        }

        // 初始化视图
        viewFinder = findViewById(R.id.viewFinder);
        drawView = findViewById(R.id.drawView);
        distanceText = findViewById(R.id.distanceText);
        debugText = findViewById(R.id.debugText);
        resetButton = findViewById(R.id.resetButton);

        setupUI();
        getCameraCharacteristics();

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CAMERA);
        } else {
            startCamera();
        }
    }

    private boolean allPermissionsGranted() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CAMERA) {
            if (allPermissionsGranted()) {
                startCamera();
            }
        }
    }

    private void getCameraCharacteristics() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIdList = manager.getCameraIdList();

            for (String cameraId : cameraIdList) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    // 检查是否是逻辑相机
                    int[] capabilities = characteristics.get(
                            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    boolean isLogicalCamera = false;
                    for (int capability : capabilities) {
                        if (capability == CameraCharacteristics
                                .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                            isLogicalCamera = true;
                            break;
                        }
                    }

                    if (isLogicalCamera && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        Set<String> physicalCameraIds = characteristics.getPhysicalCameraIds();
                        debugText.append("\nFound physical cameras: " + physicalCameraIds.toString());
                    } else {
                        // 对于较低版本的Android，使用默认的相机ID
                        mainPhysicalId = "0";
                        secondPhysicalId = "1";
                        debugText.append("\nUsing default camera IDs for older Android version");
                    }

                    // 获取相机参数
                    float[] focalLengths = characteristics.get(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                    if (focalLengths != null && focalLengths.length > 0) {
                        focalLength = focalLengths[0];
                    }

                    // 获取像素尺寸
                    android.util.SizeF sensorSize = characteristics.get(
                            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                    if (sensorSize != null) {
                        android.util.Size arraySize = characteristics.get(
                                CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                        pixelSize = sensorSize.getWidth() / arraySize.getWidth();
                    }
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setupUI() {
        resetButton.setOnClickListener(v -> {
            drawView.reset();
            distanceText.setText("Select first point");
            debugText.setText("");
            if (currentFrame1 != null) currentFrame1.release();
            if (currentFrame2 != null) currentFrame2.release();
            currentFrame1 = null;
            currentFrame2 = null;
        });

        drawView.setDistanceCallback((startX, startY, endX, endY, distance) -> {
            if (currentFrame1 != null && currentFrame2 != null) {
                calculateStereoDistance(startX, startY, endX, endY);
            }
        });
    }

    private void startCamera() {
        try {
            ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                    ProcessCameraProvider.getInstance(this);

            cameraProviderFuture.addListener(() -> {
                try {
                    ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                    try {
                        // First try with detected physical camera IDs
                        setupDualCamera(cameraProvider);
                    } catch (Exception e) {
                        Log.e(TAG, "Dual camera setup failed with physical IDs, trying default IDs", e);
                        debugText.append("\nDual camera error (physical IDs): " + e.getMessage());
                        
                        // Try with default camera IDs
                        mainPhysicalId = "0";
                        secondPhysicalId = "1";
                        try {
                            setupDualCamera(cameraProvider);
                        } catch (Exception e2) {
                            Log.e(TAG, "Dual camera setup failed with default IDs, trying single camera", e2);
                            debugText.append("\nDual camera error (default IDs): " + e2.getMessage());
                            setupSingleCamera(cameraProvider);
                        }
                    }
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Failed to get camera provider", e);
                    debugText.append("\nCamera provider error: " + e.getMessage());
                    showCameraError();
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error in camera initialization", e);
                    debugText.append("\nUnexpected error: " + e.getMessage());
                    showCameraError();
                }
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize camera future", e);
            debugText.append("\nCamera future error: " + e.getMessage());
            showCameraError();
        }
    }

    private void setupSingleCamera(ProcessCameraProvider cameraProvider) {
        try {
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

            imageAnalysis1 = new ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis1.setAnalyzer(ContextCompat.getMainExecutor(this),
                    this::processFirstCameraImage);

            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            cameraProvider.unbindAll();
            camera1 = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis1);
            
            debugText.append("\nSingle camera initialized");
        } catch (Exception e) {
            Log.e(TAG, "Single camera setup failed", e);
            debugText.append("\nSingle camera error: " + e.getMessage());
            showCameraError();
        }
    }

    private void showCameraError() {
        runOnUiThread(() -> {
            debugText.append("\nCamera initialization failed. Please restart the app.");
            distanceText.setText("Camera Error");
        });
    }

    private void setupDualCamera(ProcessCameraProvider cameraProvider) {
        try {
            // 配置第一个相机
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

            imageAnalysis1 = new ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            imageAnalysis2 = new ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();

            // 设置图像分析器
            imageAnalysis1.setAnalyzer(ContextCompat.getMainExecutor(this),
                    this::processFirstCameraImage);
            imageAnalysis2.setAnalyzer(ContextCompat.getMainExecutor(this),
                    this::processSecondCameraImage);

            // 创建相机选择器
            CameraSelector cameraSelector1 = createCameraSelector(mainPhysicalId);
            CameraSelector cameraSelector2 = createCameraSelector(secondPhysicalId);

            // 绑定用例到生命周期
            cameraProvider.unbindAll();
            camera1 = cameraProvider.bindToLifecycle(this, cameraSelector1, preview, imageAnalysis1);
            camera2 = cameraProvider.bindToLifecycle(this, cameraSelector2, imageAnalysis2);

            debugText.append("\nBoth cameras initialized");
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
            debugText.append("\nCamera setup error: " + e.getMessage());
            throw e; // Rethrow to trigger fallback to single camera
        }
    }

    private CameraSelector createCameraSelector(String physicalCameraId) {
        return new CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .addCameraFilter(cameraInfos -> {
                    List<CameraInfo> filtered = new ArrayList<>();
                    try {
                        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                        for (CameraInfo info : cameraInfos) {
                            // Get the camera ID from CameraInfo using reflection
                            java.lang.reflect.Method getIdMethod = info.getClass()
                                    .getMethod("getCameraId");
                            String cameraId = (String) getIdMethod.invoke(info);
                            
                            // Get camera characteristics
                            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                            
                            // Check if this is the physical camera we want
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                Set<String> physicalIds = characteristics.getPhysicalCameraIds();
                                if (physicalIds.contains(physicalCameraId)) {
                                    filtered.add(info);
                                }
                            } else {
                                // For older versions, just use the first two back cameras
                                if ((physicalCameraId.equals("2") && filtered.size() == 0) ||
                                    (physicalCameraId.equals("3") && filtered.size() == 1)) {
                                    filtered.add(info);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error filtering cameras", e);
                    }
                    return filtered;
                })
                .build();
    }

    private void processFirstCameraImage(ImageProxy image) {
        try {
            if (currentFrame1 != null) {
                currentFrame1.release();
            }
            currentFrame1 = imageToMat(image);
        } finally {
            image.close();
        }
    }

    private void processSecondCameraImage(ImageProxy image) {
        try {
            if (currentFrame2 != null) {
                currentFrame2.release();
            }
            currentFrame2 = imageToMat(image);
        } finally {
            image.close();
        }
    }

    private Mat imageToMat(ImageProxy image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        Mat mat = new Mat();
        mat.put(0, 0, bytes);
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY);
        return mat;
    }

    private void calculateStereoDistance(float startX, float startY, float endX, float endY) {
        if (currentFrame1 == null || currentFrame2 == null) {
            debugText.append("\nNo frames available");
            return;
        }

        try {
            // 创建ORB特征检测器
            org.opencv.features2d.ORB featureDetector = org.opencv.features2d.ORB.create();
            
            // 设置ORB参数
            featureDetector.setFastThreshold(20);
            featureDetector.setMaxFeatures(1000);
            featureDetector.setScaleFactor(2);
            featureDetector.setNLevels(8);

            // 在两幅图像中检测特征点
            MatOfKeyPoint keypoints1 = new MatOfKeyPoint();
            Mat descriptors1 = new Mat();
            featureDetector.detectAndCompute(currentFrame1, new Mat(), keypoints1, descriptors1);

            MatOfKeyPoint keypoints2 = new MatOfKeyPoint();
            Mat descriptors2 = new Mat();
            featureDetector.detectAndCompute(currentFrame2, new Mat(), keypoints2, descriptors2);

            // 获取选择点附近的特征点
            org.opencv.core.Point startPoint1 = getNearestKeypoint(keypoints1, startX, startY);
            org.opencv.core.Point startPoint2 = findMatchingPoint(descriptors1, descriptors2,
                    keypoints1, keypoints2, startPoint1);

            org.opencv.core.Point endPoint1 = getNearestKeypoint(keypoints1, endX, endY);
            org.opencv.core.Point endPoint2 = findMatchingPoint(descriptors1, descriptors2,
                    keypoints1, keypoints2, endPoint1);

            if (startPoint1 != null && startPoint2 != null && endPoint1 != null && endPoint2 != null) {
                // 计算视差
                double startDisparity = Math.abs(startPoint1.x - startPoint2.x);
                double endDisparity = Math.abs(endPoint1.x - endPoint2.x);

                // 使用三角测量计算深度
                double startDepth = (focalLength * BASELINE_DISTANCE) / startDisparity;
                double endDepth = (focalLength * BASELINE_DISTANCE) / endDisparity;

                // 计算3D点的坐标
                double startX3D = (startPoint1.x * startDepth) / focalLength;
                double startY3D = (startPoint1.y * startDepth) / focalLength;
                double startZ3D = startDepth;

                double endX3D = (endPoint1.x * endDepth) / focalLength;
                double endY3D = (endPoint1.y * endDepth) / focalLength;
                double endZ3D = endDepth;

                // 计算3D空间中的欧氏距离
                double distance = Math.sqrt(
                        Math.pow(endX3D - startX3D, 2) +
                                Math.pow(endY3D - startY3D, 2) +
                                Math.pow(endZ3D - startZ3D, 2)
                );

                // 转换为厘米并显示
                final String distanceStr = String.format("Distance: %.1f cm", distance / 10.0);
                runOnUiThread(() -> {
                    distanceText.setText(distanceStr);
                    debugText.append("\nDisparity: " + startDisparity + ", " + endDisparity);
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in stereo calculation", e);
            debugText.append("\nCalculation error: " + e.getMessage());
        }
    }

    private org.opencv.core.Point getNearestKeypoint(MatOfKeyPoint keypoints, float x, float y) {
        org.opencv.core.KeyPoint[] keypointArray = keypoints.toArray();
        org.opencv.core.KeyPoint nearest = null;
        double minDistance = Double.MAX_VALUE;

        for (org.opencv.core.KeyPoint kp : keypointArray) {
            double distance = Math.sqrt(
                    Math.pow(kp.pt.x - x, 2) +
                            Math.pow(kp.pt.y - y, 2)
            );
            if (distance < minDistance) {
                minDistance = distance;
                nearest = kp;
            }
        }

        return nearest != null ? nearest.pt : null;
    }

    private org.opencv.core.Point findMatchingPoint(
            Mat descriptors1, Mat descriptors2,
            MatOfKeyPoint keypoints1, MatOfKeyPoint keypoints2,
            org.opencv.core.Point point1) {

        // 使用BFMatcher with Hamming distance for ORB
        org.opencv.features2d.DescriptorMatcher matcher =
                org.opencv.features2d.DescriptorMatcher.create(
                        org.opencv.features2d.DescriptorMatcher.BRUTEFORCE_HAMMING);

        // 对每个关键点找到最佳匹配
        java.util.List<org.opencv.core.MatOfDMatch> knnMatches = new ArrayList<>();
        matcher.knnMatch(descriptors1, descriptors2, knnMatches, 2);

        // 应用比率测试
        org.opencv.core.KeyPoint[] kpts1 = keypoints1.toArray();
        org.opencv.core.KeyPoint[] kpts2 = keypoints2.toArray();

        for (org.opencv.core.MatOfDMatch matchesSet : knnMatches) {
            org.opencv.core.DMatch[] matches = matchesSet.toArray();
            if (matches.length < 2) continue;

            if (matches[0].distance < 0.7f * matches[1].distance) {
                if (kpts1[matches[0].queryIdx].pt.equals(point1)) {
                    return kpts2[matches[0].trainIdx].pt;
                }
            }
        }
        return null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "onStart()");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume()");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause()");
        if (currentFrame1 != null) {
            currentFrame1.release();
            currentFrame1 = null;
        }
        if (currentFrame2 != null) {
            currentFrame2.release();
            currentFrame2 = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentFrame1 != null) {
            currentFrame1.release();
        }
        if (currentFrame2 != null) {
            currentFrame2.release();
        }
    }
}
