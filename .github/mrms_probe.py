import math, json, time, subprocess, urllib.request, glob
S="https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer"
def get(u, fn, hdr=None):
    t=time.time()
    try:
        req=urllib.request.Request(u, headers=hdr or {"User-Agent":"wx-probe"})
        with urllib.request.urlopen(req, timeout=60) as r:
            b=r.read(); h=dict(r.headers)
        open(fn,"wb").write(b); return f"{fn} 200 {len(b)} {time.time()-t:.2f}s ct={h.get('Content-Type')} acao={h.get('Access-Control-Allow-Origin')} cc={h.get('Cache-Control')}"
    except Exception as e: return f"{fn} ERR {e} {time.time()-t:.2f}s"
out=[]
for q,fn in [("?f=pjson","info.json"),("/rasterFunctionInfos?f=pjson","rf.json"),("/keyProperties?f=pjson","kp.json"),("/legend?f=pjson","legend.json")]:
    out.append(get(S+q,fn))
z,x,y=8,41,98;R=20037508.342789244;s=2*R/2**z
B=f"{-R+x*s},{R-(y+1)*s},{-R+(x+1)*s},{R-y*s}"
base=f"{S}/exportImage?bbox={B}&bboxSR=3857&imageSR=3857&size=256,256&f=image"
for f in ["png","png8","png32","tiff","lerc"]:
    out.append(get(base+f"&format={f}",f"img_{f}.bin",{"User-Agent":"wx-probe","Origin":"https://willhrom-weather.pages.dev"}))
rr='&renderingRule=%7B%22rasterFunction%22%3A%22None%22%7D'
out.append(get(base+"&format=tiff&pixelType=F32"+rr,"img_rawtiff.bin"))
out.append(get(base+"&format=png"+rr,"img_rawpng.bin"))
out.append(get(base.replace("f=image","f=pjson")+"&format=png","export.json"))
try:
    info=json.load(open("info.json"));ti=info.get("timeInfo",{});out.append("timeExtent "+str(ti.get("timeExtent"))+" pixelType "+str(info.get("pixelType"))+" bands "+str(info.get("bandCount"))+" allowRasterFunction "+str(info.get("allowRasterFunction"))+" min/max "+str(info.get("minValues"))+str(info.get("maxValues")))
    te=ti.get("timeExtent")
    if te:
        for k in (0,1,2):
            t=te[1]-k*600000
            out.append(get(base+f"&format=png&time={t}",f"img_t{k}.bin"))
except Exception as e: out.append("info err "+str(e))
from PIL import Image
import numpy as np
for f in sorted(glob.glob("img_*.bin")):
    try:
        im=Image.open(f);a=np.array(im);u=np.unique(a.reshape(-1,a.shape[-1]) if a.ndim==3 else a.reshape(-1),axis=0)
        out.append(f"{f} {im.mode} {im.size} {a.dtype} unique={len(u)} sample={u[:10].tolist()} last={u[-5:].tolist()}")
        im.convert("RGBA").save(f.replace(".bin",".png"))
    except Exception as e: out.append(f"{f} notimg {open(f,'rb').read(160)}")
open("report.txt","w").write("\n".join(out)); print("\n".join(out))
