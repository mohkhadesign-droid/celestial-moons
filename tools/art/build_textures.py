"""Builds moon phase sheets + animated-detail overlay sheets for the add-on.
Layout matches vanilla 1.16.5 textures/environment/moon_phases.png: 4 columns x 2 rows of phases,
phase p at column p%4, row p/4. The moon occupies the middle quarter of each cell (vanilla: 8px moon in a 32px cell);
here 32px moon art in a 128px cell -> 512x256 sheet. Black = no contribution (the sky renderer blends additively)."""
from PIL import Image
import numpy as np, sys, os
OUT=sys.argv[1]
N=32; M=10; CELL=128; OFF=(CELL-N)//2
names=['blood','blue','harvest','mist','frost','arcane','prospector','wisdom','quiet','drift']
def lum(c): return c.sum(-1)

def moon_and_margin(name):
    g=np.load(f'g_{name}.npy')
    moon=g[M:M+N,M:M+N].copy()
    margin=np.zeros_like(g); inner=np.zeros(g.shape[:2],bool); inner[M:M+N,M:M+N]=True
    L=lum(g); r,gg,b=g[...,0],g[...,1],g[...,2]
    if name=='mist':   # wisps are drawn procedurally on the detail layer (see mist_wisps)
        keep=np.zeros_like(inner)
    elif name=='frost':  # ice spikes hugging the edge
        near=np.zeros_like(inner); near[M-2:M+N+2,M-2:M+N+2]=True
        keep=(~inner)&near&(L>330)&(b>200)
    elif name=='drift':  # lavender fragments, not the white label text or dim stars
        keep=(~inner)&(L>330)&((b-r)>18)
    else:
        keep=np.zeros_like(inner)
    margin[keep]=g[keep]
    return moon, margin

def glow_mask(name, moon):
    r,g,b=moon[...,0],moon[...,1],moon[...,2]
    if name=='blood':      m=(r>215)&(g>55)
    elif name=='blue':     m=(g>170)&(b>235)&(r<170)
    elif name=='harvest':  m=(r>245)&(g>222)&(b>120)
    elif name=='frost':    m=(r>190)&(g>222)&(b>245)
    elif name=='arcane':   m=(r>190)&(b>200)&(g<170)
    elif name=='prospector': m=((r>220)&(g>160)&(b<130))|((g>170)&(r<170)&(b<170))
    elif name=='wisdom':   m=(r>190)&(g>130)&(b<170)
    else:                  m=np.zeros(r.shape,bool)
    return m

def phase_factor(p, w=N):
    x=np.arange(w)+0.5
    soft=5.0
    def ramp(t):  # 1 lit, 0 dark ; t = signed distance into lit side
        return np.clip(0.5+t/soft,0,1)
    if p==0: f=np.ones(w)
    elif p==4: f=np.zeros(w)
    elif p in (1,2,3):   # waning: lit on the left, terminator moves left
        term={1:0.62,2:0.40,3:0.15}[p]*w; f=ramp(term-x)
    else:                # waxing: lit on the right
        term={5:0.85,6:0.60,7:0.38}[p]*w; f=ramp(x-term)
    F=np.tile(f,(w,1))
    F=0.10+0.90*F
    if p==4:  # faint rim like vanilla's new moon
        F[:]=0.10; F[0,:]=F[-1,:]=F[:,0]=F[:,-1]=0.22
    return F

def mist_wisps():
    # soft horizontal mist streaks: longer outside the silhouette, faint across the face
    w=np.zeros((CELL,CELL,3)); col=np.array([196,200,212.])
    streaks=[(5,15,11),(11,9,15),(17,16,10),(23,10,14),(28,13,9)]  # (row, left reach, right reach)
    for row,lr,rr in streaks:
        y=OFF+row
        for x in range(CELL):
            if x<OFF: t=(OFF-x)/lr; a=0.85*max(0,1-t)**1.3 if t<=1 else 0
            elif x>=OFF+N: t=(x-(OFF+N-1))/rr; a=0.85*max(0,1-t)**1.3 if t<=1 else 0
            else: a=0.22
            for dy,k in ((0,1.0),(1,0.45)):
                w[y+dy,x]=np.maximum(w[y+dy,x],col*a*k)
    return w

def halo_cell(avg):
    yy,xx=np.mgrid[0:CELL,0:CELL]+0.5
    dx=np.maximum(np.abs(xx-CELL/2)-N/2,0); dy=np.maximum(np.abs(yy-CELL/2)-N/2,0)
    d=np.maximum(dx,dy)/4.0       # distance in vanilla-scale pixels
    edge=np.clip((CELL/2-np.maximum(np.abs(xx-CELL/2),np.abs(yy-CELL/2)))/10.0,0,1)
    inten=24*np.exp(-d/4.5)*(d>0)*edge
    col=avg/np.max(avg)
    return inten[...,None]*col[None,None,:]

os.makedirs(OUT,exist_ok=True)
preview=[]
for name in names:
    moon,margin=moon_and_margin(name)
    gm=glow_mask(name,moon)
    avg=moon.reshape(-1,3).mean(0)
    base=np.zeros((2*CELL,4*CELL,3)); over=np.zeros_like(base)
    for p in range(8):
        cx=(p%4)*CELL; cy=(p//4)*CELL
        F=phase_factor(p)[...,None]
        cell=halo_cell(avg)
        cell[OFF:OFF+N,OFF:OFF+N]=moon*F
        if name=='frost':  # edge spikes are part of the static silhouette
            mg=margin[M-2:M+N+2,M-2:M+N+2]; sel=lum(mg)>0
            sub=cell[OFF-2:OFF+N+2,OFF-2:OFF+N+2]; sub[sel]=mg[sel]*(0.35 if p==4 else 1.0)
        base[cy:cy+CELL,cx:cx+CELL]=cell
        oc=np.zeros((CELL,CELL,3))
        if gm.any():
            g2=np.where(gm[...,None],np.minimum(moon*1.15,255),0)*F
            oc[OFF:OFF+N,OFF:OFF+N]=g2
        if name in ('mist','drift'):   # detached details live only on the animated layer
            k=(lum(margin)>0)
            ys,xs=np.where(k)
            for y,x in zip(ys,xs):
                ty=OFF+(y-M); tx=OFF+(x-M)
                if 0<=ty<CELL and 0<=tx<CELL: oc[ty,tx]=margin[y,x]*(0.55 if p==4 else 1.0)
            if name=='mist':
                w=mist_wisps()
                face=w[:,OFF:OFF+N].copy()
                w[OFF:OFF+N,OFF:OFF+N]*=F*0.6 if False else 1
                oc=np.maximum(oc,w)
                oc[OFF:OFF+N,OFF:OFF+N]*=np.where(F>0,1,1)
        over[cy:cy+CELL,cx:cx+CELL]=oc
    Image.fromarray(np.clip(base,0,255).astype(np.uint8),'RGB').save(f'{OUT}/{name}_moon_phases.png')
    if over.max()>0:
        Image.fromarray(np.clip(over,0,255).astype(np.uint8),'RGB').save(f'{OUT}/{name}_moon_details.png')
    print(name,'glow px',int(gm.sum()),'margin px',int((lum(margin)>0).sum()))
