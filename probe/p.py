import os,json,subprocess,time
from playwright.sync_api import sync_playwright
os.makedirs("out",exist_ok=True)
subprocess.run("mkdir -p /tmp/o /tmp/n && cp web/index.html /tmp/o/index.html && cp probe/new.html /tmp/n/index.html && cp web/*.png web/*.js web/*.json /tmp/o/ 2>/dev/null; cp web/*.png web/*.js web/*.json /tmp/n/ 2>/dev/null; true",shell=True)
srv=[subprocess.Popen(["python3","-m","http.server",str(pt),"-d",d],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL) for pt,d in ((8001,"/tmp/o"),(8002,"/tmp/n"))];time.sleep(2)
views=[("westcoast",[-125.5,40.5],5.2),("norcal",[-123.6,38.6],6.6)]
log=[]
with sync_playwright() as p:
    b=p.chromium.launch(args=["--use-gl=swiftshader","--enable-webgl","--ignore-gpu-blocklist"])
    for tag,port in (("old",8001),("new",8002)):
        ctx=b.new_context(viewport={"width":430,"height":900},device_scale_factor=2,geolocation={"latitude":37.78,"longitude":-121.98},permissions=["geolocation"])
        pg=ctx.new_page();errs=[];pg.on("pageerror",lambda e,errs=errs:errs.append(str(e)[:300]))
        pg.goto(f"http://localhost:{port}/index.html");pg.wait_for_timeout(8000)
        try:pg.click("#mapExpand",timeout=3000)
        except Exception as e:errs.append("nofs "+str(e)[:100])
        for name,c,z in views:
            pg.evaluate(f"map.jumpTo({{center:{c},zoom:{z}}})");pg.wait_for_timeout(28000)
            pg.screenshot(path=f"out/{name}_{tag}.png")
        log.append({tag:errs});ctx.close()
json.dump(log,open("out/log.json","w"),indent=1)
