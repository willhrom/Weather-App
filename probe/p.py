import os, json, time, requests
from concurrent.futures import ThreadPoolExecutor
S=requests.Session(); S.headers["User-Agent"]="WeatherApp-probe/1.0 (personal weather app)"
IEM="https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/"
os.makedirs("out",exist_ok=True); meta={"now":int(time.time()*1000)}
b=int(time.time()//300*300)-300
T=[b-k*300 for k in range(0,37)]   # last 3 hours
TILES=[(8,x,y) for x in range(39,44) for y in range(94,100)]
jobs=[]
for t in T:
    ts=time.strftime("%Y%m%d%H%M",time.gmtime(t))
    for z,x,y in TILES: jobs.append((f"{IEM}ridge::USCOMP-N0Q-{ts}/{z}/{x}/{y}.png",f"out/mos/{ts}/{z}/{x}/{y}.png"))
QC="https://mapservices.weather.noaa.gov/eventdriven/rest/services/radar/radar_base_reflectivity_time/ImageServer"
R=20037508.342789244
try:
    te=S.get(QC+"?f=pjson",timeout=20).json()["timeInfo"]["timeExtent"]; meta["qc_te"]=te
    for k in (0,6,12,24):
        tq=min((b-k*300)*1000,te[1])
        for z,x,y in TILES:
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
        except Exception: pass
        time.sleep(1+i)
    return 0
with ThreadPoolExecutor(8) as ex: meta["ok"]=sum(ex.map(get,jobs)); meta["jobs"]=len(jobs)
for name,u in [("metar","https://aviationweather.gov/api/data/metar?ids=KCIC,KOVE,KRBL,KRDD,KMYV,KBAB,KSMF,KSAC,KAUN,KGOO,KTRK,KBLU,KWLW,KLHM,KMCC,KVCB,KUKI,KSTS,KAPC,KO05&hours=4&format=json"),
               ("afd","https://api.weather.gov/products/types/AFD/locations/STO"),
               ("hrly","https://api.weather.gov/gridpoints/STO/55,120/forecast/hourly")]:
    try:
        r=S.get(u,timeout=30,headers={"Accept":"application/json"}); open(f"out/{name}.json","w").write(r.text); meta[name]=r.status_code
        if name=="afd":
            pid=r.json()["@graph"][0]["id"]; open("out/afd.txt","w").write(S.get("https://api.weather.gov/products/"+pid,timeout=30).json()["productText"])
    except Exception as e: meta[name+"_err"]=str(e)
json.dump(meta,open("out/meta.json","w")); print(meta)
