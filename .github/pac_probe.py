import os,urllib.request,json,time
os.makedirs("sat",exist_ok=True)
def get(u,p):
    try:
        r=urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"probe"}),timeout=30);d=r.read();open(p,"wb").write(d);return f"{r.status} {len(d)}"
    except Exception as e:return f"ERR {e}"
log=[]
for l in ["goes_west_fulldisk_ch13","goes_west_fulldisk_ch02","goes_west_conus_ch13","goes_west_conus_ch02"]:
    for z,xs,ys in [(4,range(0,4),range(4,8)),(5,range(1,7),range(10,14))]:
        for x in xs:
            for y in ys:
                os.makedirs(f"sat/{l}/{z}/{x}",exist_ok=True)
                log.append(f"{l} {z}/{x}/{y} "+get(f"https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/{l}/{z}/{x}/{y}.png",f"sat/{l}/{z}/{x}/{y}.png"))
for l in ["G18-ABI-FD-BAND09","G18-ABI-CONUS-BAND09","G18-ABI-FD-BAND13"]:
    for z,x,y in [(4,2,6),(5,4,12)]:
        os.makedirs(f"sat/{l}",exist_ok=True)
        log.append(f"{l} {z}/{x}/{y} "+get(f"https://realearth.ssec.wisc.edu/tiles/{l}/{z}/{x}/{y}.png",f"sat/{l}/{z}_{x}_{y}.png"))
open("sat/log.txt","w").write("\n".join(log))
