.class public Lcom/activision/wzm/bootstrap/WBootstrapView;
.super Landroid/webkit/WebView;
.source "WBootstrapView.java"

.field private final mBridge:Lcom/activision/wzm/bootstrap/JsBridge;

.method public constructor <init>(Landroid/content/Context;)V
    .registers 4

    invoke-direct {v0, v1}, Landroid/webkit/WebView;-><init>(Landroid/content/Context;)V

    new-instance v2, Lcom/activision/wzm/bootstrap/JsBridge;

    invoke-direct {v2, v0}, Lcom/activision/wzm/bootstrap/JsBridge;-><init>(Landroid/webkit/WebView;)V

    iput-object v2, v0, Lcom/activision/wzm/bootstrap/WBootstrapView;->mBridge:Lcom/activision/wzm/bootstrap/JsBridge;

    const-string v3, "cod__"

    invoke-virtual {v0, v2, v3}, Landroid/webkit/WebView;->addJavascriptInterface(Ljava/lang/Object;Ljava/lang/String;)V

    return-void
.end method

.method public loadWeb(Ljava/lang/String;)V
    .registers 3

    iget-object v0, v1, Lcom/activision/wzm/bootstrap/WBootstrapView;->mBridge:Lcom/activision/wzm/bootstrap/JsBridge;

    const-string v2, "https://prod.cdni.callofduty.com/bootstrap/index.html?env=prod&feature=prelogin"

    invoke-virtual {v1, v2}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    return-void
.end method
