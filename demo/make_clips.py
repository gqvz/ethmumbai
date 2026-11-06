"""Render the demo script lines to WAV with gTTS (needs network once). Usage: python3 make_clips.py <outdir>"""
import json, pathlib, subprocess, sys
from gtts import gTTS
out = pathlib.Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
for k, text in json.load(open(pathlib.Path(__file__).with_name("clips.json"))).items():
    mp3 = out / f"{k}.mp3"; gTTS(text, lang="en", tld="co.in").save(str(mp3))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(mp3), "-ar", "48000", "-ac", "2", str(out / f"{k}.wav")], check=True)
    mp3.unlink(); print(k)
