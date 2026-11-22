"""Make a labeled contact sheet from public proof crops only; does not judge them."""
import argparse,json
from pathlib import Path
from PIL import Image,ImageDraw
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('batch');p.add_argument('--limit',type=int,default=12);a=p.parse_args()
cases=[d for d in sorted(a.root.iterdir()) if (d/'evidence_first.png').exists() and not (d/'screen-review.json').exists()][:a.limit]
if not cases:raise SystemExit('No unreviewed evidence')
canvas=Image.new('RGB',(1200,150*len(cases)),'#dddddd');draw=ImageDraw.Draw(canvas)
for i,d in enumerate(cases):
    draw.text((8,i*150+2),d.name,fill='black')
    for j,k in enumerate(('first','last')):
        im=Image.open(d/('evidence_'+k+'.png'));im.thumbnail((580,118));canvas.paste(im,(8+600*j,i*150+25))
out=a.root.parent/a.batch;canvas.save(out.with_suffix('.png'));out.with_suffix('.json').write_text(json.dumps([d.name for d in cases],indent=2)+'\n');print(len(cases))
