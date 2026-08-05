
# F-Droid submission files

The proposed application metadata is stored at:

```text
metadata/com.thecryptosteer.xmrminer.yml
```

Before submitting it to `fdroiddata`, validate it with a current `fdroidserver` installation:

```bash
fdroid lint com.thecryptosteer.xmrminer
fdroid rewritemeta com.thecryptosteer.xmrminer
fdroid build --server --test com.thecryptosteer.xmrminer:20201
```
