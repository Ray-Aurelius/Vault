import json, os, sys
OUT = sys.argv[1]; WM = json.load(open(sys.argv[2]))
BLUE, SKY, INK, WHITE, NAVY, MIST, STEEL = "#1F4E8C", "#8FB4E8", "#1A1F26", "#FFFFFF", "#0F1B2D", "#F2F4F7", "#5B6675"
WALL = "M14 20H30V32H42V20H58V32H70V20H86V84H14Z"
MORTAR_FULL = "M14 50H86M14 67H86M32 32V50M68 32V50M50 50V67M32 67V84M68 67V84"
MORTAR_SIMPLE = "M14 50H86M14 67H86M50 50V67"

def w(path, text):
    p = os.path.join(OUT, path); os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(text)

def mark(fill, cut=None, mortar=MORTAR_FULL, sw=3):
    s = f'<path d="{WALL}" fill="{fill}"/>'
    if cut: s += f'<path d="{mortar}" stroke="{cut}" stroke-width="{sw}" fill="none"/>'
    return s

def svg(vb, body, label="Bulwark"):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}" role="img" aria-label="{label}">{body}</svg>\n'

# 1. Standalone marks (transparent background; mortar lines cut out with a mask so they stay transparent)
def mark_masked(fill, mortar=MORTAR_FULL, sw=3, mid="m"):
    return (f'<defs><mask id="{mid}"><rect width="100" height="100" fill="#fff"/>'
            f'<path d="{mortar}" stroke="#000" stroke-width="{sw}" fill="none"/></mask></defs>'
            f'<path d="{WALL}" fill="{fill}" mask="url(#{mid})"/>')
for name, col in [("blue", BLUE), ("sky", SKY), ("white", WHITE), ("ink", INK)]:
    w(f"svg/mark-{name}.svg", svg("14 20 72 64", mark_masked(col)))

# 2. App icon, full-bleed square (Play Store applies its own rounding) and rounded preview
icon_body = f'<rect width="100" height="100" fill="{BLUE}"/><g transform="translate(19 18) scale(0.62)">{mark(WHITE, BLUE)}</g>'
w("svg/app-icon-square.svg", svg("0 0 100 100", icon_body))
w("svg/app-icon-rounded.svg", svg("0 0 100 100", f'<rect width="100" height="100" rx="22" fill="{BLUE}"/><g transform="translate(19 18) scale(0.62)">{mark(WHITE, BLUE)}</g>'))

# 3. Wordmark and lockups (Archivo ExtraBold outlines)
xmin, ymin, xmax, ymax = WM["bounds"]; d = WM["d"]
def wordmark_svg(col):
    pad = 40
    return svg(f"{xmin-pad} {ymin-pad} {xmax-xmin+2*pad} {ymax-ymin+2*pad}", f'<path d="{d}" fill="{col}"/>')
for name, col in [("blue", BLUE), ("ink", INK), ("white", WHITE)]:
    w(f"svg/wordmark-{name}.svg", wordmark_svg(col))

def lockup(mark_col, word_col, bg=None, mid="lk"):
    capH = -ymin; s = capH / 64.0; gap = 0.32 * capH
    mark_w = 72 * s; wx = mark_w + gap - xmin
    total_w = wx + xmax; pad = 0.25 * capH
    body = ""
    if bg: body += f'<rect x="{-pad}" y="{ymin-pad}" width="{total_w+2*pad}" height="{ymax-ymin+2*pad}" fill="{bg}"/>'
    body += f'<g transform="translate({-14*s} {ymin-20*s}) scale({s})">{mark_masked(mark_col, mid=mid)}</g>'
    body += f'<g transform="translate({wx} 0)"><path d="{d}" fill="{word_col}"/></g>'
    return svg(f"{-pad} {ymin-pad} {total_w+2*pad} {ymax-ymin+2*pad}", body)
w("svg/lockup-light.svg", lockup(BLUE, INK))
w("svg/lockup-dark.svg", lockup(SKY, "#E6EAF0", NAVY))
w("svg/lockup-white.svg", lockup(WHITE, WHITE))

# 4. Android adaptive icon (108dp canvas, 66dp safe zone). Mark scaled 0.6, centred.
S, TX, TY = 0.6, 24.0, 22.8
def P(path):  # pre-transform path for vector drawable: use a <group> instead
    return path
fg = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Bulwark launcher icon foreground: Rampart mark. 108dp canvas, mark kept inside the 66dp safe zone. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="{TX}" android:translateY="{TY}" android:scaleX="{S}" android:scaleY="{S}">
        <path android:fillColor="@android:color/white" android:pathData="{WALL}"/>
        <path android:strokeColor="@color/ic_launcher_background" android:strokeWidth="3" android:pathData="{MORTAR_FULL}"/>
    </group>
</vector>
'''
mono = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Monochrome layer for Android 13+ themed icons: solid wall silhouette (the system tints it). -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="{TX}" android:translateY="{TY}" android:scaleX="{S}" android:scaleY="{S}">
        <path android:fillColor="#FFFFFFFF" android:pathData="{WALL}"/>
    </group>
</vector>
'''
adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
'''
stat = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Status-bar / notification icon: white silhouette on transparent, 24dp. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="100" android:viewportHeight="100">
    <path android:fillColor="#FFFFFFFF" android:pathData="{WALL}"/>
</vector>
'''
colors = f'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">{BLUE}</color>
    <color name="bulwark_blue">{BLUE}</color>
    <color name="bulwark_sky">{SKY}</color>
    <color name="bulwark_steel">{STEEL}</color>
    <color name="bulwark_mist">{MIST}</color>
    <color name="bulwark_ink">{INK}</color>
    <color name="bulwark_navy">{NAVY}</color>
    <color name="bulwark_slate">#1C2430</color>
    <color name="bulwark_silver">#A7B0BC</color>
</resources>
'''
w("android/res/drawable/ic_launcher_foreground.xml", fg)
w("android/res/drawable/ic_launcher_monochrome.xml", mono)
w("android/res/drawable/ic_stat_bulwark.xml", stat)
w("android/res/mipmap-anydpi/ic_launcher.xml", adaptive)
w("android/res/mipmap-anydpi/ic_launcher_round.xml", adaptive)
w("android/res/values/brand_colors.xml", colors)
print("ok")
