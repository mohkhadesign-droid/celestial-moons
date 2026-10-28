from PIL import Image
import numpy as np, json
im=Image.open('/mnt/user-data/attachments/f21eb6ff-e09e-5460-b2b9-9f9701980201/411576f0-image.png').convert('RGB')
a=np.asarray(im).astype(float)
boxes=json.load(open('boxes.json'))
N=32; M=10
def sample(name):
    x0,y0,x1,y1=boxes[name]; sx=(x1-x0+1)/N; sy=(y1-y0+1)/N
    g=np.zeros((N+2*M,N+2*M,3))
    for j in range(-M,N+M):
        for i in range(-M,N+M):
            X0=int(round(x0+(i+0.25)*sx)); X1=max(X0+1,int(round(x0+(i+0.75)*sx)))
            Y0=int(round(y0+(j+0.25)*sy)); Y1=max(Y0+1,int(round(y0+(j+0.75)*sy)))
            X0=max(0,min(X0,a.shape[1]-1));X1=max(X0+1,min(X1,a.shape[1]));Y0=max(0,min(Y0,a.shape[0]-1));Y1=max(Y0+1,min(Y1,a.shape[0]))
            g[j+M,i+M]=np.median(a[Y0:Y1,X0:X1].reshape(-1,3),axis=0)
    return g
res={}
for name in boxes:
    g=sample(name)
    np.save(f'g_{name}.npy',g)
    big=Image.fromarray(g.astype(np.uint8)).resize(((N+2*M)*8,(N+2*M)*8),Image.NEAREST)
    res[name]=big
W=(N+2*M)*8
sheet=Image.new('RGB',(5*W,2*W))
for k,name in enumerate(boxes): sheet.paste(res[name],((k%5)*W,(k//5)*W))
sheet.save('preview2.png')
# print margin stats for mist/drift/frost
for name in ['mist','drift','frost']:
    g=np.load(f'g_{name}.npy'); m=np.ones(g.shape[:2],bool); m[M:M+N,M:M+N]=False
    vals=g[m]; lum=vals.sum(1)
    print(name, 'margin lum pct', np.percentile(lum,[50,90,97,99,100]).round())
