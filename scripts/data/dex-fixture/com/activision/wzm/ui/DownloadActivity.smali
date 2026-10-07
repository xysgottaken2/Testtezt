.class public Lcom/activision/wzm/ui/DownloadActivity;
.super Landroid/app/Activity;
.source "DownloadActivity.java"

.field private mWeb:Landroid/webkit/WebView;

.method protected onCreate(Landroid/os/Bundle;)V
    .registers 4

    invoke-virtual {v0, v1}, Lcom/activision/wzm/ui/DownloadActivity;->findViewById(I)Landroid/view/View;

    move-result-object v2

    iput-object v2, v0, Lcom/activision/wzm/ui/DownloadActivity;->mWeb:Landroid/webkit/WebView;

    const-string v3, "https://prod.cdni.callofduty.com/prelogin/web/index.html"

    invoke-virtual {v2, v3}, Landroid/webkit/WebView;->loadUrl(Ljava/lang/String;)V

    invoke-virtual {v2}, Landroid/webkit/WebView;->getSettings()Landroid/webkit/WebSettings;

    move-result-object v1

    invoke-virtual {v1, v4}, Landroid/webkit/WebSettings;->setJavaScriptEnabled(Z)V

    return-void
.end method
