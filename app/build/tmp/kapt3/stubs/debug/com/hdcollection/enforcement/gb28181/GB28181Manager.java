package com.hdcollection.enforcement.gb28181;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000@\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0000\n\u0002\u0010\u0002\n\u0002\b\u0011\u0018\u00002\u00020\u0001B\u0015\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u00a2\u0006\u0002\u0010\u0006J\b\u0010\u0014\u001a\u00020\u0015H\u0002J\u0006\u0010\u0016\u001a\u00020\u0015J\u0012\u0010\u0017\u001a\u0004\u0018\u00010\b2\u0006\u0010\u0018\u001a\u00020\bH\u0002J\u0017\u0010\u0019\u001a\u0004\u0018\u00010\n2\u0006\u0010\u0018\u001a\u00020\bH\u0002\u00a2\u0006\u0002\u0010\u001aJ\b\u0010\u001b\u001a\u00020\bH\u0002J\u0010\u0010\u001c\u001a\u00020\u00152\u0006\u0010\u0018\u001a\u00020\bH\u0002J\u0010\u0010\u001d\u001a\u00020\u00152\u0006\u0010\u0018\u001a\u00020\bH\u0002J\u0006\u0010\u001e\u001a\u00020\u0015J \u0010\u001f\u001a\u00020\u00152\n\b\u0002\u0010 \u001a\u0004\u0018\u00010\b2\n\b\u0002\u0010!\u001a\u0004\u0018\u00010\bH\u0002J\u0010\u0010\"\u001a\u00020\u00152\u0006\u0010\u0018\u001a\u00020\bH\u0002J\b\u0010#\u001a\u00020\u0015H\u0002J\b\u0010$\u001a\u00020\u0015H\u0002J\u0006\u0010%\u001a\u00020\u0015R\u000e\u0010\u0007\u001a\u00020\bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\t\u001a\u00020\nX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u000b\u001a\u0004\u0018\u00010\fX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\r\u001a\u0004\u0018\u00010\fX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000e\u001a\u00020\bX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000f\u001a\u00020\nX\u0082D\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0010\u001a\u00020\u0011X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0010\u0010\u0012\u001a\u0004\u0018\u00010\u0013X\u0082\u000e\u00a2\u0006\u0002\n\u0000\u00a8\u0006&"}, d2 = {"Lcom/hdcollection/enforcement/gb28181/GB28181Manager;", "", "settings", "Lcom/hdcollection/enforcement/data/AppSettings;", "callback", "Lcom/hdcollection/enforcement/gb28181/StreamCallback;", "(Lcom/hdcollection/enforcement/data/AppSettings;Lcom/hdcollection/enforcement/gb28181/StreamCallback;)V", "callId", "", "cseq", "", "keepAliveJob", "Lkotlinx/coroutines/Job;", "listenJob", "localIp", "localPort", "scope", "Lkotlinx/coroutines/CoroutineScope;", "udpSocket", "Ljava/net/DatagramSocket;", "cleanup", "", "destroy", "extractSdpConnection", "message", "extractSdpMediaPort", "(Ljava/lang/String;)Ljava/lang/Integer;", "getLocalIp", "handleInvite", "handleSipMessage", "register", "sendRegister", "authNonce", "authRealm", "sendUdp", "startKeepAlive", "startListening", "unregister", "app_debug"})
public final class GB28181Manager {
    @org.jetbrains.annotations.NotNull()
    private final com.hdcollection.enforcement.data.AppSettings settings = null;
    @org.jetbrains.annotations.NotNull()
    private final com.hdcollection.enforcement.gb28181.StreamCallback callback = null;
    @org.jetbrains.annotations.Nullable()
    private java.net.DatagramSocket udpSocket;
    @org.jetbrains.annotations.Nullable()
    private kotlinx.coroutines.Job listenJob;
    @org.jetbrains.annotations.Nullable()
    private kotlinx.coroutines.Job keepAliveJob;
    @org.jetbrains.annotations.NotNull()
    private java.lang.String callId;
    private int cseq = 1;
    @org.jetbrains.annotations.NotNull()
    private final kotlinx.coroutines.CoroutineScope scope = null;
    @org.jetbrains.annotations.NotNull()
    private java.lang.String localIp = "0.0.0.0";
    private final int localPort = 5060;
    
    public GB28181Manager(@org.jetbrains.annotations.NotNull()
    com.hdcollection.enforcement.data.AppSettings settings, @org.jetbrains.annotations.NotNull()
    com.hdcollection.enforcement.gb28181.StreamCallback callback) {
        super();
    }
    
    public final void register() {
    }
    
    private final void sendRegister(java.lang.String authNonce, java.lang.String authRealm) {
    }
    
    private final void startListening() {
    }
    
    private final void handleSipMessage(java.lang.String message) {
    }
    
    private final void handleInvite(java.lang.String message) {
    }
    
    private final void startKeepAlive() {
    }
    
    private final void sendUdp(java.lang.String message) {
    }
    
    private final java.lang.String getLocalIp() {
        return null;
    }
    
    private final java.lang.String extractSdpConnection(java.lang.String message) {
        return null;
    }
    
    private final java.lang.Integer extractSdpMediaPort(java.lang.String message) {
        return null;
    }
    
    public final void unregister() {
    }
    
    private final void cleanup() {
    }
    
    public final void destroy() {
    }
}