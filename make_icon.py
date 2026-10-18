import os
from PIL import Image, ImageDraw

S = 512


def build():
    grad = Image.new("RGBA", (S, S))
    dd = ImageDraw.Draw(grad)
    top = (0x2A, 0x2E, 0x38)
    bot = (0x0E, 0x10, 0x14)
    for y in range(S):
        t = y / (S - 1)
        color = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3))
        dd.line([(0, y), (S, y)], fill=color + (255,))

    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([24, 24, 488, 488], radius=118, fill=255)

    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    img.paste(grad, (0, 0), mask)

    d = ImageDraw.Draw(img)
    white = (242, 246, 249, 255)
    green = (52, 211, 153, 255)

    # 手机背面轮廓
    d.rounded_rectangle([132, 112, 288, 400], radius=36, outline=white, width=16)
    # 摄像头
    d.ellipse([154, 138, 182, 166], fill=white)

    # 敲击波纹（从机身右侧扩散）
    center_y = 256
    for rr in (48, 84, 120):
        d.arc([288 - rr, center_y - rr, 288 + rr, center_y + rr],
              start=-50, end=50, fill=green, width=18)
    return img


img = build()
img.save("/data/local/tmp/eta/icon_preview.png")

sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for name, px in sizes.items():
    folder = "/tmp/backtapx/res/mipmap-%s" % name
    os.makedirs(folder, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"))
print("generated")
