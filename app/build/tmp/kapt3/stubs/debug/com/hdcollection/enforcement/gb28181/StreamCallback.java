package com.hdcollection.enforcement.gb28181;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000 \n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\u000e\n\u0002\b\b\n\u0002\u0010\b\n\u0002\b\u0002\bf\u0018\u00002\u00020\u0001J\u0010\u0010\u0002\u001a\u00020\u00032\u0006\u0010\u0004\u001a\u00020\u0005H&J\u0010\u0010\u0006\u001a\u00020\u00032\u0006\u0010\u0007\u001a\u00020\u0005H&J\u0010\u0010\b\u001a\u00020\u00032\u0006\u0010\t\u001a\u00020\u0005H&J \u0010\n\u001a\u00020\u00032\u0006\u0010\u000b\u001a\u00020\u00052\u0006\u0010\f\u001a\u00020\u00052\u0006\u0010\r\u001a\u00020\u000eH&J\u0010\u0010\u000f\u001a\u00020\u00032\u0006\u0010\u000b\u001a\u00020\u0005H&\u00a8\u0006\u0010"}, d2 = {"Lcom/hdcollection/enforcement/gb28181/StreamCallback;", "", "onIntercomReceived", "", "callerInfo", "", "onRegistered", "deviceId", "onRegistrationFailed", "reason", "onStreamStartRequested", "channelId", "rtpIp", "rtpPort", "", "onStreamStopRequested", "app_debug"})
public abstract interface StreamCallback {
    
    public abstract void onRegistered(@org.jetbrains.annotations.NotNull()
    java.lang.String deviceId);
    
    public abstract void onRegistrationFailed(@org.jetbrains.annotations.NotNull()
    java.lang.String reason);
    
    public abstract void onStreamStartRequested(@org.jetbrains.annotations.NotNull()
    java.lang.String channelId, @org.jetbrains.annotations.NotNull()
    java.lang.String rtpIp, int rtpPort);
    
    public abstract void onStreamStopRequested(@org.jetbrains.annotations.NotNull()
    java.lang.String channelId);
    
    public abstract void onIntercomReceived(@org.jetbrains.annotations.NotNull()
    java.lang.String callerInfo);
}