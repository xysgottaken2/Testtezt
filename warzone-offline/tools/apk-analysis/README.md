# tools/apk-analysis

Ferramentas para inspeção de APK/XAPK **obtidos legalmente**.

## Uso (quando APK preservado disponível)

```bash
# fingerprint
aapt dump badging warzone.apk | grep -E "package|version"
unzip -l warzone.apk | grep -E "lib/|split_asset|shard"

# decode
apktool d warzone.apk -o /tmp/wzm/apktool-output
jadx -d /tmp/wzm/jadx-output warzone.apk

# endpoints
grep -R "https://\|demonware\|activision\|cdn\.\|analytic\|3074" /tmp/wzm/jadx-output --include="*.java"
strings lib/arm64-v8a/libgame.so | grep -i "demonware\|https\|3074"
```

Nunca commitar APKs/shards — `.gitignore` bloqueia.
