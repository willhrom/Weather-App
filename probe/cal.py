import os,sys,io,json,numpy as np
sys.path.insert(0,"probe");import build_gfs as B
from PIL import Image
os.makedirs("out",exist_ok=True)
m=B.S.get(B.MOSAIC+".json",timeout=30).text;print("META",m[:300])
im=Image.open(io.BytesIO(B.S.get(B.MOSAIC+".png",timeout=120).content));print("MODE",im.mode,im.size)
pal=im.getpalette();print("PAL",[(i,pal[3*i:3*i+3]) for i in (0,1,40,65,80,100,120,140,160,180,200)])
cyc,day,hh=B.latest_cycle()
land=B.grab(B.fname(day,hh,0),"LAND","surface");cw,lat0,lon0=B.coast_weight(land),*B.subset(land)[1:]
rw,rd=B.radar_weight(lat0,lon0,cw.shape)
radar,valid=B.radar_now(lat0,lon0,cw.shape)
f=int(round((valid-cyc).total_seconds()/3600));print("cycle",cyc,"valid",valid,"f",f)
mn=B.gaussian_filter(np.maximum(B.subset(B.grab(B.fname(day,hh,f),"REFC","entire atmosphere"))[0],-20),.6,mode="nearest")
bias=B.radar_bias(mn,radar,rd)
# crop: lat 50..22, lon -135..-60
i0,i1=int((lat0-52)/.25),int((lat0-20)/.25);j0,j1=int((-140-lon0)/.25),int((-58-lon0)/.25)
def img(a,lo,hi,name,cmap):
    a=a[i0:i1,j0:j1];t=np.clip((np.nan_to_num(a,nan=lo)-lo)/(hi-lo),0,1)
    rgb=np.stack([cmap[0](t),cmap[1](t),cmap[2](t)],-1).astype(np.uint8);rgb[~np.isfinite(a)]=(40,40,40)
    Image.fromarray(rgb).resize((rgb.shape[1]*3,rgb.shape[0]*3),Image.NEAREST).save(f"out/{name}.png")
g=(lambda t:t*255,lambda t:t*255,lambda t:t*255)
img(radar,0,50,"radar",g);img(mn,0,50,"model",g)
img(bias,-10,10,"bias",(lambda t:np.where(t>.5,255,t*2*255),lambda t:255-abs(t-.5)*2*255,lambda t:np.where(t<.5,255,(1-t)*2*255)))
img(rw,0,1,"rw",g)
json.dump({"f":f,"valid":str(valid),"bias_min":float(bias.min()),"bias_max":float(bias.max())},open("out/log.json","w"))
