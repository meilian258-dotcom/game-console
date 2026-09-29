"""Independent alpha9 geometry predicates over already-read archive documents.

No generator imports, current source rebuilds, writes or Minecraft execution.
Frozen alpha8 documents are provided by the caller after its archive SHA check.
"""
import copy
from collections import Counter
import numpy as np
from render_rocket_arcade_preview import collect_quads


def require(ok, message):
    if not ok: raise ValueError(message)


def points(model):
    quads = collect_quads(model)
    require(bool(quads), "No exported geometry")
    result = np.concatenate([q.vertices for q in quads])
    require(np.isfinite(result).all(), "Non-finite exported geometry")
    for q in quads:
        require(np.linalg.norm(np.cross(q.vertices[1]-q.vertices[0], q.vertices[2]-q.vertices[0])) > 1e-9,
                "Degenerate exported face")
    return quads, result


def same_part(actual, expected, index):
    require(actual.keys() == expected.keys(), "Part fields changed: " + str(index))
    for key in actual:
        if key in ("from", "to"):
            require(np.allclose(actual[key], expected[key], atol=1e-8, rtol=0), "Part bounds changed: " + str(index))
        elif key == "rotation":
            require(actual[key].keys() == expected[key].keys(), "Rotation fields changed")
            for subkey in actual[key]:
                require(np.allclose(actual[key][subkey], expected[key][subkey], atol=1e-8, rtol=0)
                        if subkey == "origin" else actual[key][subkey] == expected[key][subkey], "Part rotation changed: " + str(index))
        else: require(actual[key] == expected[key], "Part UV/name/properties changed: " + str(index))


def dual(model, alpha8, legacy):
    require(len(model.get("elements", [])) == len(alpha8["elements"]) == 227, "Dual must retain 227 parts")
    require(model["textures"] == alpha8["textures"] and model["ambientocclusion"] == alpha8["ambientocclusion"], "Dual material route changed")
    expected = copy.deepcopy(alpha8["elements"])
    for i in (9,17,21,23,31,35): expected[i]["to"][1] += 5.6
    expected[4]["to"][1] = 37.35
    for key in ("from", "to"): expected[49][key][1] += 5.6
    expected[49]["name"] = "37.6单位顶盖"
    bounds = {
        48:([1.24,32.55,3.03],[30.76,37.35,13.85]),
        50:([1.18,32.45,2.705],[30.82,33.05,4.65]),
        51:([1.18,37.05,2.705],[30.82,37.5,3.2]),
        52:([30.18,32.95,2.72],[30.83,37.3,3.21]),
        53:([1.17,32.95,2.72],[1.82,37.3,3.21]),
        54:([8,33.3,2.99],[24,33.3+16*2.76/12.26,3.1]),
        55:([1.42,29.245,10.60],[30.58,32.60,13.90])}
    for i,(lo,hi) in bounds.items():
        expected[i]["from"],expected[i]["to"] = lo,hi
        expected[i].pop("rotation",None)
    for start,x in ((56,26),(62,6)):
        center=(np.array(legacy["elements"][start]["from"])+legacy["elements"][start]["to"])/2
        for i in range(start,start+6):
            for key in ("from","to"):
                expected[i][key]=(np.array(legacy["elements"][i][key])-center+[x,31,10.31]).tolist()
    for i,e in enumerate(expected):
        for key in ("from","to"): e[key][1] -= 5.6
        if "rotation" in e: e["rotation"]["origin"][1] -= 5.6
        same_part(model["elements"][i], e, i)
        require(min(model["elements"][i]["from"])>=-16 and max(model["elements"][i]["to"])<=32,
                "Dual outside vanilla element loader range")
    quads,p = points(model); world=p+[0,5.6,0]
    require(np.allclose([world.min(0),world.max(0)],[[.6,0,.3820101013],[31.4,37.6,14.65]],atol=1e-8,rtol=0), "Dual world bounds mismatch")
    old_quads,_ = points(alpha8)
    current={i:[q for q in quads if q.element_index==i] for i in range(227)}
    header={4,9,17,21,23,31,35}|set(range(48,68))
    unchanged=0
    for i in set(range(227))-header:
        old=[q for q in old_quads if q.element_index==i]
        require(len(old)==len(current[i]) and all(np.allclose(a.vertices,b.vertices+[0,5.6,0],atol=1e-8,rtol=0)
                and np.array_equal(a.uv,b.uv) for a,b in zip(old,current[i])), "Non-header world surface changed: "+str(i))
        unchanged+=1
    for i in (48,55):
        edges=Counter(tuple(sorted((tuple(np.round(a,8)),tuple(np.round(b,8)))))
                for q in current[i] for a,b in zip(q.vertices,np.roll(q.vertices,-1,axis=0)))
        require(len(current[i])==6 and len(edges)==12 and all(n==2 for n in edges.values()), "Header/canopy solid has an open face")
    screen=current[47][0].vertices+[0,5.6,0]
    return {"ok":True,"parts":227,"unchanged_non_header_world_parts":unchanged,"unchanged_uv_parts":227,
            "height_blocks":2.35,"raw_y_rebase":-5.6,"ber_y_offset":.35,"bounds_world_units":[world.min(0).tolist(),world.max(0).tolist()],
            "screen_quad":screen.tolist(),"screen_aspect":16/9,"standing_eye_within_screen":bool(screen[:,1].min()<25.92<screen[:,1].max())}


