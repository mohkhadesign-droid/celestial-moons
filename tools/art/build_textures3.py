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
REF=sys.argv[2]  # folder with the user's reference images
boxes={'wisp':(40,38,453,446),'afterimage':(52,103,370,420),'shell':(30,30,434,437)}
def sample(n):
    im=Image.open(os.path.join(REF,n+'.png')).convert('RGB').crop(boxes[n]).resize((N,N),Image.BOX)
    return np.asarray(im).astype(float)

# Afterimage: lavender moon; the detail layer is a dimmer violet copy drawn twice, offset, as fading duplicates
m=sample('afterimage')
base,_=sheet(m)
ghost=np.zeros_like(base)
for p in range(8):
    cx=(p%4)*CELL; cy=(p//4)*CELL
    ghost[cy+OFF:cy+OFF+N,cx+OFF:cx+OFF+N]=m*np.array([0.55,0.55,0.8])*phase_factor(p)[...,None]
save(base,OUT+'/afterimage_moon_phases.png'); save(ghost,OUT+'/afterimage_moon_details.png')

# Shell: ivory/sage plates; the pale mint central plate glows softly
m=sample('shell')
r,g,b=m[...,0],m[...,1],m[...,2]
yy,xx=np.mgrid[0:N,0:N]+0.5
mint=(np.hypot(xx-16,yy-16)<9)&(g>r)&(g>140)
base,over=sheet(m,glow=mint)
save(base,OUT+'/shell_moon_phases.png'); save(over*np.array([0.08,0.16,0.12]),OUT+'/shell_moon_details.png')  # only a faint mint breath, never a bright white centre

# Wisp (second build): slate moon, the soul flame glows/flickers
m=sample('wisp')
r,g,b=m[...,0],m[...,1],m[...,2]
L=(r+g+b)/3
yy,xx=np.mgrid[0:N,0:N]
# the soul flame (bright, centred, with its upward tail) and the few sparks around it; the pale plates at the
# moon's edge are just as bright, so the mask is limited to the inner area
flame=(L>175)&(np.abs(xx-16)<=7)&(yy>=6)&(yy<=26)
base,over=sheet(m,glow=flame)
save(base,OUT+'/wisp_moon_phases.png'); save(over,OUT+'/wisp_moon_details.png')
print('ok')
