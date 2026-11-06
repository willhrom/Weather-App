import urllib.request, json, io, re
from PIL import Image
import numpy as np
S="https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer"
def get(u):
    with urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"wx-probe"}),timeout=120) as r: return r.read()
pal=re.search(r'const N0Q_PAL="([0-9a-f]+)"',open("../web/index.html").read()).group(1)
P=np.array([[int(pal[i*6:i*6+2],16),int(pal[i*6+2:i*6+4],16),int(pal[i*6+4:i*6+6],16)] for i in range(256)])
iem=Image.open(io.BytesIO(get("https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0.png")))
W,H=3000,1300
idx=np.array(iem.resize((W,H),Image.NEAREST)) if iem.mode=="P" else None
info=json.loads(get(S+"?f=pjson"));t=info["timeInfo"]["timeExtent"][1]
m=np.array(Image.open(io.BytesIO(get(f"{S}/exportImage?bbox=-126,24,-66,50&bboxSR=4326&imageSR=4326&size={W},{H}&format=png32&f=image&time={t}"))).convert("RGBA"))
dbz=idx*0.5-32.5
ok=(m[:,:,3]>200)&(idx>0)
rgb=m[:,:,:3][ok].astype(float);d=dbz[ok]
out=[f"pairs {ok.sum()}  mrms echo px {(m[:,:,3]>0).sum()}  iem echo px {(idx>0).sum()}  iem>=10 {(dbz>=10).sum()}  mrms-only px {((m[:,:,3]>0)&(idx==0)).sum()}"]
# median colour per dBZ bin -> ramp
stops=[]
for b in range(-30,76,1):
    s=(d>=b)&(d<b+1)
    if s.sum()>=40: c=np.median(rgb[s],0);stops.append((b,[int(x) for x in c],int(s.sum())))
out.append("ramp (dBZ, median MRMS rgb, n): "+json.dumps(stops))
# reverse: for each MRMS colour, how consistent is the dBZ? spread of d among pixels with near-identical colour
q=(rgb//4).astype(int);key=q[:,0]*10000+q[:,1]*100+q[:,2]
order=np.argsort(key);ks=key[order];ds=d[order];spl=np.flatnonzero(np.diff(ks))+1
groups=[g for g in np.split(ds,spl) if len(g)>=30]
out.append("per-colour dBZ IQR median: %.2f, groups %d"%(np.median([np.percentile(g,75)-np.percentile(g,25) for g in groups]),len(groups)))
# alpha values used
out.append("alpha values "+str(np.unique(m[:,:,3])[:20].tolist()))
Image.fromarray(m).save("mrms_conus.png")
open("report.txt","w").write("\n".join(out));print("\n".join(out))
