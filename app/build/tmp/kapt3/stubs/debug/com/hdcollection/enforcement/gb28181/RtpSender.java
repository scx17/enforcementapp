package com.hdcollection.enforcement.gb28181;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000@\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0010\u000e\n\u0000\n\u0002\u0010\b\n\u0002\b\u0004\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0012\n\u0002\b\u0005\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0002\b\u0002\n\u0002\u0010\t\n\u0002\b\u0003\u0018\u00002\u00020\u0001B\u0015\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u0012\u0006\u0010\u0004\u001a\u00020\u0005\u00a2\u0006\u0002\u0010\u0006J0\u0010\f\u001a\u00020\r2\u0006\u0010\u000e\u001a\u00020\r2\u0006\u0010\u000f\u001a\u00020\u00052\u0006\u0010\u0010\u001a\u00020\u00052\u0006\u0010\u0011\u001a\u00020\u00052\u0006\u0010\u0012\u001a\u00020\u0013H\u0002J\u0006\u0010\u0014\u001a\u00020\u0003J\u0016\u0010\u0015\u001a\u00020\u00162\u0006\u0010\u0017\u001a\u00020\r2\u0006\u0010\u0018\u001a\u00020\u0019J\u0006\u0010\u001a\u001a\u00020\u0016J\u0006\u0010\u001b\u001a\u00020\u0016R\u000e\u0010\u0007\u001a\u00020\u0005X\u0082D\u00a2\u0006\u0002\n\u0000R\u000e\u0010\b\u001a\u00020\u0005X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u0010\u0010\t\u001a\u0004\u0018\u00010\nX\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u000b\u001a\u00020\u0005X\u0082\u000e\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0004\u001a\u00020\u0005X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u001c"}, d2 = {"Lcom/hdcollection/enforcement/gb28181/RtpSender;", "", "targetIp", "", "targetPort", "", "(Ljava/lang/String;I)V", "payloadType", "sequenceNumber", "socket", "Ljava/net/DatagramSocket;", "ssrc", "buildRtpPacket", "", "data", "offset", "length", "timestamp", "marker", "", "getSsrc", "sendVideoFrame", "", "encodedData", "timestampMs", "", "start", "stop", "app_debug"})
public final class RtpSender {
    @org.jetbrains.annotations.NotNull()
    private final java.lang.String targetIp = null;
    private final int targetPort = 0;
    @org.jetbrains.annotations.Nullable()
    private java.net.DatagramSocket socket;
    private int sequenceNumber;
    private int ssrc;
    private final int payloadType = 96;
    
    public RtpSender(@org.jetbrains.annotations.NotNull()
    java.lang.String targetIp, int targetPort) {
        super();
    }
    
    public final void start() {
    }
    
    public final void sendVideoFrame(@org.jetbrains.annotations.NotNull()
    byte[] encodedData, long timestampMs) {
    }
    
    private final byte[] buildRtpPacket(byte[] data, int offset, int length, int timestamp, boolean marker) {
        return null;
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.lang.String getSsrc() {
        return null;
    }
    
    public final void stop() {
    }
}