def wide_lcd(model):
    require(len(model.get("elements",[]))==42,"Wide LCD must retain 42 reviewed cubes")
    qs,p=points(model)
    require(np.allclose([p.min(0),p.max(0)],[[0,0,5],[24,15,11]],atol=1e-9,rtol=0),"Wide LCD bounds mismatch")
    screen=[q for q in qs if q.element_index==4]
    require(len(screen)==1 and screen[0].direction=="north", "Wide LCD must have one north glass face")
    require(np.allclose(sorted(np.linalg.norm(np.roll(screen[0].vertices,-1,axis=0)-screen[0].vertices,axis=1)),
                        [12.375,12.375,22,22],atol=1e-9,rtol=0), "Wide LCD glass is not physical 16:9")
    require(np.allclose(screen[0].vertices[:,2],6,atol=1e-9,rtol=0),"Wide LCD glass plane changed")
    by_name={e["name"]:e for e in model["elements"] if not e["name"].startswith("后板避让")}
    for x,color in ((9,"yellow"),(12,"white"),(15,"red")):
        bottom=by_name[color+"凹口底"]
        require(np.allclose(bottom["from"],[x-.4,3.6,7.66]) and np.allclose(bottom["to"],[x+.4,4.4,7.68]),"Wide LCD recessed RCA base changed")
        for suffix in ("左边","右边","下边","上边"):
            e=by_name[color+"RCA"+suffix]
            require(e["to"][2]==8.23 and all(f["texture"]=="#"+color for f in e["faces"].values()),"Wide LCD socket rim/colour changed")
    return {"ok":True,"parts":42,"bounds_units":[p.min(0).tolist(),p.max(0).tolist()],"screen_aspect":16/9,
            "screen_quad":screen[0].vertices.tolist(),"rca_socket_units":[[x,4,8.23] for x in (9,12,15)],"reserved_cells":2}


def cartridge(models, whole):
    reports=[]
    for i in range(3):
        m=models["board_"+str(i)];qs,p=points(m)
        require(np.allclose([p.min(0),p.max(0)],[[2.8,0,7.25 if i==2 else 7.268],[13.2,(6.66,7.01,4.81)[i],8.134]],atol=1e-8,rtol=0),"PCB exterior/slot bounds mismatch")
        require(all(v.startswith("minecraft:block/") or v.startswith("#") for v in m["textures"].values()),"PCB contains new bitmap or ROM identity artwork")
        for side in ("front","back"):
            require(sum(e["name"].startswith(side+"金手指") for e in m["elements"])==30,"PCB connector contact count changed")
        require(sum(e["name"]=="DIP封装" for e in m["elements"])==(0 if i==2 else 2),"PCB cosmetic chip package variant changed")
        if i==2: require(sum(e["name"]=="黑胶圆角" for e in m["elements"])==24,"PCB epoxy caps lost octagonal geometry")
        reports.append({"variant":i,"parts":len(m["elements"]),"quads":len(qs),"bounds_units":[p.min(0).tolist(),p.max(0).tolist()]})
    shell=models["shell"];qs,p=points(shell)
    require(shell["textures"]==whole["textures"],"Detached shell changed original atlas route")
    require(not any(any(s in e["name"] for s in ("线路板","金手指","中线合模")) for e in shell["elements"]),"Empty shell still includes PCB")
    original=next(e for e in whole["elements"] if e["name"]=="中央游戏标签")
    label=next(e for e in shell["elements"] if e["name"]=="前壳 / 中央游戏标签")
    require(label["faces"]==original["faces"],"Detached shell game cover UV changed")
    for key in ("from","to"):
        require(np.allclose(label[key],np.array(original[key])+[-1.45,-.35,-1.05],atol=1e-8,rtol=0),"Detached shell cover position changed")
    require(sum(e["name"] in ("左围边","右围边","顶围边","内侧定位柱") for e in shell["elements"])==10,"Detached hollow shells lost inner rim/posts")
    return {"ok":True,"boards":reports,"shell_parts":len(shell["elements"]),"shell_quads":len(qs),
            "original_cover_uv_retained":True,"new_pngs":0,"limits":["Offline geometry only, not Minecraft gameplay or transaction execution"]}
