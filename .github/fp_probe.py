import urllib.request,urllib.parse,os,json
os.makedirs("fp",exist_ok=True)
base="https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer/0"
def get(u):
    return urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"probe","Origin":"https://willhrom-weather.pages.dev"}),timeout=60)
r=get(base+"?f=json");meta=json.load(r);open("fp/meta_fields.txt","w").write("\n".join(f["name"] for f in meta.get("fields",[])))
q=urllib.parse.urlencode({"where":"poly_GISAcres>=1000","outFields":"poly_IncidentName,poly_GISAcres,attr_PercentContained,attr_IncidentName,attr_FireDiscoveryDateTime,poly_DateCurrent","maxAllowableOffset":"0.003","geometryPrecision":"4","outSR":"4326","f":"geojson"})
r=get(base+"/query?"+q);b=r.read();open("fp/big.geojson","wb").write(b)
open("fp/headers.txt","w").write(str(dict(r.headers)))
j=json.loads(b);open("fp/summary.txt","w").write(f"{len(j.get('features',[]))} features, {len(b)} bytes\n"+"\n".join(json.dumps(f["properties"]) for f in j.get("features",[])[:40]))
