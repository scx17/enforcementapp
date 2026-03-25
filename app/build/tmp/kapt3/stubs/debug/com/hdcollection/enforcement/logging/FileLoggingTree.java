package com.hdcollection.enforcement.logging;

@kotlin.Metadata(mv = {1, 9, 0}, k = 1, xi = 48, d1 = {"\u00004\n\u0002\u0018\u0002\n\u0002\u0018\u0002\n\u0000\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0018\u0002\n\u0002\b\u0002\n\u0002\u0010\u0002\n\u0000\n\u0002\u0010\b\n\u0000\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010\u0003\n\u0000\u0018\u00002\u00020\u0001B\r\u0012\u0006\u0010\u0002\u001a\u00020\u0003\u00a2\u0006\u0002\u0010\u0004J\u0006\u0010\u0007\u001a\u00020\u0003J,\u0010\b\u001a\u00020\t2\u0006\u0010\n\u001a\u00020\u000b2\b\u0010\f\u001a\u0004\u0018\u00010\r2\u0006\u0010\u000e\u001a\u00020\r2\b\u0010\u000f\u001a\u0004\u0018\u00010\u0010H\u0014R\u000e\u0010\u0005\u001a\u00020\u0006X\u0082\u0004\u00a2\u0006\u0002\n\u0000R\u000e\u0010\u0002\u001a\u00020\u0003X\u0082\u0004\u00a2\u0006\u0002\n\u0000\u00a8\u0006\u0011"}, d2 = {"Lcom/hdcollection/enforcement/logging/FileLoggingTree;", "Ltimber/log/Timber$Tree;", "logFile", "Ljava/io/File;", "(Ljava/io/File;)V", "dateFormat", "Ljava/text/SimpleDateFormat;", "getLogFile", "log", "", "priority", "", "tag", "", "message", "t", "", "app_debug"})
public final class FileLoggingTree extends timber.log.Timber.Tree {
    @org.jetbrains.annotations.NotNull()
    private final java.io.File logFile = null;
    @org.jetbrains.annotations.NotNull()
    private final java.text.SimpleDateFormat dateFormat = null;
    
    public FileLoggingTree(@org.jetbrains.annotations.NotNull()
    java.io.File logFile) {
        super();
    }
    
    @java.lang.Override()
    protected void log(int priority, @org.jetbrains.annotations.Nullable()
    java.lang.String tag, @org.jetbrains.annotations.NotNull()
    java.lang.String message, @org.jetbrains.annotations.Nullable()
    java.lang.Throwable t) {
    }
    
    @org.jetbrains.annotations.NotNull()
    public final java.io.File getLogFile() {
        return null;
    }
}