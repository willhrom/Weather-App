import urllib.request, json, io, time
from PIL import Image
import numpy as np
S="https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer"
def get(u):
    t=time.time()
    with urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"wx-probe"}),timeout=120) as r: return r.read(),time.time()-t
info=json.loads(get(S+"?f=pjson")[0]);te=info["timeInfo"]["timeExtent"]
out=[f"extent {te} now {int(time.time()*1000)} lag_min {(time.time()*1000-te[1])/6e4:.1f} span_min {(te[1]-te[0])/6e4:.1f}"]
for off in [0,-1,-3,-7,-13,-60,-115,+5]:
    t=te[1]+off*60000
    b,dt=get(f"{S}/exportImage?bbox=-100,30,-70,48&bboxSR=4326&imageSR=4326&size=600,360&format=png32&f=image&time={t}")
    a=np.array(Image.open(io.BytesIO(b)).convert("RGBA"));out.append(f"off {off:+} min: echo px {(a[:,:,3]>0).sum()} hash {hash(a.tobytes())%100000} {dt:.2f}s")
# catalog of raster times
try:
    q,_=get(S+"/query?where=1%3D1&outFields=*&returnGeometry=false&f=pjson&resultRecordCount=50");j=json.loads(q)
    out.append("query fields "+str([f["name"] for f in j.get("fields",[])]))
    out.append("rows "+json.dumps([r["attributes"] for r in j.get("features",[])][:40])[:3000])
except Exception as e: out.append("query err "+str(e))
open("report.txt","w").write("\n".join(out));print("\n".join(out))
