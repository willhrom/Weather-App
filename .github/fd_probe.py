import urllib.request,urllib.parse,os,json,time,collections
os.makedirs("fd",exist_ok=True)
base="https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Incident_Locations_Current/FeatureServer/0"
get=lambda u:json.load(urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"probe"}),timeout=60))
meta=get(base+"?f=json");open("fd/fields.txt","w").write("\n".join(f["name"]+" "+f["type"] for f in meta.get("fields",[])))
j=get(base+"/query?"+urllib.parse.urlencode({"where":"1=1","outFields":"*","outSR":"4326","f":"geojson","resultRecordCount":"4000"}))
open("fd/all.geojson","w").write(json.dumps(j))
fs=j.get("features",[]);now=time.time()*1000
out=[f"{len(fs)} features"]
c=collections.Counter((f["properties"].get("IncidentTypeCategory"),f["properties"].get("PercentContained") is not None and f["properties"]["PercentContained"]>=100) for f in fs);out.append(str(c))
ages=collections.Counter(min(12,int((now-(f["properties"].get("ModifiedOnDateTime_dt") or 0))/864e5/30)) for f in fs);out.append("months since modified "+str(sorted(ages.items())))
sz=collections.Counter(("none" if f["properties"].get("IncidentSize") is None else "<10" if f["properties"]["IncidentSize"]<10 else "<100" if f["properties"]["IncidentSize"]<100 else "<1000" if f["properties"]["IncidentSize"]<1000 else "1000+") for f in fs);out.append(str(sz))
out.append(json.dumps(fs[0]["properties"]) if fs else "")
open("fd/summary.txt","w").write("\n".join(out))
