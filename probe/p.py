import os, json, time, hashlib, requests
S=requests.Session(); S.headers["User-Agent"]="WeatherApp-probe/1.0 (personal weather app)"
IEM="https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/"
os.makedirs("out/t",exist_ok=True); res=[]
def one(L,z,x,y,tag):
    r=S.get(f"{IEM}{L}/{z}/{x}/{y}.png",timeout=30); h=hashlib.md5(r.content).hexdigest()[:8]
    res.append([tag,L,r.status_code,r.headers.get("content-type"),len(r.content),h,r.headers.get("access-control-allow-origin"),r.headers.get("cache-control")])
    open(f"out/t/{tag}_{L.replace(':','_')}_{z}_{x}_{y}.png","wb").write(r.content)
for rnd in range(3):
    now=time.time(); b=int(now//300*300)
    for k in range(-1,5):
        ts=time.strftime("%Y%m%d%H%M",time.gmtime(b-k*300))
        one(f"ridge::USCOMP-N0Q-{ts}",6,10,22,f"r{rnd}_age{int(now-(b-k*300))}s")
    one("nexrad-n0q-900913",6,10,22,f"r{rnd}_roll"); one("nexrad-n0q-900913-m05m",6,10,22,f"r{rnd}_roll5"); one("nexrad-n0q-900913",6,9,22,f"r{rnd}_roll_x9")
    ts=time.strftime("%Y%m%d%H%M",time.gmtime(b-600)); one(f"ridge::USCOMP-N0Q-{ts}",6,9,22,f"r{rnd}_abs10_x9"); one(f"ridge::USCOMP-N0Q-{ts}",8,40,89,f"r{rnd}_abs10_z8")
    if rnd<2: time.sleep(100)
json.dump(res,open("out/res.json","w"),indent=0); print(json.dumps(res,indent=0))
