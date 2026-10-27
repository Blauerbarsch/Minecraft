"""Erzeugt design/fonts.css: die drei Schriften als eingebettete WOFF-Dateien (nur lateinischer Zeichensatz).

Quelle der TTF-Dateien: https://github.com/google/fonts (ofl/pixelifysans, ofl/rubik, ofl/jetbrainsmono).
Aufruf:  python3 build-fonts.py <ordner-mit-google-fonts-checkout>     (braucht fontTools)
"""
import base64, io, os, sys
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib import instancer

src = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
U = 'U+0000-00FF,U+0131,U+0152-0153,U+02C6,U+02DA,U+02DC,U+2000-206F,U+20AC,U+2122,U+2190-2193,U+2212,U+00D7,U+2714,U+2715,U+25B4-25BE'
fonts = [('Pixelify Sans', 'ofl/pixelifysans/PixelifySans[wght].ttf', (500, 700)),
         ('Rubik', 'ofl/rubik/Rubik[wght].ttf', (400, 700)),
         ('JetBrains Mono', 'ofl/jetbrainsmono/JetBrainsMono[wght].ttf', (400, 500))]
css = ['/* Eingebettete Schriften (SIL Open Font License 1.1, siehe design/fonts/OFL-*.txt).',
       '   Erzeugt von design/fonts/build-fonts.py – nicht von Hand ändern. */']
for fam, path, (lo, hi) in fonts:
    f = instancer.instantiateVariableFont(TTFont(os.path.join(src, path)), {'wght': (lo, hi)})
    tmp = io.BytesIO(); f.save(tmp); tmp.seek(0); f = TTFont(tmp)
    opts = subset.Options(); opts.layout_features = ['*']; opts.name_IDs = ['*']; opts.notdef_outline = True
    sub = subset.Subsetter(opts); sub.populate(unicodes=subset.parse_unicodes(U)); sub.subset(f)
    f.flavor = 'woff'; out = io.BytesIO(); f.save(out); data = out.getvalue()
    print(f'{fam}: {len(data) // 1024} KB')
    css.append("@font-face{font-family:'%s';font-style:normal;font-weight:%d %d;font-display:swap;"
               "src:url(data:font/woff;base64,%s) format('woff')}" % (fam, lo, hi, base64.b64encode(data).decode()))
target = os.path.join(here, '..', 'fonts.css')
open(target, 'w', encoding='utf-8').write('\n'.join(css) + '\n')
print('fonts.css:', os.path.getsize(target) // 1024, 'KB')
