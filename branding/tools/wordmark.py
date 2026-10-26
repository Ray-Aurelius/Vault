import sys, json
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.boundsPen import BoundsPen

src, out = sys.argv[1], sys.argv[2]
f = instantiateVariableFont(TTFont(src), {"wght": 800, "wdth": 100})
upem = f["head"].unitsPerEm
cmap = f.getBestCmap(); gs = f.getGlyphSet(); hmtx = f["hmtx"]
text = "Bulwark"
names = [cmap[ord(c)] for c in text]
# kerning via GPOS is complex; apply small manual tracking instead
tracking = -0.01 * upem
pen = SVGPathPen(gs); x = 0; bp = BoundsPen(gs)
for n in names:
    tp = TransformPen(pen, (1, 0, 0, -1, x, 0)); gs[n].draw(tp)
    tb = TransformPen(bp, (1, 0, 0, -1, x, 0)); gs[n].draw(tb)
    x += hmtx[n][0] + tracking
xmin, ymin, xmax, ymax = bp.bounds
json.dump({"d": pen.getCommands(), "bounds": [xmin, ymin, xmax, ymax], "upem": upem}, open(out, "w"))
print(upem, [round(v) for v in bp.bounds])
