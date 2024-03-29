package com.gemini.jalen.scanner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.PermissionChecker;

import com.gemini.jalen.scanner.camera.CameraManager;
import com.gemini.jalen.scanner.decode.MainHandler;
import com.gemini.jalen.scanner.utils.BeepManager;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Desc: 1:启动一个SurfaceView作为取景预览
 * 2:开启camera,在后台独立线程中完成扫描任务
 * 3:对解码返回的结果进行处理.
 * 4:释放资源
 */
public class CaptureActivity extends Activity implements SurfaceHolder.Callback {
    private static final int MY_PERMISSIONS_REQUEST_CAMERA = 26;
    public static final String EXTRA_STRING = "extra_string";
    public static final String INTENT_WIDTH = "scanner_width";
    public static final String INTENT_HEIGHT = "scanner_height";
    public static final String INTENT_TITLE = "scanner_title";
    private static final String TAG = "CaptureActivity";
    private MainHandler mainHandler;
    private SurfaceHolder mHolder;

    private CameraManager mCameraManager;
    private BeepManager beepManager;

    private SurfaceView scanPreview;
    private RelativeLayout scanContainer;
    private RelativeLayout scanCropView;
    private ImageView scanLine;
    private Rect mCropRect = null;

    private boolean isHasSurface = false;
    private boolean isOpenCamera = false;

