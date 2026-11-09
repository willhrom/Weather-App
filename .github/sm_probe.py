import os,json,math,urllib.request
RE="https://realearth.ssec.wisc.edu"
def get(u):
    return urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"probe"}),timeout=30).read()
os.makedirs("re",exist_ok=True)
times=json.loads(get(RE+"/api/times?products=HRRR-smoke-surface,GOESWestGLMFEDRadC"))
open("re/times.json","w").write(json.dumps(times))
t=times["HRRR-smoke-surface"][len(times["HRRR-smoke-surface"])//3]
def tx(lon,z):return int((lon+180)/360*2**z)
def ty(lat,z):return int((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**z)
log=[]
for prod,path in [("HRRR-smoke-surface",f"HRRR-smoke-surface/{t[:8]}/{t[9:]}"),("GOESWestGLMFEDRadC","GOESWestGLMFEDRadC"),("NOAA-SPC-fire-wx","NOAA-SPC-fire-wx")]:
    for z in range(4,10):
        cx,cy=tx(-121,z),ty(37.5,z);r=1 if z<8 else 2
        for x in range(cx-r,cx+r+1):
            for y in range(cy-r,cy+r+1):
                d=f"re/{prod}/{z}/{x}";os.makedirs(d,exist_ok=True)
                try:open(f"{d}/{y}.png","wb").write(get(f"{RE}/tiles/{path}/{z}/{x}/{y}.png"));log.append(f"{prod} {z}/{x}/{y} ok")
                except Exception as e:log.append(f"{prod} {z}/{x}/{y} {e}")
open("re/log.txt","w").write("smoke time "+t+"\n"+"\n".join(log))
