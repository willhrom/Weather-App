import urllib.request,os
RE="https://realearth.ssec.wisc.edu"
os.makedirs("re",exist_ok=True)
for name,u in [("products.json","/api/products?products=HRRR-smoke-surface"),("legend.png","/api/legend?products=HRRR-smoke-surface"),("legend2.png","/products/HRRR-smoke-surface/legend.png")]:
    try:open("re/"+name,"wb").write(urllib.request.urlopen(urllib.request.Request(RE+u,headers={"User-Agent":"probe"}),timeout=30).read())
    except Exception as e:open("re/"+name+".err","w").write(str(e))
