import os, json, time, math, requests
from concurrent.futures import ThreadPoolExecutor
S=requests.Session(); S.headers["User-Agent"]="WeatherApp-probe/1.0 (personal weather app)"
IEM="https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/"
now=int(time.time()*1000); base=(now-2*60000)//300000*300000
run=(now-55*60000)//3600000*3600000; first=math.ceil((now-run)/900000)*15
meta={"now":now,"base":base,"run":run,"first":first}
jobs=[]
Z6=[(6,x,y) for x in range(9,21) for y in range(21,28)]
Z7=[(7,x,y) for x in range(18,25) for y in range(42,52)]
Z5=[(5,x,y) for x in range(4,11) for y in range(10,14)]
for m in range(0,60,5):
    L="nexrad-n0q-900913"+("-m%02dm"%m if m else "")
    for z,x,y in Z5+Z6+Z7: jobs.append((f"{IEM}{L}/{z}/{x}/{y}.png?{base}",f"out/iem/{L}/{z}/{x}/{y}.png"))
for fm in range(first,first+181,15):
    L="hrrr::REFD-F%04d-0"%fm
    for z,x,y in Z5+Z6+Z7: jobs.append((f"{IEM}{L}/{z}/{x}/{y}.png?{run}",f"out/iem/{L}/{z}/{x}/{y}.png"))
for z in (3,4,5,6):
    n=2**z
    for x in range(0,n//2):
        for y in range(n//8,n//2+1):
            if z==6 and not (5<=x<=21 and 18<=y<=28): continue
            jobs.append((f"{IEM}goes_west_fulldisk_ch13/{z}/{x}/{y}.png",f"out/iem/goes_west_fulldisk_ch13/{z}/{x}/{y}.png"))
G="https://willhrom-weather-gfs.pages.dev/gfs/"
man=S.get(G+"manifest.json",timeout=30).json(); meta["gfs"]=man
os.makedirs("out/gfs",exist_ok=True); json.dump(man,open("out/gfs/manifest.json","w"))
for h in man["hours"]:
    for z in (2,3):
        for x in range(2**z):
            for y in range(2**z): jobs.append((f"{G}m/f{h:03d}/{z}/{x}/{y}.png",f"out/gfs/m/f{h:03d}/{z}/{x}/{y}.png"))
QC="https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer"
try:
    te=S.get(QC+"?f=pjson",timeout=20).json()["timeInfo"]["timeExtent"]; meta["qc_te"]=te
    R=20037508.342789244
    for m in range(0,60,5):
        t=base-m*60000; tq=min(t,te[1])
        for z,x,y in Z6+Z7:
            s=2*R/2**z; bbox=f"{-R+x*s},{R-(y+1)*s},{-R+(x+1)*s},{R-y*s}"
            jobs.append((f"{QC}/exportImage?bbox={bbox}&bboxSR=3857&imageSR=3857&size=256,256&format=png32&time={tq}&f=image",f"out/qc/{tq}/{z}/{x}/{y}.png"))
except Exception as e: meta["qc_err"]=str(e)
def get(j):
    u,p=j
    for i in range(3):
        try:
            r=S.get(u,timeout=30)
            if r.status_code==200 and r.headers.get("content-type","").startswith("image"):
                os.makedirs(os.path.dirname(p),exist_ok=True); open(p,"wb").write(r.content); return 1
            if r.status_code==404: return 0
        except Exception: pass
        time.sleep(1+i)
    return 0
with ThreadPoolExecutor(8) as ex: ok=sum(ex.map(get,jobs))
meta["jobs"]=len(jobs); meta["ok"]=ok; json.dump(meta,open("out/meta.json","w")); print(meta["jobs"],ok)
