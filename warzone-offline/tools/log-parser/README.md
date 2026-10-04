# tools/log-parser

Parser de `adb logcat` para extrair hosts/ports tentados pelo cliente.

## Uso

```bash
adb logcat | tee /tmp/wzm/logcat.txt
python3 parser.py /tmp/wzm/logcat.txt
```

Filtra linhas com `demonware`, `activision`, `cdn`, `http`, `tls`, `auth`, `lsg`, `manifest`, `shard`.