    public Handler getHandler() {
        return mainHandler;
    }


    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);
        initView();
        checkPermissionCamera();
    }

    private void initView() {
        scanPreview = findViewById(R.id.capture_preview);
        scanContainer = findViewById(R.id.capture_container);
        scanCropView = findViewById(R.id.capture_crop_view);
        scanLine = findViewById(R.id.capture_scan_line);
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams)
                findViewById(R.id.capture_mask_top).getLayoutParams();
        DisplayMetrics outMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(outMetrics);
        params.height = (int) ((outMetrics.heightPixels -
                (200 * getResources().getDisplayMetrics().density + 0.5f * (200 >= 0 ? 1 : -1))) / 2);
        int width = getIntent().getIntExtra(INTENT_WIDTH, 0);
        int height = getIntent().getIntExtra(INTENT_HEIGHT, 0);
        if (width != 0 && height != 0) {
            params = (RelativeLayout.LayoutParams) scanCropView.getLayoutParams();
            params.width = width;
            params.height = height;
        }
        String title = getIntent().getStringExtra(INTENT_TITLE);
        if (title != null) {
            ((TextView) findViewById(R.id.scanTitle)).setText(title);
        }
        findViewById(R.id.scan_capture_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        isHasSurface = false;
        beepManager = new BeepManager(this);

        TranslateAnimation animation = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation
                .RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT,
                0.9f);
        animation.setDuration(3000);
        animation.setRepeatCount(-1);
        animation.setRepeatMode(Animation.RESTART);
        scanLine.startAnimation(animation);
    }

    @Override
    protected void onResume() {
        super.onResume();
        init();
    }

    private void init() {
        if (isOpenCamera && mCameraManager == null) {
            mHolder = scanPreview.getHolder();
            mCameraManager = new CameraManager(getApplication());
            if (isHasSurface) {
                initCamera(mHolder);
            } else {
                mHolder.addCallback(this);
                mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
            }
        }
    }

    @Override
    public void onPause() {
        releaseCamera();
        super.onPause();
        if (scanLine != null) {
            scanLine.clearAnimation();
            scanLine.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        //remove SurfaceCallback
        if (!isHasSurface) {
            scanPreview.getHolder().removeCallback(this);
        }
    }

    //region 初始化和回收相关资源
    private void initCamera(SurfaceHolder surfaceHolder) {
        mainHandler = null;
        try {
            mCameraManager.openDriver(surfaceHolder, getWindowManager().getDefaultDisplay().getRotation());
            if (mainHandler == null) {
                mainHandler = new MainHandler(this, mCameraManager);
            }
        } catch (IOException ioe) {
            Log.e(TAG, "相机被占用", ioe);
        } catch (RuntimeException e) {
            e.printStackTrace();
            Log.e(TAG, "Unexpected error initializing camera");
            displayFrameworkBugMessageAndExit();
        }

    }

    private void releaseCamera() {
        if (null != mainHandler) {
            //关闭聚焦,停止预览,清空预览回调,quit子线程looper
            mainHandler.quitSynchronously();
            mainHandler = null;
        }
        //关闭声音
        if (null != beepManager) {
            Log.e(TAG, "releaseCamera: beepManager release" );
            beepManager.releaseRing();
            beepManager = null;
        }
        //关闭相机
        if (mCameraManager != null) {
            mCameraManager.closeDriver();
            mCameraManager = null;
        }
    }
    //endregion

    //region 检查权限
    private void checkPermissionCamera() {
        int checkPermission = 0;
        if (Build.VERSION.SDK_INT >= 23) {
            checkPermission = PermissionChecker.checkSelfPermission(this, Manifest.permission.CAMERA);
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.CAMERA},
                        MY_PERMISSIONS_REQUEST_CAMERA);
            } else {
                isOpenCamera = true;
            }

        } else {
            checkPermission = checkPermission(MY_PERMISSIONS_REQUEST_CAMERA);
            if (checkPermission == AppOpsManager.MODE_ALLOWED) {
                isOpenCamera = true;
            } else if (checkPermission == AppOpsManager.MODE_IGNORED) {
                isOpenCamera = false;
                displayFrameworkBugMessageAndExit();
            }
        }
    }

    /**
     * 反射调用系统权限,判断权限是否打开
     *
     * @param permissionCode 相应的权限所对应的code
     * @see {@link AppOpsManager }
     */
    private int checkPermission(int permissionCode) {
        int checkPermission = 0;
        if (Build.VERSION.SDK_INT >= 19) {
            AppOpsManager _manager = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            try {
                Class<?>[] types = new Class[]{int.class, int.class, String.class};
                Object[] args = new Object[]{permissionCode, Binder.getCallingUid(), getPackageName()};
                Method method = _manager.getClass().getDeclaredMethod("noteOp", types);
                method.setAccessible(true);
                Object _o = method.invoke(_manager, args);
                if ((_o instanceof Integer)) {
                    checkPermission = (Integer) _o;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            checkPermission = 0;
        }
        return checkPermission;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_REQUEST_CAMERA: {
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    isOpenCamera = true;
                    init();
                } else {
                    finish();
                }
                return;
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void displayFrameworkBugMessageAndExit() {
        String per = String.format(getString(R.string.permission), getString(R.string.camera), getString(R.string.camera));
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.qr_name));
        builder.setMessage(per);
        builder.setPositiveButton(getString(R.string.i_know), new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                CaptureActivity.this.finish();
            }

        });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {

            @Override
            public void onCancel(DialogInterface dialog) {
                CaptureActivity.this.finish();
            }
        });
        builder.show();
    }
    //endregion

    //region 扫描结果
    public void checkResult(final String result) {
        if (beepManager != null) {
            beepManager.startRing();
        }
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                activityResult(result.trim());
            }
        }, beepManager.getTimeDuration());
    }

    private void activityResult(String result) {
        if (!isFinishing()) {
            Intent intent =new Intent();
            Bundle bundle = new Bundle();
            bundle.putInt("width", mCropRect.width());
            bundle.putInt("height", mCropRect.height());
            bundle.putString(EXTRA_STRING, result);
            intent.putExtras(bundle);
            setResult(RESULT_OK,intent);
            CaptureActivity.this.finish();
        }
    }

    //endregion

    //region  初始化截取的矩形区域
    public Rect initCrop() {
        int cameraWidth = 0;
        int cameraHeight = 0;
        if (null != mCameraManager) {
            cameraWidth = mCameraManager.getCameraResolution().y;
            cameraHeight = mCameraManager.getCameraResolution().x;
        }

        /** 获取布局中扫描框的位置信息 */
        int[] location = new int[2];
        scanCropView.getLocationInWindow(location);

        int cropLeft = location[0];
        int cropTop = location[1] - getStatusBarHeight();

        int cropWidth = scanCropView.getWidth();
        int cropHeight = scanCropView.getHeight();

        /** 获取布局容器的宽高 */
        int containerWidth = scanContainer.getWidth();
        int containerHeight = scanContainer.getHeight();

        /** 计算最终截取的矩形的左上角顶点x坐标 */
        int x = cropLeft * cameraWidth / containerWidth;
        /** 计算最终截取的矩形的左上角顶点y坐标 */
        int y = cropTop * cameraHeight / containerHeight;

        /** 计算最终截取的矩形的宽度 */
        int width = cropWidth * cameraWidth / containerWidth;
        /** 计算最终截取的矩形的高度 */
        int height = cropHeight * cameraHeight / containerHeight;

        /** 生成最终的截取的矩形 */
        mCropRect = new Rect(x, y, width + x, height + y);
        return new Rect(x, y, width + x, height + y);
    }

    private int getStatusBarHeight() {
        try {
            Class<?> c = Class.forName("com.android.internal.R$dimen");
            Object obj = c.newInstance();
            Field field = c.getField("status_bar_height");
            int x = Integer.parseInt(field.get(obj).toString());
            return getResources().getDimensionPixelSize(x);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0;
    }
    //endregion

    //region SurfaceHolder Callback 回调方法
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            Log.e(TAG, "*** 没有添加SurfaceHolder的Callback");
        }
        if (isOpenCamera) {
            if (!isHasSurface) {
                isHasSurface = true;
                initCamera(holder);
            }
        }

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.e(TAG, "surfaceChanged: ");

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        isHasSurface = false;

    }
    //endregion
}
