from PIL import Image
import numpy as np, json
im=Image.open('/mnt/user-data/attachments/f21eb6ff-e09e-5460-b2b9-9f9701980201/411576f0-image.png').convert('RGB')
a=np.asarray(im).astype(int)
bg=np.array([2,9,24])
names=[['blood','blue','harvest','mist','frost'],['arcane','prospector','wisdom','quiet','drift']]
cx=[191,537,884,1234,1584]; cy=[218,621]
boxes={}
for r in range(2):
  for c in range(5):
    x0,x1=cx[c]-175,cx[c]+175; y0,y1=cy[r]-140,cy[r]+140
    sub=a[y0:y1,x0:x1]
    d=np.abs(sub-bg).sum(2)>60
    rowfrac=d.mean(1); colfrac=d.mean(0)
    rows=np.where(rowfrac>0.55)[0]; 
    # columns: fraction over the found rows only
    d2=d[rows.min():rows.max()+1]
    colfrac=d2.mean(0); cols=np.where(colfrac>0.75)[0]
    box=(int(x0+cols.min()),int(y0+rows.min()),int(x0+cols.max()),int(y0+rows.max()))
    boxes[names[r][c]]=box
    print(names[r][c],box,box[2]-box[0]+1,box[3]-box[1]+1)
json.dump(boxes,open('boxes.json','w'))
