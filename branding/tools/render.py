import sys, os, pathlib
from playwright.sync_api import sync_playwright
B = pathlib.Path(sys.argv[1]); svgdir = B/"svg"; png = B/"png"; png.mkdir(exist_ok=True)
jobs = [("app-icon-square.svg","play-store-icon-512.png",512,512),
        ("app-icon-rounded.svg","app-icon-rounded-1024.png",1024,1024),
        ("lockup-light.svg","lockup-light-2000.png",2000,None),
        ("lockup-dark.svg","lockup-dark-2000.png",2000,None),
        ("mark-blue.svg","mark-blue-1024.png",1024,None)]
with sync_playwright() as p:
    br = p.chromium.launch(); pg = br.new_page()
    for src, out, wd, ht in jobs:
        s = (svgdir/src).read_text()
        vb = [float(v) for v in s.split('viewBox="')[1].split('"')[0].split()]
        h = ht or round(wd * vb[3] / vb[2])
        pg.set_viewport_size({"width": wd, "height": h})
        pg.set_content(f'<html><body style="margin:0;background:transparent">{s.replace("<svg ", f"<svg width=\"{wd}\" height=\"{h}\" ",1)}</body></html>')
        pg.screenshot(path=str(png/out), omit_background=True, clip={"x":0,"y":0,"width":wd,"height":h})
        print(out, wd, h)
    # preview sheet: adaptive icon masks + sizes + lockups
    fg = '<g transform="translate(24 22.8) scale(0.6)"><path d="M14 20H30V32H42V20H58V32H70V20H86V84H14Z" fill="#fff"/><path d="M14 50H86M14 67H86M32 32V50M68 32V50M50 50V67M32 67V84M68 67V84" stroke="#1F4E8C" stroke-width="3" fill="none"/></g>'
    def ad(shape):
        clip = {"circle":'<circle cx="54" cy="54" r="54"/>',"squircle":'<rect x="0" y="0" width="108" height="108" rx="36"/>',"square":'<rect x="0" y="0" width="108" height="108" rx="14"/>'}[shape]
        return f'<svg viewBox="9 9 90 90" width="140" height="140"><defs><clipPath id="c{shape}">{clip}</clipPath></defs><g clip-path="url(#c{shape})"><rect width="108" height="108" fill="#1F4E8C"/>{fg}</g><circle cx="54" cy="54" r="33" fill="none" stroke="#F5A524" stroke-dasharray="2 2" stroke-width="0.6"/></svg>'
    mono = '<svg viewBox="9 9 90 90" width="140" height="140"><circle cx="54" cy="54" r="45" fill="#D7E3F8"/><g transform="translate(24 22.8) scale(0.6)"><path d="M14 20H30V32H42V20H58V32H70V20H86V84H14Z" fill="#1F4E8C"/></g></svg>'
    lk = (svgdir/"lockup-light.svg").read_text().replace("<svg ","<svg height=\"90\" ",1)
    lkd = (svgdir/"lockup-dark.svg").read_text().replace("<svg ","<svg height=\"110\" ",1)
    html = f'''<html><body style="margin:0;padding:32px;background:#F2F4F7;font-family:sans-serif;color:#1A1F26">
    <p>Adaptive icon in circle, squircle and rounded-square masks (dashed = 66dp safe zone), themed mono:</p>
    <div style="display:flex;gap:28px;align-items:center">{ad("circle")}{ad("squircle")}{ad("square")}{mono}
    <img src="file://{png}/play-store-icon-512.png" width="140" style="border-radius:28px"></div>
    <p>Lockups:</p><div style="display:flex;flex-direction:column;gap:20px">{lk}{lkd}</div></body></html>'''
    pg.set_viewport_size({"width":1100,"height":620}); pg.set_content(html); pg.wait_for_timeout(200)
    pg.screenshot(path=sys.argv[2]); br.close()
