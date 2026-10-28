"""Textures for the four moons added in the second update (same layout rules as build_textures.py)."""
from PIL import Image
import numpy as np, sys, os
OUT=sys.argv[1]
N=32; M=10; CELL=128; OFF=(CELL-N)//2
def lum(c): return c.sum(-1)

def phase_factor(p, w=N):
    x=np.arange(w)+0.5; soft=5.0
    ramp=lambda t: np.clip(0.5+t/soft,0,1)
    if p==0: f=np.ones(w)
    elif p==4: f=np.zeros(w)
    elif p in (1,2,3): f=ramp({1:0.62,2:0.40,3:0.15}[p]*w-x)
    else: f=ramp(x-{5:0.85,6:0.60,7:0.38}[p]*w)
    F=np.tile(f,(w,1)); F=0.10+0.90*F
    if p==4:
        F[:]=0.10; F[0,:]=F[-1,:]=F[:,0]=F[:,-1]=0.22
    return F

def halo_cell(avg):
    yy,xx=np.mgrid[0:CELL,0:CELL]+0.5
    dx=np.maximum(np.abs(xx-CELL/2)-N/2,0); dy=np.maximum(np.abs(yy-CELL/2)-N/2,0)
    d=np.sqrt(dx*dx+dy*dy)/4.0
    edge=np.clip((CELL/2-np.maximum(np.abs(xx-CELL/2),np.abs(yy-CELL/2)))/16.0,0,1)
    inten=18*np.exp(-d/3.5)*(d>0)*edge
    col=avg/np.max(avg)
    return inten[...,None]*col[None,None,:]

def sheet(moon, margin_static=None, glow=None, margin_detail=None):
    avg=moon.reshape(-1,3).mean(0)
    base=np.zeros((2*CELL,4*CELL,3)); over=np.zeros_like(base)
    for p in range(8):
        cx=(p%4)*CELL; cy=(p//4)*CELL; F=phase_factor(p)[...,None]
        cell=halo_cell(avg); cell[OFF:OFF+N,OFF:OFF+N]=moon*F
        if margin_static is not None:
            ys,xs=np.where(lum(margin_static)>0)
            for y,x in zip(ys,xs): cell[OFF+y-M,OFF+x-M]=margin_static[y,x]*(0.4 if p==4 else 1)
        base[cy:cy+CELL,cx:cx+CELL]=cell
        oc=np.zeros((CELL,CELL,3))
        if glow is not None: oc[OFF:OFF+N,OFF:OFF+N]=np.where(glow[...,None],np.minimum(moon*1.15,255),0)*F
        if margin_detail is not None:
            ys,xs=np.where(lum(margin_detail)>0)
            for y,x in zip(ys,xs): oc[OFF+y-M,OFF+x-M]=margin_detail[y,x]
        over[cy:cy+CELL,cx:cx+CELL]=oc
    return base, over

def save(arr,path):
    Image.fromarray(np.clip(arr,0,255).astype(np.uint8),'RGB').save(path)

os.makedirs(OUT,exist_ok=True)
def load(name):
    g=np.load(f'g_{name}.npy'); inner=np.zeros(g.shape[:2],bool); inner[M:M+N,M:M+N]=True
    return g, g[M:M+N,M:M+N].copy(), inner

# ---- Revealing: base + blink frames (eyelids close over a fixed iris) -------------------------
g,moon,inner=load('revealing')
r,gg,b=moon[...,0],moon[...,1],moon[...,2]
veins=(r>140)&(gg<90)&(b<90)
CY,CX,R=16.2,15.3,7.4
yy,xx=np.mgrid[0:N,0:N]+0.5
disc=((yy-CY)**2+(xx-CX)**2)<=R*R
veins&=~disc
LID=np.array([226,206,194.]); LID_DARK=np.array([196,166,156.]); EDGE=np.array([92,40,40.])
openness=[1.0,0.62,0.28,0.0]
for k,o in enumerate(openness):
    m=moon.copy()
    if o<1.0:
        top=CY-R*o; bot=CY+R*o
        for y in range(N):
            for x in range(N):
                if not disc[y,x]: continue
                py=y+0.5
                if py<top or py>bot:
                    # shade lids: darker near the closing edge
                    dist=min(abs(py-top),abs(py-bot))
                    m[y,x]=LID_DARK if dist<1.5 else LID
        # lid edge line (lash line) one pixel thick
        for y in range(N):
            py=y+0.5
            for x in range(N):
                if disc[y,x] and (abs(py-top)<0.6 or abs(py-bot)<0.6 or (o==0 and abs(py-CY)<0.6)):
                    m[y,x]=EDGE
    base,over=sheet(m,glow=veins)
    save(base,f'{OUT}/revealing_moon_phases.png' if k==0 else f'{OUT}/revealing_moon_blink{k}.png')
    if k==0: save(over,f'{OUT}/revealing_moon_details.png')

# ---- Thread: static loose thread tail in the margin ------------------------------------------
g,moon,inner=load('thread')
L=lum(g); tail=np.zeros_like(g); sel=(~inner)&(L>420)&(g[...,0]>g[...,2]+10)
sel[:M+20,:]=False
tail[sel]=g[sel]
base,over=sheet(moon,margin_static=tail)
save(base,f'{OUT}/thread_moon_phases.png')

# ---- War: glowing gouges + floating embers ----------------------------------------------------
g,moon,inner=load('war')
r,gg,b=moon[...,0],moon[...,1],moon[...,2]
glow=(r>190)&(gg<140)&(b<90)
emb=np.zeros_like(g); L=lum(g); sel=(~inner)&(g[...,0]>170)&(g[...,2]<110)&(L>260)
emb[sel]=g[sel]
base,over=sheet(moon,glow=glow,margin_detail=emb)
save(base,f'{OUT}/war_moon_phases.png'); save(over,f'{OUT}/war_moon_details.png')

# ---- Gluttony: sesame seeds twinkle ----------------------------------------------------------
g,moon,inner=load('gluttony')
r,gg,b=moon[...,0],moon[...,1],moon[...,2]
seeds=(r>235)&(gg>225)&(b>190)
base,over=sheet(moon,glow=seeds)
save(base,f'{OUT}/gluttony_moon_phases.png'); save(over,f'{OUT}/gluttony_moon_details.png')

# ---- Reveal marker sprite (16x16 RGBA eye) ----------------------------------------------------
S=16; img=np.zeros((S,S,4),np.uint8)
yy,xx=np.mgrid[0:S,0:S]+0.5
eye=((xx-8)/7.2)**2+((yy-8)/4.6)**2<=1
ring=eye&~(((xx-8)/6.0)**2+((yy-8)/3.6)**2<=1)
iris=((xx-8)**2+(yy-8)**2)<=3.4**2
pupil=((xx-8)**2+(yy-8)**2)<=1.5**2
img[eye]=[240,232,220,235]; img[ring]=[120,30,30,255]; img[iris&eye]=[255,150,20,255]; img[pupil]=[20,10,10,255]
img[6,6]=[255,255,255,255]
Image.fromarray(img,'RGBA').save(f'{OUT}/reveal_marker.png')
print('ok')
