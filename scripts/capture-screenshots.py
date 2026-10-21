#!/usr/bin/env python3
"""Capture the actual debug Activity at native device resolution, without edits."""
from pathlib import Path
import argparse
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCREENS = ('home', 'search', 'detail', 'time', 'favorites', 'bookings', 'my', 'login', 'signup', 'reset', 'confirm', 'booking', 'cancel', 'review')
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True, help='Dedicated Android emulator serial')
parser.add_argument('--skip-install', action='store_true')
parser.add_argument('--screens', nargs='+', choices=SCREENS, default=list(SCREENS))
args=parser.parse_args()
def adb(*parts, **kwargs):
    return subprocess.run(['adb', '-s', args.serial, *parts], check=True, timeout=120, **kwargs)

if not args.skip_install:
    adb('install','-r',str(ROOT/'app/build/outputs/apk/debug/app-debug.apk'))
for setting in ('window_animation_scale','transition_animation_scale','animator_duration_scale'):
    adb('shell','settings','put','global',setting,'0',stdout=subprocess.DEVNULL)
output=ROOT/'docs/screenshots'
output.mkdir(parents=True,exist_ok=True)
for i,screen in enumerate(SCREENS,1):
    if screen not in args.screens: continue
    adb('shell','am','start','-W','-f','0x10008000','-n','com.portfolio.littlespace/.ScreenshotActivity','--es','screen',screen,stdout=subprocess.DEVNULL)
    # Software rendering can take much longer than am start -W. Wait for
    # accessibility text from Compose, rather than saving a blank window.
    ready=False
    for attempt in range(12):
        try:
            adb('shell','uiautomator','dump','/sdcard/littlespace-window.xml',stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            tree=adb('shell','cat','/sdcard/littlespace-window.xml',capture_output=True,text=True).stdout
            nodes=ET.fromstring(tree).iter('node')
            if any(n.get('package')=='com.portfolio.littlespace' and n.get('text','').strip() for n in nodes):
                ready=True
                break
        except (subprocess.SubprocessError,ET.ParseError):
            pass
        time.sleep(2)
    if not ready: raise RuntimeError(f'Compose content was not ready for {screen}')
    time.sleep(2)
    target=output/f'{i:02d}-{screen}.png'
    with target.open('wb') as f: adb('exec-out','screencap','-p',stdout=f)
    if not target.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):
        raise RuntimeError(f'Invalid screen capture: {target}')
    print(target.relative_to(ROOT), flush=True)
