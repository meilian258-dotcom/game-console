"""Immutable alpha10 Vintage TV inputs. Never read active model/Java for historical QA."""
import io,json,zipfile
import numpy as np
from PIL import Image
from build_lcd_tv_model import TEXTURE_HASHES
from import_subor_hardware import CATEGORY,sha

ARCHIVE=CATEGORY/'piq_fc_arcade-0.31.0-alpha.10.jar'
ARCHIVE_SHA='84F0EA4C5B2E944423396A0E44C92C9217F761AAD6DEF8F2D4925512B778CA4C'
PREFIX='assets/piq_fc_arcade/'
MODEL_SHA='8BD167B0E687795750A73B2DEE3A477B53C96C4E8A94407F980BF5BABBF83EAA'
ITEM_SHA='2E6E4A0852C6BE1B3867740048DEAB2AD0F93C81E6BAD384B993C70E5BF744B5'

def release():
    data=ARCHIVE.read_bytes()
    if sha(data)!=ARCHIVE_SHA:raise ValueError('Frozen alpha10 JAR changed; historical verification stopped')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        model_bytes=archive.read(PREFIX+'models/block/home_vintage_tv.json')
        item_bytes=archive.read(PREFIX+'models/item/vintage_tv.json')
        if sha(model_bytes)!=MODEL_SHA or sha(item_bytes)!=ITEM_SHA:raise ValueError('Frozen alpha10 Vintage assets differ')
        model=json.loads(model_bytes);textures={}
        for key,digest in TEXTURE_HASHES.items():
            image=archive.read(PREFIX+'textures/block/home_retro_tv_'+key+'.png')
            if sha(image)!=digest:raise ValueError('Frozen alpha10 texture differs: '+key)
            textures[model['textures'][key]]=np.array(Image.open(io.BytesIO(image)).convert('RGBA'))
        classes={name:sha(archive.read(name)) for name in ('cn/piq/fcarcade/home/VintageTvLayout.class',
            'cn/piq/fcarcade/layout/RocketArcadeGeometry.class','cn/piq/fcarcade/client/ArcadeBlockScreenRenderer.class')}
        other={name:sha(archive.read(PREFIX+'models/block/'+name)) for name in ('home_lcd_tv.json','home_wide_lcd_tv.json')}
    return {'model':model,'model_bytes':model_bytes,'item':json.loads(item_bytes),'item_bytes':item_bytes,
            'textures':textures,'texture_sha256':TEXTURE_HASHES,'class_sha256':classes,'old_models':other}
