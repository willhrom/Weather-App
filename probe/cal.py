import requests,os;os.makedirs("out",exist_ok=True)
t=requests.get("https://willhrom-weather-gfs.pages.dev/gfs/manifest.json",timeout=30).text;open("out/manifest.txt","w").write(t[:400]);print(t[:400])
