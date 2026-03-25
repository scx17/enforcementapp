package com.hdcollection.enforcement.service;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u0000(\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\u0018\u00002\u00020\u0001B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\"\u0010\b\u001a\u00020\t2\f\u0010\n\u001a\b\u0012\u0004\u0012\u00020\t0\u000b2\f\u0010\f\u001a\b\u0012\u0004\u0012\u00020\t0\u000bR\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u0011\u0010\u0005\u001a\u00020\u00068F\u00a2\u0006\u0006\u001a\u0004\b\u0005\u0010\u0007\u00a8\u0006\r"}, d2 = {"Lcom/hdcollection/enforcement/service/NetworkMonitor;", "", "cm", "Landroid/net/ConnectivityManager;", "(Landroid/net/ConnectivityManager;)V", "isOnline", "", "()Z", "registerCallback", "", "onAvailable", "Lkotlin/Function0;", "onLost", "app_debug"})
public final class NetworkMonitor {
    @org.jetbrains.annotations.NotNull()
    private final android.net.ConnectivityManager cm = null;
    
    public NetworkMonitor(@org.jetbrains.annotations.NotNull()
    android.net.ConnectivityManager cm) {
        super();
    }
    
    public final boolean isOnline() {
        return false;
    }
    
    public final void registerCallback(@org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function0<kotlin.Unit> onAvailable, @org.jetbrains.annotations.NotNull()
    kotlin.jvm.functions.Function0<kotlin.Unit> onLost) {
    }
}