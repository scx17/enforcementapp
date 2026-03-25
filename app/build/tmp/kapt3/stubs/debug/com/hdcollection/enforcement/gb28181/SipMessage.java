package com.hdcollection.enforcement.gb28181;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000\u001c\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0002\n\u0002\u0010\u000e\n\u0002\b\u0007\n\u0002\u0010\b\n\u0002\b\u0017\b\u00c6\u0002\u0018\u00002\u00020\u0001B\u0007\b\u0002\u00a2\u0006\u0002\u0010\u0002J\u000e\u0010\u0003\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u0004J>\u0010\u0006\u001a\u00020\u00042\u0006\u0010\u0005\u001a\u00020\u00042\u0006\u0010\u0007\u001a\u00020\u00042\u0006\u0010\b\u001a\u00020\u00042\u0006\u0010\t\u001a\u00020\u00042\u0006\u0010\n\u001a\u00020\u00042\u0006\u0010\u000b\u001a\u00020\f2\u0006\u0010\r\u001a\u00020\u0004JH\u0010\u000e\u001a\u00020\u00042\u0006\u0010\u000f\u001a\u00020\u00042\u0006\u0010\u0010\u001a\u00020\u00042\u0006\u0010\u0011\u001a\u00020\u00042\u0006\u0010\n\u001a\u00020\u00042\u0006\u0010\u0012\u001a\u00020\f2\u0006\u0010\u0005\u001a\u00020\u00042\u0006\u0010\t\u001a\u00020\f2\b\b\u0002\u0010\u0013\u001a\u00020\fJh\u0010\u0014\u001a\u00020\u00042\u0006\u0010\u000f\u001a\u00020\u00042\u0006\u0010\u0010\u001a\u00020\u00042\u0006\u0010\u0011\u001a\u00020\u00042\u0006\u0010\n\u001a\u00020\u00042\u0006\u0010\u0012\u001a\u00020\f2\u0006\u0010\u0005\u001a\u00020\u00042\u0006\u0010\t\u001a\u00020\f2\u0006\u0010\u0015\u001a\u00020\u00042\u0006\u0010\u0016\u001a\u00020\u00042\u0006\u0010\u0017\u001a\u00020\u00042\u0006\u0010\u0018\u001a\u00020\u00042\b\b\u0002\u0010\u0013\u001a\u00020\fJ \u0010\u0019\u001a\u00020\u00042\u0006\u0010\n\u001a\u00020\u00042\u0006\u0010\u000b\u001a\u00020\f2\u0006\u0010\r\u001a\u00020\u0004H\u0002J\u0018\u0010\u001a\u001a\u0004\u0018\u00010\u00042\u0006\u0010\u001b\u001a\u00020\u00042\u0006\u0010\u001c\u001a\u00020\u0004J\u0010\u0010\u001d\u001a\u0004\u0018\u00010\u00042\u0006\u0010\u001b\u001a\u00020\u0004J\u0010\u0010\u001e\u001a\u0004\u0018\u00010\u00042\u0006\u0010\u001b\u001a\u00020\u0004J\u0010\u0010\u001f\u001a\u00020\u00042\u0006\u0010 \u001a\u00020\u0004H\u0002J\u000e\u0010!\u001a\u00020\u00042\u0006\u0010\"\u001a\u00020\u0004\u00a8\u0006#"}, d2 = {"Lcom/hdcollection/enforcement/gb28181/SipMessage;", "", "()V", "buildBye", "", "callId", "buildInviteOk", "fromHeader", "toHeader", "cseq", "localIp", "rtpPort", "", "ssrc", "buildRegister", "deviceId", "sipServer", "sipPort", "localPort", "expires", "buildRegisterWithAuth", "username", "password", "realm", "nonce", "buildSdp", "extractHeader", "message", "header", "extractNonce", "extractRealm", "extractVia", "headers", "md5", "input", "app_debug"})
public final class SipMessage {
    @org.jetbrains.annotations.NotNull()
    public static final com.hdcollection.enforcement.gb28181.SipMessage INSTANCE = null;
    
    private SipMessage() {
        super();
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String buildRegister(@org.jetbrains.annotations.NotNull()
    java.lang.String deviceId, @org.jetbrains.annotations.NotNull()
    java.lang.String sipServer, @org.jetbrains.annotations.NotNull()
    java.lang.String sipPort, @org.jetbrains.annotations.NotNull()
    java.lang.String localIp, int localPort, @org.jetbrains.annotations.NotNull()
    java.lang.String callId, int cseq, int expires) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String buildRegisterWithAuth(@org.jetbrains.annotations.NotNull()
    java.lang.String deviceId, @org.jetbrains.annotations.NotNull()
    java.lang.String sipServer, @org.jetbrains.annotations.NotNull()
    java.lang.String sipPort, @org.jetbrains.annotations.NotNull()
    java.lang.String localIp, int localPort, @org.jetbrains.annotations.NotNull()
    java.lang.String callId, int cseq, @org.jetbrains.annotations.NotNull()
    java.lang.String username, @org.jetbrains.annotations.NotNull()
    java.lang.String password, @org.jetbrains.annotations.NotNull()
    java.lang.String realm, @org.jetbrains.annotations.NotNull()
    java.lang.String nonce, int expires) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String buildInviteOk(@org.jetbrains.annotations.NotNull()
    java.lang.String callId, @org.jetbrains.annotations.NotNull()
    java.lang.String fromHeader, @org.jetbrains.annotations.NotNull()
    java.lang.String toHeader, @org.jetbrains.annotations.NotNull()
    java.lang.String cseq, @org.jetbrains.annotations.NotNull()
    java.lang.String localIp, int rtpPort, @org.jetbrains.annotations.NotNull()
    java.lang.String ssrc) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String buildBye(@org.jetbrains.annotations.NotNull()
    java.lang.String callId) {
        return null;
    }
    
    private final java.lang.String buildSdp(java.lang.String localIp, int rtpPort, java.lang.String ssrc) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String md5(@org.jetbrains.annotations.NotNull()
    java.lang.String input) {
        return null;
    }
    
    private final java.lang.String extractVia(java.lang.String headers) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.String extractHeader(@org.jetbrains.annotations.NotNull()
    java.lang.String message, @org.jetbrains.annotations.NotNull()
    java.lang.String header) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.String extractNonce(@org.jetbrains.annotations.NotNull()
    java.lang.String message) {
        return null;
    }
    
    @org.jetbrains.annotations.Nullable()
    public final java.lang.String extractRealm(@org.jetbrains.annotations.NotNull()
    java.lang.String message) {
        return null;
    }
}