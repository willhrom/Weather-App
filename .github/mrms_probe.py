import time, urllib.request, json
from PIL import Image
import numpy as np, io
S="https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer"
def get(u):
    with urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"wx-probe"}),timeout=90) as r: return r.read()
out=[]
# western US, same bbox for both
W,Sx,E,N=-125,32,-113,42.5
m=get(f"{S}/exportImage?bbox={W},{Sx},{E},{N}&bboxSR=4326&imageSR=4326&size=1440,1260&format=png32&f=image")
open("mrms_west.png","wb").write(m)
iem=Image.open(io.BytesIO(get("https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0.png")))
w,h=iem.size;dx=60/w;dy=26/h
c=iem.crop((int((W+126)/dx),int((50-N)/dy),int((E+126)/dx),int((50-Sx)/dy))).convert("RGBA").resize((1440,1260),Image.NEAREST)
c.save("iem_west.png")
a=np.array(Image.open("mrms_west.png").convert("RGBA")).reshape(-1,4);a=a[a[:,3]>0]
u,cnt=np.unique(a[:,:3],axis=0,return_counts=True)
o=np.argsort(-cnt)
out.append(f"MRMS colours: {len(u)} unique, top: "+str([(tuple(u[i].tolist()),int(cnt[i])) for i in o[:40]]))
# full CONUS palette sample
m2=get(f"{S}/exportImage?bbox=-125,24,-66,50&bboxSR=4326&imageSR=4326&size=2400,1060&format=png32&f=image")
open("mrms_conus.png","wb").write(m2)
a=np.array(Image.open(io.BytesIO(m2)).convert("RGBA")).reshape(-1,4);a=a[a[:,3]>0]
u,cnt=np.unique(a[:,:3],axis=0,return_counts=True)
out.append(f"CONUS colours: {len(u)} unique: "+str(sorted([(tuple(u[i].tolist()),int(cnt[i])) for i in range(len(u)) if cnt[i]>30],key=lambda t:-t[1])[:60]))
info=json.loads(get(S+"?f=pjson"));out.append("timeExtent "+str(info["timeInfo"]["timeExtent"]))
open("report.txt","w").write("\n".join(out));print("\n".join(out))
