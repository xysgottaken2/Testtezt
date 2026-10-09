.class public Lcom/activision/wzm/bootstrap/JsBridge;
.super Ljava/lang/Object;
.source "JsBridge.java"

.field private mLatch:Ljava/util/concurrent/CountDownLatch;

.method public passPreloginFence()V
    .registers 3
    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    const-string v0, "passPreloginFence"

    iget-object v1, v2, Lcom/activision/wzm/bootstrap/JsBridge;->mLatch:Ljava/util/concurrent/CountDownLatch;

    invoke-virtual {v1}, Ljava/util/concurrent/CountDownLatch;->countDown()V

    invoke-static {v0}, Lcom/activision/wzm/WZMNative;->dispatch(Ljava/lang/String;)V

    return-void
.end method

.method public saveKVP(Ljava/lang/String;Ljava/lang/String;)V
    .registers 4
    .annotation runtime Landroid/webkit/JavascriptInterface;
    .end annotation

    const-string v0, "game"

    const-string v1, "kvps"

    invoke-static {v0, v1, p1}, Lcom/activision/wzm/WZMNative;->setKVP(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V

    return-void
.end method

.method public CDNI_UseDevServer()V
    .registers 2

    const-string v0, "CDNI_UseDevServer"

    invoke-static {v0}, Lcom/activision/wzm/WZMNative;->dispatch(Ljava/lang/String;)V

    return-void
.end method

.method public native nativeDispatch(Ljava/lang/String;)V
.end method
