import json, urllib.request, time
def get(u,t=60):
    with urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"wx-probe"}),timeout=t) as r: return r.read()
out=[]
try:
    prods=json.loads(get("https://realearth.ssec.wisc.edu/api/products"))
    out.append(f"RealEarth products: {len(prods)}")
    for p in prods:
        s=json.dumps(p).lower()
        if any(k in s for k in ["precip","rain","gfs","imerg","reflectivity","g18","goes-west","goeswest","pacific","fd-band","band13","ir "]):
            out.append(f"  {p.get('id')} | {p.get('name','')[:70]} | {p.get('description','')[:80] if isinstance(p.get('description'),str) else ''} | times {len(p.get('times',[]))} | {str(p.get('times',[])[-1:])}")
except Exception as e: out.append("RE err "+str(e))
# IEM tile services
for L in ["goes_west_conus_ch13","goes_west_fulldisk_ch13","goes_west_pacus_ch13","goes_west_conus_ch02","goes_west_fd_ch13"]:
    try:
        b=get(f"https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/{L}/4/2/5.png");out.append(f"IEM {L} {len(b)}")
    except Exception as e: out.append(f"IEM {L} ERR {str(e)[:60]}")
# GIBS IMERG
for L in ["IMERG_Precipitation_Rate","IMERG_Precipitation_Rate_30min"]:
    try:
        b=get(f"https://gibs.earthdata.nasa.gov/wmts/epsg3857/best/{L}/default/default/GoogleMapsCompatible_Level6/3/1/3.png");out.append(f"GIBS {L} {len(b)}")
    except Exception as e: out.append(f"GIBS {L} ERR {str(e)[:80]}")
open("report.txt","w").write("\n".join(out));print("\n".join(out))
