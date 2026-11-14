import json,urllib.request,os
os.makedirs("out",exist_ok=True);R={}
def g(u):
    try:
        r=urllib.request.urlopen(urllib.request.Request(u,headers={"User-Agent":"weather-app probe (github.com/willhrom)"}),timeout=20);return r.read().decode()[:6000]
    except Exception as e:return "ERR "+str(e)
la,lo=37.7799,-121.978
R["om"]=g(f"https://api.open-meteo.com/v1/forecast?latitude={la}&longitude={lo}&current=temperature_2m,weather_code,is_day,apparent_temperature,precipitation,cloud_cover&minutely_15=precipitation&forecast_minutely_15=8&past_minutely_15=4&temperature_unit=fahrenheit&precipitation_unit=inch&timezone=auto")
R["metar"]=g("https://aviationweather.gov/api/data/metar?ids=KLVK,KCCR,KOAK,KHWD,KSFO&format=json&hours=2")
R["rel"]=g("https://api.github.com/repos/willhrom/Weather-App/releases?per_page=3")
json.dump(R,open("out/log.json","w"),indent=1)
