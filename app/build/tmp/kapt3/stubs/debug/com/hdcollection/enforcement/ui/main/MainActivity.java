package com.hdcollection.enforcement.ui.main;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000X\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0011\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u000b\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\n\n\u0002\u0010\b\n\u0002\b\u0002\n\u0002\u0010\u0015\n\u0002\b\u0011\u0018\u0000 32\u00020\u00012\u00020\u0002:\u00013B\u0005\u00a2\u0006\u0002\u0010\u0003J\b\u0010\u0010\u001a\u00020\u0011H\u0002J\u0012\u0010\u0012\u001a\u00020\u00132\b\u0010\u0014\u001a\u0004\u0018\u00010\u0015H\u0014J\b\u0010\u0016\u001a\u00020\u0013H\u0014J\u0010\u0010\u0017\u001a\u00020\u00132\u0006\u0010\u0018\u001a\u00020\fH\u0016J\b\u0010\u0019\u001a\u00020\u0013H\u0014J\u0010\u0010\u001a\u001a\u00020\u00132\u0006\u0010\u001b\u001a\u00020\fH\u0016J\u0010\u0010\u001c\u001a\u00020\u00132\u0006\u0010\u001d\u001a\u00020\fH\u0016J-\u0010\u001e\u001a\u00020\u00132\u0006\u0010\u001f\u001a\u00020 2\u000e\u0010!\u001a\n\u0012\u0006\b\u0001\u0012\u00020\f0\u000b2\u0006\u0010\"\u001a\u00020#H\u0016\u00a2\u0006\u0002\u0010$J\b\u0010%\u001a\u00020\u0013H\u0014J \u0010&\u001a\u00020\u00132\u0006\u0010\'\u001a\u00020\f2\u0006\u0010(\u001a\u00020\f2\u0006\u0010)\u001a\u00020 H\u0016J\u0010\u0010*\u001a\u00020\u00132\u0006\u0010\'\u001a\u00020\fH\u0016J\b\u0010+\u001a\u00020\u0013H\u0002J\b\u0010,\u001a\u00020\u0013H\u0002J\b\u0010-\u001a\u00020\u0013H\u0002J\b\u0010.\u001a\u00020\u0013H\u0002J\b\u0010/\u001a\u00020\u0013H\u0002J\u0018\u00100\u001a\u00020\u00132\u0006\u00101\u001a\u00020\f2\u0006\u00102\u001a\u00020\fH\u0002R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0006\u001a\u00020\u0007X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\tX\u0082.\u00a2\u0006\u0002\n\u0000R\u0016\u0010\n\u001a\b\u0012\u0004\u0012\u00020\f0\u000bX\u0082\u0004\u00a2\u0006\u0004\n\u0002\u0010\rR\u000e\u0010\u000e\u001a\u00020\u000fX\u0082.\u00a2\u0006\u0002\n\u0000\u00a8\u00064"}, d2 = {"Lcom/hdcollection/enforcement/ui/main/MainActivity;", "Landroidx/appcompat/app/AppCompatActivity;", "Lcom/hdcollection/enforcement/gb28181/StreamCallback;", "()V", "clockHandler", "Landroid/os/Handler;", "clockRunnable", "Ljava/lang/Runnable;", "gb28181Manager", "Lcom/hdcollection/enforcement/gb28181/GB28181Manager;", "requiredPermissions", "", "", "[Ljava/lang/String;", "settings", "Lcom/hdcollection/enforcement/data/AppSettings;", "hasRequiredPermissions", "", "onCreate", "", "savedInstanceState", "Landroid/os/Bundle;", "onDestroy", "onIntercomReceived", "callerInfo", "onPause", "onRegistered", "deviceId", "onRegistrationFailed", "reason", "onRequestPermissionsResult", "requestCode", "", "permissions", "grantResults", "", "(I[Ljava/lang/String;[I)V", "onResume", "onStreamStartRequested", "channelId", "rtpIp", "rtpPort", "onStreamStopRequested", "setupBottomButtons", "startGB28181", "updateClock", "updateDeviceInfo", "updateStorageInfo", "updateStreamStatus", "text", "colorHex", "Companion", "app_debug"})
public final class MainActivity extends androidx.appcompat.app.AppCompatActivity implements com.hdcollection.enforcement.gb28181.StreamCallback {
    private com.hdcollection.enforcement.data.AppSettings settings;
    private com.hdcollection.enforcement.gb28181.GB28181Manager gb28181Manager;
    @org.jetbrains.annotations.NotNull()
    private final android.os.Handler clockHandler = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.Runnable clockRunnable = null;
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String[] requiredPermissions = {"android.permission.CAMERA", "android.permission.RECORD_AUDIO", "android.permission.ACCESS_FINE_LOCATION"};
    private static final int REQUEST_PERMISSIONS = 1001;
    @org.jetbrains.annotations.NotNull()
    public static final com.hdcollection.enforcement.ui.main.MainActivity.Companion Companion = null;
    
    public MainActivity() {
        super();
    }
    
    @java.lang.Override()
    protected void onCreate(@org.jetbrains.annotations.Nullable()
    android.os.Bundle savedInstanceState) {
    }
    
    @java.lang.Override()
    protected void onResume() {
    }
    
    @java.lang.Override()
    protected void onPause() {
    }
    
    @java.lang.Override()
    protected void onDestroy() {
    }
    
    private final void setupBottomButtons() {
    }
    
    private final void startGB28181() {
    }
    
    private final void updateClock() {
    }
    
    private final void updateDeviceInfo() {
    }
    
    private final void updateStorageInfo() {
    }
    
    private final void updateStreamStatus(java.lang.String text, java.lang.String colorHex) {
    }
    
    private final boolean hasRequiredPermissions() {
        return false;
    }
    
    @java.lang.Override()
    public void onRequestPermissionsResult(int requestCode, @org.jetbrains.annotations.NotNull()
    java.lang.String[] permissions, @org.jetbrains.annotations.NotNull()
    int[] grantResults) {
    }
    
    @java.lang.Override()
    public void onRegistered(@org.jetbrains.annotations.NotNull()
    java.lang.String deviceId) {
    }
    
    @java.lang.Override()
    public void onRegistrationFailed(@org.jetbrains.annotations.NotNull()
    java.lang.String reason) {
    }
    
    @java.lang.Override()
    public void onStreamStartRequested(@org.jetbrains.annotations.NotNull()
    java.lang.String channelId, @org.jetbrains.annotations.NotNull()
    java.lang.String rtpIp, int rtpPort) {
    }
    
    @java.lang.Override()
    public void onStreamStopRequested(@org.jetbrains.annotations.NotNull()
    java.lang.String channelId) {
    }
    
    @java.lang.Override()
    public void onIntercomReceived(@org.jetbrains.annotations.NotNull()
    java.lang.String callerInfo) {
    }
    
    @kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u0012\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\b\n\u0000\b\u0086\u0003\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002R\u000e\u0010\u0003\u001a\u00020\u0004X\u0082T\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0005"}, d2 = {"Lcom/hdcollection/enforcement/ui/main/MainActivity$Companion;", "", "()V", "REQUEST_PERMISSIONS", "", "app_debug"})
    public static final class Companion {
        
        private Companion() {
            super();
        }
    }
}