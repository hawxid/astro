# Large native libraries (split storage)

Three large files are stored here as 2 MB chunks because GitHub's API
rejects single-file uploads above ~5 MB:

| Original file | Chunks |
|---|---|
| `app/src/main/jniLibs/arm64-v8a/libqemu.so` | `libqemu.so.00` … `libqemu.so.20` |
| `app/src/main/jniLibs/arm64-v8a/libuml.so` | `libuml.so.00` … `libuml.so.09` |
| `terminal/src/main/assets/fonts/ZedMonoMedium.ttf` | `ZedMonoMedium.ttf.00` … `.02` |

The GitHub Actions workflow reassembles them before the Gradle build and
verifies them against `sha256sums.txt`. The original paths are listed in
`.gitignore` so they are never committed directly.

If a library is ever updated, re-split it with:

```sh
split -b 2M -d --suffix-length=2 <file> vendor-libs/jni-arm64/<name>.
```

and regenerate `sha256sums.txt` from the new originals.
