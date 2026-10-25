# Media fixtures

Repository-owned synthetic 4x3 fixtures, generated with Pillow. No external photographs or metadata.

Equivalent regeneration example (encoded bytes can vary with the Pillow/libwebp version):

```python
from PIL import Image
alpha = Image.new("RGBA", (4, 3), (220, 40, 60, 128))
alpha.save("alpha.webp", lossless=True)
alpha.convert("RGB").save("lossy.webp", quality=80)
alpha.save("animated.webp", save_all=True,
           append_images=[Image.new("RGBA", (4, 3), (30, 100, 200, 255))],
           duration=100, loop=0)
```

Tests generate JPEG/PNG, valid-CRC APNG/chunk boundaries and a deterministic noisy JPEG for the output-size boundary at runtime.
