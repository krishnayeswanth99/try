package com.example.test1;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {


    private static final String TAG = "AndroidCameraApi";
    private TextureView textureView;
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private String cameraId;
    protected CameraDevice cameraDevice;
    protected CameraCaptureSession cameraCaptureSessions;
    protected CaptureRequest captureRequest;
    protected CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private ImageReader imageReader;
    private File file;
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textureView = findViewById(R.id.texture);
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);
        TextView textView = findViewById(R.id.disp_ip);
        textView.setText(getIP(MainActivity.this));
        textView.setTextColor(ContextCompat.getColor(this,R.color.colorPrimaryDark));
    }

    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //open your camera here
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened");
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };
    final CameraCaptureSession.CaptureCallback captureCallbackListener = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            Toast.makeText(MainActivity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
            createCameraPreview();
        }
    };

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void onClick(View view){
        EditText editText = findViewById(R.id.port);
        int port = Integer.parseInt(editText.getText().toString());
        Server server = new Server(port);
        server.start();
    }

    protected void takePicture() {
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
            Size[] jpegSizes = null;
            if(characteristics != null){
                jpegSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
            }
            int width = 640;
            int height = 480;
            if (jpegSizes != null && 0 < jpegSizes.length) {
                width = jpegSizes[0].getWidth();
                height = jpegSizes[0].getHeight();
            }
            imageReader = ImageReader.newInstance(width,height,ImageFormat.JPEG,1);
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            outputSurfaces.add(imageReader.getSurface());
            outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION,ORIENTATIONS.get(rotation));

            file = createImageFile();

            ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.capacity()];
                        buffer.get(bytes);
                        save(bytes);
                    } catch (Exception e){
                        e.printStackTrace();
                    } finally {
                        if(image != null){
                            image.close();
                        }
                    }
                }
                private void save(byte[] bytes) throws IOException {
                    OutputStream out = null;
                    try{
                        out = new FileOutputStream(file);
                        out.write(bytes);
                    } finally {
                        if(null != out){
                            out.close();
                        }
                    }
                }
            };
            imageReader.setOnImageAvailableListener(onImageAvailableListener,mBackgroundHandler);
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    try {
                        session.capture(captureRequestBuilder.build(),captureCallbackListener,mBackgroundHandler);
                    } catch (CameraAccessException e){
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {

                }
            },mBackgroundHandler);
        } catch (CameraAccessException e){
            Toast.makeText(MainActivity.this,e.getReason(),Toast.LENGTH_SHORT).show();
        }
    }

    protected void createCameraPreview(){
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(),imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    if(null == cameraDevice)
                        return;

                    cameraCaptureSessions = session;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this,"Configured",Toast.LENGTH_SHORT).show();
                }
            },null);
        } catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    private File createImageFile(){

        File storageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"try");

        if(!storageDir.exists() && !storageDir.mkdirs()){
            return null;
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File f = new File(storageDir.getPath()+File.separator+"IMG_"+timeStamp+".jpg");
        if(!f.exists()){
            try {
                f.createNewFile();
            } catch (IOException e){
                e.printStackTrace();
            }
        }
        return f;
    }
    private void openCamera(){
        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        Log.i(TAG,"Is camera open");
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];

            if(ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.CAMERA,Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId,stateCallback,null);
        } catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int reqCode, String [] permissions, int [] grant){
        if(reqCode == REQUEST_CAMERA_PERMISSION){
            if(grant[0] == PackageManager.PERMISSION_DENIED){
                Toast.makeText(this,"Sorry",Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume(){
        super.onResume();
        Log.i(TAG,"onResume");
        startBackgroundThread();
        if(textureView.isAvailable()){
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause(){
        super.onPause();
//        close();
        Log.i(TAG,"onPause");
        stopBackgroundThread();
    }

    protected void updatePreview(){
        if(null == cameraDevice)
            Log.i(TAG,"updatePreview");
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE,CameraMetadata.CONTROL_MODE_AUTO);
        try {
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(),null,mBackgroundHandler);
        } catch (CameraAccessException e){
            e.printStackTrace();
        }
    }

    private void close(){
        if(null != cameraDevice){
            cameraDevice.close();
            cameraDevice = null;
        }
        if(null != imageReader){
            imageReader.close();
            imageReader = null;
        }
    }

    public static String getIP(Context context){
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().
                getSystemService(WIFI_SERVICE);
        return intToInetAddress(wifiManager.getDhcpInfo().ipAddress).getHostAddress();
    }

    public static InetAddress intToInetAddress(int hostAdd){
        byte[] addBytes = { (byte)(0xff & hostAdd),
                (byte)(0xff & (hostAdd >> 8)),
                (byte)(0xff & (hostAdd >> 16)),
                (byte)(0xff & (hostAdd >> 24))};
        try {
            return InetAddress.getByAddress(addBytes);
        } catch (UnknownHostException e){
            throw new AssertionError();
        }
    }

    class Server extends Thread{

        private ServerSocket serverSocket;
        private String msg;
        private int port;
        private Socket socket;

        Server(int port){
            this.port = port;
        }

        public void run(){
            try {
                serverSocket = new ServerSocket(port);
                socket = serverSocket.accept();
                while (socket != null){
                        BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        msg = input.readLine();
                        if(msg.contentEquals("Capture")){
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    takePicture();
                                }
                            });
                        }
                        if(msg.contentEquals("Disconnect")) {
                            socket.close();
                            socket = null;
                        }
                }
            } catch (IOException e){
                Log.e("Error: ",e.toString());
            }
        }
    }
}
