/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.eszdman.photoncamera.api;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import com.eszdman.photoncamera.AutoFitTextureView;
import com.eszdman.photoncamera.ImageProcessing;
import com.eszdman.photoncamera.Parameters.ExposureIndex;
import com.eszdman.photoncamera.Parameters.FrameNumberSelector;
import com.eszdman.photoncamera.Parameters.IsoExpoSelector;
import com.eszdman.photoncamera.R;
import com.eszdman.photoncamera.SurfaceViewOverViewfinder;
import com.eszdman.photoncamera.gallery.GalleryActivity;
import com.eszdman.photoncamera.ui.MainActivity;
import com.eszdman.photoncamera.ui.SettingsActivity;
import com.eszdman.photoncamera.util.CustomLogger;
import com.eszdman.photoncamera.util.FileManager;
import com.manual.ManualModeImpl;
import rapid.decoder.BitmapDecoder;

import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT;

@SuppressWarnings("rawtypes")
public class CameraFragment extends Fragment
        implements View.OnClickListener, ActivityCompat.OnRequestPermissionsResultCallback {

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    private static final String FRAGMENT_DIALOG = "dialog";
    private Size target;
    private final Field[] metadataFields = CameraReflectionApi.getAllMetadataFields();


    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Tag for the {@link Log}.
     */
    private static final String TAG = "Camera2Api";

    /**
     * Camera state: Showing camera preview.
     */
    private static final int STATE_PREVIEW = 0;

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private static final int STATE_WAITING_LOCK = 1;

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private static final int STATE_WAITING_PRECAPTURE = 2;

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private static final int STATE_WAITING_NON_PRECAPTURE = 3;

    /**
     * Camera state: Picture was taken.
     */
    private static final int STATE_PICTURE_TAKEN = 4;

    private static final int STATE_CLOSED = 5;

    /**
     * Max preview width that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_WIDTH = 1920;

    /**
     * Max preview height that is guaranteed by Camera2 API
     */
    private static final int MAX_PREVIEW_HEIGHT = 1080;

    public static CameraCharacteristics mCameraCharacteristics;
    public static CaptureResult mCaptureResult;
    public static final int rawFormat = ImageFormat.RAW_SENSOR;
    public static final int yuvFormat = ImageFormat.YUV_420_888;
    public static final int prevFormat = ImageFormat.YUV_420_888;
    public static int mTargetFormat = rawFormat;
    public static final int mPreviewTargetFormat = prevFormat;
    public static CaptureResult mPreviewResult;
    public long mPreviewExposuretime;
    public int mPreviewIso;
    public Rational[] mPreviewTemp;
    public ColorSpaceTransform mColorSpaceTransform;
    Range FpsRangeDef;
    Range FpsRangeHigh;
    private float mFocus;
    /**
     * {@link TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
            configureTransform(width, height);
        }

        @Override

        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
        }

    };

    /**
     * ID of the current {@link CameraDevice}.
     */

    public String[] mCameraIds;

    /**
     * An {@link AutoFitTextureView} for camera preview.
     */
    public AutoFitTextureView mTextureView;
    public SurfaceViewOverViewfinder surfaceView;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    public CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    public CameraDevice mCameraDevice;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    public static CameraFragment context;

    public CameraFragment() {
        super();
        context = this;
    }

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }

    };
    /*An additional thread for running tasks that shouldn't block the UI.*/
    private HandlerThread mBackgroundThread;
    /*A {@link Handler} for running tasks in the background.*/
    public Handler mBackgroundHandler;
    /*An {@link ImageReader} that handles still image capture.*/
    public ImageReader mImageReaderPreview;
    public ImageReader mImageReaderRaw;
    /**
     * This a callback object for the {@link ImageReader}. "onImageAvailable" will be called when a
     * still image is ready to be saved.
     */
    private ImageSaver imageSaver;
    private final ImageReader.OnImageAvailableListener mOnYuvImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            //imageSaver.mImage = reader.acquireNextImage();
            //imageSaver.Process(reader);
            Message msg = new Message();
            msg.obj = reader;
            imageSaver.ProcessCall.sendMessage(msg);
            //mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage()));
        }
    };
    private final ImageReader.OnImageAvailableListener mOnRawImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            //dequeueAndSaveImage(mRawResultQueue, mRawImageReader);
            //imageSaver.mImage = reader.acquireNextImage();
            Message msg = new Message();
            msg.obj = reader;
            imageSaver.ProcessCall.sendMessage(msg);
            //mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage()));
        }

    };
    /*{@link CaptureRequest.Builder} for the camera preview*/
    public CaptureRequest.Builder mPreviewRequestBuilder;
    public CaptureRequest mPreviewRequest;

    /**
     * The current state of camera state for taking pictures.
     */
    public int mState = STATE_PREVIEW;

    /**
     * Timer to use with pre-capture sequence to ensure a timely capture if 3A convergence is
     * taking too long.
     */
    private long mCaptureTimer;

    /**
     * Timeout for the pre-capture sequence.
     */
    private static final long PRECAPTURE_TIMEOUT_MS = 1000;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);

    /**
     * Whether the current camera device supports Flash or not.
     */
    private boolean mFlashSupported;
    public final boolean mFlashEnabled = false;

    /**
     * Orientation of the camera sensor
     */
    public int mSensorOrientation;
    int[] mCameraAfModes;
    public boolean is30Fps = true;
    /**
     * A {@link CameraCaptureSession.CaptureCallback} that handles events related to JPEG capture.
     */
    private final CameraCaptureSession.CaptureCallback mCaptureCallback
            = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result) {
            switch (mState) {
                case STATE_PREVIEW: {
                    // We have nothing to do when the camera preview is working normally.
                    //Log.v(TAG, "PREVIEW");
                    break;
                }
                case STATE_WAITING_LOCK: {
                    //Log.v(TAG, "WAITING_LOCK");
                    Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                    // If we haven't finished the pre-capture sequence but have hit our maximum
                    // wait timeout, too bad! Begin capture anyway.
                    if (hitTimeoutLocked()) {
                        Log.w(TAG, "Timed out waiting for pre-capture sequence to complete.");
                        mState = STATE_PICTURE_TAKEN;
                        mPreviewResult = result;
                        captureStillPicture();
                    }
                    if (afState == null) {
                        mState = STATE_PICTURE_TAKEN;
                        mPreviewResult = result;
                        captureStillPicture();
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState ||
                            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        if (aeState == null ||
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN;
                            mPreviewResult = result;
                            captureStillPicture();
                        } else {
                            runPrecaptureSequence();
                        }
                    }
                    break;
                }
                //TODO Check why this wrong
                /*case STATE_WAITING_PRECAPTURE: {
                    Log.v(TAG, "WAITING_PRECAPTURE");
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null ||
                            aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                            aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mState = STATE_WAITING_NON_PRECAPTURE;
                    }
                    break;
                }*/
                case STATE_WAITING_PRECAPTURE:
                case STATE_WAITING_NON_PRECAPTURE: {
                    //Log.v(TAG, "WAITING_NON_PRECAPTURE");
                    // CONTROL_AE_STATE can be null on some devices
                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN;
                        mPreviewResult = result;
                        captureStillPicture();
                    }
                    break;
                }
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                        @NonNull CaptureRequest request,
                                        @NonNull CaptureResult partialResult) {

            process(partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                       @NonNull CaptureRequest request,
                                       @NonNull TotalCaptureResult result) {
            Object exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Object iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Object focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
            Rational[] mtemp = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if(exposure != null) mPreviewExposuretime = (long)exposure;
            if(iso != null) mPreviewIso = (int)iso;
            if(focus != null) mFocus = (float)focus;
            mPreviewTemp = mtemp;
            mColorSpaceTransform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            process(result);
            updateScreenLog(result);
        }
        //Automatic 60fps preview
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
            super.onCaptureStarted(session, request, timestamp, frameNumber);
            if(frameNumber % 20 == 19){
                if(ExposureIndex.index() > 8.0){
                    if(!is30Fps) {
                        Log.d(TAG,"Changed preview target 30fps");
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,FpsRangeDef);
                        mPreviewRequest = mPreviewRequestBuilder.build();
                        rebuildPreview();
                        is30Fps = true;
                    }
                }
                if(ExposureIndex.index()+0.9 < 8.0) {
                    if(is30Fps && Interface.getSettings().fpsPreview && !mCameraDevice.getId().equals("1"))
                    {
                        Log.d(TAG,"Changed preview target 60fps");
                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,FpsRangeHigh);
                        mPreviewRequest = mPreviewRequestBuilder.build();
                        rebuildPreview();
                        is30Fps = false;
                    }

                }
            }
        }
    };

    void updateScreenLog(CaptureResult result) {
        CustomLogger cl = new CustomLogger(getActivity(), R.id.screen_log_focus);
        if (Interface.getSettings().afdata) {
            IsoExpoSelector.ExpoPair expoPair = IsoExpoSelector.GenerateExpoPair(0);
            LinkedHashMap<String, String> dataset = new LinkedHashMap<>();
            dataset.put("AF_MODE", getResultFieldName("CONTROL_AF_MODE_", result.get(CaptureResult.CONTROL_AF_MODE)));
            dataset.put("AF_TRIGGER", getResultFieldName("CONTROL_AF_TRIGGER_", result.get(CaptureResult.CONTROL_AF_TRIGGER)));
            dataset.put("AF_STATE", getResultFieldName("CONTROL_AF_STATE_", result.get(CaptureResult.CONTROL_AF_STATE)));
            dataset.put("FOCUS_DISTANCE", String.valueOf(result.get(CaptureResult.LENS_FOCUS_DISTANCE)));
            dataset.put("EXPOSURE_TIME", expoPair.ExposureString() + "s");
            dataset.put("EXPOSURE_TIME_CR", String.format(Locale.ROOT,"%.5f",result.get(CaptureResult.SENSOR_EXPOSURE_TIME).doubleValue()/1E9)+ "s");
            dataset.put("ISO", String.valueOf(expoPair.iso));
            dataset.put("ISO_CR", String.valueOf(result.get(CaptureResult.SENSOR_SENSITIVITY)));
            dataset.put("Shakeness", String.valueOf(Interface.getSensors().getShakeness()));
            dataset.put("FOCUS_RECT", Arrays.deepToString(result.get(CaptureResult.CONTROL_AF_REGIONS)));
            MeteringRectangle[] rectobj = result.get(CaptureResult.CONTROL_AF_REGIONS);
            if(rectobj != null && rectobj.length > 0) {
                RectF rect = getScreenRectFromMeteringRect(rectobj[0]);
                dataset.put("F_RECT(px)", rect.toString());
                surfaceView.update(rect);
            }
            cl.setVisibility(View.VISIBLE);
            cl.updateText(cl.createTextFrom(dataset));
        } else {
            if (surfaceView.rectToDraw != null) {
                surfaceView.rectToDraw = null;
                surfaceView.invalidate();
                cl.setVisibility(View.GONE);
            }
        }
    }

    private RectF getScreenRectFromMeteringRect(MeteringRectangle meteringRectangle) {
        if(mImageReaderPreview == null) return new RectF();
            float left = (((float) meteringRectangle.getY() / mImageReaderPreview.getHeight()) * (surfaceView.getWidth()));
            float top = (((float) meteringRectangle.getX() / mImageReaderPreview.getWidth())* (surfaceView.getHeight()));
            float width = (((float) meteringRectangle.getHeight() / mImageReaderPreview.getHeight()) * (surfaceView.getWidth()));
            float height = (((float) meteringRectangle.getWidth() / mImageReaderPreview.getWidth()) * (surfaceView.getHeight()));
            return new RectF(
                          left        , //Left
                          top         ,  //Top
                    left + width,//Right
                    top+height //Bottom
            );
    }

    private String getResultFieldName(String prefix, Integer value) {
        for (Field f : this.metadataFields)
            if (f.getName().startsWith(prefix)) {
                try {
                    if (f.getInt(f) == value)
                        return f.getName().replace(prefix, "").concat("(" + value + ")");
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        return "";
    }

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    public void showToast(final String text) {
        final Activity activity = getActivity();
        if (activity != null) {
            activity.runOnUiThread(() -> Toast.makeText(activity, text, Toast.LENGTH_SHORT).show());
        }
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, choose the smallest one that
     * is at least as large as the respective texture view size, and that is at most as large as the
     * respective max size, and whose aspect ratio matches with the specified value. If such size
     * doesn't exist, choose the largest one that is at most as large as the respective max size,
     * and whose aspect ratio matches with the specified value.
     *
     * @param choices           The list of sizes that the camera supports for the intended output
     *                          class
     * @param textureViewWidth  The width of the texture view relative to sensor coordinate
     * @param textureViewHeight The height of the texture view relative to sensor coordinate
     * @param maxWidth          The maximum width that can be chosen
     * @param maxHeight         The maximum height that can be chosen
     * @param aspectRatio       The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int textureViewWidth,
                                          int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {

        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        // Collect the supported resolutions that are smaller than the preview Surface
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight &&
                    option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth &&
                        option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, new CompareSizesByArea());
        } else {
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }

    public static CameraFragment newInstance() {
        return new CameraFragment();
    }

    /**
     * Returns the ConstraintLayout object after adjusting the LayoutParams of Views contained in it.
     * Adjusts the relative position of layout_topbar and camera_container (= viewfinder + rest of the buttons excluding layout_topbar)
     * depending on the aspect ratio of device.
     * This is done in order to re-organise the camera layout for long displays (having aspect ratio > 16:9)
     *
     * @param aspectRatio     the aspect ratio of device display given by (height in pixels / width in pixels)
     * @param activity_layout here, the layout of activity_main
     * @return Object of {@param activity_layout} after adjustments.
     */
    private ConstraintLayout getAdjustedLayout(float aspectRatio, ConstraintLayout activity_layout) {
        ConstraintLayout camera_container = activity_layout.findViewById(R.id.camera_container);
        ConstraintLayout.LayoutParams camera_containerLP = (ConstraintLayout.LayoutParams) camera_container.getLayoutParams();
        if (aspectRatio > 16f / 9f) {
            camera_containerLP.height = WRAP_CONTENT;
            showToast(String.valueOf(aspectRatio));
            ConstraintLayout.LayoutParams layout_topbarLP = ((ConstraintLayout.LayoutParams) activity_layout.findViewById(R.id.layout_topbar).getLayoutParams());
            layout_topbarLP.bottomToTop = R.id.camera_container;    //sets the bottom constraint of layout_topbar to top of camera_container
            if (aspectRatio > 2) {                  //for ratios even greater than 18:9
                layout_topbarLP.topToTop = -1;      //resets/removes the top constraint of topbar
            } else if (aspectRatio == 2) {          //for ratio 18:9
                camera_containerLP.topToTop = -1;   //resets/removes the top constraint of camera_container
                camera_containerLP.topToBottom = R.id.layout_topbar;    //constraints the top of cameracontainer to bottom of layout_topbar
            }
            if (((ConstraintLayout.LayoutParams) activity_layout.findViewById(R.id.texture).getLayoutParams()).dimensionRatio.equals("H,3:4")) {  //if viewfinder ratio is 3:4
                ConstraintLayout.LayoutParams layout_viewfinderLP = (ConstraintLayout.LayoutParams) camera_container.findViewById(R.id.layout_viewfinder).getLayoutParams();
                layout_viewfinderLP.bottomToTop = R.id.layout_bottombar;    //set the bottom of layout_viewfinder to top of layout_bottombar
            }
        }
        return activity_layout;
    }

    /**
     * Logs the device display properties
     *
     * @param dm Object of {@link DisplayMetrics} obtained from Fragment
     */
    private void logDisplayProperties(DisplayMetrics dm) {
        String TAG = "DisplayProps";
        Log.i(TAG, "ScreenResolution = " + dm.heightPixels + "x" + dm.widthPixels);
        Log.i(TAG, "AspectRatio = " + (float) dm.heightPixels / dm.widthPixels);
        Log.i(TAG, "SmallestWidth = " + (int) (dm.widthPixels / (dm.densityDpi / 160f)) + "dp");
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        logDisplayProperties(dm);
        float aspectRatio = (float) dm.heightPixels / dm.widthPixels;
        ConstraintLayout activity_main = (ConstraintLayout) inflater.inflate(R.layout.activity_main, container, false);
        return getAdjustedLayout(aspectRatio, activity_main);
    }
    boolean onUnlimited = false;
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.picture: {
                if(Interface.getSettings().selectedMode != Settings.CameraMode.UNLIMITED) {
                    Interface.getCameraUI().shot.setActivated(false);
                    Interface.getCameraUI().shot.setClickable(false);
                    takePicture();
                }
                else {
                    if(!onUnlimited) {
                        onUnlimited = true;
                        Interface.getCameraUI().shot.setActivated(false);
                        Interface.getCameraUI().shot.setClickable(true);
                        takePicture();
                    } else {
                        Interface.getCameraUI().shot.setActivated(true);
                        Interface.getCameraUI().shot.setClickable(true);
                        onUnlimited = false;
                        try {
                            mCaptureSession.abortCaptures();
                            ImageProcessing.UnlimitedEnd();
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                        createCameraPreviewSession();
                    }
                }
                break;
            }
            case R.id.settings: {
//                closeCamera();
//                Interface.getSettings().openSettingsActivity();
                Intent intent = new Intent(MainActivity.act, SettingsActivity.class);
                startActivity(intent);
                break;
            }
            case R.id.stacking: {
                ToggleButton sw = (ToggleButton) view;
                if (sw.isChecked()) {
                    mTargetFormat = rawFormat;
                    Interface.getSettings().hdrx = true;
                } else {
                    mTargetFormat = yuvFormat;
                    Interface.getSettings().hdrx = false;
                }
                Interface.getSettings().save();
                restartCamera();
                break;
            }
            case R.id.ImageOut: {
                Intent intent = new Intent(MainActivity.act, GalleryActivity.class);
                startActivity(intent);
            }
        }
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState) {
        mTextureView = view.findViewById(R.id.texture);
        surfaceView = view.findViewById(R.id.surfaceView);
        Interface.getCameraUI().onCameraViewCreated();
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

    }

    public void loadGalleryButtonImage() {
        File[] files = FileManager.DCIM_CAMERA.listFiles((dir, name) -> name.toUpperCase().endsWith(".JPG"));
        if (files != null) {
            long lastModifiedTime = -1;
            File lastImage = null;
            for (File f : files) {      //finds the last modified file from the list
                if (f.lastModified() > lastModifiedTime) {
                    lastImage = f;
                    lastModifiedTime = f.lastModified();
                }
            }
            //Used fastest decoder on the wide west
            if(lastImage != null) {
                    Interface.getCameraUI().galleryImageButton.setImageBitmap(
                            BitmapDecoder.from(Uri.fromFile(lastImage))
                                    .scaleBy(0.1f)
                                    .decode());
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Interface.getCameraUI().onCameraResume();
        if (mTextureView == null) mTextureView = new AutoFitTextureView(MainActivity.act);
        if (mTextureView.isAvailable()) {
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
        loadGalleryButtonImage();
    }

    @Override
    public void onPause() {
        Interface.getCameraUI().onCameraPause();
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                ErrorDialog.newInstance(getString(R.string.request_permission))
                        .show(getChildFragmentManager(), FRAGMENT_DIALOG);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    private void mul(Rect in, double k) {
        in.bottom *= k;
        in.left *= k;
        in.right *= k;
        in.top *= k;
    }
    private Size getCameraOutputSize(Size[] in) {
        Arrays.sort(in, new CompareSizesByArea());
        List<Size> sizes = new ArrayList<>(Arrays.asList(in));
        int s = sizes.size() - 1;
        if (sizes.get(s).getWidth() * sizes.get(s).getHeight() <= 40 * 1000000) {
            target = sizes.get(s);
            return target;
        }
        else {
            if(sizes.size()>1) {
                target = sizes.get(s - 1);
                return target;
            }
        }
        return null;
    }

     private Size getCameraOutputSize(Size[] in, Size mPreviewSize) {
        if(in == null) return mPreviewSize;
         Arrays.sort(in, new CompareSizesByArea());
         List<Size> sizes = new ArrayList<>(Arrays.asList(in));
         int s = sizes.size() - 1;
         if (sizes.get(s).getWidth() * sizes.get(s).getHeight() <= 40 * 1000000 || Interface.getSettings().QuadBayer){
             target = sizes.get(s);
             if(Interface.getSettings().QuadBayer) {
                 Rect pre = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
                 if(pre == null) return target;
                 Rect act = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                 if(act == null) return target;
                 double k = (double) (target.getHeight()) / act.bottom;
                 mul(pre, k);
                 mul(act, k);
                 CameraReflectionApi.set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, act);
                 CameraReflectionApi.set(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE, pre);
             }
             return target;
         }
         else {
             if(sizes.size()> 1 ) {
                 target = sizes.get(s - 1);
                 return target;
             }
         }
         return mPreviewSize;
     }

    int mPreviewwidth;
    int mPreviewheight;
    /**
     * Sets up member variables related to camera.
     *
     * @param width  The width of available size for camera preview
     * @param height The height of available size for camera preview
     */
    private void setUpCameraOutputs(int width, int height) {
        CameraManager manager = (CameraManager) Interface.getMainActivity().getSystemService(Context.CAMERA_SERVICE);
        CameraManager2 manager2 = new CameraManager2(manager);
        manager2.getCameraIdList();
        // manager2.CameraArr(manager);
        try {
                mCameraCharacteristics = manager.getCameraCharacteristics(Interface.getSettings().mCameraID);
                mPreviewwidth = width;
                mPreviewheight = height;
                UpdateCameraCharacteristics(Interface.getSettings().mCameraID);
                imageSaver = new ImageSaver();
                //Thread thr = new Thread(imageSaver);
                //thr.start();
                mBackgroundHandler.post(imageSaver);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                    .show(getChildFragmentManager(), FRAGMENT_DIALOG);
        }
    }

    public void UpdateCameraCharacteristics(String cameraId) {
        CameraManager manager = (CameraManager) Interface.getMainActivity().getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics characteristics
                = null;
        try {
            characteristics = manager.getCameraCharacteristics(cameraId);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        mCameraCharacteristics = characteristics;
        //Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);

        StreamConfigurationMap map = null;
        if (mCameraCharacteristics != null) {
            map = mCameraCharacteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        }
        if (map == null) {
            return;
        }
        Size preview = getCameraOutputSize(map.getOutputSizes(mPreviewTargetFormat));
        Size target = getCameraOutputSize(map.getOutputSizes(mTargetFormat),preview);
        int maxjpg = 3;
        if(mTargetFormat == mPreviewTargetFormat) maxjpg = Interface.getSettings().frameCount+3;
        mImageReaderPreview = ImageReader.newInstance(target.getWidth(), target.getHeight(),
                mPreviewTargetFormat, maxjpg);
        mImageReaderPreview.setOnImageAvailableListener(
                mOnYuvImageAvailableListener, mBackgroundHandler);
        mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(),
                mTargetFormat, Interface.getSettings().frameCount + 3);
        mImageReaderRaw.setOnImageAvailableListener(mOnRawImageAvailableListener, mBackgroundHandler);
        // Find out if we need to swap dimension to get the preview size relative to sensor
        // coordinate.
        int displayRotation = Interface.getGravity().getRotation();
        //int displayRotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        //noinspection ConstantConditions
        mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        Range[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        int def = 30;
        int min = 20;
        if(ranges == null) {
            ranges = new Range[1];
            ranges[0] = new Range(15, 30);
        }
            for (Range value : ranges) {
                if ((int) value.getUpper() >= def) {
                    FpsRangeDef = value;
                    break;
                }
            }
        if(FpsRangeDef == null)
            for (Range range : ranges) {
                if ((int) range.getUpper() >= min) {
                    FpsRangeDef = range;
                    break;
                }
            }
        for (Range range : ranges) {
            if ((int) range.getUpper() > def) {
                FpsRangeDef = range;
                break;
            }
        }
        if(FpsRangeHigh == null) FpsRangeHigh = FpsRangeDef;
        boolean swappedDimensions = false;
        switch (displayRotation) {
            case 0:
            case 180:
                if (mSensorOrientation == 90 || mSensorOrientation == 270) {
                    swappedDimensions = true;
                }
                break;
            case 90:
            case 270:
                if (mSensorOrientation == 0 || mSensorOrientation == 180) {
                    swappedDimensions = true;
                }
                break;
            default:
                Log.e(TAG, "Display rotation is invalid: " + displayRotation);
        }

        mCameraAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        Point displaySize = new Point();
        Interface.getMainActivity().getWindowManager().getDefaultDisplay().getSize(displaySize);
        int rotatedPreviewWidth = mPreviewwidth;
        int rotatedPreviewHeight = mPreviewheight;
        int maxPreviewWidth = displaySize.x;
        int maxPreviewHeight = displaySize.y;

        if (swappedDimensions) {
            rotatedPreviewWidth = mPreviewheight;
            rotatedPreviewHeight = mPreviewwidth;
            //noinspection SuspiciousNameCombination
            maxPreviewWidth = displaySize.y;
            //noinspection SuspiciousNameCombination
            maxPreviewHeight = displaySize.x;
        }

        if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
            maxPreviewWidth = MAX_PREVIEW_WIDTH;
        }

        if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
            maxPreviewHeight = MAX_PREVIEW_HEIGHT;
        }

        // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
        // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
        // garbage capture data.
        mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                maxPreviewHeight, target);

        // We fit the aspect ratio of TextureView to the size of preview we picked.
        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mTextureView.setAspectRatio(
                    mPreviewSize.getWidth(), mPreviewSize.getHeight());
        } else {
            mTextureView.setAspectRatio(
                    mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }

        // Check if the flash is supported.
        Boolean available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        mFlashSupported = available == null ? false : available;
        Interface.getCameraUI().onCameraInitialization();
    }

    @SuppressLint("MissingPermission")
    public void restartCamera() {
        try {
            mCameraOpenCloseLock.acquire();

            if (mCaptureSession != null) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReaderPreview) {
                mImageReaderPreview.close();
                mImageReaderPreview = null;
                mImageReaderRaw.close();
                mImageReaderRaw = null;
            }
            if (null != mPreviewRequestBuilder) {
                mPreviewRequestBuilder = null;
            }
            stopBackgroundThread();
            UpdateCameraCharacteristics(Interface.getSettings().mCameraID);
        } catch (Exception e) {
            throw new RuntimeException("Interrupted while trying to lock camera restarting.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }

        Activity activity = getActivity();
        CameraManager manager = null;
        if (activity != null) {
            manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        }
        if(manager == null) return;
        StreamConfigurationMap map = null;
        try {
            map = manager.getCameraCharacteristics(Interface.getSettings().mCameraID).get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        if(map == null) return;
        Size preview = getCameraOutputSize(map.getOutputSizes(mPreviewTargetFormat));
        Size target = getCameraOutputSize(map.getOutputSizes(mTargetFormat),preview);
        int max = 3;
        if(mTargetFormat == mPreviewTargetFormat) max = Interface.getSettings().frameCount + 3;
        //largest = target;
        mImageReaderPreview = ImageReader.newInstance(target.getWidth(), target.getHeight(),
                mPreviewTargetFormat, /*maxImages*/max);
        mImageReaderPreview.setOnImageAvailableListener(
                mOnYuvImageAvailableListener, mBackgroundHandler);

        mImageReaderRaw = ImageReader.newInstance(target.getWidth(), target.getHeight(),
                mTargetFormat, Interface.getSettings().frameCount + 3);
        mImageReaderRaw.setOnImageAvailableListener(
                mOnRawImageAvailableListener, mBackgroundHandler);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(Interface.getSettings().mCameraID, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to restart camera.", e);
        }
        //stopBackgroundThread();
        UpdateCameraCharacteristics(Interface.getSettings().mCameraID);
        startBackgroundThread();
        mBackgroundHandler.post(imageSaver);
    }
    /*public void restartCamera2{
        Intent intent = new Intent(MainActivity.act, CameraActivity.class);
        intent.addFlags(32768);
        intent.addFlags(268435456);
        MainActivity.act.startActivity(intent);
        System.exit(0);
    }*/

    /**
     * Opens the camera specified by {@link CameraFragment#mCameraIds}.
     */
    private void openCamera(int width, int height) {
        context = this;
        if (ContextCompat.checkSelfPermission(Interface.getMainActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            //requestCameraPermission();
            return;
        }
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        CameraManager manager = (CameraManager) Interface.getMainActivity().getSystemService(Context.CAMERA_SERVICE);
        CameraManager2 manager2 = new CameraManager2(manager);
        mCameraIds = manager2.getCameraIdList();
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
//                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(Interface.getSettings().mCameraID, mStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    public void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReaderPreview) {
                mImageReaderPreview.close();
                mImageReaderPreview = null;
                mImageReaderRaw.close();
                mImageReaderRaw = null;
            }
            mState = STATE_CLOSED;
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    public void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        //mBackgroundHandler.post(imageSaver);
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        if(mBackgroundThread == null) return;
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private boolean burst = false;
    @SuppressLint("LongLogTag")
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;
            // We configure the size of default buffer to be the size of camera preview we want.
            Log.d("createCameraPreviewSession() mTextureView", "" + mTextureView);
            Log.d("createCameraPreviewSession() Texture", "" + texture);
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);
            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            List<Surface> surfaces = Arrays.asList(surface, mImageReaderPreview.getSurface());
            if(burst){
                surfaces = Arrays.asList(mImageReaderPreview.getSurface(),mImageReaderRaw.getSurface());
            }
            if(mTargetFormat == mPreviewTargetFormat){
                surfaces = Arrays.asList(surface, mImageReaderPreview.getSurface());
            }
                mCameraDevice.createCaptureSession(surfaces,
                        new CameraCaptureSession.StateCallback() {
                            @Override
                            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                                // The camera is already closed
                                if (null == mCameraDevice) {
                                    return;
                                }
                                // When the session is ready, we start displaying the preview.
                                mCaptureSession = cameraCaptureSession;
                                try {
                                    // Auto focus should be continuous for camera preview.
                                    //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                    // Flash is automatically enabled when necessary.
                                    setAutoFlash();
                                    Interface.getSettings().applyPrev(mPreviewRequestBuilder);

                                    //lightcycle.setVisibility(View.INVISIBLE);
                                    // Finally, we start displaying the camera preview.
                                    if (Interface.getCameraFragment().is30Fps) {
                                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                FpsRangeDef);
                                    } else {
                                        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                                FpsRangeHigh);
                                    }
                                    mPreviewRequest = mPreviewRequestBuilder.build();

                                    //CameraReflectionApi.set(mPreviewRequest,CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);
                                    if (!burst) {
                                        mCaptureSession.setRepeatingRequest(mPreviewRequest,
                                                mCaptureCallback, mBackgroundHandler);
                                        unlockFocus();
                                    } else {
                                        Log.d(TAG,"Preview, captureBurst");
                                        if(Interface.getSettings().selectedMode != Settings.CameraMode.UNLIMITED) mCaptureSession.captureBurst(captures, CaptureCallback, null);
                                        else mCaptureSession.setRepeatingBurst(captures, CaptureCallback, null);
                                        burst = false;
                                    }
                                    if(getActivity()!=null){
                                        getActivity().runOnUiThread(() -> Interface.getTouchFocus().resetFocusCircle());
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }

                            @Override
                            public void onConfigureFailed(
                                    @NonNull CameraCaptureSession cameraCaptureSession) {
                                showToast("Failed");
                            }
                        }, null
                );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public void rebuildPreview(){
        try {
            //mCaptureSession.stopRepeating();
            mCaptureSession.setRepeatingRequest(mPreviewRequest,
                    mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    public void rebuildPreviewBuilder(){
        try {
            //mCaptureSession.stopRepeating();
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    public void rebuildPreviewBuilderOneShot(){
        try {
            mCaptureSession.capture(mPreviewRequestBuilder.build(),
                    mCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }
    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = Interface.getGravity().getRotation();//activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    /**
     * Initiate a still image capture.
     */
    private void takePicture() {
        if (mCameraAfModes.length > 1) lockFocus();
        else {
            try {
                mState = STATE_WAITING_NON_PRECAPTURE;
                mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                        mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Lock the focus as the first step for a still image capture.
     */
    private void lockFocus() {
        try {
            startTimerLocked();
            // This is how to tell the camera to lock focus.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Run the precapture sequence for capturing a still image. This method should be called when
     * we get a response in {@link #mCaptureCallback} from {@link #lockFocus()}.
     */
    private void runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            // Tell #mCaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE;
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    ArrayList<CaptureRequest> captures;
    CameraCaptureSession.CaptureCallback CaptureCallback;
    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder =
                    mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            mCaptureSession.stopRepeating();
            if(mTargetFormat != mPreviewTargetFormat) captureBuilder.addTarget(mImageReaderRaw.getSurface());
            else captureBuilder.addTarget(mImageReaderPreview.getSurface());
            Interface.getSettings().applyRes(captureBuilder);
            //captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            //captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
            Log.d(TAG,"Focus:"+mFocus);
            //captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE,mFocus);
            captureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
            captureBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
            for(int i =0; i<3;i++){
                Log.d(TAG,"Temperature:"+mPreviewTemp[i]);
            }
            Log.d(TAG,"CaptureBuilderStarted!");
            //setAutoFlash(captureBuilder);
            //int rotation = Interface.getGravity().getCameraRotation();//activity.getWindowManager().getDefaultDisplay().getRotation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, Interface.getGravity().getCameraRotation());
            captures = new ArrayList<>();
            FrameNumberSelector.getFrames();
            Interface.getCameraUI().lightcycle.setMax(FrameNumberSelector.frameCount);
            IsoExpoSelector.HDR = false;//Force HDR for tests
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_OFF);
            captureBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE,mFocus);
            IsoExpoSelector.useTripod = Interface.getSensors().getShakeness() < 5;
            for (int i = 0; i < FrameNumberSelector.frameCount; i++) {
                IsoExpoSelector.setExpo(captureBuilder, i);
                captures.add(captureBuilder.build());
            }
            if(FrameNumberSelector.frameCount == -1){
                IsoExpoSelector.setExpo(captureBuilder, 0);
                captures.add(captureBuilder.build());
            }
            //img
            Log.d(TAG,"FrameCount:"+FrameNumberSelector.frameCount);
            final int[] burstcount = {0, 0, FrameNumberSelector.frameCount};
            Log.d(TAG,"CaptureStarted!");
            Interface.getCameraUI().lightcycle.setAlpha(1.0f);
            mTextureView.setAlpha(0.5f);
            MediaPlayer burstPlayer = MediaPlayer.create(Interface.getMainActivity(),R.raw.sound_burst);
            CaptureCallback
                    = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    Interface.getCameraUI().lightcycle.setProgress(Interface.getCameraUI().lightcycle.getProgress() + 1);
                    burstPlayer.start();
                    Log.v(TAG,"Completed!");
                    mCaptureResult = result;
                }

                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    burstPlayer.seekTo(0);
                    Log.v(TAG,"FrameCaptureStarted! FrameNumber:"+frameNumber);
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                }

                @Override
                public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session, int sequenceId, long frameNumber) {
                    Log.d(TAG,"SequenceCompleted");
                    try {
                        Interface.getCameraUI().lightcycle.setAlpha(0f);
                        Interface.getCameraUI().lightcycle.setProgress(0);
                        mTextureView.setAlpha(1f);
                    } catch (Exception e){
                    e.printStackTrace();
                    }
                    //unlockFocus();
                    createCameraPreviewSession();
                    super.onCaptureSequenceCompleted(session, sequenceId, frameNumber);
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
                    burstcount[1]++;
                    if(Interface.getSettings().selectedMode != Settings.CameraMode.UNLIMITED)
                    if (burstcount[1] >= burstcount[2] + 1 || ImageSaver.imageBuffer.size() >= burstcount[2]) {
                        try {
                            mCaptureSession.abortCaptures();
                            Interface.getCameraUI().lightcycle.setAlpha(0f);
                            Interface.getCameraUI().lightcycle.setProgress(0);
                            mTextureView.setAlpha(1f);
                            createCameraPreviewSession();
                        } catch (CameraAccessException e) {
                            e.printStackTrace();
                        }
                    }
                    super.onCaptureProgressed(session, request, partialResult);
                }
            };

            //mCaptureSession.setRepeatingBurst(captures, CaptureCallback, null);
            burst = true;
            createCameraPreviewSession();
            //mCaptureSession.captureBurst(captures, CaptureCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Unlock the focus. This method should be called when still image capture sequence is
     * finished.
     */
    private void unlockFocus() {
        // Reset the auto-focus trigger
        //mCaptureSession.stopRepeating();
        CameraReflectionApi.set(mPreviewRequest,CaptureRequest.CONTROL_AF_TRIGGER,CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
        setAutoFlash();
        //mCaptureSession.capture(mPreviewRequest, mCaptureCallback,
        //        mBackgroundHandler);
        // After this, the camera will go back to the normal state of preview.
        mState = STATE_PREVIEW;
        rebuildPreview();
        //mCaptureSession.setRepeatingRequest(mPreviewRequest, mCaptureCallback,
        //        mBackgroundHandler);
    }


    public String cycler(String id) {
        String[] ids;
        if(CameraManager2.cameraManager2.supportFrontCamera) {
            if(Interface.getCameraUI().auxGroup.getChildCount() != 0) {
                int i = Interface.getCameraUI().auxGroup.getCheckedRadioButtonId();
                if (i >= 2) i++;
                ids = new String[]{mCameraIds[i - 1], "1"};
            } else ids = new String[]{"0","1"};
        }
        else {
           return "0";
        }
        int n = 0;
        for (int i = 0; i < ids.length; i++) {
            if (id.equals(ids[i])) n = i;
        }
        n++;
        n %= ids.length;
        if(n == 1) Interface.getCameraUI().auxGroup.setVisibility(View.INVISIBLE);
        else {
            Interface.getCameraUI().auxGroup.setVisibility(View.VISIBLE);
        }
        return ids[n];
    }

    public void setAutoFlash() {
        if (mFlashSupported) {
            if (mFlashEnabled)
            CameraReflectionApi.set(mPreviewRequest,CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }


    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }

    }

    /**
     * Shows an error message dialog.
     */
    public static class ErrorDialog extends DialogFragment {

        private static final String ARG_MESSAGE = "message";

        public static ErrorDialog newInstance(String message) {
            ErrorDialog dialog = new ErrorDialog();
            Bundle args = new Bundle();
            args.putString(ARG_MESSAGE, message);
            dialog.setArguments(args);
            return dialog;
        }
        @NonNull
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Activity activity = getActivity();
            assert getArguments() != null;
            return new AlertDialog.Builder(activity)
                    .setMessage(getArguments().getString(ARG_MESSAGE))
                    .setPositiveButton(android.R.string.ok, (dialogInterface, i) -> {
                        if (activity != null) {
                            activity.finish();
                        }
                    })
                    .create();
        }
    }

    /**
     * Start the timer for the pre-capture sequence.
     * <p/>
     * Call this only with { #mCameraStateLock} held.
     */
    private void startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime();
    }

    /**
     * Check if the timer for the pre-capture sequence has been hit.
     * <p/>
     * Call this only with { #mCameraStateLock} held.
     *
     * @return true if the timeout occurred.
     */
    private boolean hitTimeoutLocked() {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
    }
}