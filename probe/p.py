import os,json
from playwright.sync_api import sync_playwright
os.makedirs("out",exist_ok=True);log=[]
with sync_playwright() as p:
    b=p.chromium.launch()
    for name,(la,lo) in {"sanramon":(37.7799,-121.978),"sf":(37.7749,-122.4194)}.items():
        ctx=b.new_context(viewport={"width":393,"height":852},device_scale_factor=2,geolocation={"latitude":la,"longitude":lo},permissions=["geolocation"])
        pg=ctx.new_page();errs=[];reqs=[]
        pg.on("console",lambda m,errs=errs:errs.append(f"{m.type}: {m.text}"[:400]) if m.type in("error","warning") else None)
        pg.on("pageerror",lambda e,errs=errs:errs.append("PAGEERROR "+str(e)[:600]))
        pg.on("requestfailed",lambda r,reqs=reqs:reqs.append("FAIL "+r.url[:200]+" "+str(r.failure)))
        pg.on("response",lambda r,reqs=reqs:reqs.append(f"{r.status} {r.url[:200]}") if r.status>=400 else None)
        pg.goto("https://willhrom-weather.pages.dev/?probe=1");pg.wait_for_timeout(9000)
        pg.click("#tabbar [data-tab=sky]");pg.wait_for_timeout(9000)
        pg.screenshot(path=f"out/{name}_sky.png",full_page=True)
        html=pg.evaluate("(document.querySelector('#tab-sky')||document.querySelector('[data-pane=sky]')||document.body).innerText.slice(0,3000)")
        log.append({"name":name,"errs":errs,"reqs":reqs,"text":html})
        ctx.close()
json.dump(log,open("out/log.json","w"),indent=1)
